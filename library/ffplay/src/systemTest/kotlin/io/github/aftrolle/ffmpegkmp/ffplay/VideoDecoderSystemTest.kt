// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.asSkiaBitmap
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.Source
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * Runs on the JVM and Kotlin/Native against the real bridge. Fixtures and their generator live in
 * commonTest/resources/video-decoder; each test source set provides [readVideoDecoderFixture],
 * [stalledSource] and [pauseBriefly].
 */
class VideoDecoderSystemTest {
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "video-decoder-${Random.nextLong().toULong()}"

    @AfterTest
    fun cleanUp() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    @Test
    fun constantFrameRatesAreSampledOntoA30FpsClock() = runBlocking {
        for (fps in listOf(24, 30, 60)) {
            decoder("cfr-$fps.mp4").use { decoder ->
                assertEquals(5.seconds, decoder.duration)
                repeat(5 * CLOCK_FPS) { tick ->
                    val time = tick.seconds / CLOCK_FPS
                    val frame = decoder.frameAt(time)
                    val expected = tick * fps / CLOCK_FPS
                    assertEquals(expected, frame.number(), "$fps fps source at tick $tick ($time)")
                    assertClose(expected.seconds / fps, frame.pts, 1.microseconds, "pts of frame $expected")
                    assertClose(1.seconds / fps, frame.duration, 1.microseconds, "duration of frame $expected")
                }
            }
        }
    }

    @Test
    fun theHeldFrameOfAVariableFrameRateSourceChangesExactlyAtEachPts() = runBlocking {
        val starts = (0 until VFR_FRAMES)
            .runningFold(0) { start, index -> start + VFR_DURATIONS_MS[index % VFR_DURATIONS_MS.size] }
            .map { it.milliseconds }
        decoder("vfr.mp4").use { decoder ->
            for (index in 0 until VFR_FRAMES) {
                val pts = starts[index]
                if (index > 0) {
                    // One time-base unit (1 ms) and just over half of one before: still the previous frame.
                    assertEquals(index - 1, decoder.frameAt(pts - 1.milliseconds).number(), "1 ms before frame $index")
                    assertEquals(index - 1, decoder.frameAt(pts - 600.microseconds).number(), "0.6 ms before $index")
                }
                val frame = decoder.frameAt(pts)
                assertEquals(index, frame.number(), "at the pts of frame $index")
                assertEquals(pts, frame.pts)
                if (index < VFR_FRAMES - 1) assertEquals(starts[index + 1] - pts, frame.duration, "duration of $index")
                assertEquals(index, decoder.frameAt(starts[index + 1] - 1.milliseconds).number(), "end of $index")
            }
        }
    }

    @Test
    fun seeksBackwardAndForwardAcrossKeyframesLandOnTheSequentialFrame() = runBlocking {
        decoder("cfr-30.mp4").use { decoder ->
            val sequential = (0 until 150).map { index -> decoder.frameAt(index.seconds / 30).number() }
            assertEquals((0 until 150).toList(), sequential)
            // Every GOP boundary (keyframes every 12 frames) and its neighbours, in a scrambled order.
            val positions = (0 until 150).filter { it % 12 in setOf(0, 1, 11) } + listOf(149, 75, 3)
            for (index in positions.shuffled(Random(7))) {
                assertEquals(sequential[index], decoder.frameAt(index.seconds / 30).number(), "frameAt frame $index")
                val between = index.seconds / 30 + 10.milliseconds
                decoder.seekTo(between)
                assertEquals(sequential[index], decoder.current?.number(), "seekTo inside frame $index")
            }
        }
    }

    @Test
    fun boundariesHoldTheFirstAndLastFrames() = runBlocking {
        decoder("cfr-24.mp4").use { decoder ->
            assertNull(decoder.current)
            val first = decoder.frameAt(Duration.ZERO)
            assertEquals(0, first.number())
            assertEquals(Duration.ZERO, first.pts)
            assertSame(first, decoder.current)
            // A position the current frame still covers returns it without decoding.
            assertSame(first, decoder.frameAt(40.milliseconds))

            val onPts = decoder.frameAt(1.seconds / 24 * 13)
            assertEquals(13, onPts.number())

            val last = decoder.frameAt(10.seconds)
            assertEquals(119, last.number())
            assertSame(last, decoder.frameAt(1_000.seconds))
            assertSame(last, decoder.frameAt(Duration.INFINITE))
            assertClose(5.seconds, last.pts + last.duration, 1.microseconds, "end of the last frame")

            assertEquals(0, decoder.frameAt(Duration.ZERO).number(), "back to the start after the end")
        }
    }

