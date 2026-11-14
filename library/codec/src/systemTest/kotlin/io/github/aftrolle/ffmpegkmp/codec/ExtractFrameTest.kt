// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class, ExperimentalAtomicApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.nativeFrameStatistics
import io.github.aftrolle.ffmpegkmp.bindings.nativePlayerStatistics
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.FileHandle

/**
 * [VideoDecoder.extractFrame] on the JVM and Kotlin/Native. Each result is checked against what a
 * decoder opened at the expected size returns, turned and cut here in the test, pixel for pixel.
 */
class ExtractFrameTest {
    /**
     * How FFmpeg's tools show each fixture: the coded picture turned [anticlockwise] quarter turns,
     * then mirrored left to right ([hflip]) or top to bottom ([vflip]). These are the flags
     * generate.py gives `ffmpeg` (`-display_rotation`, `-display_hflip`, `-display_vflip`), read as
     * FFmpeg applies them, rotation first; `ffmpeg -i <fixture>` with autorotate shows each so.
     * The test derives the picture from the flags alone, not from the bridge's filter choice.
     */
    private data class Orientation(val name: String, val anticlockwise: Int, val hflip: Boolean = false, val vflip: Boolean = false) {
        val sideways: Boolean get() = anticlockwise % 2 == 1

        fun show(coded: Picture): Picture {
            var picture = coded
            repeat(anticlockwise) { picture = picture.anticlockwise() }
            if (hflip) picture = picture.mirrored()
            if (vflip) picture = picture.flipped()
            return picture
        }

        override fun toString(): String = name
    }

    /** Between them, every filter chain the bridge chooses from a display matrix. */
    private val orientations = listOf(
        Orientation("cfr-30.mp4", 0), // No matrix: nothing to turn.
        Orientation("rotated-90.mp4", 1), // transpose=cclock
        Orientation("rotated-180.mp4", 2), // vflip,hflip
        Orientation("rotated-270.mp4", 3), // transpose=clock
        Orientation("hflip.mp4", 0, hflip = true), // hflip
        Orientation("vflip.mp4", 0, vflip = true), // vflip
        Orientation("rotated-90-hflip.mp4", 1, hflip = true), // transpose=clock_flip
        Orientation("rotated-270-hflip.mp4", 3, hflip = true), // transpose=cclock_flip
    )

    /**
     * Each orientation whole, fitted into a box, and filled into a box that leaves an odd number of
     * pixels to cut. The frame code in the top-left corner makes every other turn or mirror of the
     * same size differ, so a wrong chain cannot pass.
     */
    @Test
    fun everyDisplayMatrixComesOutAsFFmpegShowsIt() = runBlocking<Unit> {
        val position = 5.seconds / 30
        for (orientation in orientations) {
            // The coded picture is 96x64 and the pixels square; the box is in upright pixels.
            for (case in listOf(
                // Whole: decoded at the coded size.
                Triple(null, FrameScale.FIT, FrameSize(96, 64)),
                // FIT 32x32: 32x21 either way round (21.33 rounded).
                Triple(FrameSize(32, 32), FrameScale.FIT, FrameSize(32, 21)),
                // CROP 41x41: the short side to 41, the long to 62 (61.5 rounded); 21 spare, cut at 10.
                Triple(FrameSize(41, 41), FrameScale.CROP, FrameSize(62, 41)),
            )) {
                val (box, scale, decoded) = case
                decoder(orientation.name, VideoOutput.Memory(FrameFormat.Rgba8, decoded)).use { decoder ->
                    decoder.frameAt(position).use { coded ->
                        val upright = orientation.show(Picture.of(coded))
                        val (width, height) = when {
                            box == null || scale == FrameScale.FIT -> upright.width to upright.height
                            else -> box.width to box.height
                        }
                        val expected = upright.cut((upright.width - width) / 2, (upright.height - height) / 2, width, height)
                        val what = "$orientation, ${box ?: "whole"} $scale"
                        extract(orientation.name, position, box, scale).use { frame ->
                            assertEquals(FrameSize(width, height), FrameSize(frame.width, frame.height), what)
                            assertEquals(0.0, frame.rotationDegrees, what)
                            assertEquals(1.0, frame.sampleAspectRatio, what)
                            assertEquals(coded.pts, frame.pts, what)
                            val actual = Picture.of(frame)
                            assertEquals(expected, actual, what)
                            for (variant in Picture.of(coded).variants()) {
                                if (variant == upright || variant.width != upright.width) continue
                                val wrong = variant.cut((variant.width - width) / 2, (variant.height - height) / 2, width, height)
                                assertNotEquals(wrong, actual, "$what: a wrong turn or mirror")
                            }
                        }
                    }
                }
            }
        }
        // The sizes the cases above rely on: sideways the decoder still decodes the coded way round.
        assertEquals(FrameSize(62, 41), frameFit(96, 64, 1.0, 90.0, FrameSize(41, 41), FrameScale.CROP).decodeSize)
    }

