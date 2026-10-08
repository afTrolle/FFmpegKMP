// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.nativeFrameStatistics
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.math.abs
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
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.Source

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
                    decoder.frameAt(time).use { frame ->
                        val expected = tick * fps / CLOCK_FPS
                        assertEquals(expected, frame.number(), "$fps fps source at tick $tick ($time)")
                        assertClose(expected.seconds / fps, frame.pts, 1.microseconds, "pts of frame $expected")
                        assertClose(1.seconds / fps, frame.duration, 1.microseconds, "duration of frame $expected")
                    }
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
                    assertEquals(index - 1, decoder.number(pts - 1.milliseconds), "1 ms before frame $index")
                    assertEquals(index - 1, decoder.number(pts - 600.microseconds), "0.6 ms before $index")
                }
                decoder.frameAt(pts).use { frame ->
                    assertEquals(index, frame.number(), "at the pts of frame $index")
                    assertEquals(pts, frame.pts)
                    if (index < VFR_FRAMES - 1) assertEquals(starts[index + 1] - pts, frame.duration, "duration of $index")
                }
                assertEquals(index, decoder.number(starts[index + 1] - 1.milliseconds), "end of $index")
            }
        }
    }

    @Test
    fun seeksBackwardAndForwardAcrossKeyframesLandOnTheSequentialFrame() = runBlocking {
        decoder("cfr-30.mp4").use { decoder ->
            val sequential = (0 until 150).map { index -> decoder.number(index.seconds / 30) }
            assertEquals((0 until 150).toList(), sequential)
            // Every GOP boundary (keyframes every 12 frames) and its neighbours, in a scrambled order.
            val positions = (0 until 150).filter { it % 12 in setOf(0, 1, 11) } + listOf(149, 75, 3)
            for (index in positions.shuffled(Random(7))) {
                assertEquals(sequential[index], decoder.number(index.seconds / 30), "frameAt frame $index")
                val between = index.seconds / 30 + 10.milliseconds
                decoder.seekTo(between)
                assertEquals(sequential[index], decoder.number(between), "seekTo inside frame $index")
            }
        }
    }

    @Test
    fun everyThreadCountReturnsTheSameFramesAtTheSamePositions() = runBlocking {
        // Forward steps, then jumps back and ahead, then an accurate seek: what frame threading reorders.
        val positions = (0 until 150).map { it.seconds / 30 } +
            listOf(4.seconds, 0.5.seconds, 10.seconds, 2.4.seconds, Duration.ZERO, 3.seconds)
        // The SDR fixtures carry their numbers; the HDR ones are flat, so their RGBA8 pixels stand in.
        fun VideoFrame.id(numbered: Boolean): String = if (numbered) "${number()}" else "${packed(4).contentHashCode()}"
        suspend fun VideoDecoder.walk(numbered: Boolean): List<String> =
            positions.map { position -> frameAt(position).use { "${it.id(numbered)} ${it.pts} ${it.duration}" } } +
                seekTo(1.2.seconds).let { "seekTo ${frameAt(1.2.seconds).use { it.id(numbered) }}" }
        // B-frames (mpeg4), H.264, a variable frame rate, and HEVC, which slice threads split.
        for (name in listOf("cfr-30.mp4", "cfr-30-h264.mp4", "vfr.mp4", "hdr10-pq.mp4", "hlg.mp4")) {
            val numbered = !name.startsWith("hdr10") && !name.startsWith("hlg")
            val output = if (numbered) VideoOutput.Memory() else VideoOutput.Memory(FrameFormat.Rgba8)
            val expected = decoder(name, output, threads = DecoderThreads.Fixed(1)).use { it.walk(numbered) }
            for (threads in listOf(DecoderThreads.Fixed(3), DecoderThreads.Auto)) {
                decoder(name, output, threads = threads).use { decoder ->
                    assertEquals(expected, decoder.walk(numbered), "$name with $threads")
                }
            }
        }
    }

    @Test
    fun boundariesHoldTheFirstAndLastFrames() = runBlocking {
        decoder("cfr-24.mp4").use { decoder ->
            decoder.frameAt(Duration.ZERO).use { first ->
                assertEquals(0, first.number())
                assertEquals(Duration.ZERO, first.pts)
            }
            // A position the current frame still covers returns it without decoding.
            decoder.frameAt(40.milliseconds).use { assertEquals(Duration.ZERO, it.pts) }

            assertEquals(13, decoder.number(1.seconds / 24 * 13))

            decoder.frameAt(10.seconds).use { last ->
                assertEquals(119, last.number())
                assertClose(5.seconds, last.pts + last.duration, 1.microseconds, "end of the last frame")
                for (beyond in listOf(1_000.seconds, Duration.INFINITE)) {
                    decoder.frameAt(beyond).use { assertEquals(last.pts, it.pts, "held at $beyond") }
                }
            }

            assertEquals(0, decoder.number(Duration.ZERO), "back to the start after the end")
        }
    }

    @Test
    fun aRotatedSourceReportsItsRotationAndKeepsTheCodedSize() = runBlocking {
        decoder("rotated-90.mp4").use { decoder ->
            assertEquals(90.0, decoder.info.rotationDegrees)
            assertEquals(96, decoder.info.width)
            assertEquals(64, decoder.info.height)
            decoder.frameAt(Duration.ZERO).use { frame ->
                assertEquals(90.0, frame.rotationDegrees)
                assertEquals(1.0, frame.sampleAspectRatio)
                assertEquals(96, frame.width)
                assertEquals(64, frame.height)
                assertEquals(0, frame.number())
            }
        }
    }

    @Test
    fun framesAsDecodedKeepTheSourcesLayoutAndColour() = runBlocking {
        decoder("cfr-30.mp4", VideoOutput.Memory()).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { frame ->
                val format = assertNotNull(frame.format)
                // mpeg4 decodes to yuv420p; the Apple runtimes copy it into an NV12 CVPixelBuffer.
                assertTrue(format.layout in setOf(PixelLayout.YUV420P, PixelLayout.NV12), "$format")
                assertEquals(ColorRange.LIMITED, format.color.range)
                assertEquals(ColorMatrix.BT709, format.color.matrix)
                frame.usePlanes { planes ->
                    assertEquals(if (format.layout == PixelLayout.NV12) 2 else 3, planes.size)
                    assertEquals(64, planes[0].rows)
                    assertEquals(32, planes[1].rows)
                    assertTrue(planes[0].rowBytes >= 96)
                }
            }
        }
        decoder("hdr10-pq.mp4", VideoOutput.Memory()).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { frame ->
                val format = assertNotNull(frame.format)
                assertTrue(format.layout in setOf(PixelLayout.YUV420P10, PixelLayout.P010), "$format")
                assertEquals(FrameColor.Bt2020Pq, format.color)
            }
        }
    }

    @Test
    fun anHdr10SourceReportsItsMetadataAndKeepsHighlightsInRgbaF16() = runBlocking {
        decoder("hdr10-pq.mp4", VideoOutput.Memory(FrameFormat.RgbaF16)).use { decoder ->
            val info = decoder.info
            assertEquals(HdrType.HDR10, info.hdrType)
            assertEquals("PQ", info.colorTransfer)
            assertEquals("BT.2020", info.colorPrimaries)
            assertEquals(10, info.bitDepth)
            assertEquals("1000.0", info.masteringDisplay?.raw?.get("maxLuminance"))
            assertEquals(1000, info.contentLight?.maxContentLightLevel)
            assertEquals(400, info.contentLight?.maxFrameAverageLightLevel)
            // What generate.py's mDCV and cLLI chunks give, as ffprobe reads them.
            assertEquals(hdr10PqFixtureMetadata, info.hdrMetadata)

            decoder.frameAt(0.5.seconds).use { frame ->
                assertEquals(FrameFormat.RgbaF16, frame.format)
                // 100 nits on the left and 1000 nits on the right, with 1.0 = 203 nits.
                assertEquals(100.0 / 203.0, frame.linear(x = 12, y = 32), 0.02)
                assertEquals(1000.0 / 203.0, frame.linear(x = 84, y = 32), 0.15)
            }
        }
        decoder("hdr10-pq.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { frame ->
                val left = frame.luma(x = 12, y = 32)
                val right = frame.luma(x = 84, y = 32)
                assertTrue(right in (left + 1)..255, "Tone mapped highlight $right should stay above $left")
            }
        }
    }

    @Test
    fun hardwareDecodingReturnsTheSameFramesWhereTheHostHasIt() = runBlocking {
        decoder("hdr10-pq.mp4", VideoOutput.Memory(FrameFormat.RgbaF16), DecoderPreference.AUTO).use { decoder ->
            if (decoder.decoderKind == DecoderKind.HARDWARE) assertEquals("p010le", decoder.info.pixelFormat)
            decoder.frameAt(Duration.ZERO).use { assertEquals(1000.0 / 203.0, it.linear(x = 84, y = 32), 0.15) }
        }
        // No hardware decoder takes these MPEG-4 Part 2 clips, so AUTO falls back to software.
        decoder("cfr-30.mp4", decoder = DecoderPreference.AUTO).use { decoder ->
            assertEquals(DecoderKind.SOFTWARE, decoder.decoderKind)
            assertEquals(45, decoder.number(1.5.seconds))
        }
    }

    @Test
    fun framesStayIntactWhileLaterFramesAreDecoded() = runBlocking {
        for (output in listOf(VideoOutput.Memory(), VideoOutput.Memory(FrameFormat.Rgba8), VideoOutput.Memory(FrameFormat.RgbaF16))) {
            decoder("cfr-30.mp4", output).use { decoder ->
                decoder.frameAt(Duration.ZERO).use { first ->
                    decoder.frameAt(2.seconds).use { later ->
                        assertEquals(0, first.number(), "$output frame 0 after decoding frame 60")
                        assertEquals(60, later.number(), "$output frame 60")
                    }
                }
            }
        }
    }

    @Test
    fun aPathInputDecodesLikeAMountedOne() = runBlocking {
        FileSystem.SYSTEM.createDirectories(directory)
        val file = directory / "cfr-60.mp4"
        FileSystem.SYSTEM.write(file) { write(fixture("cfr-60.mp4")) }
        VideoDecoder.open(MediaSource(file.toString()), decoder = DecoderPreference.SOFTWARE).use {
            assertEquals(150, it.number(2.5.seconds))
        }
    }

    @Test
    fun invalidRequestsFail() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> {
            VideoDecoder.open(MediaSource("cfr-30.mp4"), VideoOutput.Surface(Any()))
        }
        assertFailsWith<IllegalArgumentException> {
            VideoDecoder.open(MediaSource("cfr-30.mp4"), VideoOutput.GpuBuffers)
        }
        assertFailsWith<IllegalArgumentException> {
            VideoDecoder.open(MediaSource("x.mp4", protection = ContentProtection.REQUIRE_SECURE_PATH))
        }
        assertFailsWith<VideoDecodingException> {
            VideoDecoder.open(MediaSource((directory / "missing.mp4").toString()))
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
                decoder("stalled.mp4", decoder = DecoderPreference.AUTO, source = stalled.source, timeout = TIMEOUT)
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
                MediaSource(name, CommandIo { input(name, input) }),
                VideoOutput.Memory(),
                DecoderPreference.SOFTWARE,
                TIMEOUT,
            )
            // The last host read is buffered; going back to the start needs a new one.
            assertEquals(147, decoder.number(4.9.seconds))
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
                val number = try {
                    decoder.number(tick.seconds / 30)
                } catch (failure: VideoDecodingException) {
                    assertFalse("timed out" in failure.message.orEmpty(), "frame $tick: ${failure.message}")
                    break
                }
                assertTrue(number in last..tick, "frame $tick decoded as $number after $last")
                last = number
            }
            assertTrue(last in 30 until 149, "the last good frame before the cut was $last")
        }
    }

    @Test
    fun callsFromSeveralCoroutinesQueueAndEachGetsItsFrame() = runBlocking {
        decoder("cfr-30.mp4").use { decoder ->
            val indices = listOf(0, 45, 12, 149, 30, 90, 91, 2)
            val numbers = indices.map { index ->
                async(Dispatchers.Default) { decoder.number(index.seconds / 30) }
            }.awaitAll()
            assertEquals(indices, numbers)
        }
    }

    @Test
    fun aCancelledCallReturnsAtOnceAndTheNextOneSeeksAfresh() = runBlocking {
        val name = "cfr-30-faststart.mp4"
        val input = StallingFileHandle(fixture(name))
        try {
            VideoDecoder.open(MediaSource(name, CommandIo { input(name, input) }), decoder = DecoderPreference.SOFTWARE).use { decoder ->
                // The last host read is buffered; going back to the start needs a new one, which stalls.
                assertEquals(147, decoder.number(4.9.seconds))
                input.stall()
                val stuck = launch(Dispatchers.Default) { decoder.frameAt(1.seconds).close() }
                delay(100.milliseconds)
                val cancelling = TimeSource.Monotonic.markNow()
                stuck.cancelAndJoin()
                assertTrue(cancelling.elapsedNow() < 200.milliseconds, "cancelling took ${cancelling.elapsedNow()}")
                input.resume()
                assertEquals(60, decoder.number(2.seconds))
                assertEquals(61, decoder.number(2.seconds + 1.seconds / 30))
            }
        } finally {
            input.release()
        }
    }

    @Test
    fun framesDeliverEachDecodedFrameOnceUntilTheEnd() = runBlocking {
        decoder("vfr.mp4").use { decoder ->
            val frames = decoder.frames().map { frame -> frame.use { it.number() to it.pts } }.toList()
            assertEquals((0 until VFR_FRAMES).toList(), frames.map { it.first })
            assertEquals(frames.map { it.second }.sorted(), frames.map { it.second })
        }
        decoder("cfr-30.mp4").use { decoder ->
            assertEquals((0 until 150).toList(), decoder.frames(prefetch = 0).map { frame -> frame.use { it.number() } }.toList())
            val middle = decoder.frames(from = 1.seconds, until = 2.seconds).map { frame -> frame.use { it.number() } }.toList()
            assertEquals((30 until 60).toList(), middle)
        }
    }

    @Test
    fun framesAtARateOrIntervalSampleLikeAConstantRateClock() = runBlocking {
        decoder("cfr-24.mp4").use { decoder ->
            val numbers = decoder.frames(step = FrameStep.Rate(FrameRate(30))).map { frame -> frame.use { it.number() } }.toList()
            assertEquals((0 until 150).map { tick -> tick * 24 / 30 }, numbers)
        }
        decoder("cfr-30.mp4").use { decoder ->
            val numbers = decoder.frames(step = FrameStep.Every(100.milliseconds)).map { frame -> frame.use { it.number() } }.toList()
            assertEquals((0 until 50).map { tick -> tick * 3 }, numbers)
        }
    }

    @Test
    fun framesDecodedAheadThatTheCollectorNeverTakesAreClosed() = runBlocking {
        decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            suspend fun firstTwo(from: Int) = decoder.frames(from = from.seconds / 30, prefetch = 2)
                .take(2)
                .map { frame -> frame.use { it.number() } }
                .toList()
            // Warm the pool, then its buffers must come back each time instead of growing.
            repeat(2) { assertEquals(listOf(it, it + 1), firstTwo(it)) }
            val buffers = nativeFrameStatistics().buffers
            for (from in listOf(10, 40, 70, 100, 130)) assertEquals(listOf(from, from + 1), firstTwo(from))
            // A leak grows the pool by the untaken frames of every call; under load, the decoder
            // getting a frame further ahead than in the warm-up can add one or two.
            val grown = nativeFrameStatistics().buffers - buffers
            assertTrue(grown <= 2, "pooled frames were not returned: the pool grew by $grown")
        }
    }

    @Test
    fun aPrefetchOutsideZeroToTwoIsRejected() = runBlocking<Unit> {
        decoder("cfr-30.mp4").use { decoder ->
            assertFailsWith<IllegalArgumentException> { decoder.frames(prefetch = -1) }
            assertFailsWith<IllegalArgumentException> { decoder.frames(prefetch = 3) }
        }
    }

    private suspend fun decoder(
        name: String,
        output: VideoOutput = VideoOutput.Memory(),
        decoder: DecoderPreference = DecoderPreference.SOFTWARE,
        source: Source = Buffer().write(fixture(name)),
        timeout: Duration = 10.seconds,
        threads: DecoderThreads = DecoderThreads.Auto,
    ): VideoDecoder = VideoDecoder.open(
        MediaSource(name, CommandIo { input(name, source) }),
        output,
        decoder,
        timeout,
        threads,
    )

    private fun fixture(name: String): ByteArray = readVideoDecoderFixture(name)

    /** The number of the frame at [position], closing it. */
    private suspend fun VideoDecoder.number(position: Duration): Int = frameAt(position).use { it.number() }

    private companion object {
        const val CLOCK_FPS = 30
        val TIMEOUT = 500.milliseconds

        /** Must match generate.py. */
        val VFR_DURATIONS_MS = listOf(40, 100, 20, 60, 250, 33, 17)
        const val VFR_FRAMES = 28

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

    /** Serves reads again, including one blocked since [stall]. */
    fun resume() = stalled.store(false)

    fun release() = released.store(true)

    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
        while (stalled.load()) {
            if (released.load()) return -1
            pauseBriefly()
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

/** The HDR10 metadata hdr10-pq.mp4 is made with: BT.2020 primaries and D65 from 0.0001 to 1000 nits, MaxCLL 1000, MaxFALL 400. */
internal val hdr10PqFixtureMetadata = HdrMetadata(
    MasteringDisplay(
        red = Chromaticity(0.708, 0.292),
        green = Chromaticity(0.17, 0.797),
        blue = Chromaticity(0.131, 0.046),
        whitePoint = Chromaticity(0.3127, 0.329),
        minLuminance = 0.0001,
        maxLuminance = 1000.0,
    ),
    ContentLightMetadata(1000, 400),
)
