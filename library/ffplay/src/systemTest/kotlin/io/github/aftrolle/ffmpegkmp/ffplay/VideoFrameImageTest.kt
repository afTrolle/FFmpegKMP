// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.MediaSource
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * [toImageBitmap] against the decoder's golden references (codec's
 * commonTest/resources/video-decoder/golden, which scripts/test-converter.sh records): frames the
 * decoder converted into [FrameFormat.Rgba8] and [FrameFormat.RgbaF16] keep their format in the
 * bitmap, and frames as decoded become RGBA8 in it. RGBA8 may differ by one code per channel,
 * RGBA F16 by [HALF_ULPS] half-float steps.
 */
class VideoFrameImageTest {
    @Test
    fun rgba8FramesAndYuvFramesBecomeRgba8BitmapsAsTheReferences() = runBlocking<Unit> {
        for ((clip, reference) in listOf(
            "cfr-30.mp4" to "cfr-30-rgba8.bin",
            "cfr-30-h264.mp4" to "cfr-30-h264-rgba8.bin",
            "hdr10-pq.mp4" to "hdr10-pq-rgba8.bin",
            "hlg.mp4" to "hlg-rgba8.bin",
        )) {
            assertBitmapsMatch(clip, VideoOutput.Memory(FrameFormat.Rgba8), reference, half = false)
            // Tone mapped by toImageBitmap itself, from the frames as decoded.
            assertBitmapsMatch(clip, VideoOutput.Memory(), reference, half = false)
        }
    }

    @Test
    fun rgbaF16FramesKeepLinearExtendedSrgbAndTheirHighlights() = runBlocking<Unit> {
        for ((clip, reference) in listOf(
            "cfr-30.mp4" to "cfr-30-linear-f16.bin",
            "hdr10-pq.mp4" to "hdr10-pq-linear-f16.bin",
            "hlg.mp4" to "hlg-linear-f16.bin",
        )) {
            assertBitmapsMatch(clip, VideoOutput.Memory(FrameFormat.RgbaF16), reference, half = true)
        }
    }

    @Test
    fun theFrameStaysOpenAndAClosedOneFails() = runBlocking<Unit> {
        decoder("cfr-30.mp4", VideoOutput.Memory()).use { decoder ->
            val frame = decoder.frameAt(1.seconds)
            val image = frame.toImageBitmap()
            assertEquals(96 to 64, image.width to image.height)
            frame.toImageBitmap()
            frame.close()
            assertFailsWith<IllegalStateException> { frame.toImageBitmap() }
        }
    }

    private suspend fun assertBitmapsMatch(clip: String, output: VideoOutput, reference: String, half: Boolean) {
        val golden = Golden.parse(readVideoDecoderFixture("golden/$reference"))
        decoder(clip, output).use { decoder ->
            for ((index, expected) in golden.frames.withIndex()) {
                val image = decoder.frameAt(expected.ptsNs.nanoseconds).use(VideoFrame::toImageBitmap)
                val pixels = image.pixels(half)
                assertEquals(golden.width to golden.height, image.width to image.height, reference)
                var sample = 0
                for (y in 0 until image.height step golden.step) {
                    for (x in 0 until image.width step golden.step) {
                        for (channel in 0 until 3) {
                            val actual = pixels[(y * image.width + x) * 4 + channel]
                            val wanted = expected.samples[sample++]
                            assertTrue(
                                error(actual, wanted, half) <= if (half) HALF_ULPS else 1,
                                "$reference ($output) frame $index at $x,$y channel $channel: $actual, reference $wanted",
                            )
                        }
                    }
                }
                assertEquals(expected.samples.size, sample, "$reference samples")
                if (half) {
                    val maximum = pixels.filterIndexed { position, _ -> position % 4 != 3 }.maxOf(::halfToDouble)
                    assertTrue(abs(maximum - expected.maximum) < 0.05 * expected.maximum, "$reference maximum $maximum")
                }
            }
        }
    }

    private suspend fun decoder(name: String, output: VideoOutput): VideoDecoder = VideoDecoder.open(
        MediaSource(name, CommandIo { input(name, Buffer().write(readVideoDecoderFixture(name))) }),
        output,
        DecoderPreference.SOFTWARE,
    )

    /** RGBA samples: 8-bit codes from an 8-bit bitmap, half-float bits from a linear F16 one. */
    private fun ImageBitmap.pixels(half: Boolean): IntArray {
        val bitmap = asSkiaBitmap()
        if (!half) {
            assertEquals(ColorType.RGBA_8888, bitmap.colorType)
            val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB)
            val bytes = assertNotNull(bitmap.readPixels(info, width * 4, 0, 0))
            return IntArray(bytes.size) { bytes[it].toInt() and 0xff }
        }
        assertEquals(ColorType.RGBA_F16, bitmap.colorType)
        assertTrue(bitmap.colorSpace?.isGammaLinear == true, "a linear bitmap")
        val info = ImageInfo(width, height, ColorType.RGBA_F16, ColorAlphaType.UNPREMUL, ColorSpace.sRGBLinear)
        val bytes = assertNotNull(bitmap.readPixels(info, width * 8, 0, 0))
        return IntArray(bytes.size / 2) { (bytes[it * 2].toInt() and 0xff) or (bytes[it * 2 + 1].toInt() and 0xff shl 8) }
    }

    /** One reference file, as converter_test.c writes it (see there for the layout). */
    private class Golden(val width: Int, val height: Int, val step: Int, val frames: List<Frame>) {
        class Frame(val ptsNs: Long, val maximum: Float, val samples: IntArray)

        companion object {
            fun parse(bytes: ByteArray): Golden {
                var offset = 4
                fun u8(): Int = bytes[offset++].toInt() and 0xff
                fun u16(): Int = u8() or (u8() shl 8)
                fun u32(): Int = u16() or (u16() shl 16)
                fun u64(): Long = (u32().toLong() and 0xffffffffL) or (u32().toLong() shl 32)
                check(bytes.copyOfRange(0, 4).decodeToString() == "FKG1") { "Not a golden reference" }
                val half = u32() == 1
                val width = u32()
                val height = u32()
                val step = u32()
                val frames = List(u32()) {
                    val pts = u64()
                    u64() // The full frame's hash.
                    u32() // The smallest component.
                    val maximum = Float.fromBits(u32())
                    Frame(pts, maximum, IntArray(u32()) { if (half) u16() else u8() })
                }
                return Golden(width, height, step, frames)
            }
        }
    }

    private companion object {
        const val HALF_ULPS = 2

        fun error(actual: Int, expected: Int, half: Boolean): Int =
            if (half) abs(halfOrder(actual) - halfOrder(expected)) else abs(actual - expected)

        /** A half float's position on the number line, so that neighbours differ by one. */
        fun halfOrder(bits: Int): Int = if (bits and 0x8000 != 0) -(bits and 0x7fff) else bits and 0x7fff

        fun halfToDouble(bits: Int): Double {
            val exponent = bits shr 10 and 0x1f
            val mantissa = bits and 0x3ff
            val magnitude = when (exponent) {
                0 -> mantissa / 1024.0 * 2.0.pow(-14)
                0x1f -> Double.POSITIVE_INFINITY
                else -> (1 + mantissa / 1024.0) * 2.0.pow(exponent - 15)
            }
            return if (bits and 0x8000 != 0) -magnitude else magnitude
        }
    }
}