    /** An HDR source with a rotation is tone mapped and turned, at its own size and in a box. */
    @Test
    fun aRotatedHdrSourceIsToneMappedAndTurned() = runBlocking<Unit> {
        val name = "hdr10-pq-rotated-90.mp4"
        val position = 3.seconds / 30
        for ((box, decoded) in listOf(null to FrameSize(96, 64), FrameSize(48, 48) to FrameSize(48, 32))) {
            decoder(name, VideoOutput.Memory(FrameFormat.Rgba8, decoded)).use { decoder ->
                assertEquals(90.0, decoder.info.rotationDegrees)
                decoder.frameAt(position).use { coded ->
                    extract(name, position, box).use { frame ->
                        assertEquals(FrameSize(decoded.height, decoded.width), FrameSize(frame.width, frame.height))
                        assertEquals(Picture.of(coded).anticlockwise(), Picture.of(frame), "at ${box ?: "its own size"}")
                    }
                }
            }
        }
    }

    /** PQ and HLG come out tone mapped, as an RGBA8 decoder at that size gives them. */
    @Test
    fun hdrSourcesGiveTheRgba8DecodersPixels() = runBlocking<Unit> {
        for (clip in listOf("hdr10-pq.mp4", "hlg.mp4", "hdr10-pq-large.mp4", "hlg-large.mp4")) {
            for (box in listOf(null, FrameSize(48, 48))) {
                val position = 3.seconds / 30
                extract(clip, position, box).use { frame ->
                    val expectedSize = box?.let { FrameSize(48, if (clip.contains("large")) 29 else 32) }
                    decoder(clip, VideoOutput.Memory(FrameFormat.Rgba8, expectedSize)).use { decoder ->
                        decoder.frameAt(position).use { expected ->
                            assertEquals(Picture.of(expected), Picture.of(frame), "$clip at ${box ?: "its own size"}")
                            assertEquals(expected.pts, frame.pts)
                        }
                    }
                }
            }
        }
    }

    /** Under AUTO, on this host's decoder (VideoToolbox takes this H.264 clip on macOS): the AUTO decoder's frame. */
    @Test
    fun autoExtractsWithTheHostsDecoder() = runBlocking<Unit> {
        val name = "cfr-30-h264-128.mp4"
        decoder(name, VideoOutput.Memory(FrameFormat.Rgba8, FrameSize(64, 64)), CodecPreference.AUTO).use { decoder ->
            println("$name under AUTO: ${decoder.decoderKind}")
            decoder.frameAt(1.seconds).use { expected ->
                VideoDecoder.extractFrame(source(name), 1.seconds, FrameSize(64, 64)).use { frame ->
                    assertEquals(FrameSize(64, 64), FrameSize(frame.width, frame.height))
                    assertEquals(expected.pts, frame.pts)
                    assertEquals(Picture.of(expected), Picture.of(frame))
                }
            }
        }
    }

