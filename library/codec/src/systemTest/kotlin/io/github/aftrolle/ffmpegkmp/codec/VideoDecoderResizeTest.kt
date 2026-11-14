// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.nativeFrameStatistics
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Buffer

/** [VideoDecoder.resize] on the JVM and Kotlin/Native: an open decoder's frames at another size. */
class VideoDecoderResizeTest {
    /**
     * Each resize's frame is the one a decoder opened at that size returns, byte for byte, and null
     * goes back to the source's size. With AUTO the host's hardware decoder, where it takes the
     * clip, keeps the decoded frame in its own memory, which each resize converts again.
     */
    @Test
    fun aResizedFrameIsTheFrameOfADecoderOpenedAtThatSize() = runBlocking<Unit> {
        val cases = listOf(
            Case("cfr-30.mp4", FrameFormat.Rgba8),
            Case("cfr-30.mp4", FrameFormat.Nv12),
            Case("cfr-30-h264.mp4", FrameFormat.Rgba8),
            Case("cfr-30-h264-128.mp4", FrameFormat.Rgba8, CodecPreference.AUTO),
            Case("rotated-90.mp4", FrameFormat.Rgba8),
            Case("hdr10-pq.mp4", FrameFormat.Rgba8),
            Case("hlg.mp4", FrameFormat.Rgba8),
            Case("hdr10-pq.mp4", FrameFormat.RgbaF16),
        )
        val position = 5.seconds / 30
        for (case in cases) {
            decoder(case.clip, VideoOutput.Memory(case.format), case.preference).use { decoder ->
                println("${case.clip} under ${case.preference}: ${decoder.decoderKind}")
                decoder.frameAt(position).close()
                for (size in listOf(FrameSize(48, 32), FrameSize(40, 24), null, FrameSize(48, 32))) {
                    decoder.resize(size)
                    assertSameAsOpenedAt(decoder, case, size, position)
                }
            }
        }
    }

    /** Past the end the decoder holds the last frame; a resize converts that one again. */
    @Test
    fun aResizePastTheEndConvertsTheLastFrameAgain() = runBlocking<Unit> {
        val case = Case("cfr-30.mp4", FrameFormat.Rgba8)
        decoder(case.clip, VideoOutput.Memory(case.format)).use { decoder ->
            decoder.frameAt(10.seconds).use { assertEquals(149, it.number()) }
            decoder.resize(FrameSize(48, 32))
            assertSameAsOpenedAt(decoder, case, FrameSize(48, 32), 10.seconds)
        }
    }

    @Test
    fun aResizeRightAfterASeekGivesTheSoughtFrameAtTheNewSize() = runBlocking<Unit> {
        val case = Case("cfr-30.mp4", FrameFormat.Rgba8)
        decoder(case.clip, VideoOutput.Memory(case.format)).use { decoder ->
            decoder.frameAt(4.seconds).close()
            decoder.seekTo(2.seconds)
            decoder.resize(FrameSize(48, 32))
            assertSameAsOpenedAt(decoder, case, FrameSize(48, 32), 2.seconds)
        }
    }

