// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import io.github.aftrolle.ffmpegkmp.bindings.NativeGpuBuffer
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerHdrType
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.FrameSize
import io.github.aftrolle.ffmpegkmp.codec.MediaSource
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** [FrameImage] against the real bridge: two reused bitmaps in turn, and frames drawn upright at their display aspect. */
class FrameImageTest {
    @Test
    fun manyUpdatesWithFewPtsConvertOncePerPtsIntoTwoBitmapsInTurn() = runBlocking<Unit> {
        decoder("cfr-30.mp4", VideoOutput.Memory()).use { decoder ->
            FrameImage().use { image ->
                assertEquals(Size.Zero, image.displaySize)
                val bitmaps = mutableListOf<ImageBitmap>()
                val generations = mutableSetOf<Int>()
                // A preview drawing ten times a frame: 100 updates, ten pts.
                for (index in 0 until 10) {
                    decoder.frameAt(index.seconds / 30).use { frame ->
                        repeat(10) { frame.retain().use { image.update(it) } }
                        val shown = assertNotNull(image.shown).image
                        if (index < 2) bitmaps += shown
                        assertSame(bitmaps[index % 2], shown, "the bitmap at frame $index")
                        generations += shown.asSkiaBitmap().generationId
                        assertEquals(rgba(frame.toImageBitmap()).toList(), rgba(shown).toList(), "frame $index")
                    }
                }
                assertTrue(bitmaps[0] !== bitmaps[1], "two bitmaps")
                assertEquals(2, image.allocations)
                assertEquals(10, image.conversions)
                // Skia caches what it drew by generation, so each conversion has to give the bitmap a new one.
                assertEquals(10, generations.size)
                assertEquals(Size(96f, 64f), image.displaySize)
            }
        }
    }

