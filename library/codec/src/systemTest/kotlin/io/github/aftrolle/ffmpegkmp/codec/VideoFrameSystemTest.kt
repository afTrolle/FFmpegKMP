// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.nativeFrameStatistics
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem

/** Frame references and pools against the real bridge, on the JVM and Kotlin/Native. */
class VideoFrameSystemTest {
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "video-frames-${Random.nextLong().toULong()}"

    @AfterTest
    fun cleanUp() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    @Test
    fun framesOutliveTheDecoderAndEachReferenceClosesOnce() = runBlocking<Unit> {
        val decoder = decoder("cfr-24.mp4", VideoOutput.Memory(FrameFormat.Rgba8))
        val frame = decoder.frameAt(1.seconds)
        val retained = frame.retain()
        decoder.close()
        assertEquals(24, frame.number())
        frame.close()
        frame.close()
        assertFailsWith<IllegalStateException> { frame.number() }
        assertFailsWith<IllegalStateException> { frame.retain() }
        assertFailsWith<IllegalStateException> { frame.convert(FrameFormat.RgbaF16) }
        // The other reference still holds the memory.
        assertEquals(24, retained.number())
        retained.close()
        assertFailsWith<IllegalStateException> { retained.usePlanes { } }
    }

    @Test
    fun planeViewsAreValidOnlyInsideUsePlanes() = runBlocking<Unit> {
        decoder("cfr-24.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { frame ->
                val escaped = assertNotNull(frame.usePlanes { planes -> planes.single().bytes })
                assertFailsWith<IllegalStateException> { escaped[0] }
                assertFailsWith<IllegalStateException> { escaped.copyInto(ByteArray(escaped.size)) }
                // Closing inside the block releases the memory once the block is done with it.
                frame.usePlanes { planes ->
                    frame.close()
                    assertEquals(-1, planes.single().bytes[3].toInt(), "opaque alpha, still readable")
                }
                assertFailsWith<IllegalStateException> { frame.usePlanes { } }
            }
        }
    }

    @Test
    fun convertingToTheFramesOwnFormatRetainsIt() = runBlocking<Unit> {
        decoder("cfr-24.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { frame ->
                val before = nativeFrameStatistics().buffers
                frame.convert(FrameFormat.Rgba8).use { same ->
                    assertEquals(FrameFormat.Rgba8, same.format)
                    assertEquals(before, nativeFrameStatistics().buffers, "no copy for the same format")
                }
                assertEquals(0, frame.number(), "the original is still open")
                assertFailsWith<IllegalArgumentException> { frame.convertInto(frame) }
            }
        }
    }

    @Test
    fun aPositionTheHeldFrameCoversIsNeitherDecodedNorConvertedAgain() = runBlocking<Unit> {
        decoder("cfr-24.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { first ->
                val before = nativeFrameStatistics().buffers
                // With the first held, a conversion would need a pool buffer of its own.
                decoder.frameAt(40.milliseconds).use { again ->
                    assertEquals(Duration.ZERO, again.pts)
                    assertEquals(0, again.number())
                }
                assertEquals(before, nativeFrameStatistics().buffers)
                assertEquals(0, first.number())
            }
        }
    }

    @Test
    fun aDecodersRingHoldsThreeFramesAndAFourthWaitsForOneToClose() = runBlocking<Unit> {
        decoder("cfr-24.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            val start = nativeFrameStatistics().buffers
            val open = (0 until 3).map { index -> decoder.frameAt(1.seconds / 24 * index) }
            open.forEachIndexed { index, frame -> assertEquals(index, frame.number()) }
            // The ring is full: a fourth frame waits for one of the three to close.
            val fourth = async(Dispatchers.Default) { decoder.frameAt(1.seconds / 24 * 3) }
            delay(200.milliseconds)
            assertFalse(fourth.isCompleted, "the fourth frame waits while three are held")
            open.first().close()
            fourth.await().use { frame -> assertEquals(3, frame.number()) }
            open.drop(1).forEach(VideoFrame::close)

            repeat(200) { tick ->
                // Past the end the position wraps, so the loop also seeks back to the start.
                val index = tick % 120
                decoder.frameAt(1.seconds / 24 * index).use { frame -> assertEquals(index, frame.number()) }
            }
            // The ring's buffers are made once; a leaked frame would have stopped the loop instead.
            assertEquals(3L, nativeFrameStatistics().buffers - start, "buffers made for 204 frames")
        }
    }

    @Test
    fun aFourthFrameWhileTheCallerHoldsTheRingFailsAtOnceAndTheDecoderGoesOnOnceOneCloses() = runBlocking<Unit> {
        decoder("cfr-24.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            val open = (0 until 3).map { index -> decoder.frameAt(1.seconds / 24 * index) }
            val asked = TimeSource.Monotonic.markNow()
            val failure = assertFailsWith<IllegalStateException> {
                withContext(Dispatchers.Default) { decoder.frameAt(1.seconds / 24 * 3) }
            }
            val took = asked.elapsedNow()
            println("The fourth frame failed after $took: ${failure.message}")
            assertContains(failure.message.orEmpty(), "holds all 3")
            assertTrue(took < 2.seconds, "failed after $took, well before the 10 s timeout")
            open.first().close()
            decoder.frameAt(1.seconds / 24 * 3).use { assertEquals(3, it.number()) }
            // The frames it handed out stay valid, and a frame closing during the call's grace lets it through.
            open.drop(1).forEachIndexed { index, frame -> frame.use { assertEquals(index + 1, it.number()) } }
            val held = (0 until 3).map { index -> decoder.frameAt(1.seconds / 24 * (10 + index)) }
            val fourth = async(Dispatchers.Default) { decoder.frameAt(1.seconds / 24 * 13) }
            delay(50.milliseconds)
            held.first().close()
            fourth.await().use { assertEquals(13, it.number()) }
            held.drop(1).forEach(VideoFrame::close)
        }
    }

    @Test
    fun conversionsAndRetainsKeepMoreThanTheRingHolds() = runBlocking<Unit> {
        decoder("cfr-24.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            // A conversion copies into the process-wide pool, so the decoded frame can close.
            val kept = (0 until 6).map { index -> decoder.frameAt(1.seconds / 24 * index).use { it.convert(FrameFormat.Nv12) } }
            decoder.frameAt(1.seconds / 4).use { frame ->
                // A retain shares its frame's slot, however many there are.
                val references = (0 until 6).map { frame.retain() }
                decoder.frameAt(1.seconds / 24 * 7).use { assertEquals(7, it.number()) }
                references.forEach { reference -> reference.use { assertEquals(6, it.number()) } }
            }
            kept.forEachIndexed { index, frame -> frame.use { assertEquals(index, it.number()) } }
        }
    }

    @Test
    fun a4kFrameIsInOneBufferAsDecodedAndOnePooledOneConverted() = runBlocking<Unit> {
        val clip = uhdClip()
        VideoDecoder.open(MediaSource(clip), decoder = DecoderPreference.SOFTWARE).use { decoder ->
            val before = nativeFrameStatistics().buffers
            decoder.frameAt(Duration.ZERO).use { frame ->
                assertEquals(3840 to 2160, frame.width to frame.height)
                val format = assertNotNull(frame.format)
                // The decoder's own buffer, handed out. The Apple runtimes copy it into a pooled
                // NV12 CVPixelBuffer instead, which this count does not see.
                if (format.layout == PixelLayout.YUV420P) {
                    assertEquals(before, nativeFrameStatistics().buffers, "no buffer allocated as decoded")
                }
            }
        }
        VideoDecoder.open(MediaSource(clip), VideoOutput.Memory(FrameFormat.Rgba8), DecoderPreference.SOFTWARE).use { decoder ->
            val before = nativeFrameStatistics()
            decoder.frameAt(Duration.ZERO).close()
            val first = nativeFrameStatistics()
            assertEquals(1L, first.buffers - before.buffers, "one pooled RGBA8 buffer")
            assertTrue(first.bytes - before.bytes >= 3840L * 2160 * 4)
            // Closed, it goes back to the pool for the next frame.
            decoder.frameAt(1.seconds / 24).close()
            decoder.frameAt(2.seconds / 24).close()
            assertEquals(first.buffers, nativeFrameStatistics().buffers, "the pooled buffer reused")
        }
    }

    @Test
    fun framesOfDifferentReferencesConvertOnDifferentThreadsAtOnce() = runBlocking<Unit> {
        decoder("cfr-24.mp4").use { decoder ->
            // Three: on Apple frames as decoded come from the decoder's ring.
            val frames = (0 until 3).map { decoder.frameAt(1.seconds / 24 * it) }
            val converted = frames.map { frame ->
                async(Dispatchers.Default) { frame.use { it.convert(FrameFormat.Rgba8) } }
            }.map { it.await() }
            converted.forEachIndexed { index, frame -> frame.use { assertEquals(index, it.number()) } }
        }
    }

    /** Two seconds of cfr-30.mp4 scaled to 3840x2160, MPEG-4 Part 2 so every build decodes it in software. */
    private suspend fun uhdClip(): String {
        FileSystem.SYSTEM.createDirectories(directory)
        val path = directory / "uhd.mp4"
        val result = FFmpegClient().use { client ->
            client.execute(
                listOf(
                    "-y", "-i", "cfr-30.mp4", "-vf", "scale=3840:2160", "-frames:v", "3",
                    "-c:v", "mpeg4", "-q:v", "8", "-f", "mp4", path.toString(),
                ),
                CommandIo { input("cfr-30.mp4", Buffer().write(readVideoDecoderFixture("cfr-30.mp4"))) },
            )
        }
        assertTrue(result.isSuccess, result.errorOutput)
        return path.toString()
    }

    private suspend fun decoder(
        name: String,
        output: VideoOutput = VideoOutput.Memory(),
        timeout: Duration = 10.seconds,
    ): VideoDecoder = VideoDecoder.open(
        MediaSource(name, CommandIo { input(name, Buffer().write(readVideoDecoderFixture(name))) }),
        output,
        DecoderPreference.SOFTWARE,
        timeout,
    )
}
