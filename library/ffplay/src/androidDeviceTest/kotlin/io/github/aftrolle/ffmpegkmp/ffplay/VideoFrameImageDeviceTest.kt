// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import android.graphics.Bitmap
import android.os.Build
import android.graphics.ColorSpace
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.ExperimentalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okio.Buffer

/** toImageBitmap on a device: frames converted straight into a Bitmap through AndroidBitmap_lockPixels. */
@OptIn(ExperimentalFFmpegKmpApi::class)
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
        // Tone mapped to SDR from the same frames as decoded, before Android 14 tags PQ for the platform to show.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@runBlocking
        open("hdr10-pq.mp4", VideoOutput.Memory()).use { decoder ->
            val bitmap = decoder.frameAt(0.5.seconds).use { it.toImageBitmap() }.asAndroidBitmap()
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            val left = bitmap.getPixel(12, 32) and 0xff
            val right = bitmap.getPixel(84, 32) and 0xff
            assertTrue(right in (left + 1)..255, "Tone mapped highlight $right should stay above $left")
        }
    }

    @Test
    fun hdrPostersKeepTheirCodesInATaggedTenBitBitmapTurnedUpright() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            println("Skipped: PQ and HLG bitmaps need API 34 (API ${Build.VERSION.SDK_INT})")
            return@runBlocking
        }
        val upright = poster("hdr10-pq.mp4")
        assertEquals(Bitmap.Config.RGBA_1010102, upright.config)
        assertEquals(ColorSpace.get(ColorSpace.Named.BT2020_PQ), upright.colorSpace)
        assertEquals(96 to 64, upright.width to upright.height)
        val codes = upright.codes()
        // 100 nits on the left and 1000 nits on the right, as PQ codes rather than tone mapped.
        assertEquals(pqCode(100.0).toDouble(), codes.red(12, 32).toDouble(), 2.0)
        assertEquals(pqCode(1000.0).toDouble(), codes.red(84, 32).toDouble(), 2.0)

        val turned = poster("hdr10-pq-rotated-90.mp4")
        assertEquals(Bitmap.Config.RGBA_1010102, turned.config)
        assertEquals(64 to 96, turned.width to turned.height)
        val turnedCodes = turned.codes()
        for ((x, y) in listOf(12 to 32, 84 to 32, 48 to 10, 48 to 54)) {
            assertEquals(codes.red(x, y), turnedCodes.red(y, 95 - x), "($x, $y) turned anticlockwise")
        }

        open("hdr10-pq.mp4", VideoOutput.Memory()).use { decoder ->
            val frame = decoder.frameAt(0.5.seconds).use { it.toImageBitmap() }.asAndroidBitmap()
            assertEquals(Bitmap.Config.RGBA_1010102, frame.config)
            assertEquals(ColorSpace.get(ColorSpace.Named.BT2020_PQ), frame.colorSpace)
        }

        val hlg = poster("hlg.mp4")
        assertEquals(Bitmap.Config.RGBA_1010102, hlg.config)
        assertEquals(ColorSpace.get(ColorSpace.Named.BT2020_HLG), hlg.colorSpace)
        assertEquals(Bitmap.Config.ARGB_8888, poster("cfr-30.mp4").config)
    }

    private suspend fun poster(name: String): Bitmap = VideoDecoder.extractImageBitmap(source(name), 3.seconds / 30, decoder = CodecPreference.SOFTWARE)
        .asAndroidBitmap()

    /** The 10-bit codes of an RGBA_1010102 bitmap, red in the low bits of each little-endian word. */
    private class Codes(private val words: ByteBuffer, private val rowBytes: Int) {
        fun red(x: Int, y: Int): Int = words.getInt(y * rowBytes + x * 4) and 0x3ff
    }

    private fun Bitmap.codes(): Codes = Codes(ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN).also { copyPixelsToBuffer(it) }, rowBytes)

    private fun pqCode(nits: Double): Int {
        val y = Math.pow(nits / 10000, 0.1593017578125)
        return Math.round(Math.pow((0.8359375 + 18.8515625 * y) / (1 + 18.6875 * y), 78.84375) * 1023).toInt()
    }

    private suspend fun open(name: String, output: VideoOutput): VideoDecoder = VideoDecoder.open(source(name), output, CodecPreference.SOFTWARE)

    private fun source(name: String): MediaSource {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/video-decoder/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
        return MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) })
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