    @Test
    fun anUpdateWhileARendererDrawsGivesTheOldFrameOrTheNewOneNeverAMix() = runBlocking<Unit> {
        // 1080p, so that a conversion and a draw take long enough to overlap.
        decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8, FrameSize(1920, 1080))).use { decoder ->
            val images = List(2) { FrameImage() }
            val (image, reference) = images
            // Completed as the renderer under test starts drawing, which is when the update starts.
            var drawing: CompletableDeferred<Unit>? = null
            val renderers = List(2) { index ->
                ComposeFrameRenderer<FrameImage>(480, 270) {
                    Canvas(Modifier.fillMaxSize()) {
                        if (index == 0) drawing?.complete(Unit)
                        drawFrameImage(it)
                    }
                }
            }
            try {
                decoder.frameAt(Duration.ZERO).use { first -> images.forEach { it.update(first) } }
                var before = renderers[1].render(Duration.ZERO, reference).use(::bytes)
                var mixes = 0
                for (index in 1 until 150) {
                    val time = index.seconds / 30
                    decoder.frameAt(time).use { frame ->
                        reference.update(frame)
                        val after = renderers[1].render(time, reference).use(::bytes)
                        val drawn = coroutineScope {
                            val started = CompletableDeferred<Unit>().also { drawing = it }
                            val render = async { renderers[0].render(time, image).use(::bytes) }
                            withContext(Dispatchers.Default) {
                                withTimeout(5.seconds) { started.await() }
                                image.update(frame)
                            }
                            render.await()
                        }
                        if (!drawn.contentEquals(before) && !drawn.contentEquals(after)) mixes++
                        before = after
                    }
                }
                assertEquals(0, mixes, "drawings that were neither the old frame nor the new one")
            } finally {
                renderers.forEach { it.close() }
                images.forEach { it.close() }
            }
        }
    }

    @Test
    fun twoRenderersDrawTwoFrameImagesUpdatedFromTwoCoroutines() = runBlocking<Unit> {
        listOf("cfr-30.mp4", "cfr-24.mp4").map { name ->
            async(Dispatchers.Default) {
                decoder(name, VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
                    FrameImage().use { image ->
                        ComposeFrameRenderer<FrameImage>(96, 64) { Canvas(Modifier.fillMaxSize()) { drawFrameImage(it) } }.use { renderer ->
                            var count = 0
                            decoder.frames().take(50).collect { frame ->
                                val drawn = frame.use {
                                    image.update(it)
                                    renderer.render(it.pts, image).use(::bytes) to bytes(it)
                                }
                                val differing = drawn.first.indices.count { index ->
                                    abs((drawn.first[index].toInt() and 0xff) - (drawn.second[index].toInt() and 0xff)) > 1
                                }
                                assertEquals(0, differing, "$name frame $count: $differing bytes differ")
                                count++
                            }
                            assertEquals(50, count)
                        }
                    }
                }
            }
        }.awaitAll()
    }

    @Test
    fun aNewSizeOrFormatAllocatesAnotherBitmap() = runBlocking<Unit> {
        FrameImage().use { image ->
            decoder("cfr-30.mp4", VideoOutput.Memory()).use { it.frameAt(Duration.ZERO).use(image::update) }
            assertEquals(ColorType.RGBA_8888, assertNotNull(image.shown).image.asSkiaBitmap().colorType)
            // The same pts, in half floats.
            decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.RgbaF16)).use { it.frameAt(Duration.ZERO).use(image::update) }
            assertEquals(ColorType.RGBA_F16, assertNotNull(image.shown).image.asSkiaBitmap().colorType)
            assertEquals(2 to 2, image.allocations to image.conversions)
            decoder("cfr-30-h264-128.mp4", VideoOutput.Memory()).use { it.frameAt(Duration.ZERO).use(image::update) }
            assertEquals(3 to 3, image.allocations to image.conversions)
            assertEquals(Size(128f, 128f), image.displaySize)
        }
    }

    @Test
    fun rotatedAndAnamorphicFramesDrawUprightAtTheirDisplayAspect() = runBlocking<Unit> {
        // Turned a quarter anticlockwise, as FFmpeg shows it (-display_rotation 90).
        decoder("rotated-90.mp4", VideoOutput.Memory()).use { decoder ->
            decoder.frameAt(13.seconds / 30).use { frame ->
                assertEquals(90.0, frame.rotationDegrees)
                assertUpright(frame, Size(64f, 96f))
            }
        }
        // Synthesised: pixels twice as wide as they are tall, upright and turned.
        decoder("cfr-30.mp4", VideoOutput.Memory()).use { decoder ->
            decoder.frameAt(13.seconds / 30).use { frame ->
                frame.withDisplay(rotationDegrees = 0.0, sampleAspectRatio = 2.0).use { assertUpright(it, Size(192f, 64f)) }
                frame.withDisplay(rotationDegrees = 90.0, sampleAspectRatio = 2.0).use { assertUpright(it, Size(64f, 192f)) }
                frame.withDisplay(rotationDegrees = -90.0, sampleAspectRatio = 1.0).use { assertUpright(it, Size(64f, 96f)) }
            }
        }
    }

    @Test
    fun aRendererDrawingAFrameImageMatchesOneDrawingToImageBitmap() = runBlocking<Unit> {
        for (format in listOf(FrameFormat.Rgba8, FrameFormat.RgbaF16)) {
            decoder("cfr-30.mp4", VideoOutput.Memory(format)).use { decoder ->
                FrameImage().use { image ->
                    ComposeFrameRenderer<FrameImage>(96, 64, format) { Canvas(Modifier.fillMaxSize()) { drawFrameImage(it) } }.use { withImage ->
                        ComposeFrameRenderer<ImageBitmap>(96, 64, format) { Image(it, null, Modifier.fillMaxSize()) }.use { withBitmap ->
                            // One FrameImage, given again each time: only its own state tells Compose it changed.
                            for (index in listOf(0, 45, 46, 149)) {
                                val time = index.seconds / 30
                                val (drawn, expected) = decoder.frameAt(time).use { frame ->
                                    image.update(frame)
                                    val bitmap = frame.toImageBitmap()
                                    withImage.render(time, image).use(::bytes) to withBitmap.render(time, bitmap).use(::bytes)
                                }
                                val differing = drawn.indices.count { abs((drawn[it].toInt() and 0xff) - (expected[it].toInt() and 0xff)) > 1 }
                                assertEquals(0, differing, "$format frame $index: $differing bytes differ")
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aClosedFrameImageShowsNothingAndTakesNoFrames() = runBlocking<Unit> {
        decoder("cfr-30.mp4", VideoOutput.Memory()).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { frame ->
                val image = FrameImage()
                image.update(frame)
                image.close()
                assertEquals(null, image.shown)
                assertEquals(Size.Zero, image.displaySize)
                assertFailsWith<IllegalStateException> { image.update(frame) }
                image.close()
            }
        }
    }

    @Test
    fun aFrameInGpuMemoryFailsOffAndroidAndLeavesTheImageAsItWas() = runBlocking<Unit> {
        var releases = 0
        val buffer = NativeGpuBuffer(Any(), 1L, 0, 0, 96, 64, NativePlayerHdrType.SDR) { releases++ }
        decoder("cfr-30.mp4", VideoOutput.Memory()).use { decoder ->
            FrameImage().use { image ->
                decoder.frameAt(Duration.ZERO).use(image::update)
                val shown = image.shown
                VideoFrame.of(buffer, Duration.ZERO, Duration.ZERO, width = 96, height = 64).use { frame ->
                    assertFailsWith<IllegalStateException> { image.update(frame) }
                }
                assertSame(shown, image.shown)
            }
        }
        assertEquals(1, releases, "the image kept no reference to the frame")
    }

    /**
     * Draws [frame] through a [FrameImage] into a renderer of its display size, which must be
     * [display], and checks each drawn pixel against the frame pixel it shows: the frame turned
     * anticlockwise by its rotation, as FFmpeg shows it, and stretched by its sample aspect ratio. Only
     * pixels where the frame is flat are compared, since edges blend as they are scaled.
     */
    private suspend fun assertUpright(frame: VideoFrame, display: Size) {
        val image = FrameImage().apply { update(frame) }
        assertEquals(display, image.displaySize, "$frame")
        val width = display.width.toInt()
        val height = display.height.toInt()
        val drawn = ComposeFrameRenderer<FrameImage>(width, height) { Canvas(Modifier.fillMaxSize()) { drawFrameImage(it) } }
            .use { renderer -> renderer.render(frame.pts, image).use(::bytes) }
        image.close()
        val pixels = rgba(frame.toImageBitmap())
        val turn = uprightTurn(frame.rotationDegrees)
        val stretchedWidth = frame.width * frame.sampleAspectRatio
        fun source(x: Int, y: Int, channel: Int): Int? =
            if (x !in 0 until frame.width || y !in 0 until frame.height) null else pixels[(y * frame.width + x) * 4 + channel].toInt() and 0xff
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
                    val actual = drawn[(y * width + x) * 4 + channel].toInt() and 0xff
                    val expected = assertNotNull(source(sourceX, sourceY, channel))
                    assertTrue(abs(actual - expected) <= 2, "$frame at ($x, $y) channel $channel: $actual, expected $expected")
                }
            }
        }
        assertTrue(compared > width * height / 2, "$frame: only $compared of ${width * height} pixels are flat")
    }

    /** Another reference to [this] frame's pixels, reported with another rotation and sample aspect ratio. */
    private fun VideoFrame.withDisplay(rotationDegrees: Double, sampleAspectRatio: Double): VideoFrame = useNative { native ->
        VideoFrame.of(checkNotNull(native).retain(), pts, duration, width, height, rotationDegrees, sampleAspectRatio)
    }

    private suspend fun decoder(name: String, output: VideoOutput): VideoDecoder = VideoDecoder.open(
        MediaSource(name, CommandIo { input(name, Buffer().write(readVideoDecoderFixture(name))) }),
        output,
        DecoderPreference.SOFTWARE,
    )

    /** An RGBA8 bitmap's pixels, rows packed. */
    private fun rgba(image: ImageBitmap): ByteArray {
        val info = ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB)
        return assertNotNull(image.asSkiaBitmap().readPixels(info, image.width * 4, 0, 0))
    }

    /** A packed RGBA8 or RGBA F16 frame's pixels, rows packed. */
    private fun bytes(frame: VideoFrame): ByteArray = assertNotNull(
        frame.usePlanes { planes ->
            val plane = planes.single()
            val all = ByteArray(plane.bytes.size).also(plane.bytes::copyInto)
            val rowBytes = frame.width * if (frame.format == FrameFormat.RgbaF16) 8 else 4
            ByteArray(rowBytes * frame.height).also { packed ->
                for (y in 0 until frame.height) all.copyInto(packed, y * rowBytes, y * plane.rowBytes, y * plane.rowBytes + rowBytes)
            }
        },
    )
}