    /** Exactly frameAt's frame, between keyframes (cfr-30 has one every 12 frames) and past the end. */
    @Test
    fun theFrameIsTheOneFrameAtReturns() = runBlocking<Unit> {
        decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            for ((position, number) in listOf(17.5.seconds / 30 to 17, Duration.ZERO to 0, 100.seconds to 149)) {
                decoder.frameAt(position).use { expected ->
                    extract("cfr-30.mp4", position).use { frame ->
                        assertEquals(number, frame.number(), "at $position")
                        assertEquals(expected.pts, frame.pts, "at $position")
                        assertEquals(expected.duration, frame.duration, "at $position")
                        assertEquals(Picture.of(expected), Picture.of(frame), "at $position")
                    }
                }
            }
        }
    }

    @Test
    fun fitAndFillSizes() = runBlocking<Unit> {
        val position = 1.seconds
        // FIT 40x40: 96x64 at 40x27 (26.67 rounded).
        decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8, FrameSize(40, 27))).use { decoder ->
            decoder.frameAt(position).use { expected ->
                extract("cfr-30.mp4", position, FrameSize(40, 40)).use { frame -> assertEquals(Picture.of(expected), Picture.of(frame)) }
            }
        }
        // CROP 40x40: scaled to 60x40, columns 10 until 50.
        decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8, FrameSize(60, 40))).use { decoder ->
            decoder.frameAt(position).use { expected ->
                extract("cfr-30.mp4", position, FrameSize(40, 40), FrameScale.CROP).use { frame ->
                    assertEquals(Picture.of(expected).cut(10, 0, 40, 40), Picture.of(frame))
                }
            }
        }
        // CROP to the picture's own aspect cuts nothing: the frame is the decoder's at that size.
        decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8, FrameSize(48, 32))).use { decoder ->
            decoder.frameAt(position).use { expected ->
                extract("cfr-30.mp4", position, FrameSize(48, 32), FrameScale.CROP).use { frame ->
                    assertEquals(Picture.of(expected), Picture.of(frame))
                }
            }
        }
    }

    /** A box larger than the picture: FIT gives it at its own size, CROP its largest centred square. */
    @Test
    fun aLargerBoxNeverEnlarges() = runBlocking<Unit> {
        decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            decoder.frameAt(1.seconds).use { expected ->
                val whole = Picture.of(expected)
                extract("cfr-30.mp4", 1.seconds, FrameSize(1000, 1000)).use { assertEquals(whole, Picture.of(it)) }
                extract("cfr-30.mp4", 1.seconds, FrameSize(1000, 1000), FrameScale.CROP).use {
                    assertEquals(whole.cut(16, 0, 64, 64), Picture.of(it))
                }
                extract("cfr-30.mp4", 1.seconds).use { assertEquals(whole, Picture.of(it)) }
            }
        }
    }

    /** anamorphic-2x1.mp4: 96x64 coded with pixels twice as wide, so 192x64 with square pixels. */
    @Test
    fun anAnamorphicSourceComesOutWithSquarePixels() = runBlocking<Unit> {
        decoder("anamorphic-2x1.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { assertEquals(2.0, it.info.sampleAspectRatio) }
        for ((box, size) in listOf(null to FrameSize(192, 64), FrameSize(96, 96) to FrameSize(96, 32))) {
            decoder("anamorphic-2x1.mp4", VideoOutput.Memory(FrameFormat.Rgba8, size)).use { decoder ->
                decoder.frameAt(0.5.seconds).use { expected ->
                    extract("anamorphic-2x1.mp4", 0.5.seconds, box).use { frame ->
                        assertEquals(size, FrameSize(frame.width, frame.height))
                        assertEquals(1.0, frame.sampleAspectRatio)
                        assertEquals(Picture.of(expected), Picture.of(frame))
                    }
                }
            }
        }
    }

    /**
     * Nothing the call opened outlives it: no codec stays open and no decoder ring buffer stays
     * allocated. The result, turned or not, is libavfilter's copy, which these counters do not see.
     */
    @Test
    fun theCallClosesWhatItOpened() = runBlocking<Unit> {
        val baseline = Usage.now()
        extract("rotated-90.mp4", 0.5.seconds, FrameSize(32, 32)).use { frame ->
            assertEquals(baseline, Usage.now())
            assertEquals(FrameSize(21, 32), FrameSize(frame.width, frame.height))
        }
        extract("cfr-30.mp4", 0.5.seconds, FrameSize(48, 48)).use { assertEquals(baseline, Usage.now()) }
        extract("cfr-30.mp4", 0.5.seconds, FrameSize(40, 40), FrameScale.CROP).use { assertEquals(baseline, Usage.now()) }
        assertEquals(baseline, Usage.now())
    }

    /**
     * An HDR or rotated source asked for a thumbnail is tone mapped and scaled in one pass to the
     * small size. What is measured is the bridge's own pixel buffers: `bytes`, every one allocated
     * during the call (the decoder's ring and any conversion), and `ringBytes`, the ring's live
     * size at its peak, read while the decoder is still open. Neither counts libavfilter's buffers
     * (the small upright result) or the codec's own reference frames. Both stay under one RGBA8
     * frame of the source.
     */
    @Test
    fun aThumbnailNeedsNoFullSizeFrame() = runBlocking<Unit> {
        for (clip in listOf("hdr10-pq-large.mp4", "rotated-90.mp4")) {
            val before = nativeFrameStatistics().bytes
            val (width, height) = decoder(clip, VideoOutput.Memory()).use { it.info.width to it.info.height }
            val opened = nativeFrameStatistics().bytes
            val ringBefore = nativeFrameStatistics().ringBytes
            var ringPeak = 0L
            extractUprightFrame(source(clip), 0.5.seconds, FrameSize(32, 32), FrameScale.FIT, CodecPreference.SOFTWARE, 10.seconds) {
                ringPeak = nativeFrameStatistics().ringBytes - ringBefore
            }.close()
            val allocated = nativeFrameStatistics().bytes - opened
            val fullFrame = width.toLong() * height * 4
            println(
                "$clip (${width}x$height) to a 32-pixel thumbnail: $allocated bytes of pixel memory, ring peak $ringPeak " +
                    "(${opened - before} to open as decoded)",
            )
            assertTrue(ringPeak > 0, "the ring was read while open")
            assertTrue(ringPeak < fullFrame, "a $ringPeak-byte ring for a ${width}x$height source")
            assertTrue(allocated < fullFrame, "$allocated bytes for a ${width}x$height source")
        }
    }

    @Test
    fun aMissingOrUndecodableSourceFails() = runBlocking<Unit> {
        val baseline = Usage.now()
        assertFailsWith<VideoDecodingException> {
            VideoDecoder.extractFrame(MediaSource("/no/such/directory/missing.mp4"), decoder = CodecPreference.SOFTWARE)
        }
        val garbage = ByteArray(4096) { (it * 31 + 7).toByte() }
        assertFailsWith<VideoDecodingException> {
            VideoDecoder.extractFrame(MediaSource("garbage.mp4", CommandIo { input("garbage.mp4", Buffer().write(garbage)) }))
        }
        assertFailsWith<IllegalArgumentException> { extract("cfr-30.mp4", (-1).seconds) }
        assertEquals(baseline, Usage.now())
    }

    /** A call cancelled while the input stalls returns, and closes its decoder once the read gives up. */
    @Test
    fun aCancelledCallClosesEverything() = runBlocking<Unit> {
        val baseline = Usage.now()
        val input = StallingTail(readVideoDecoderFixture("cfr-60.mp4"))
        try {
            val call = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                VideoDecoder.extractFrame(input.source("cfr-60.mp4"), 4.9.seconds, decoder = CodecPreference.SOFTWARE)
            }
            input.awaitStall()
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
        } finally {
            input.release()
        }
        awaitUsage(baseline)
    }

    /** A call that outlives its timeout fails with VideoDecodingException and closes everything. */
    @Test
    fun aTimedOutCallClosesEverything() = runBlocking<Unit> {
        val baseline = Usage.now()
        val input = StallingTail(readVideoDecoderFixture("cfr-60.mp4"))
        try {
            val failure = assertFailsWith<VideoDecodingException> {
                VideoDecoder.extractFrame(input.source("cfr-60.mp4"), 4.9.seconds, decoder = CodecPreference.SOFTWARE, timeout = 1.seconds)
            }
            assertContains(failure.message.orEmpty(), "timed out")
            assertTrue(input.stalled.load(), "the input stalled")
        } finally {
            input.release()
        }
        awaitUsage(baseline)
    }

    /** A timeout that lands after the result is made, before the call returns, closes the result. */
    @Test
    fun aTimeoutAfterTheFrameIsMadeClosesIt() = runBlocking<Unit> {
        val baseline = Usage.now()
        var made: VideoFrame? = null
        val failure = assertFailsWith<VideoDecodingException> {
            extractUprightFrame(source("rotated-90.mp4"), 0.5.seconds, FrameSize(32, 32), FrameScale.CROP, CodecPreference.SOFTWARE, 2.seconds) {
                made = it
                delay(30.seconds)
            }
        }
        assertContains(failure.message.orEmpty(), "timed out")
        assertClosed(assertNotNullFrame(made))
        awaitUsage(baseline)
    }

    /** A cancellation that lands after the result is made, before the call returns, closes the result. */
    @Test
    fun aCancellationAfterTheFrameIsMadeClosesIt() = runBlocking<Unit> {
        val baseline = Usage.now()
        val made = CompletableDeferred<VideoFrame>()
        val call = async(Dispatchers.Default) {
            extractUprightFrame(source("cfr-30.mp4"), 0.5.seconds, null, FrameScale.FIT, CodecPreference.SOFTWARE, 10.seconds) {
                made.complete(it)
                awaitCancellation()
            }
        }
        val frame = withTimeout(10.seconds) { made.await() }
        call.cancelAndJoin()
        assertTrue(call.isCancelled)
        assertClosed(frame)
        awaitUsage(baseline)
    }

    /**
     * An attempt under AUTO that times out on a hardware decoder is made once more in software: the
     * input stalls once, for longer than the timeout, so only a second attempt gets the frame. Where
     * AUTO opens software, and under REQUIRE_HARDWARE, there is one attempt, and it fails.
     */
    @Test
    fun autoRetriesInSoftwareWhenAHardwareAttemptTimesOut() = runBlocking<Unit> {
        val name = "cfr-30-h264-128.mp4"
        val kind = decoder(name, VideoOutput.Memory(FrameFormat.Rgba8), CodecPreference.AUTO).use { it.decoderKind }
        println("$name under AUTO: $kind")
        val baseline = Usage.now()
        val timeout = 2.seconds
        val input = StallingTail(readVideoDecoderFixture(name), once = true, stallFor = 3.seconds)
        try {
            val started = TimeSource.Monotonic.markNow()
            if (kind == DecoderKind.HARDWARE) {
                VideoDecoder.extractFrame(input.source(name), 4.9.seconds, decoder = CodecPreference.AUTO, timeout = timeout).use { frame ->
                    assertEquals(147, frame.number())
                }
                assertTrue(input.stalled.load(), "the first attempt stalled")
                // The first attempt's whole timeout, its bounded close, and the second attempt.
                assertTrue(started.elapsedNow() < timeout * 2 + 2.seconds, "took ${started.elapsedNow()}")
                val again = StallingTail(readVideoDecoderFixture(name), once = true, stallFor = 3.seconds)
                try {
                    assertFailsWith<VideoDecodingException> {
                        VideoDecoder.extractFrame(again.source(name), 4.9.seconds, decoder = CodecPreference.REQUIRE_HARDWARE, timeout = timeout)
                    }
                } finally {
                    again.release()
                }
            } else {
                val failure = assertFailsWith<VideoDecodingException> {
                    VideoDecoder.extractFrame(input.source(name), 4.9.seconds, decoder = CodecPreference.AUTO, timeout = timeout)
                }
                assertContains(failure.message.orEmpty(), "timed out")
            }
        } finally {
            input.release()
        }
        awaitUsage(baseline)
    }

    private fun assertNotNullFrame(frame: VideoFrame?): VideoFrame = kotlin.test.assertNotNull(frame, "the hook saw the result")

    private fun assertClosed(frame: VideoFrame) {
        assertFailsWith<IllegalStateException>("the result was closed") { frame.usePlanes { } }
    }

    /** What the bridge holds open: codecs, and the decoders' ring buffers. */
    private data class Usage(val codecs: Long, val ringBuffers: Long, val ringBytes: Long) {
        companion object {
            fun now(): Usage {
                val players = nativePlayerStatistics()
                val frames = nativeFrameStatistics()
                return Usage(players.codecOpens - players.codecCloses, frames.ringBuffers, frames.ringBytes)
            }
        }
    }

    /** Waits for a decoder whose close waited behind a stalled read to finish closing. */
    private suspend fun awaitUsage(expected: Usage) {
        try {
            withTimeout(10.seconds) { while (Usage.now() != expected) delay(10.milliseconds) }
        } finally {
            assertEquals(expected, Usage.now())
        }
    }

    /** RGBA8 pixels, packed, comparable. */
    private data class Picture(val width: Int, val height: Int, val pixels: List<Int>) {
        fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

        /** Turned a quarter anticlockwise: the top row becomes the left column, read upwards. */
        fun anticlockwise(): Picture = Picture(height, width, List(width * height) { index ->
            val x = index % height
            val y = index / height
            pixel(width - 1 - y, x)
        })

        /** Mirrored left to right. */
        fun mirrored(): Picture = Picture(width, height, List(width * height) { index -> pixel(width - 1 - index % width, index / width) })

        /** Mirrored top to bottom. */
        fun flipped(): Picture = Picture(width, height, List(width * height) { index -> pixel(index % width, height - 1 - index / width) })

        /** All eight turns and mirrors of this picture. */
        fun variants(): List<Picture> = (0 until 4).flatMap { turns ->
            var turned = this
            repeat(turns) { turned = turned.anticlockwise() }
            listOf(turned, turned.mirrored())
        }

        fun cut(left: Int, top: Int, cutWidth: Int, cutHeight: Int): Picture =
            Picture(cutWidth, cutHeight, List(cutWidth * cutHeight) { index -> pixel(left + index % cutWidth, top + index / cutWidth) })

        override fun toString(): String = "Picture(${width}x$height)"

        companion object {
            fun of(frame: VideoFrame): Picture {
                assertEquals(PixelLayout.RGBA8, frame.format?.layout)
                val bytes = frame.packed(4)
                return Picture(frame.width, frame.height, List(frame.width * frame.height) { index ->
                    (bytes[index * 4].toInt() and 0xff shl 24) or (bytes[index * 4 + 1].toInt() and 0xff shl 16) or
                        (bytes[index * 4 + 2].toInt() and 0xff shl 8) or (bytes[index * 4 + 3].toInt() and 0xff)
                })
            }
        }
    }

    /**
     * Serves an MP4; reads of the second half of its media data block, so opening (the index and the
     * first frames) succeeds and a frame near the end stalls. A stalled read waits for [release], or
     * [stallFor] when given, then fails. With [once], only the first read there stalls and the
     * rest are served, as a second attempt at the same source needs.
     */
    private class StallingTail(
        private val bytes: ByteArray,
        private val once: Boolean = false,
        private val stallFor: Duration? = null,
    ) : FileHandle(readWrite = false) {
        val stalled = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private val stallFrom: Long
        private val stallUntil: Long

        init {
            var offset = 0L
            var mdat = 0L until 0L
            while (offset + 8 <= bytes.size) {
                val size = (0 until 4).fold(0L) { value, index -> value shl 8 or (bytes[(offset + index).toInt()].toLong() and 0xff) }
                val type = bytes.decodeToString((offset + 4).toInt(), (offset + 8).toInt())
                if (type == "mdat") mdat = offset until offset + size
                if (size < 8) break
                offset += size
            }
            check(!mdat.isEmpty()) { "No media data" }
            stallFrom = mdat.first + (mdat.last - mdat.first) / 2
            stallUntil = mdat.last + 1
        }

        fun source(name: String): MediaSource = MediaSource(name, CommandIo { input(name, this@StallingTail) })

        suspend fun awaitStall() = withTimeout(10.seconds) { while (!stalled.load()) delay(10.milliseconds) }

        fun release() = released.store(true)

        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
            if (fileOffset + byteCount > stallFrom && fileOffset < stallUntil) {
                val first = stalled.compareAndSet(expectedValue = false, newValue = true)
                if (first || !once) {
                    val started = TimeSource.Monotonic.markNow()
                    while (!released.load() && (stallFor == null || started.elapsedNow() < stallFor)) pauseBriefly()
                    return -1
                }
            }
            if (fileOffset >= bytes.size) return -1
            val count = minOf(byteCount.toLong(), bytes.size - fileOffset).toInt()
            bytes.copyInto(array, arrayOffset, fileOffset.toInt(), fileOffset.toInt() + count)
            return count
        }

        override fun protectedSize(): Long = bytes.size.toLong()

        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            throw UnsupportedOperationException()

        override fun protectedFlush() = Unit

        override fun protectedResize(size: Long) = throw UnsupportedOperationException()

        override fun protectedClose() = Unit
    }

    private suspend fun extract(
        name: String,
        position: Duration,
        size: FrameSize? = null,
        scale: FrameScale = FrameScale.FIT,
    ): VideoFrame = VideoDecoder.extractFrame(source(name), position, size, scale, CodecPreference.SOFTWARE)

    private suspend fun decoder(name: String, output: VideoOutput, preference: CodecPreference = CodecPreference.SOFTWARE): VideoDecoder =
        VideoDecoder.open(source(name), output, preference, 10.seconds)

    private fun source(name: String): MediaSource =
        MediaSource(name, CommandIo { input(name, Buffer().write(readVideoDecoderFixture(name))) })
}
