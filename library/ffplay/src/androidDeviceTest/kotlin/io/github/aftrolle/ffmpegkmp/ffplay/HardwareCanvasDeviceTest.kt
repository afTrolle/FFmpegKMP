// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import android.os.Build
import android.util.Half
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.DecoderKind
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue

/**
 * The Compose Canvas output's hardware frames on a device: the real player renders MediaCodec's frames into the
 * output's `ImageReader` as it queues them, and the canvas draws their buffers. Each check draws the output's image
 * with [ComposeFrameRenderer] on the GPU, at the video's own size, and reads it back.
 */
class HardwareCanvasDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The colour check: a frame drawn from GPU memory against the same frame converted in software, both on an 8-bit
     * sRGB canvas: BT.709 and Display P3 shown as coded, HLG and PQ tagged for the platform, which tone maps both alike. Only pixels where the software
     * picture is flat are held to the tolerance, since the GPU and swscale site chroma differently at edges; the worst
     * of all pixels is printed beside it. Each fixture is checked, and the failures reported together.
     */
    @Test
    fun framesFromGpuMemoryLookAsTheSoftwareConversionShowsThem() = runBlocking {
        assumeHardwareCanvas()
        val failures = FIXTURES.mapNotNull { fixture -> runCatching { assertLooksAsConverted(fixture) }.exceptionOrNull()?.message }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** No frame from before a seek shows after it: paused, at each target exactly, and playing, never again once gone. */
    @Test
    fun aSeekNeverShowsAStaleFrame() = runBlocking {
        assumeHardwareCanvas()
        canvasPlayer(NUMBERED, hardware = true).use { session ->
            for (target in listOf(1.55.seconds, 0.4.seconds, 4.2.seconds, 2.seconds)) {
                within("seek to $target") { session.player.seekTo(target) }
                assertEquals(frameAt(target), number(session.drawn()), "the frame shown after a seek to $target")
            }
            val clock = object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                    delay(8)
                    return onFrame(System.nanoTime())
                }
            }
            val refresh = launch(Dispatchers.Default + clock) { session.output.driveRefresh() }
            try {
                within("play") { session.player.play() }
                withTimeout(10.seconds) { while (session.player.snapshot.value.position < 3.5.seconds) delay(20) }
                val before = number(session.drawn())
                within("seek back while playing") { session.player.seekTo(1.seconds) }
                // The first picture after the seek may lag it by a refresh; once it is one from after the target, no
                // frame queued before the seek may come back.
                withTimeout(5.seconds) { while (number(session.drawn()) >= before) delay(10) }
                repeat(20) {
                    val shown = number(session.drawn())
                    assertTrue(shown in frameAt(1.seconds) until before, "frame $shown after the seek to 1 s from frame $before")
                    delay(15)
                }
            } finally {
                refresh.cancelAndJoin()
            }
        }
    }

    private suspend fun assertLooksAsConverted(fixture: Fixture, canvas: FrameFormat = FrameFormat.Rgba8) {
        if (!fixture.fromGpu) {
            // Visibly converted in software instead: a software decoder, no zero copy.
            canvasPlayer(fixture.name, hardware = true, takesHardware = false).use { session ->
                val output = assertNotNull(session.player.snapshot.value.output)
                assertEquals(DecoderKind.SOFTWARE, output.decoder, "${fixture.name}: $output")
                assertEquals(false, output.zeroCopy, "${fixture.name}: $output")
            }
            return
        }
        val gpu = canvasPlayer(fixture.name, hardware = true).use { session ->
            val output = assertNotNull(session.player.snapshot.value.output)
            if (output.decoder != DecoderKind.HARDWARE) {
                println("HardwareCanvasDeviceTest: ${fixture.name} plays on a ${output.decoder} decoder (${output.decoderFallback}), skipping")
                return
            }
            assertTrue(output.zeroCopy, "${fixture.name}: $output")
            within("seek") { session.player.seekTo(fixture.at) }
            session.drawn(canvas)
        }
        val software = canvasPlayer(fixture.name, hardware = false).use { session ->
            assertEquals(DecoderKind.SOFTWARE, session.player.snapshot.value.output?.decoder)
            within("seek") { session.player.seekTo(fixture.at) }
            session.drawn(canvas)
        }
        assertClose("${fixture.name} from GPU memory against software on ${canvas.layout}", gpu, software, fixture.flatTolerance)
    }

    /**
     * The colour check on a canvas wider than sRGB, here linear extended sRGB as an HDR window draws on: a frame from
     * GPU memory looks as the software decoder's does there too, since both reach the platform tagged as they are.
     */
    @Test
    fun onAWideCanvasFramesFromGpuMemoryLookAsTheSoftwareConversionShowsThem() = runBlocking {
        assumeHardwareCanvas()
        val failures = FIXTURES.filter { it.fromGpu }.mapNotNull { fixture ->
            runCatching { assertLooksAsConverted(fixture, FrameFormat.RgbaF16) }.exceptionOrNull()?.message
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * [gpu] against [reference]: only pixels where the reference is flat are held to the tolerance, since the GPU and
     * swscale site chroma differently at edges; the worst of all pixels is printed beside it.
     */
    private fun assertClose(what: String, gpu: Picture, software: Picture, flatTolerance: Int) {
        var worst = 0
        var worstFlat = 0
        var flat = 0
        var sum = 0L
        val (width, height) = software.width to software.height
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val isFlat = (0 until 3).all { channel ->
                    val centre = software.at(x, y, channel)
                    (-1..1).all { dy -> (-1..1).all { dx -> abs(software.at(x + dx, y + dy, channel) - centre) <= FLAT } }
                }
                if (isFlat) flat++
                for (channel in 0 until 3) {
                    val error = abs(gpu.at(x, y, channel) - software.at(x, y, channel))
                    sum += error
                    worst = maxOf(worst, error)
                    if (isFlat) worstFlat = maxOf(worstFlat, error)
                }
            }
        }
        val mean = sum.toDouble() / ((width - 2) * (height - 2) * 3)
        val summary = "$what: worst $worstFlat codes over $flat flat pixels, worst $worst over all, mean %.2f".format(mean)
        Log.i(TAG, summary)
        assertTrue(flat > width * height / 50, "$summary: too few flat pixels")
        assertTrue(worstFlat <= flatTolerance && mean <= MEAN_TOLERANCE, summary)
    }

    /** A player showing [name] on a Canvas output, with or without its hardware frames, paused at its first frame. */
    private suspend fun canvasPlayer(
        name: String,
        hardware: Boolean,
        takesHardware: Boolean = hardware,
        frameQueueSize: Int = FFplayConfiguration().frameQueueSize,
        canvasFrames: Int = frameQueueSize + 2,
    ): Session {
        val preference = if (hardware) CodecPreference.AUTO else CodecPreference.SOFTWARE
        val player = FFplayPlayer(
            FFplayConfiguration(decoder = preference, audio = false, frameQueueSize = frameQueueSize, canvasFrames = canvasFrames),
        )
        val frames = if (hardware) ImageReaderCanvasFrames(player.configuration.canvasFrames) else null
        val output = ComposeCanvasOutput(player, System::nanoTime, frames)
        val session = Session(player, output)
        try {
            within("attach") { player.attachOutput(output) }
            withTimeout(15.seconds) { player.prepare(source(name)) }
            // As the composable does once the stream is known.
            if (output.negotiatesFor(player.snapshot.value.video)) within("attach again") { player.attachOutput(output) }
            assertEquals(takesHardware, output.capabilities.prepareHardwareFrames, "$name: whether the output takes hardware frames")
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
        return session
    }

    private inner class Session(val player: FFplayPlayer, val output: ComposeCanvasOutput) : AutoCloseable {
        /**
         * The output's image drawn at the video's size on the GPU onto a canvas of [format], as rows of 8-bit codes:
         * sRGB for [FrameFormat.Rgba8], and Display P3 for [FrameFormat.RgbaF16]'s linear extended sRGB.
         */
        suspend fun drawn(format: FrameFormat = FrameFormat.Rgba8): Picture {
            val info = assertNotNull(player.snapshot.value.video)
            return ComposeFrameRenderer<FrameImage>(context, info.width, info.height, format, Density(1f), true) {
                Canvas(Modifier.fillMaxSize()) { drawFrameImage(it) }
            }.use { renderer ->
                renderer.render(Duration.ZERO, output.image).use { frame ->
                    assertTrue(renderer.drewOnGpu)
                    Picture(frame.width, frame.height, if (format == FrameFormat.RgbaF16) displayP3(frame) else rgba(frame))
                }
            }
        }

        override fun close() {
            within("detach") { player.detachOutput(output) }
            output.close()
            within("close") { player.close() }
        }
    }

    class Picture(val width: Int, val height: Int, private val pixels: IntArray) {
        fun at(x: Int, y: Int, channel: Int): Int = pixels[(y * width + x) * 4 + channel]
    }

    /**
     * The Canvas keeps as many frames as it is told, down to one, whatever the queue's size, from GPU memory and
     * converted: each seek shows its frame, and playing shows frames in order to the clip's last second.
     */
    @Test
    fun aCanvasOfAnyNumberOfFramesShowsEveryTargetAndPlays() = runBlocking {
        assumeHardwareCanvas()
        for (hardware in listOf(true, false)) {
            for ((queue, frames) in listOf(1 to 1, 6 to 1, 6 to 2, 1 to 2, 6 to 4, 8 to 10)) {
                val what = "${if (hardware) "hardware" else "software"}, queue $queue, $frames frames"
                canvasPlayer(NUMBERED, hardware, frameQueueSize = queue, canvasFrames = frames).use { session ->
                    val expected = if (hardware) DecoderKind.HARDWARE else DecoderKind.SOFTWARE
                    assertEquals(expected, session.player.snapshot.value.output?.decoder, what)
                    for (target in listOf(1.55.seconds, 0.4.seconds)) {
                        within("$what: seek to $target") { session.player.seekTo(target) }
                        assertEquals(frameAt(target), number(session.drawn()), "$what: the frame shown after a seek to $target")
                    }
                    val clock = object : MonotonicFrameClock {
                        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                            delay(8)
                            return onFrame(System.nanoTime())
                        }
                    }
                    val refresh = launch(Dispatchers.Default + clock) { session.output.driveRefresh() }
                    try {
                        within("$what: play") { session.player.play() }
                        var last = number(session.drawn())
                        var shown = 1
                        withTimeout(10.seconds) {
                            while (session.player.snapshot.value.position < 3.5.seconds) {
                                val now = number(session.drawn())
                                assertTrue(now >= last, "$what: frame $now after frame $last")
                                if (now != last) shown++
                                last = now
                                delay(10)
                            }
                        }
                        val snapshot = session.player.snapshot.value
                        Log.i(TAG, "$what: $shown pictures seen to frame $last, ${snapshot.droppedFrames} dropped")
                        assertEquals(FFplayState.PLAYING, snapshot.state, what)
                        assertEquals(expected, snapshot.output?.decoder, what)
                        assertTrue(last >= frameAt(3.seconds), "$what: reached frame $last")
                        assertTrue(snapshot.droppedFrames <= 10, "$what: ${snapshot.droppedFrames} frames dropped")
                    } finally {
                        refresh.cancelAndJoin()
                    }
                }
            }
        }
    }

    /**
     * A fixture at [at]; [fromGpu] false for one the hardware Canvas leaves to the conversion, and [flatTolerance] the
     * worst 8-bit code its flat pixels may differ by.
     */
    private class Fixture(val name: String, val at: Duration, val fromGpu: Boolean = true, val flatTolerance: Int = FLAT_TOLERANCE)

    private companion object {
        /** 128x128 H.264 at 30 fps, five seconds, its 12-bit frame number drawn in the top rows. */
        const val NUMBERED = "video-decoder/cfr-30-h264-128.mp4"
        const val FPS = 30

        /** core's colour fixtures: one H.264 picture, tagged BT.709 and Display P3 SDR, HLG and HDR10 PQ. */
        val FIXTURES = listOf(
            Fixture("sdr-bt709.mp4", 0.5.seconds),
            // HWUI maps Display P3 into sRGB unlike the conversion: up to 16 codes apart on an S25.
            Fixture("sdr-display-p3.mp4", 0.5.seconds, fromGpu = false),
            Fixture("hdr-hlg.mp4", 0.5.seconds),
            // 8-bit 4:2:0 with highlights near PQ's peak, where the GPU's chroma and swscale's interpolated chroma
            // tone map up to 14 codes apart on an S25 in a small patch; the mean stays under 0.3.
            Fixture("hdr10-pq.mp4", 0.5.seconds, flatTolerance = 16),
            // Main 10 HEVC, which hardware decoders hand out in P010 buffers that carry their own data space.
            Fixture("video-decoder/hlg-large.mp4", 0.5.seconds),
            Fixture("video-decoder/hdr10-pq-patches.mp4", 0.5.seconds),
        )

        /** Linear sRGB to linear Display P3, both D65. */
        val SRGB_TO_DISPLAY_P3 = doubleArrayOf(
            0.8224621, 0.1775380, 0.0,
            0.0331941, 0.9668058, 0.0,
            0.0170827, 0.0723974, 0.9105199,
        )

        /** Of an 8-bit code, where the picture is flat: the GPU's YUV conversion against swscale's, both rounding. */
        const val FLAT_TOLERANCE = 4

        /** The mean error of every channel of every pixel, edges too, in 8-bit codes. */
        const val MEAN_TOLERANCE = 1.5

        /** How far a pixel's 3 x 3 neighbours may stray, in 8-bit codes, for it to count as flat in a natural picture. */
        const val FLAT = 6

        const val TAG = "HardwareCanvasTest"
    }

    /** The 12-bit frame number the fixture generator draws as 16x16 cells in the top rows. */
    private fun number(picture: Picture): Int = (0 until 12).sumOf { bit ->
        if (picture.at((bit % 6) * 16 + 8, (bit / 6) * 16 + 8, 1) > 128) 1 shl bit else 0
    }

    private fun frameAt(time: Duration): Int = floor(time.inWholeMicroseconds * FPS / 1e6 + 1e-3).toInt()

    /** Hardware Canvas frames need Android 14, and a real device: the emulator's decoders reject FFmpeg's input. */
    private fun assumeHardwareCanvas() {
        assumeTrue("Hardware Canvas frames need Android 14", Build.VERSION.SDK_INT >= 34)
        assumeFalse("no emulator decoder renders to a Surface through FFmpeg", Build.HARDWARE == "ranchu")
    }

    private fun source(name: String): MediaSource {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/$name")) { "Missing fixture $name" }.use { it.readBytes() }
        val file = name.substringAfterLast('/')
        return MediaSource(file, CommandIo { input(file, Buffer().write(bytes)) })
    }

    /** An RGBA8 frame's components, rows packed. */
    private fun rgba(frame: VideoFrame): IntArray = assertNotNull(
        frame.usePlanes { planes ->
            val plane = planes.single()
            IntArray(frame.width * frame.height * 4) { index ->
                val pixel = index / 4
                plane.bytes[pixel / frame.width * plane.rowBytes + pixel % frame.width * 4 + index % 4].toInt() and 0xff
            }
        },
    )

    /** An RGBA_F16 frame of linear extended sRGB as 8-bit Display P3 codes, rows packed. */
    private fun displayP3(frame: VideoFrame): IntArray = assertNotNull(
        frame.usePlanes { planes ->
            val plane = planes.single()
            val codes = IntArray(frame.width * frame.height * 4)
            for (pixel in 0 until frame.width * frame.height) {
                val at = pixel / frame.width * plane.rowBytes + pixel % frame.width * 8
                val light = FloatArray(3) { channel ->
                    val low = plane.bytes[at + channel * 2].toInt() and 0xff
                    val high = plane.bytes[at + channel * 2 + 1].toInt() and 0xff
                    Half.toFloat((low or (high shl 8)).toShort())
                }
                for (row in 0 until 3) {
                    val p3 = (0 until 3).sumOf { column -> SRGB_TO_DISPLAY_P3[row * 3 + column] * light[column] }.coerceIn(0.0, 1.0)
                    val encoded = if (p3 <= 0.0031308) p3 * 12.92 else 1.055 * p3.pow(1 / 2.4) - 0.055
                    codes[pixel * 4 + row] = (encoded * 255).roundToInt()
                }
                codes[pixel * 4 + 3] = 255
            }
            codes
        },
    )

    /** Runs a blocking player call on its own thread, failing with where it is stuck if it does not return in time. */
    private fun <T> within(what: String, timeout: Duration = 10.seconds, block: () -> T): T {
        val task = FutureTask(block)
        val thread = Thread(task, "HardwareCanvas test: $what").apply { isDaemon = true }
        thread.start()
        return try {
            task.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            fail("$what did not return within $timeout; it is at\n${thread.stackTrace.joinToString("\n")}")
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }
}