    @Test
    fun aRotatedSourceReportsItsRotationAndKeepsTheCodedSize() = runBlocking {
        decoder("rotated-90.mp4").use { decoder ->
            assertEquals(90.0, decoder.info.rotationDegrees)
            assertEquals(96, decoder.info.width)
            assertEquals(64, decoder.info.height)
            val frame = decoder.frameAt(Duration.ZERO)
            assertEquals(90.0, frame.rotationDegrees)
            assertEquals(1.0, frame.sampleAspectRatio)
            val image = assertNotNull(frame.image)
            assertEquals(96, image.width)
            assertEquals(64, image.height)
            assertEquals(0, frame.number())
        }
    }

    @Test
    fun anHdr10SourceReportsItsMetadataAndKeepsHighlightsInLinearF16() = runBlocking {
        decoder("hdr10-pq.mp4", VideoOutput.LinearF16).use { decoder ->
            val info = decoder.info
            assertEquals(FFplayHdrType.HDR10, info.hdrType)
            assertEquals("PQ", info.colorTransfer)
            assertEquals("BT.2020", info.colorPrimaries)
            assertEquals(10, info.bitDepth)
            assertEquals("1000.0", info.masteringDisplay?.raw?.get("maxLuminance"))
            assertEquals(1000, info.contentLight?.maxContentLightLevel)
            assertEquals(400, info.contentLight?.maxFrameAverageLightLevel)

            val pixels = decoder.frameAt(0.5.seconds).linearPixels()
            // 100 nits on the left and 1000 nits on the right, with 1.0 = 203 nits.
            assertEquals(100.0 / 203.0, pixels.at(x = 12, y = 32), 0.02)
            assertEquals(1000.0 / 203.0, pixels.at(x = 84, y = 32), 0.15)
        }
        decoder("hdr10-pq.mp4", VideoOutput.Rgba8).use { decoder ->
            val image = assertNotNull(decoder.frameAt(Duration.ZERO).image)
            val argb = IntArray(image.width * image.height).also { image.readPixels(it) }
            val left = argb[32 * image.width + 12] and 0xff
            val right = argb[32 * image.width + 84] and 0xff
            assertTrue(right in (left + 1)..255, "Tone mapped highlight $right should stay above $left")
        }
    }

    @Test
    fun hardwareDecodingReturnsTheSameFramesWhereTheHostHasIt() = runBlocking {
        decoder("hdr10-pq.mp4", VideoOutput.LinearF16, FFplayDecoderPreference.AUTO).use { decoder ->
            if (decoder.decoderKind == FFplayDecoderKind.HARDWARE) assertEquals("p010le", decoder.info.pixelFormat)
            assertEquals(1000.0 / 203.0, decoder.frameAt(Duration.ZERO).linearPixels().at(x = 84, y = 32), 0.15)
        }
        // No hardware decoder takes these MPEG-4 Part 2 clips, so AUTO falls back to software.
        decoder("cfr-30.mp4", decoder = FFplayDecoderPreference.AUTO).use { decoder ->
            assertEquals(FFplayDecoderKind.SOFTWARE, decoder.decoderKind)
            assertEquals(45, decoder.frameAt(1.5.seconds).number())
        }
    }

    @Test
    fun imagesStayIntactWhileLaterFramesAreDecoded() = runBlocking {
        for (output in listOf(VideoOutput.Rgba8, VideoOutput.LinearF16)) {
            decoder("cfr-30.mp4", output).use { decoder ->
                val first = decoder.frameAt(Duration.ZERO)
                val later = decoder.frameAt(2.seconds)
                assertEquals(0, first.number(), "$output frame 0 after decoding frame 60")
                assertEquals(60, later.number(), "$output frame 60")
            }
        }
    }

