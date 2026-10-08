// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.DynamicRange
import io.github.aftrolle.ffmpegkmp.codec.FrameColor
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.FrameRate
import io.github.aftrolle.ffmpegkmp.codec.MediaOutput
import io.github.aftrolle.ffmpegkmp.codec.MediaSource
import io.github.aftrolle.ffmpegkmp.codec.MediaWriter
import io.github.aftrolle.ffmpegkmp.codec.PixelLayout
import io.github.aftrolle.ffmpegkmp.codec.VideoCodec
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoEncoderConfig
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okio.FileSystem

/** Compose drawn into frames FFmpeg owns, on the JVM and Kotlin/Native, against the real bridge. */
class ComposeFrameRendererTest {
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "compose-renderer-${Random.nextLong().toULong()}"

    @AfterTest
    fun cleanUp() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    @Test
    fun eachCanvasFormatHoldsWhatComposeDrew() = runBlocking {
        val formats = listOf(
            FrameFormat.Rgba8,
            FrameFormat(PixelLayout.BGRA8, FrameColor.Srgb),
            FrameFormat(PixelLayout.RGBA_1010102, FrameColor.Srgb),
            FrameFormat.RgbaF16,
        )
        for (format in formats) {
            ComposeFrameRenderer<Int>(64, 32, format) { x -> Scene(x) }.use { renderer ->
                renderer.render(Duration.ZERO, 40).use { frame ->
                    assertEquals(format, frame.format)
                    assertEquals(64, frame.width)
                    // White background, a red square from x = 40.
                    assertColour(frame, x = 8, y = 8, red = 1.0, green = 1.0, blue = 1.0, "$format background")
                    assertColour(frame, x = 48, y = 8, red = 1.0, green = 0.0, blue = 0.0, "$format square")
                }
            }
        }
    }

    @Test
    fun whatTheContentLeavesUncoveredIsTransparentBlackInEveryFrame() = runBlocking {
        ComposeFrameRenderer<Int>(64, 32) { x -> Box(Modifier.offset(x.dp, 0.dp).size(16.dp).background(Color.Red)) }.use { renderer ->
            // Pooled frames come back with the earlier frames' pixels until the renderer clears them.
            repeat(4) { renderer.render(Duration.ZERO, 0).close() }
            renderer.render(Duration.ZERO, 40).use { frame ->
                assertColour(frame, x = 8, y = 8, red = 0.0, green = 0.0, blue = 0.0, "where the square was")
                assertEquals(0, frame.usePlanes { planes -> planes.single().bytes[8 * planes.single().rowBytes + 8 * 4 + 3].toInt() })
                assertColour(frame, x = 48, y = 8, red = 1.0, green = 0.0, blue = 0.0, "the square")
            }
        }
    }

    @Test
    fun theSceneAndImageSceneRoutesDrawTheSamePixels() = runBlocking {
        val scene = frames(ComposeFrameRenderer.Route.SCENE)
        val image = frames(ComposeFrameRenderer.Route.IMAGE_SCENE)
        assertEquals(scene.size, image.size)
        scene.zip(image).forEachIndexed { index, (first, second) ->
            val differing = first.indices.count { abs((first[it].toInt() and 0xff) - (second[it].toInt() and 0xff)) > 1 }
            assertEquals(0, differing, "frame $index: $differing bytes differ by more than one code")
        }
    }

    @Test
    fun theFrameClockFollowsEachFramesTime() = runBlocking {
        ComposeFrameRenderer<Unit>(64, 32) {
            var nanos by remember { mutableLongStateOf(0L) }
            LaunchedEffect(Unit) {
                while (true) withFrameNanos { nanos = it }
            }
            Scene(x = (nanos / 20_000_000L).toInt())
        }.use { renderer ->
            // 20 ms a pixel: the square is at x = 25 at half a second, whatever the wall clock says.
            renderer.render(Duration.ZERO, Unit).close()
            renderer.render(500.milliseconds, Unit).use { frame ->
                assertEquals(500.milliseconds, frame.pts)
                assertColour(frame, x = 20, y = 8, red = 1.0, green = 1.0, blue = 1.0, "left of the square")
                assertColour(frame, x = 30, y = 8, red = 1.0, green = 0.0, blue = 0.0, "the square")
            }
        }
    }