    @Test
    fun aFrameHandedOutBeforeAResizeKeepsItsSizeAndPixels() = runBlocking<Unit> {
        decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            decoder.frameAt(1.seconds).use { kept ->
                val pixels = kept.pixels()
                decoder.resize(FrameSize(48, 32))
                decoder.frameAt(1.seconds).use { resized ->
                    assertEquals(FrameSize(48, 32), FrameSize(resized.width, resized.height))
                    assertEquals(kept.pts, resized.pts)
                }
                decoder.resize(null)
                decoder.frameAt(2.seconds).close()
                assertEquals(FrameSize(96, 64), FrameSize(kept.width, kept.height))
                assertEquals(30, kept.number())
                assertContentEquals(pixels, kept.pixels())
            }
        }
    }

    /**
     * A resize between emissions: no frame is lost or repeated, and the size changes once, after at
     * most the one frame the flow decoded ahead at the old size.
     */
    @Test
    fun aResizeBetweenEmissionsOfFramesChangesTheSizeOfTheFramesAfterIt() = runBlocking<Unit> {
        decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            val unresized = decoder.frames(until = 2.seconds).map { frame -> frame.use { it.pts } }.toList()
            assertEquals(60, unresized.size)
            val seen = mutableListOf<Pair<Duration, FrameSize>>()
            decoder.frames(until = 2.seconds).collect { frame ->
                frame.use { seen += it.pts to FrameSize(it.width, it.height) }
                if (seen.size == RESIZED_AFTER) decoder.resize(FrameSize(48, 32))
            }
            assertEquals(unresized, seen.map { it.first })
            val old = seen.indexOfFirst { it.second == FrameSize(48, 32) }
            assertTrue(old == RESIZED_AFTER || old == RESIZED_AFTER + 1, "the first resized frame was frame $old")
            assertTrue(seen.take(old).all { it.second == FrameSize(96, 64) })
            assertTrue(seen.drop(old).all { it.second == FrameSize(48, 32) })
        }
    }

    /**
     * A resize frees the old size's ring: through more sizes than the pool has places for, some
     * frames held across resizes, the decoder's rings hold at most the current size's ring and the
     * frames the caller holds, and once those close, the current size's alone.
     */
    @Test
    fun aResizeFreesTheOldSizesRing() = runBlocking<Unit> {
        val baseline = nativeFrameStatistics()
        fun live() = nativeFrameStatistics().let { it.ringBuffers - baseline.ringBuffers to it.ringBytes - baseline.ringBytes }
        val held = mutableListOf<VideoFrame>()
        try {
            decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
                var peak = 0L to 0L
                for (index in 0 until 12) {
                    val size = FrameSize(32 + 8 * index, 16 + 4 * index)
                    decoder.resize(size)
                    val frame = decoder.frameAt(index.seconds / 30)
                    assertEquals(size, FrameSize(frame.width, frame.height))
                    if (index in 3..5) held += frame else frame.close()
                    val (buffers, bytes) = live()
                    println("at $size holding ${held.size}: $buffers ring buffers, $bytes bytes")
                    if (bytes > peak.second) peak = buffers to bytes
                    assertTrue(buffers <= RING + held.size, "at $size holding ${held.size}: $buffers ring buffers")
                }
                println("peak: ${peak.first} ring buffers, ${peak.second} bytes")
                held.forEach(VideoFrame::close)
                held.clear()
                decoder.resize(FrameSize(48, 32))
                decoder.frameAt(Duration.ZERO).close()
                val (buffers, bytes) = live()
                println("held frames closed, at 48x32: $buffers ring buffers, $bytes bytes")
                assertTrue(buffers in 1..RING, "after the held frames closed: $buffers ring buffers")
            }
            assertEquals(0L to 0L, live(), "the closed decoder's rings")
        } finally {
            held.forEach(VideoFrame::close)
        }
    }

    @Test
    fun resizeTakesTheSizesOpenTakes() = runBlocking<Unit> {
        decoder("cfr-30.mp4").use { decoder ->
            // Frames as decoded keep the source's size.
            assertFailsWith<IllegalArgumentException> { decoder.resize(FrameSize(48, 32)) }
            decoder.resize(null)
            decoder.frameAt(Duration.ZERO).use { assertEquals(96, it.width) }
        }
        decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Nv12)).use { decoder ->
            assertFailsWith<IllegalArgumentException> { decoder.resize(FrameSize(47, 32)) }
            decoder.frameAt(Duration.ZERO).use { assertEquals(96, it.width, "a rejected size changes nothing") }
        }
        decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            decoder.resize(FrameSize(47, 31))
            decoder.frameAt(Duration.ZERO).use { assertEquals(FrameSize(47, 31), FrameSize(it.width, it.height)) }
            decoder.close()
            assertFailsWith<IllegalStateException> { decoder.resize(FrameSize(48, 32)) }
        }
    }

    /**
     * A resize queued behind a stalled call, cancelled while it waits for its turn, changes nothing,
     * and the decoder stays usable, as after a cancelled [VideoDecoder.seekTo].
     */
    @Test
    fun aQueuedResizeCancelledBeforeItsTurnChangesNothing() = runBlocking<Unit> {
        val name = "cfr-30-faststart.mp4"
        val input = StallingFileHandle(readVideoDecoderFixture(name))
        try {
            VideoDecoder.open(
                MediaSource(name, CommandIo { input(name, input) }),
                VideoOutput.Memory(FrameFormat.Rgba8),
                CodecPreference.SOFTWARE,
            ).use { decoder ->
                assertEquals(147, decoder.frameAt(4.9.seconds).use { it.number() })
                input.stall()
                // Undispatched, each runs until it suspends: the frame's call takes the decoder and stalls
                // in the host read, so the resize waits for its turn.
                val stuck = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { decoder.frameAt(1.seconds).close() }
                val resize = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { decoder.resize(FrameSize(48, 32)) }
                assertTrue(resize.isActive && stuck.isActive)
                resize.cancelAndJoin()
                assertTrue(resize.isCancelled)
                stuck.cancelAndJoin()
                input.resume()
                decoder.frameAt(2.seconds).use { frame ->
                    assertEquals(FrameSize(96, 64), FrameSize(frame.width, frame.height))
                    assertEquals(60, frame.number())
                }
                decoder.resize(FrameSize(48, 32))
                decoder.frameAt(2.seconds).use { assertEquals(FrameSize(48, 32), FrameSize(it.width, it.height)) }
            }
        } finally {
            input.release()
        }
    }

    /** The frame's visible bytes, plane after plane, without row padding. */
    private fun VideoFrame.pixels(): ByteArray {
        val layout = assertNotNull(format).layout
        val rowBytes = when (layout) {
            PixelLayout.RGBA8, PixelLayout.BGRA8 -> listOf(width * 4)
            PixelLayout.RGBA_F16 -> listOf(width * 8)
            PixelLayout.NV12 -> listOf(width, width)
            else -> error("No byte comparison for $layout")
        }
        return assertNotNull(
            usePlanes { planes ->
                assertEquals(rowBytes.size, planes.size)
                planes.zip(rowBytes).flatMap { (plane, visible) ->
                    val bytes = ByteArray(plane.bytes.size).also { plane.bytes.copyInto(it) }
                    (0 until plane.rows).map { row -> bytes.copyOfRange(row * plane.rowBytes, row * plane.rowBytes + visible) }
                }.fold(ByteArray(0)) { all, row -> all + row }
            },
        )
    }

    private class Case(val clip: String, val format: FrameFormat, val preference: CodecPreference = CodecPreference.SOFTWARE)

    /** [decoder]'s frame at [position] is the one a decoder of [case] opened at [size] returns there. */
    private suspend fun assertSameAsOpenedAt(decoder: VideoDecoder, case: Case, size: FrameSize?, position: Duration) {
        decoder(case.clip, VideoOutput.Memory(case.format, size), case.preference).use { fresh ->
            decoder.frameAt(position).use { frame ->
                fresh.frameAt(position).use { expected ->
                    val name = "${case.clip} under ${case.preference} in ${case.format.layout} at ${size ?: "its own size"}, $position"
                    assertEquals(fresh.decoderKind, decoder.decoderKind, name)
                    assertEquals(FrameSize(expected.width, expected.height), FrameSize(frame.width, frame.height), name)
                    size?.let { assertEquals(it, FrameSize(frame.width, frame.height), name) }
                    assertEquals(expected.pts, frame.pts, name)
                    assertEquals(expected.sampleAspectRatio, frame.sampleAspectRatio, name)
                    assertEquals(expected.rotationDegrees, frame.rotationDegrees, name)
                    assertContentEquals(expected.pixels(), frame.pixels(), name)
                }
            }
        }
    }

    private suspend fun decoder(
        name: String,
        output: VideoOutput = VideoOutput.Memory(),
        preference: CodecPreference = CodecPreference.SOFTWARE,
    ): VideoDecoder = VideoDecoder.open(
        MediaSource(name, CommandIo { input(name, Buffer().write(readVideoDecoderFixture(name))) }),
        output,
        preference,
        10.seconds,
    )

    private companion object {
        const val RESIZED_AFTER = 10

        /** The decoder's ring per layout and size. */
        const val RING = 3
    }
}