    @Test
    fun aPathInputDecodesLikeAMountedOne() = runBlocking {
        FileSystem.SYSTEM.createDirectories(directory)
        val file = directory / "cfr-60.mp4"
        FileSystem.SYSTEM.write(file) { write(fixture("cfr-60.mp4")) }
        VideoDecoder.open(FFplaySource(file.toString()), VideoOutput.Rgba8, FFplayDecoderPreference.SOFTWARE).use {
            assertEquals(150, it.frameAt(2.5.seconds).number())
        }
    }

    @Test
    fun invalidRequestsFail() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> {
            VideoDecoder.open(FFplaySource("cfr-30.mp4"), VideoOutput.Surface(Any()))
        }
        assertFailsWith<IllegalArgumentException> {
            VideoDecoder.open(
                FFplaySource("x.mp4", protection = FFplayContentProtection.REQUIRE_SECURE_PATH),
                VideoOutput.Rgba8,
            )
        }
        assertFailsWith<VideoDecodingException> {
            VideoDecoder.open(FFplaySource((directory / "missing.mp4").toString()), VideoOutput.Rgba8)
        }
        val decoder = decoder("cfr-30.mp4")
        assertFailsWith<IllegalArgumentException> { decoder.frameAt((-1).milliseconds) }
        decoder.close()
        decoder.close()
        assertFailsWith<IllegalStateException> { decoder.frameAt(Duration.ZERO) }
    }

    @Test
    fun openTimesOutOnAMountedSourceThatNeverDelivers() = runBlocking {
        val stalled = stalledSource(ByteArray(0))
        try {
            val started = TimeSource.Monotonic.markNow()
            val failure = assertFailsWith<VideoDecodingException> {
                // AUTO: the stall comes before any decoder, so there is nothing to fall back from.
                decoder("stalled.mp4", decoder = FFplayDecoderPreference.AUTO, source = stalled.source, timeout = TIMEOUT)
            }
            assertContains(failure.message.orEmpty(), "timed out after $TIMEOUT")
            // Timeout, watchdog grace, and the bounded close of a decoder whose read is still blocked.
            assertTrue(started.elapsedNow() < 5.seconds, "open took ${started.elapsedNow()}")
        } finally {
            stalled.release()
        }
    }

    @Test
    fun frameAtTimesOutWhenTheInputStallsMidStreamAndCloseReturnsPromptly() = runBlocking {
        val name = "cfr-30-faststart.mp4"
        val input = StallingFileHandle(fixture(name))
        try {
            val decoder = VideoDecoder.open(
                FFplaySource(name, CommandIo { input(name, input) }),
                VideoOutput.Rgba8,
                FFplayDecoderPreference.SOFTWARE,
                TIMEOUT,
            )
            // The last host read is buffered; going back to the start needs a new one.
            assertEquals(147, decoder.frameAt(4.9.seconds).number())
            input.stall()
            val started = TimeSource.Monotonic.markNow()
            val failure = assertFailsWith<VideoDecodingException> { decoder.frameAt(1.seconds) }
            assertContains(failure.message.orEmpty(), "timed out after $TIMEOUT")
            assertTrue(started.elapsedNow() < 3.seconds, "frameAt took ${started.elapsedNow()}")
            assertFailsWith<IllegalStateException> { decoder.frameAt(Duration.ZERO) }
            val closing = TimeSource.Monotonic.markNow()
            decoder.close()
            assertTrue(closing.elapsedNow() < 2.seconds, "close took ${closing.elapsedNow()}")
        } finally {
            input.release()
        }
    }

    @Test
    fun aTruncatedInputFailsOrHoldsTheLastGoodFrameButNeverHangs() = runBlocking {
        // Cut mid-file, the moov-last original loses its index and cannot open.
        val original = fixture("cfr-30.mp4")
        assertFailsWith<VideoDecodingException> {
            decoder("cut.mp4", source = Buffer().write(original.copyOf(original.size / 2)), timeout = TIMEOUT)
        }
        val faststart = fixture("cfr-30-faststart.mp4")
        decoder("cut.mp4", source = Buffer().write(faststart.copyOf(faststart.size / 2)), timeout = TIMEOUT).use { decoder ->
            var last = -1
            for (tick in 0 until 150) {
                val frame = try {
                    decoder.frameAt(tick.seconds / 30)
                } catch (failure: VideoDecodingException) {
                    assertFalse("timed out" in failure.message.orEmpty(), "frame $tick: ${failure.message}")
                    break
                }
                val number = frame.number()
                assertTrue(number in last..tick, "frame $tick decoded as $number after $last")
                last = number
            }
            assertTrue(last in 30 until 149, "the last good frame before the cut was $last")
        }
    }

    private suspend fun decoder(
        name: String,
        output: VideoOutput = VideoOutput.Rgba8,
        decoder: FFplayDecoderPreference = FFplayDecoderPreference.SOFTWARE,
        source: Source = Buffer().write(fixture(name)),
        timeout: Duration = 10.seconds,
    ): VideoDecoder = VideoDecoder.open(
        FFplaySource(name, CommandIo { input(name, source) }),
        output,
        decoder,
        timeout,
    )

    private fun fixture(name: String): ByteArray = readVideoDecoderFixture(name)

    /** Reads the 12-bit frame number the fixture generator draws as 16x16 cells in the top rows. */
    private fun VideoFrame.number(): Int {
        val image = assertNotNull(image)
        val argb = IntArray(image.width * image.height).also { image.readPixels(it) }
        return (0 until 12).sumOf { bit ->
            val x = (bit % 6) * 16 + 8
            val y = (bit / 6) * 16 + 8
            if ((argb[y * image.width + x] shr 8 and 0xff) > 128) 1 shl bit else 0
        }
    }

    private class LinearPixels(val halves: ShortArray, val width: Int) {
        /** The red channel; the fixtures are grey. */
        fun at(x: Int, y: Int): Double = halfToDouble(halves[(y * width + x) * 4])
    }

    private fun VideoFrame.linearPixels(): LinearPixels {
        val bitmap = assertNotNull(image).asSkiaBitmap()
        val info = ImageInfo(bitmap.width, bitmap.height, ColorType.RGBA_F16, ColorAlphaType.UNPREMUL, ColorSpace.sRGBLinear)
        val bytes = assertNotNull(bitmap.readPixels(info, bitmap.width * 8, 0, 0))
        val halves = ShortArray(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
        }
        return LinearPixels(halves, bitmap.width)
    }

    private companion object {
        const val CLOCK_FPS = 30
        val TIMEOUT = 500.milliseconds

        /** Must match generate.py. */
        val VFR_DURATIONS_MS = listOf(40, 100, 20, 60, 250, 33, 17)
        const val VFR_FRAMES = 28

        fun halfToDouble(half: Short): Double {
            val bits = half.toInt() and 0xffff
            val exponent = bits shr 10 and 0x1f
            val mantissa = bits and 0x3ff
            val magnitude = when (exponent) {
                0 -> mantissa / 1024.0 * 2.0.pow(-14)
                0x1f -> Double.POSITIVE_INFINITY
                else -> (1 + mantissa / 1024.0) * 2.0.pow(exponent - 15)
            }
            return if (bits and 0x8000 != 0) -magnitude else magnitude
        }

        fun assertClose(expected: Duration, actual: Duration, tolerance: Duration, message: String) {
            assertTrue(
                abs((expected - actual).inWholeNanoseconds) <= tolerance.inWholeNanoseconds,
                "$message: expected $expected, was $actual",
            )
        }
    }
}

/** A Source that stops delivering: a read past what it holds blocks until [release]. */
internal class StalledSource(val source: Source, val release: () -> Unit)

/** Serves [bytes]; once [stall] is called, reads block until [release]. */
@OptIn(ExperimentalAtomicApi::class)
internal class StallingFileHandle(private val bytes: ByteArray) : FileHandle(readWrite = false) {
    private val stalled = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    fun stall() = stalled.store(true)

    fun release() = released.store(true)

    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
        if (stalled.load()) {
            while (!released.load()) pauseBriefly()
            return -1
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