    @Test
    fun animationsFollowEachFramesTime() = runBlocking {
        for (route in listOf(ComposeFrameRenderer.Route.SCENE, ComposeFrameRenderer.Route.IMAGE_SCENE)) {
            val drawn = mutableListOf<List<Float>>()
            ComposeFrameRenderer<Boolean>(64, 32, FrameFormat.Rgba8, androidx.compose.ui.unit.Density(1f), route) { moved ->
                Animated(moved) { drawn += it }
            }.use { renderer ->
                val samples = ANIMATION_TIMES.mapIndexed { index, time ->
                    renderer.render(time, index > 0).close()
                    drawn.last()
                }
                assertEquals(ANIMATION_SAMPLES, samples, "$route")
            }
        }
    }

    @Test
    fun aRenderedHdrCanvasEncodesWithItsWhiteAt203Nits() = runBlocking {
        val config = VideoEncoderConfig(64, 64, FrameRate(30), VideoCodec.HEVC, DynamicRange.HDR10)
        if (!MediaWriter.canEncode(config)) {
            println("Skipped: no HEVC Main10 encoder on this host")
            return@runBlocking
        }
        FileSystem.SYSTEM.createDirectories(directory)
        val output = directory / "rendered.mp4"
        MediaWriter.open(MediaOutput.File(output.toString())).use { writer ->
            val track = writer.addVideoTrack(config)
            ComposeFrameRenderer<Int>(track) { x -> Scene(x) }.use { renderer ->
                assertEquals(FrameFormat.RgbaF16, renderer.format)
                for (index in 0 until 10) track.write(renderer.render(index.seconds / 30, 40))
            }
            assertEquals(10, writer.finish().videoFrames)
        }
        VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.RgbaF16), DecoderPreference.SOFTWARE).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { frame ->
                // Compose's white is SDR white, 1.0 in the canvas: 203 nits once encoded as PQ.
                assertColour(frame, x = 8, y = 8, red = 1.0, green = 1.0, blue = 1.0, "white", tolerance = 0.06)
                assertColour(frame, x = 48, y = 8, red = 1.0, green = 0.0, blue = 0.0, "red", tolerance = 0.1)
            }
        }
    }

    @Test
    fun aTenBitSdrTrackDrawsIntoTenBitRgbAndEncodesAtTenBits() = runBlocking {
        val config = VideoEncoderConfig(64, 64, FrameRate(30), VideoCodec.HEVC, DynamicRange.SDR, bitDepth = 10)
        if (!MediaWriter.canEncode(config)) {
            println("Skipped: no 10-bit HEVC encoder on this host")
            return@runBlocking
        }
        FileSystem.SYSTEM.createDirectories(directory)
        val output = directory / "rendered-10.mp4"
        MediaWriter.open(MediaOutput.File(output.toString())).use { writer ->
            val track = writer.addVideoTrack(config)
            ComposeFrameRenderer<Int>(track) { x -> Scene(x) }.use { renderer ->
                assertEquals(FrameFormat(PixelLayout.RGBA_1010102, FrameColor.Srgb), renderer.format)
                for (index in 0 until 10) track.write(renderer.render(index.seconds / 30, 40))
            }
            assertEquals(10, writer.finish().videoFrames)
        }
        VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.Rgba8), DecoderPreference.SOFTWARE).use { decoder ->
            assertEquals(10, decoder.info.bitDepth)
            decoder.frameAt(Duration.ZERO).use { frame ->
                assertColour(frame, x = 8, y = 8, red = 1.0, green = 1.0, blue = 1.0, "white", tolerance = 0.03)
                assertColour(frame, x = 48, y = 8, red = 1.0, green = 0.0, blue = 0.0, "red", tolerance = 0.1)
            }
        }
    }

    private suspend fun frames(route: ComposeFrameRenderer.Route): List<ByteArray> {
        val renderer = ComposeFrameRenderer<Int>(96, 48, FrameFormat.Rgba8, androidx.compose.ui.unit.Density(1.5f), route) { x ->
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Blue, Color.Yellow)))) {
                Box(Modifier.offset(x.dp, 4.dp).size(12.dp).background(Color.Red.copy(alpha = 0.5f)))
            }
        }
        return renderer.use {
            assertEquals(null, it.activeRoute)
            List(3) { index ->
                it.render((index * 40).milliseconds, index * 10).use { frame ->
                    assertEquals(route, it.activeRoute)
                    assertNotNull(frame.usePlanes { planes -> ByteArray(planes.single().bytes.size).also(planes.single().bytes::copyInto) })
                }
            }
        }
    }

    private fun assertColour(
        frame: VideoFrame,
        x: Int,
        y: Int,
        red: Double,
        green: Double,
        blue: Double,
        message: String,
        tolerance: Double = 0.01,
    ) {
        val actual = assertNotNull(frame.usePlanes { planes -> pixel(planes.single(), assertNotNull(frame.format), x, y) })
        listOf(red, green, blue).forEachIndexed { channel, expected ->
            assertTrue(abs(actual[channel] - expected) <= tolerance, "$message at ($x, $y): ${actual.toList()}, expected ${listOf(red, green, blue)}")
        }
    }

    /** Red, green and blue in 0..1, or linear light for F16. */
    private fun pixel(plane: io.github.aftrolle.ffmpegkmp.codec.FramePlane, format: FrameFormat, x: Int, y: Int): DoubleArray {
        fun byte(offset: Int) = plane.bytes[offset].toInt() and 0xff
        val row = y * plane.rowBytes
        return when (format.layout) {
            PixelLayout.RGBA8 -> DoubleArray(3) { byte(row + x * 4 + it) / 255.0 }
            PixelLayout.BGRA8 -> DoubleArray(3) { byte(row + x * 4 + 2 - it) / 255.0 }
            PixelLayout.RGBA_1010102 -> {
                val word = (0 until 4).sumOf { byte(row + x * 4 + it).toLong() shl (8 * it) }
                DoubleArray(3) { ((word shr (10 * it)) and 0x3ff) / 1023.0 }
            }
            PixelLayout.RGBA_F16 -> DoubleArray(3) { halfToDouble(byte(row + x * 8 + it * 2) or (byte(row + x * 8 + it * 2 + 1) shl 8)) }
            else -> error("Not a canvas layout: $format")
        }
    }

    private fun halfToDouble(bits: Int): Double {
        val exponent = bits shr 10 and 0x1f
        val mantissa = bits and 0x3ff
        val magnitude = if (exponent == 0) mantissa / 1024.0 * 2.0.pow(-14) else (1 + mantissa / 1024.0) * 2.0.pow(exponent - 15)
        return if (bits and 0x8000 != 0) -magnitude else magnitude
    }
}

