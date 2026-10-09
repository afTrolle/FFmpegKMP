// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import android.os.Build
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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.DynamicRange
import io.github.aftrolle.ffmpegkmp.codec.FrameColor
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.FrameRate
import io.github.aftrolle.ffmpegkmp.codec.MediaOutput
import io.github.aftrolle.ffmpegkmp.codec.MediaSource
import io.github.aftrolle.ffmpegkmp.codec.MediaWriter
import io.github.aftrolle.ffmpegkmp.codec.MediaWritingException
import io.github.aftrolle.ffmpegkmp.codec.PixelLayout
import io.github.aftrolle.ffmpegkmp.codec.VideoCodec
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoEncoderConfig
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okio.FileSystem

/** Compose drawn into frames FFmpeg owns on a device, through a Presentation on a private display. */
class ComposeFrameRendererDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun eachCanvasFormatHoldsWhatComposeDrew() = runBlocking {
        for (format in canvasFormats()) {
            ComposeFrameRenderer<Int>(context, 64, 32, format) { x -> Scene(x) }.use { renderer ->
                renderer.render(Duration.ZERO, 40).use { frame ->
                    assertEquals(format, frame.format)
                    assertColour(frame, x = 8, y = 8, red = 1.0, green = 1.0, blue = 1.0, "$format background")
                    assertColour(frame, x = 48, y = 8, red = 1.0, green = 0.0, blue = 0.0, "$format square")
                }
                // A new value moves the square in the next frame.
                renderer.render(1.seconds / 30, 8).use { frame ->
                    assertColour(frame, x = 16, y = 8, red = 1.0, green = 0.0, blue = 0.0, "$format moved square")
                    assertColour(frame, x = 48, y = 8, red = 1.0, green = 1.0, blue = 1.0, "$format old square")
                }
            }
        }
    }

    @Test
    fun uncoveredPixelsAreTransparentBlackAndLayersDraw() = runBlocking {
        ComposeFrameRenderer<Int>(context, 64, 32) { x ->
            Box(Modifier.offset(x.dp, 0.dp).size(16.dp).graphicsLayer { alpha = 0.5f }.background(Color.Red))
        }.use { renderer ->
            repeat(3) { renderer.render(Duration.ZERO, 0).close() }
            renderer.render(Duration.ZERO, 40).use { frame ->
                assertColour(frame, x = 8, y = 8, red = 0.0, green = 0.0, blue = 0.0, "where the square was")
                // Half-transparent red, premultiplied: half red, half alpha.
                assertColour(frame, x = 48, y = 8, red = 0.5, green = 0.0, blue = 0.0, "a layer at half alpha", tolerance = 0.02)
            }
        }
    }

    @Test
    fun theGpuAndSoftwarePathsDrawTheSamePixels() = runBlocking {
        for (format in canvasFormats()) {
            val frames = listOf(true, false).map { gpu ->
                ComposeFrameRenderer<Int>(context, 96, 48, format, Density(1f), gpu) { x ->
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Blue, Color.Yellow)))) {
                        Box(Modifier.offset(x.dp, 4.dp).size(24.dp).graphicsLayer { alpha = 0.5f }.background(Color.Red))
                    }
                }.use { renderer ->
                    renderer.render(Duration.ZERO, 20).use { frame ->
                        // The GPU draws on Android 14 and later; the software canvas before, and when asked.
                        assertEquals(gpu && Build.VERSION.SDK_INT >= 34, renderer.drewOnGpu, "$format, GPU $gpu")
                        assertNotNull(frame.usePlanes { planes -> ByteArray(planes.single().bytes.size).also(planes.single().bytes::copyInto) })
                    }
                }
            }
            // Gradients and edges rasterize a code or two apart on the GPU and the CPU; compared by value.
            val (onGpu, inSoftware) = frames.map { bytes -> components(bytes, format) }
            val differing = onGpu.indices.count { abs(onGpu[it] - inSoftware[it]) > 3.0 / 255 }
            assertTrue(differing <= onGpu.size / 100, "$format: $differing of ${onGpu.size} components differ")
        }
    }

    @Test
    fun theFrameClockFollowsEachFramesTime() = runBlocking {
        for (gpu in listOf(true, false)) {
            ComposeFrameRenderer<Unit>(context, 64, 32, FrameFormat.Rgba8, Density(1f), gpu) {
                var nanos by remember { mutableLongStateOf(0L) }
                LaunchedEffect(Unit) {
                    while (true) withFrameNanos { nanos = it }
                }
                Scene(x = (nanos / 20_000_000L).toInt())
            }.use { renderer ->
                // 20 ms a pixel: the square is at x = 25 at half a second, whatever the display's clock says.
                renderer.render(Duration.ZERO, Unit).close()
                renderer.render(500.milliseconds, Unit).use { frame ->
                    assertEquals(gpu && Build.VERSION.SDK_INT >= 34, renderer.drewOnGpu)
                    assertEquals(500.milliseconds, frame.pts)
                    assertColour(frame, x = 20, y = 8, red = 1.0, green = 1.0, blue = 1.0, "GPU $gpu: left of the square")
                    assertColour(frame, x = 30, y = 8, red = 1.0, green = 0.0, blue = 0.0, "GPU $gpu: the square")
                }
            }
        }
    }

    @Test
    fun animationsFollowEachFramesTimeAsOnSkiko() = runBlocking {
        // Twice on each path: the same times give the same values every run.
        for (gpu in listOf(true, false, true, false)) {
            val drawn = mutableListOf<List<Float>>()
            ComposeFrameRenderer<Boolean>(context, 64, 32, FrameFormat.Rgba8, Density(1f), gpu) { moved ->
                Animated(moved) { drawn += it }
            }.use { renderer ->
                val samples = ANIMATION_TIMES.mapIndexed { index, time ->
                    renderer.render(time, index > 0).close()
                    drawn.last()
                }
                assertEquals(gpu && Build.VERSION.SDK_INT >= 34, renderer.drewOnGpu)
                assertEquals(ANIMATION_SAMPLES, samples, "GPU $gpu")
            }
        }
    }

    @Test
    fun anHdrCanvasEncodesWithMediaCodecWhereTheDeviceHasAnEncoder() = runBlocking {
        val config = VideoEncoderConfig(128, 128, FrameRate(30), VideoCodec.HEVC, DynamicRange.HDR10)
        if (Build.VERSION.SDK_INT < 33 || !MediaWriter.canEncode(config)) {
            println("Skipped: no HEVC Main10 encoder on this device (API ${Build.VERSION.SDK_INT})")
            return@runBlocking
        }
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "compose-renderer-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(directory)
        try {
            val output = directory / "rendered.mp4"
            MediaWriter.open(MediaOutput.File(output.toString()), timeout = 5.seconds).use { writer ->
                val track = writer.addVideoTrack(config)
                ComposeFrameRenderer<Int>(context, track) { x -> Scene(x) }.use { renderer ->
                    for (index in 0 until 10) track.write(renderer.render(index.seconds / 30, 80))
                }
                writer.finish()
            }
            VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.RgbaF16), DecoderPreference.SOFTWARE).use { decoder ->
                decoder.frameAt(Duration.ZERO).use { frame ->
                    assertColour(frame, x = 16, y = 16, red = 1.0, green = 1.0, blue = 1.0, "white at 203 nits", tolerance = 0.06)
                    assertColour(frame, x = 88, y = 8, red = 1.0, green = 0.0, blue = 0.0, "red", tolerance = 0.1)
                }
            }
        } catch (failure: MediaWritingException) {
            // The emulator's MediaCodec encoders take frames without giving packets.
            assertTrue(Build.HARDWARE == "ranchu" && "timed out" in failure.message.orEmpty(), "Unexpected $failure")
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun aTenBitSdrCanvasEncodesWithMediaCodecWhereTheDeviceHasAnEncoder() = runBlocking {
        val config = VideoEncoderConfig(128, 128, FrameRate(30), VideoCodec.HEVC, DynamicRange.SDR, bitDepth = 10)
        if (!MediaWriter.canEncode(config)) {
            println("Skipped: no 10-bit HEVC encoder on this device (API ${Build.VERSION.SDK_INT})")
            return@runBlocking
        }
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "compose-renderer-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(directory)
        try {
            val output = directory / "rendered-10.mp4"
            MediaWriter.open(MediaOutput.File(output.toString()), timeout = 5.seconds).use { writer ->
                val track = writer.addVideoTrack(config)
                ComposeFrameRenderer<Int>(context, track) { x -> Scene(x) }.use { renderer ->
                    // Android 13 brought 10-bit bitmaps; before it the canvas is 8-bit and the track converts it.
                    assertEquals(if (Build.VERSION.SDK_INT >= 33) TEN_BIT else FrameFormat.Rgba8, renderer.format)
                    for (index in 0 until 10) track.write(renderer.render(index.seconds / 30, 80))
                }
                writer.finish()
            }
            VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.Rgba8), DecoderPreference.SOFTWARE).use { decoder ->
                assertEquals(10, decoder.info.bitDepth)
                decoder.frameAt(Duration.ZERO).use { frame ->
                    assertColour(frame, x = 16, y = 16, red = 1.0, green = 1.0, blue = 1.0, "white", tolerance = 0.04)
                    assertColour(frame, x = 88, y = 8, red = 1.0, green = 0.0, blue = 0.0, "red", tolerance = 0.1)
                }
            }
        } catch (failure: MediaWritingException) {
            // The emulator's MediaCodec encoders take frames without giving packets.
            assertTrue(Build.HARDWARE == "ranchu" && "timed out" in failure.message.orEmpty(), "Unexpected $failure")
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    /** The canvases this device draws: 8-bit always, half floats from API 26, 10-bit sRGB from API 33. */
    private fun canvasFormats(): List<FrameFormat> = listOfNotNull(
        FrameFormat.Rgba8,
        FrameFormat.RgbaF16.takeIf { Build.VERSION.SDK_INT >= 26 },
        TEN_BIT.takeIf { Build.VERSION.SDK_INT >= 33 },
    )

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
        val format = assertNotNull(frame.format)
        val actual = assertNotNull(
            frame.usePlanes { planes ->
                val plane = planes.single()
                fun byte(offset: Int) = plane.bytes[offset].toInt() and 0xff
                val row = y * plane.rowBytes
                when (format.layout) {
                    PixelLayout.RGBA8 -> DoubleArray(3) { byte(row + x * 4 + it) / 255.0 }
                    PixelLayout.RGBA_F16 -> DoubleArray(3) { half(byte(row + x * 8 + it * 2) or (byte(row + x * 8 + it * 2 + 1) shl 8)) }
                    PixelLayout.RGBA_1010102 -> {
                        val word = (0 until 4).sumOf { byte(row + x * 4 + it).toLong() shl (8 * it) }
                        DoubleArray(3) { ((word shr (10 * it)) and 0x3ff) / 1023.0 }
                    }
                    else -> error("Not a canvas layout: $format")
                }
            },
        )
        listOf(red, green, blue).forEachIndexed { channel, expected ->
            assertTrue(abs(actual[channel] - expected) <= tolerance, "$message at ($x, $y): ${actual.toList()}")
        }
    }

    /** Every component of a packed frame's pixels, in 0..1 for RGBA8 and 10-bit RGB, and as linear light for F16. */
    private fun components(bytes: ByteArray, format: FrameFormat): DoubleArray = when (format.layout) {
        PixelLayout.RGBA8 -> DoubleArray(bytes.size) { (bytes[it].toInt() and 0xff) / 255.0 }
        PixelLayout.RGBA_1010102 -> DoubleArray(bytes.size / 4 * 3) { index ->
            val offset = index / 3 * 4
            val word = (0 until 4).sumOf { (bytes[offset + it].toLong() and 0xff) shl (8 * it) }
            ((word shr (10 * (index % 3))) and 0x3ff) / 1023.0
        }
        else -> DoubleArray(bytes.size / 2) { half((bytes[it * 2].toInt() and 0xff) or (bytes[it * 2 + 1].toInt() and 0xff shl 8)) }
    }

    private fun half(bits: Int): Double {
        val exponent = bits shr 10 and 0x1f
        val mantissa = bits and 0x3ff
        val magnitude = if (exponent == 0) mantissa / 1024.0 * 2.0.pow(-14) else (1 + mantissa / 1024.0) * 2.0.pow(exponent - 15)
        return if (bits and 0x8000 != 0) -magnitude else magnitude
    }
}

@Composable
private fun Scene(x: Int) {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Box(Modifier.offset(x.dp, 0.dp).size(16.dp).background(Color.Red))
    }
}

/**
 * The frame clock in milliseconds, a one-second slide towards [moved] and a 400 ms pulse, as drawn: the content of
 * Skiko's ComposeFrameRendererTest.animationsFollowEachFramesTime.
 */
@Composable
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

/** What Skiko draws at [ANIMATION_TIMES]. */
private val ANIMATION_SAMPLES = listOf(
    listOf(0f, 0f, 0f),
    listOf(100f, 0f, 0.25f),
    listOf(200f, 0f, 0.5f),
    listOf(450f, 0.25f, 0.125f),
    listOf(700f, 0.5f, 0.75f),
    listOf(1300f, 1f, 0.25f),
)

private val TEN_BIT = FrameFormat(PixelLayout.RGBA_1010102, FrameColor.Srgb)
