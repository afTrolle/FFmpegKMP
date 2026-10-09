// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.util.Half
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.ContentLightMetadata
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.DynamicRange
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.FrameRate
import io.github.aftrolle.ffmpegkmp.codec.HdrMetadata
import io.github.aftrolle.ffmpegkmp.codec.MasteringDisplay
import io.github.aftrolle.ffmpegkmp.codec.MediaOutput
import io.github.aftrolle.ffmpegkmp.codec.MediaSource
import io.github.aftrolle.ffmpegkmp.codec.MediaWriter
import io.github.aftrolle.ffmpegkmp.codec.MediaWritingException
import io.github.aftrolle.ffmpegkmp.codec.VideoCodec
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoEncoderConfig
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import io.github.aftrolle.ffmpegkmp.codec.hardwareBuffer
import io.github.aftrolle.ffmpegkmp.codec.hardwareBufferCrop
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import org.junit.Assume.assumeTrue

/**
 * Rendered frames reaching a hardware encoder with no copy (Android 14 and later), then decoded
 * back: the renderer draws into buffers of the encoder's input surface, `track.write` queues them
 * with their pts, and the encoder's packets are muxed through the writer's packet track. Where the
 * device has no such encoder the renderer keeps the one-copy path and the tests still pass, except
 * where they say a track must be zero-copy. The emulator's MediaCodec encoders take frames without
 * giving packets, so there a writer that timed out, within its timeout, is the other outcome that
 * passes. The HDR10 test needs the surface and is skipped, with the reason printed, where no
 * hardware encoder opens one.
 *
 * ```
 * ./gradlew :library:ffplay:connectedAndroidDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.aftrolle.ffmpegkmp.ffplay.SurfaceEncoderDeviceTest
 * ```
 */
class SurfaceEncoderDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "surface-encoder-${Random.nextLong().toULong()}"

    init {
        FileSystem.SYSTEM.createDirectories(directory)
    }

    @AfterTest
    fun cleanUp() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    @Test
    fun thirtyRenderedFramesEncodeThroughTheSurfaceAndDecodeBackFrameForFrame() = runBlocking {
        assumeTrue("Needs Android 14", Build.VERSION.SDK_INT >= 34)
        for (codec in listOf(VideoCodec.H264, VideoCodec.HEVC)) {
            val config = VideoEncoderConfig(SIZE, SIZE, FrameRate(30), codec)
            if (!MediaWriter.canEncode(config)) {
                println("Skipped $codec: no encoder on this device")
                continue
            }
            val output = directory / "$codec.mp4"
            timesOutOnTheEmulator {
                MediaWriter.open(MediaOutput.File(output.toString()), timeout = TIMEOUT).use { writer ->
                    val track = writer.addVideoTrack(config)
                    assertFalse(track.zeroCopy, "$codec: the track is zero-copy once a renderer asks for it")
                    ComposeFrameRenderer<Int>(context, track) { Numbered(it) }.use { renderer ->
                        for (index in 0 until FRAMES) {
                            val frame = renderer.render(pts(index), index)
                            if (track.zeroCopy) {
                                assertNull(frame.format, "$codec: a frame over the encoder's buffer has no pixels in memory")
                                assertNotNull(frame.hardwareBuffer, "$codec: its buffer")
                                assertEquals(android.graphics.Rect(0, 0, SIZE, SIZE), frame.hardwareBufferCrop)
                            }
                            assertEquals(pts(index), frame.pts)
                            track.write(frame)
                        }
                        // A hardware encoder on a phone takes the surface; the emulator's may not.
                        if (Build.HARDWARE != "ranchu") assertTrue(track.zeroCopy, "$codec: the surface encoder is open")
                    }
                    assertEquals(FRAMES.toLong(), writer.finish().videoFrames)
                }
                VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.Rgba8), DecoderPreference.SOFTWARE).use { decoder ->
                    assertEquals(SIZE, decoder.info.width)
                    val frames = decoder.frames().map { frame -> frame.use { it.pts to it.number() } }.toList()
                    assertEquals((0 until FRAMES).toList(), frames.map { it.second }, "$codec: frame numbers")
                    frames.forEachIndexed { index, (pts, _) ->
                        assertTrue(abs((pts - pts(index)).inWholeMicroseconds) < 1000, "$codec: frame $index at $pts, not ${pts(index)}")
                    }
                }
            }
        }
    }

    @Test
    fun theSurfaceEncodeKeepsTheOneCopyPathsColours() = runBlocking {
        assumeTrue("Needs Android 14", Build.VERSION.SDK_INT >= 34)
        val config = VideoEncoderConfig(SIZE, SIZE, FrameRate(30))
        assumeTrue("No encoder", MediaWriter.canEncode(config))
        timesOutOnTheEmulator {
            // The same content through the surface and, with a renderer that has no track to draw into, through the copy.
            val patches = listOf(true, false).map { surface ->
                val output = directory / "colours-$surface.mp4"
                MediaWriter.open(MediaOutput.File(output.toString()), timeout = TIMEOUT).use { writer ->
                    val track = writer.addVideoTrack(config)
                    val renderer = if (surface) {
                        ComposeFrameRenderer<Int>(context, track) { Numbered(it) }
                    } else {
                        ComposeFrameRenderer<Int>(context, SIZE, SIZE, config.canvasFormat) { Numbered(it) }
                    }
                    renderer.use { for (index in 0 until 10) track.write(it.render(pts(index), index)) }
                    if (!surface) assertFalse(track.zeroCopy) else if (Build.HARDWARE != "ranchu") assertTrue(track.zeroCopy)
                    writer.finish()
                }
                VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.Rgba8), DecoderPreference.SOFTWARE).use { decoder ->
                    decoder.frameAt(Duration.ZERO).use { it.pixel(x = SIZE / 2, y = SIZE * 3 / 4) }
                }
            }
            val (viaSurface, viaCopy) = patches
            // 8 of 255 covers the encoders' own rate control and a BT.709 conversion done by the encoder rather than swscale.
            assertTrue(viaSurface.indices.all { abs(viaSurface[it] - viaCopy[it]) <= 8 }, "surface ${viaSurface.toList()}, copy ${viaCopy.toList()}")
            assertTrue(viaSurface.indices.all { abs(viaSurface[it] - PATCH_RGB[it]) <= 12 }, "patch ${viaSurface.toList()}, not $PATCH_RGB")
        }
    }

    /**
     * HDR10 through the surface: six flat patches of known linear light on the F16 canvas, 0.1 to 4.93 (1000 nits)
     * and a saturated red, plus the frame number, through an HDR10 HEVC track's input surface and, with a renderer
     * that has no track, through the one-copy path's converter. Both files decode back as 10-bit BT.2020 PQ with
     * the config's metadata, each frame with its number, and each patch within [hdrTolerance] of what was drawn and
     * of the other path, so a wrong matrix or curve in the encode shader shows against the converter.
     */
    @Test
    fun hdr10PatchesEncodeThroughTheSurfaceAsThroughMemoryAndDecodeBackAsBt2020Pq() = runBlocking {
        assumeTrue("Needs Android 14", Build.VERSION.SDK_INT >= 34)
        val config = VideoEncoderConfig(
            SIZE, SIZE, FrameRate(30), VideoCodec.HEVC, DynamicRange.HDR10,
            hdrMetadata = HdrMetadata(MasteringDisplay.DisplayP3At1000Nits, ContentLightMetadata(1000, 400)),
        )
        assumeTrue("No HDR10 HEVC encoder on this device", MediaWriter.canEncode(config))
        val patches = hdrPatches()
        timesOutOnTheEmulator {
            val (viaSurface, viaCopy) = listOf(true, false).map { surface ->
                val output = directory / "hdr-$surface.mp4"
                MediaWriter.open(MediaOutput.File(output.toString()), timeout = TIMEOUT).use { writer ->
                    val track = writer.addVideoTrack(config)
                    val renderer = if (surface) {
                        ComposeFrameRenderer<Int>(context, track) { HdrNumbered(patches, it) }
                    } else {
                        ComposeFrameRenderer<Int>(context, SIZE, SIZE, config.canvasFormat) { HdrNumbered(patches, it) }
                    }
                    renderer.use { for (index in 0 until HDR_FRAMES) track.write(it.render(pts(index), index)) }
                    if (!surface) {
                        assertFalse(track.zeroCopy)
                    } else if (!track.zeroCopy) {
                        val reason = "No hardware encoder opened an HDR10 input surface on ${Build.MODEL} (API ${Build.VERSION.SDK_INT}); " +
                            "the track kept the memory path with ${track.encoderName}"
                        println("Skipped: $reason")
                        assumeTrue(reason, false)
                    }
                    writer.finish()
                }
                VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.RgbaF16), DecoderPreference.SOFTWARE).use { decoder ->
                    val frames = decoder.frames().map { frame ->
                        frame.use { HdrFrame(it.hdrNumber(), List(HDR_PATCHES.size) { patch -> it.linearRgb(patchCentreX(patch), PATCH_CENTRE_Y) }) }
                    }.toList()
                    HdrClip(decoder.info, frames)
                }
            }
            for ((path, clip) in listOf("surface" to viaSurface, "copy" to viaCopy)) {
                val info = clip.info
                assertEquals(ColorPrimaries.BT2020, info.color.primaries, "$path: primaries")
                assertEquals(ColorTransfer.PQ, info.color.transfer, "$path: transfer")
                assertEquals(10, info.bitDepth, "$path: bit depth")
                assertEquals(1000.0, info.hdrMetadata?.masteringDisplay?.maxLuminance, "$path: peak luminance in ${info.hdrMetadata}")
                assertEquals(1000, info.hdrMetadata?.contentLight?.maxContentLightLevel, "$path: MaxCLL in ${info.hdrMetadata}")
                assertEquals((0 until HDR_FRAMES).toList(), clip.frames.map { it.number }, "$path: frame numbers")
            }
            assertEquals(viaCopy.info.hdrMetadata, viaSurface.info.hdrMetadata, "the surface path's HDR metadata")
            println("SurfaceEncoderDeviceTest: HDR10 patches drawn ${HDR_PATCHES.map { it.toList() }}")
            for (frameIndex in listOf(0, HDR_FRAMES - 1)) {
                val surface = viaSurface.frames[frameIndex].patches
                val copy = viaCopy.frames[frameIndex].patches
                println("SurfaceEncoderDeviceTest: frame $frameIndex through the surface ${surface.map { it.rounded() }}, through the copy ${copy.map { it.rounded() }}")
                HDR_PATCHES.forEachIndexed { patch, drawn ->
                    for (channel in 0 until 3) {
                        val expected = drawn[channel]
                        val tolerance = hdrTolerance(expected)
                        assertTrue(
                            abs(surface[patch][channel] - expected) <= tolerance,
                            "frame $frameIndex, patch $patch through the surface: ${surface[patch].toList()}, drawn ${drawn.toList()}",
                        )
                        assertTrue(
                            abs(surface[patch][channel] - copy[patch][channel]) <= tolerance,
                            "frame $frameIndex, patch $patch through the surface: ${surface[patch].toList()}, through the copy ${copy[patch].toList()}",
                        )
                    }
                }
            }
        }
    }

    private class HdrFrame(val number: Int, val patches: List<DoubleArray>)

    private class HdrClip(val info: VideoInfo, val frames: List<HdrFrame>)

    /**
     * 6% of the light plus 0.03 (6 nits): PQ at 10 bits in limited range spends about half a percent of the light
     * per code over these values, a hardware encoder's quantisation of a flat patch moves it a few codes, and the
     * 4:2:0 chroma rounding leaves a small constant on the dark patch and the red one's empty channels.
     */
    private fun hdrTolerance(expected: Double): Double = 0.06 * expected + 0.03

    /** The patches as an F16 bitmap in linear extended sRGB, [HDR_PATCHES] as vertical bands below the number rows. */
    private fun hdrPatches(): ImageBitmap {
        val height = SIZE - PATCH_TOP
        val pixels = ByteBuffer.allocate(SIZE * height * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) {
            for (x in 0 until SIZE) {
                val patch = HDR_PATCHES[(x * HDR_PATCHES.size / SIZE).coerceAtMost(HDR_PATCHES.size - 1)]
                for (channel in 0 until 3) pixels.putShort(Half.toHalf(patch[channel].toFloat()))
                pixels.putShort(Half.toHalf(1f))
            }
        }
        pixels.flip()
        return Bitmap.createBitmap(SIZE, height, Bitmap.Config.RGBA_F16, true, ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
            .apply { copyPixelsFromBuffer(pixels) }
            .asImageBitmap()
    }

    private fun patchCentreX(patch: Int): Int = patch * SIZE / HDR_PATCHES.size + SIZE / HDR_PATCHES.size / 2

    /** The frame number [HdrNumbered] draws as 16x16 cells in the top rows of an F16 frame, read from the red channel. */
    private fun VideoFrame.hdrNumber(): Int = (0 until 12).sumOf { bit ->
        if (linearRgb(x = (bit % 6) * 16 + 8, y = (bit / 6) * 16 + 8)[0] > 0.5) 1 shl bit else 0
    }

    /** The linear light at [x], [y] of an RGBA F16 frame, 1.0 at 203 nits. */
    private fun VideoFrame.linearRgb(x: Int, y: Int): DoubleArray = assertNotNull(
        usePlanes { planes ->
            val plane = planes.single()
            DoubleArray(3) { channel ->
                val offset = y * plane.rowBytes + (x * 4 + channel) * 2
                Half.toFloat(((plane.bytes[offset].toInt() and 0xff) or (plane.bytes[offset + 1].toInt() and 0xff shl 8)).toShort()).toDouble()
            }
        },
    )

    private fun DoubleArray.rounded(): List<Double> = map { Math.round(it * 1000) / 1000.0 }

    /** Runs [block]; on the emulator a writer that timed out, within the timeout, passes too. */
    private suspend fun timesOutOnTheEmulator(block: suspend () -> Unit) {
        if (Build.HARDWARE != "ranchu") return block()
        val started = TimeSource.Monotonic.markNow()
        try {
            block()
        } catch (failure: MediaWritingException) {
            assertTrue("timed out" in failure.message.orEmpty(), "Unexpected $failure")
            assertTrue(started.elapsedNow() < TIMEOUT * 3, "Timing out took ${started.elapsedNow()}")
            println("The emulator's encoder timed out: ${failure.message}")
        }
    }

    /** The frame number [Numbered] draws as 16x16 cells in the top rows, read from the red channel. */
    private fun VideoFrame.number(): Int = (0 until 12).sumOf { bit ->
        if (pixel(x = (bit % 6) * 16 + 8, y = (bit / 6) * 16 + 8)[0] > 128) 1 shl bit else 0
    }

    private fun VideoFrame.pixel(x: Int, y: Int): IntArray = assertNotNull(
        usePlanes { planes ->
            val plane = planes.single()
            IntArray(3) { plane.bytes[y * plane.rowBytes + x * 4 + it].toInt() and 0xff }
        },
    )

    /** The frame at [index] of a 30 fps clip, rounded to the nearest nanosecond as the decoder does. */
    private fun pts(index: Int): Duration = ((index * 1_000_000_000L + 15) / 30).nanoseconds

    private companion object {
        const val SIZE = 128
        const val FRAMES = 30
        const val HDR_FRAMES = 10
        const val PATCH_TOP = 32
        const val PATCH_CENTRE_Y = (SIZE + PATCH_TOP) / 2
        val TIMEOUT = 5.seconds
        val PATCH_RGB = listOf(200, 100, 40)

        /** Linear light, 1.0 at 203 nits: greys from 20 nits to 1000, and red at reference white. */
        val HDR_PATCHES = listOf(
            doubleArrayOf(0.1, 0.1, 0.1),
            doubleArrayOf(0.5, 0.5, 0.5),
            doubleArrayOf(1.0, 1.0, 1.0),
            doubleArrayOf(2.0, 2.0, 2.0),
            doubleArrayOf(1000.0 / 203.0, 1000.0 / 203.0, 1000.0 / 203.0),
            doubleArrayOf(1.0, 0.0, 0.0),
        )
    }
}

/** Black with the 12-bit [number] as white 16x16 cells in the top two rows, and an orange patch below. */
@Composable
private fun Numbered(number: Int) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color.Black)
        for (bit in 0 until 12) {
            if (number shr bit and 1 == 1) drawRect(Color.White, Offset((bit % 6) * 16f, (bit / 6) * 16f), Size(16f, 16f))
        }
        drawRect(Color(200, 100, 40), Offset(0f, size.height / 2), Size(size.width, size.height / 2))
    }
}

/** [Numbered]'s cells with the [patches] bitmap, drawn as it is, below them. */
@Composable
private fun HdrNumbered(patches: ImageBitmap, number: Int) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color.Black)
        for (bit in 0 until 12) {
            if (number shr bit and 1 == 1) drawRect(Color.White, Offset((bit % 6) * 16f, (bit / 6) * 16f), Size(16f, 16f))
        }
        drawImage(patches, dstOffset = IntOffset(0, 32))
    }
}