@androidx.compose.runtime.Composable
private fun Scene(x: Int) {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Box(Modifier.offset(x.dp, 0.dp).size(16.dp).background(Color.Red))
    }
}

/**
 * The frame clock in milliseconds, a one-second slide towards [moved] and a 400 ms pulse, as drawn. Android's
 * ComposeFrameRendererDeviceTest renders the same content at the same times and expects the same values.
 */
@androidx.compose.runtime.Composable
private fun Animated(moved: Boolean, drawn: (List<Float>) -> Unit) {
    var nanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { nanos = it }
    }
    val slide by animateFloatAsState(if (moved) 1f else 0f, tween(1000, easing = LinearEasing))
    val pulse by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(400, easing = LinearEasing)))
    Box(Modifier.fillMaxSize().drawBehind { drawn(listOf(nanos / 1_000_000f, slide, pulse)) })
}

private val ANIMATION_TIMES = listOf(0, 100, 200, 450, 700, 1300).map { it.milliseconds }

/**
 * The clock reads each frame's time, the pulse starts on the first frame, and the slide starts on the frame after the
 * one that composed its new target.
 */
private val ANIMATION_SAMPLES = listOf(
    listOf(0f, 0f, 0f),
    listOf(100f, 0f, 0.25f),
    listOf(200f, 0f, 0.5f),
    listOf(450f, 0.25f, 0.125f),
    listOf(700f, 0.5f, 0.75f),
    listOf(1300f, 1f, 0.25f),
)
