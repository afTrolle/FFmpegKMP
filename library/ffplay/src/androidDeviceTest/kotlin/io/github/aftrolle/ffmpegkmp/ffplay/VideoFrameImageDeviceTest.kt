// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.MediaSource
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okio.Buffer

/** toImageBitmap on a device: frames converted straight into a Bitmap through AndroidBitmap_lockPixels. */
class VideoFrameImageDeviceTest {
    @Test
    fun yuvAndRgba8FramesBecomeSrgbArgb8888Bitmaps() = runBlocking<Unit> {
        for (output in listOf(VideoOutput.Memory(), VideoOutput.Memory(FrameFormat.Rgba8))) {
            open("cfr-30.mp4", output).use { decoder ->
                for (index in listOf(0, 45, 149)) {
                    val bitmap = decoder.frameAt(index.seconds / 30).use { it.toImageBitmap() }.asAndroidBitmap()
                    assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
                    assertEquals(96 to 64, bitmap.width to bitmap.height)
                    assertEquals(index, bitmap.number(), "frame $index from $output")
                    assertEquals(0xff, bitmap.getPixel(0, 0) ushr 24, "opaque")
                }
            }
        }
    }

    @Test
    fun rgbaF16FramesKeepTheirHighlightsInALinearExtendedSrgbBitmap() = runBlocking<Unit> {
        open("hdr10-pq.mp4", VideoOutput.Memory(FrameFormat.RgbaF16)).use { decoder ->
            val bitmap = decoder.frameAt(0.5.seconds).use { it.toImageBitmap() }.asAndroidBitmap()
            assertEquals(Bitmap.Config.RGBA_F16, bitmap.config)
            assertEquals(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB), bitmap.colorSpace)
            val pixels = ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.LITTLE_ENDIAN)
            bitmap.copyPixelsToBuffer(pixels)
            // 100 nits on the left and 1000 nits on the right, with 1.0 = 203 nits.
            fun red(x: Int, y: Int): Float = halfToFloat(pixels.getShort(y * bitmap.rowBytes + x * 8))
            assertEquals(100f / 203f, red(12, 32), 0.02f)
            assertEquals(1000f / 203f, red(84, 32), 0.15f)
            assertTrue(red(84, 32) > 1f, "HDR above SDR white")
        }
        // Tone mapped to SDR from the same frames as decoded.
        open("hdr10-pq.mp4", VideoOutput.Memory()).use { decoder ->
            val bitmap = decoder.frameAt(0.5.seconds).use { it.toImageBitmap() }.asAndroidBitmap()
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            val left = bitmap.getPixel(12, 32) and 0xff
            val right = bitmap.getPixel(84, 32) and 0xff
            assertTrue(right in (left + 1)..255, "Tone mapped highlight $right should stay above $left")
        }
    }

    private suspend fun open(name: String, output: VideoOutput): VideoDecoder {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/video-decoder/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
        return VideoDecoder.open(
            MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }),
            output,
            DecoderPreference.SOFTWARE,
        )
    }

    /** The 12-bit frame number the fixture generator draws as 16x16 cells in the top rows. */
    private fun Bitmap.number(): Int = (0 until 12).sumOf { bit ->
        val green = getPixel((bit % 6) * 16 + 8, (bit / 6) * 16 + 8) shr 8 and 0xff
        if (green > 128) 1 shl bit else 0
    }

    private fun halfToFloat(half: Short): Float {
        val bits = half.toInt() and 0xffff
        val exponent = bits shr 10 and 0x1f
        val mantissa = bits and 0x3ff
        val magnitude = when (exponent) {
            0 -> mantissa / 1024f / 16384f
            else -> (1 + mantissa / 1024f) * Math.pow(2.0, exponent - 15.0).toFloat()
        }
        return if (bits and 0x8000 != 0) -magnitude else magnitude
    }
}
