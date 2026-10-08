// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aftrolle.ffmpegkmp.bindings.gpuBuffersKeepDeepSources
import io.github.aftrolle.ffmpegkmp.codec.DecoderKind
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.FramePlane
import io.github.aftrolle.ffmpegkmp.codec.FrameSize
import io.github.aftrolle.ffmpegkmp.codec.MediaSource
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import io.github.aftrolle.ffmpegkmp.codec.hardwareBuffer
import io.github.aftrolle.ffmpegkmp.codec.hardwareBufferCrop
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue

/** [FrameImage] on a device, drawn by both of ComposeFrameRenderer's paths, the GPU and the software canvas. */
class FrameImageDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manyUpdatesWithFewPtsConvertOncePerPtsIntoTwoBitmapsInTurn() = runBlocking<Unit> {
        open("cfr-30.mp4", VideoOutput.Memory()).use { decoder ->
            FrameImage().use { image ->
                val bitmaps = mutableListOf<ImageBitmap>()
                val generations = mutableSetOf<Int>()
                for (index in 0 until 10) {
                    decoder.frameAt(index.seconds / 30).use { frame -> repeat(10) { image.update(frame) } }
                    val shown = assertNotNull(image.shown).image
                    if (index < 2) bitmaps += shown
                    assertSame(bitmaps[index % 2], shown, "the bitmap at frame $index")
                    // AndroidBitmap_unlockPixels gives the bitmap a new generation, which is what HWUI uploads by.
                    generations += shown.asAndroidBitmap().generationId
                }
                assertTrue(bitmaps[0] !== bitmaps[1], "two bitmaps")
                assertEquals(2 to 10, image.allocations to image.conversions)
                assertEquals(10, generations.size)
            }
        }
    }

    @Test
    fun anUpdateWhileARendererDrawsGivesTheOldFrameOrTheNewOneNeverAMixOnBothPaths() = runBlocking<Unit> {
        for (gpu in listOf(true, false)) {
            // 1080p, so that a conversion and a draw take long enough to overlap.
            open("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8, FrameSize(1920, 1080))).use { decoder ->
                val images = List(2) { FrameImage() }
                val (image, reference) = images
                // Completed as the renderer under test starts drawing, which is when the update starts.
                var drawing: CompletableDeferred<Unit>? = null
                val renderers = List(2) { index ->
                    renderer<FrameImage>(480, 270, gpu) {
                        Canvas(Modifier.fillMaxSize()) {
                            if (index == 0) drawing?.complete(Unit)
                            drawFrameImage(it)
                        }
                    }
                }
                try {
                    decoder.frameAt(Duration.ZERO).use { first -> images.forEach { it.update(first) } }
                    var before = renderers[1].render(Duration.ZERO, reference).use(::rgba)
                    var mixes = 0
                    for (index in 1 until 150) {
                        val time = index.seconds / 30
                        decoder.frameAt(time).use { frame ->
                            reference.update(frame)
                            val after = renderers[1].render(time, reference).use(::rgba)
                            val drawn = coroutineScope {
                                val started = CompletableDeferred<Unit>().also { drawing = it }
                                val render = async { renderers[0].render(time, image).use(::rgba) }
                                withContext(Dispatchers.Default) {
                                    withTimeout(5.seconds) { started.await() }
                                    image.update(frame)
                                }
                                render.await()
                            }
                            fun matches(expected: IntArray) = drawn.indices.all { abs(drawn[it] - expected[it]) <= 1 }
                            if (!matches(before) && !matches(after)) mixes++
                            before = after
                        }
                    }
                    assertEquals(0, mixes, "GPU $gpu: drawings that were neither the old frame nor the new one")
                } finally {
                    renderers.forEach { it.close() }
                    images.forEach { it.close() }
                }
            }
        }
    }

    @Test
    fun aRendererDrawingAFrameImageMatchesOneDrawingToImageBitmapOnBothPaths() = runBlocking<Unit> {
        for (gpu in listOf(true, false)) {
            open("cfr-30.mp4", VideoOutput.Memory()).use { decoder ->
                FrameImage().use { image ->
                    renderer<FrameImage>(96, 64, gpu) { Canvas(Modifier.fillMaxSize()) { drawFrameImage(it) } }.use { withImage ->
                        renderer<ImageBitmap>(96, 64, gpu) { Image(it, null, Modifier.fillMaxSize()) }.use { withBitmap ->
                            for (index in listOf(0, 45, 46, 149)) {
                                val time = index.seconds / 30
                                val (drawn, expected) = decoder.frameAt(time).use { frame ->
                                    image.update(frame)
                                    val bitmap = frame.toImageBitmap()
                                    withImage.render(time, image).use(::rgba) to withBitmap.render(time, bitmap).use(::rgba)
                                }
                                assertEquals(gpu && Build.VERSION.SDK_INT >= 34, withImage.drewOnGpu, "GPU $gpu")
                                val differing = drawn.indices.count { abs(drawn[it] - expected[it]) > 1 }
                                assertEquals(0, differing, "GPU $gpu, frame $index: $differing components differ")
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun rotatedAndAnamorphicFramesDrawUprightAtTheirDisplayAspectOnBothPaths() = runBlocking<Unit> {
        for (gpu in listOf(true, false)) {
            open("rotated-90.mp4", VideoOutput.Memory()).use { decoder ->
                decoder.frameAt(13.seconds / 30).use { frame -> assertUpright(frame, Size(64f, 96f), gpu) }
            }
            open("cfr-30.mp4", VideoOutput.Memory()).use { decoder ->
                decoder.frameAt(13.seconds / 30).use { frame ->
                    frame.withDisplay(rotationDegrees = 0.0, sampleAspectRatio = 2.0).use { assertUpright(it, Size(192f, 64f), gpu) }
                    frame.withDisplay(rotationDegrees = -90.0, sampleAspectRatio = 2.0).use { assertUpright(it, Size(64f, 192f), gpu) }
                }
            }
        }
    }

    @Test
    fun aGpuBuffersFrameDrawsAsTheSameFrameFromMemoryCroppedUprightAtItsDisplayAspect() = runBlocking<Unit> {
        assumeGpuBuffers()
        open("cfr-30-h264-128.mp4", VideoOutput.GpuBuffers, DecoderPreference.AUTO).use { gpuDecoder ->
            open("cfr-30-h264-128.mp4", VideoOutput.Memory()).use { memoryDecoder ->
                for (index in listOf(0, 13, 46, 149)) {
                    val time = index.seconds / 30
                    gpuDecoder.frameAt(time).use { frame ->
                        memoryDecoder.frameAt(time).use { memory ->
                            val buffer = assertNotNull(frame.hardwareBuffer, "frame $index lies in a HardwareBuffer")
                            println("FrameImageDeviceTest: frame $index in a ${buffer.width}x${buffer.height} buffer, crop ${frame.hardwareBufferCrop}")
                            assertUpright(frame, Size(128f, 128f), gpu = true, reference = memory, tolerance = 4)
                            for ((rotation, display) in listOf(0.0 to Size(256f, 128f), -90.0 to Size(128f, 256f))) {
                                frame.withDisplay(rotation, 2.0).use { shown ->
                                    memory.withDisplay(rotation, 2.0).use { assertUpright(shown, display, gpu = true, reference = it, tolerance = 4) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun framesUpdatingAFrameImageBeforeEachRenderNeverWaitAndShowEachFramesOwnContentsWrappingOnEveryUpdate() = runBlocking<Unit> {
        assumeGpuBuffers()
        open("cfr-30-h264-128.mp4", VideoOutput.GpuBuffers, DecoderPreference.AUTO).use { decoder ->
            FrameImage().use { image ->
                renderer<FrameImage>(128, 128, gpu = true) { Canvas(Modifier.fillMaxSize()) { drawFrameImage(it) } }.use { renderer ->
                    val buffers = mutableSetOf<Long>()
                    var index = 0
                    // The ordinary loop: the decoder's ring of three is the frame decoded ahead and the FrameImage's two.
                    // Each buffer comes round with a new frame, which a wrap made for its earlier one would not show.
                    decoder.frames().collect { frame ->
                        buffers += assertNotNull(frame.hardwareBuffer).id
                        frame.use(image::update)
                        val drawn = renderer.render(frame.pts, image).use(::rgba)
                        assertEquals(index, number(drawn, 128), "the frame drawn at $index")
                        index++
                    }
                    assertEquals(150, index)
                    assertEquals(0, image.allocations, "bitmaps allocated")
                    println("FrameImageDeviceTest: ${image.wrapCount} wraps over $index updates, ${buffers.size} buffers")
                    assertTrue(buffers.size < index, "the ${buffers.size} buffers take turns, so the frames reuse them")
                    assertEquals(index, image.wrapCount, "one wrap per update")
                }
            }
        }
    }

    @Test
    fun aGpuBuffersFrameFailsClearlyOnTheSoftwarePath() = runBlocking<Unit> {
        assumeGpuBuffers()
        open("cfr-30-h264-128.mp4", VideoOutput.GpuBuffers, DecoderPreference.AUTO).use { decoder ->
            FrameImage().use { image ->
                decoder.frameAt(Duration.ZERO).use(image::update)
                renderer<FrameImage>(128, 128, gpu = false) { Canvas(Modifier.fillMaxSize()) { drawFrameImage(it) } }.use { renderer ->
                    val failure = assertFailsWith<IllegalStateException> { renderer.render(Duration.ZERO, image).close() }
                    assertTrue(failure.message!!.contains("GPU canvas"), failure.message)
                }
            }
        }
    }

    /**
     * The HDR check: an HDR10 source through `GpuBuffers`, drawn into an F16 canvas, keeps its 1,000-nit highlight at
     * 1000/203, as the same frame from memory does. Until it passes, sources deeper than 8 bits decode into memory.
     */
    @Test
    fun anHdr10FrameInAGpuBufferKeepsItsHighlightOnAnF16CanvasAsFromMemory() = runBlocking<Unit> {
        assumeGpuBuffers()
        gpuBuffersKeepDeepSources = true
        try {
            val highlights = listOf(VideoOutput.GpuBuffers, VideoOutput.Memory(FrameFormat.RgbaF16)).map { output ->
                open("hdr10-pq-large.mp4", output, DecoderPreference.AUTO).use { decoder ->
                    if (output == VideoOutput.GpuBuffers) {
                        assumeTrue("a hardware decoder takes the 320x192 HDR10 fixture", decoder.decoderKind == DecoderKind.HARDWARE)
                    }
                    FrameImage().use { image ->
                        ComposeFrameRenderer<FrameImage>(context, 320, 192, FrameFormat.RgbaF16, Density(1f), true) {
                            Canvas(Modifier.fillMaxSize()) { drawFrameImage(it) }
                        }.use { renderer ->
                            decoder.frameAt(0.5.seconds).use { frame ->
                                assertEquals(output == VideoOutput.GpuBuffers, frame.hardwareBuffer != null, "$output")
                                image.update(frame)
                                renderer.render(frame.pts, image).use { drawn ->
                                    assertTrue(renderer.drewOnGpu)
                                    assertNotNull(drawn.usePlanes { planes -> planes.single().half(y = 96, x = 280) })
                                }
                            }
                        }
                    }
                }
            }
            println("FrameImageDeviceTest: HDR10 highlight ${highlights[0]} through GpuBuffers, ${highlights[1]} from memory")
            highlights.forEach { assertEquals(1000.0 / 203.0, it, 0.15) }
        } finally {
            gpuBuffersKeepDeepSources = false
        }
    }

    /** A 4K H.264 update: a wrap of the frame's buffer through `GpuBuffers`, a 4K conversion from memory. */
    @Test
    fun aFourKUpdateFromGpuBuffersTakesUnderAMillisecond() = runBlocking<Unit> {
        assumeGpuBuffers()
        val resource = javaClass.getResourceAsStream("/budget/h264-2160p-4s.mp4")
        assumeTrue("Generate the clip first with scripts/generate-budget-clip.sh", resource != null)
        val clip = File.createTempFile("h264-2160p", ".mp4")
        checkNotNull(resource).use { input -> clip.outputStream().use { input.copyTo(it) } }
        try {
            val medians = listOf(VideoOutput.GpuBuffers, VideoOutput.Memory()).associateWith { output ->
                VideoDecoder.open(MediaSource(clip.path), output, DecoderPreference.AUTO).use { decoder ->
                    FrameImage().use { image ->
                        val times = mutableListOf<Long>()
                        decoder.frames(until = 2.seconds).collect { frame ->
                            val started = System.nanoTime()
                            frame.use(image::update)
                            times += System.nanoTime() - started
                        }
                        // The first updates wrap or allocate.
                        times.drop(3).sorted()[(times.size - 3) / 2] / 1e6
                    }
                }
            }
            println("FrameImageDeviceTest: a 4K H.264 update takes ${medians[VideoOutput.GpuBuffers]} ms through GpuBuffers, " +
                "${medians[VideoOutput.Memory()]} ms from memory")
            assertTrue(checkNotNull(medians[VideoOutput.GpuBuffers]) < 1.0, "$medians")
        } finally {
            clip.delete()
        }
    }

    /**
     * Draws [frame] through a [FrameImage] into a renderer of its display size, which must be
     * [display], and checks each drawn pixel against the [reference] pixel it shows, within
     * [tolerance]: the frame turned anticlockwise by its rotation, as FFmpeg shows it, and stretched
     * by its sample aspect ratio. Only pixels where the frame is flat are compared, since edges
     * blend as they are scaled.
     */
    private suspend fun assertUpright(frame: VideoFrame, display: Size, gpu: Boolean, reference: VideoFrame = frame, tolerance: Int = 3) {
        val image = FrameImage().apply { update(frame) }
        assertEquals(display, image.displaySize, "$frame")
        val width = display.width.toInt()
        val height = display.height.toInt()
        val drawn = renderer<FrameImage>(width, height, gpu) { Canvas(Modifier.fillMaxSize()) { drawFrameImage(it) } }
            .use { renderer -> renderer.render(frame.pts, image).use(::rgba) }
        image.close()
        val pixels = reference.toImageBitmap().asAndroidBitmap()
        val turn = uprightTurn(frame.rotationDegrees)
        val stretchedWidth = frame.width * frame.sampleAspectRatio
        fun source(x: Int, y: Int, channel: Int): Int? =
            if (x !in 0 until frame.width || y !in 0 until frame.height) null else pixels.getPixel(x, y) shr (16 - 8 * channel) and 0xff
        var compared = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                // The display pixel's centre, back through the clockwise turn, then the stretch.
                val (stretchedX, frameY) = when (turn) {
                    0f -> (x + 0.5) to (y + 0.5)
                    90f -> (y + 0.5) to (frame.height - (x + 0.5))
                    270f -> (stretchedWidth - (y + 0.5)) to (x + 0.5)
                    else -> error("Not a turn this test draws: $turn")
                }
                val sourceX = floor(stretchedX / frame.sampleAspectRatio).toInt()
                val sourceY = floor(frameY).toInt()
                val flat = (0 until 3).all { channel ->
                    val centre = source(sourceX, sourceY, channel) ?: return@all false
                    (-1..1).all { dy -> (-1..1).all { dx -> source(sourceX + dx, sourceY + dy, channel)?.let { abs(it - centre) <= 2 } == true } }
                }
                if (!flat) continue
                compared++
                for (channel in 0 until 3) {
                    val actual = drawn[(y * width + x) * 4 + channel]
                    val expected = assertNotNull(source(sourceX, sourceY, channel))
                    assertTrue(
                        abs(actual - expected) <= tolerance,
                        "GPU $gpu, $frame at ($x, $y) channel $channel: $actual, expected $expected",
                    )
                }
            }
        }
        assertTrue(compared > width * height / 2, "$frame: only $compared of ${width * height} pixels are flat")
    }

    private fun <T> renderer(width: Int, height: Int, gpu: Boolean, content: @androidx.compose.runtime.Composable (T) -> Unit) =
        ComposeFrameRenderer(context, width, height, FrameFormat.Rgba8, Density(1f), gpu, content)

    /** Another reference to [this] frame's pixels, reported with another rotation and sample aspect ratio. */
    private fun VideoFrame.withDisplay(rotationDegrees: Double, sampleAspectRatio: Double): VideoFrame {
        gpuBuffer?.let { return VideoFrame.of(it.retain(), pts, duration, width, height, rotationDegrees, sampleAspectRatio) }
        return useNative { native ->
            VideoFrame.of(checkNotNull(native).retain(), pts, duration, width, height, rotationDegrees, sampleAspectRatio)
        }
    }

    /** The 12-bit frame number the fixture generator draws as 16x16 cells in the top rows, read from RGBA8 [pixels]. */
    private fun number(pixels: IntArray, width: Int): Int = (0 until 12).sumOf { bit ->
        val x = (bit % 6) * 16 + 8
        val y = (bit / 6) * 16 + 8
        if (pixels[(y * width + x) * 4 + 1] > 128) 1 shl bit else 0
    }

    /** The half float at [x], [y] in [channel] of an RGBA F16 plane. */
    private fun FramePlane.half(y: Int, x: Int, channel: Int = 0): Double {
        val offset = y * rowBytes + (x * 4 + channel) * 2
        val bits = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() and 0xff shl 8)
        val exponent = bits shr 10 and 0x1f
        val magnitude = if (exponent == 0) {
            (bits and 0x3ff) / 1024.0 / 16384.0
        } else {
            (1 + (bits and 0x3ff) / 1024.0) * Math.pow(2.0, exponent - 15.0)
        }
        return if (bits and 0x8000 != 0) -magnitude else magnitude
    }

    /** The emulator's decoders reject FFmpeg's input, so `GpuBuffers` needs a real device of Android 14 or later. */
    private fun assumeGpuBuffers() {
        assumeTrue("GpuBuffers needs Android 14", Build.VERSION.SDK_INT >= 34)
        assumeFalse("no emulator decoder renders to a Surface through FFmpeg", Build.HARDWARE == "ranchu")
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

    private suspend fun open(name: String, output: VideoOutput, preference: DecoderPreference = DecoderPreference.SOFTWARE): VideoDecoder {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/video-decoder/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
        return VideoDecoder.open(
            MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }),
            output,
            preference,
        )
    }
}
