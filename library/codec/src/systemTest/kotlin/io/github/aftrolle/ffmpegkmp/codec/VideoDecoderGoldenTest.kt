// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okio.Buffer

/**
 * The decoder's conversions against references recorded from the bridge before its conversions
 * moved onto swscale. The references live in commonTest/resources/video-decoder/golden;
 * scripts/test-converter.sh records and checks them, the CVPixelBuffer ones included. RGBA8 may
 * differ by one code per channel, RgbaF16 by [HALF_ULPS] half-float steps.
 *
 * Each reference is checked three ways: frames the decoder converted on its thread
 * ([VideoOutput.Memory] with a format), frames as decoded converted with [VideoFrame.convert], and
 * the same converted with [VideoFrame.convertInto] into a frame of another decoder.
 */
class VideoDecoderGoldenTest {
    @Test
    fun sdrSourcesConvertToRgba8AsBefore() = runBlocking<Unit> {
        assertMatchesGolden("cfr-30.mp4", FrameFormat.Rgba8, "cfr-30-rgba8.bin")
        assertMatchesGolden("cfr-30-h264.mp4", FrameFormat.Rgba8, "cfr-30-h264-rgba8.bin")
    }

    @Test
    fun sdrSourcesConvertToRgbaF16AsBefore() = runBlocking<Unit> {
        assertMatchesGolden("cfr-30.mp4", FrameFormat.RgbaF16, "cfr-30-linear-f16.bin")
    }

    @Test
    fun hdrSourcesToneMapToRgba8AsRecorded() = runBlocking<Unit> {
        assertMatchesGolden("hdr10-pq.mp4", FrameFormat.Rgba8, "hdr10-pq-rgba8.bin")
        assertMatchesGolden("hlg.mp4", FrameFormat.Rgba8, "hlg-rgba8.bin")
    }

    /**
     * BT.2390's EETF onto SDR white, from each clip's own peak: below the knee the source keeps its
     * nits, 203-nit reference white lands near SDR white and highlights roll off to it at the
     * clip's peak. The codes are the EETF's, through the sRGB curve, give or take two.
     */
    @Test
    fun hdrSourcesToneMapWithBt2390OntoSdrWhite() = runBlocking {
        decoder("hdr10-pq.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { frame ->
                val pixels = frame.pixels(half = false)
                // 100 nits sits below the 1000-nit master's knee: 100 / 203 of SDR white.
                assertCode(186, pixels.at(12, 32, 1), "100 nits")
                // The master's 1000-nit peak is SDR white.
                assertCode(255, pixels.at(84, 32, 1), "the 1000-nit peak")
            }
        }
        decoder("hlg.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { frame ->
                val pixels = frame.pixels(half = false)
                // 75% grey is HLG's reference white, 203 nits on its 1000-nit display.
                assertCode(230, pixels.at(24, 16, 1), "HLG reference white")
                assertCode(255, pixels.at(72, 16, 1), "HLG peak white")
                // BT.2020 green and red, outside sRGB: compressed into it, keeping their hue.
                val green = (0 until 3).map { pixels.at(24, 48, it) }
                val red = (0 until 3).map { pixels.at(72, 48, it) }
                assertTrue(green[0] <= 2 && green[2] <= 2 && green[1] > 200, "green $green")
                assertTrue(red[1] <= 10 && red[2] <= 2 && red[0] > 200, "red $red")
            }
        }
    }

    /**
     * The 320x192 colour fixtures decode to the light of their codes on sRGB primaries, 1.0 at 203 nits, at a few
     * patch centres; FrameImageDeviceTest checks every patch on the GPU against the same table.
     */
    @Test
    fun hdrColourPatchesDecodeToTheirLinearColoursInRgbaF16() = runBlocking {
        val patches = mapOf(
            "hdr10-pq-patches.mp4" to listOf(
                Triple("red 203", 40 to 24, doubleArrayOf(1.6598, -0.1245, -0.0181)),
                Triple("cyan 1000", 40 to 168, doubleArrayOf(-3.2502, 5.5337, 5.0102)),
                Triple("grey 1000", 280 to 120, doubleArrayOf(4.9208, 4.9208, 4.9208)),
            ),
            "hlg-large.mp4" to listOf(
                Triple("75% grey", 80 to 48, doubleArrayOf(0.9993, 0.9993, 0.9993)),
                Triple("white", 240 to 48, doubleArrayOf(4.9261, 4.9261, 4.9261)),
                Triple("75% green", 80 to 144, doubleArrayOf(-0.5433, 1.0474, -0.0930)),
            ),
        )
        for ((clip, expected) in patches) {
            decoder(clip, VideoOutput.Memory(FrameFormat.RgbaF16)).use { decoder ->
                decoder.frameAt(0.5.seconds).use { frame ->
                    val pixels = frame.pixels(half = true)
                    for ((name, centre, light) in expected) {
                        val tolerance = 0.03 * light.maxOf { abs(it) } + 0.03
                        for (channel in 0 until 3) {
                            val actual = pixels.value(pixels.at(centre.first, centre.second, channel))
                            assertTrue(abs(actual - light[channel]) <= tolerance, "$clip $name channel $channel: $actual, expected ${light[channel]}")
                        }
                    }
                }
            }
        }
    }

    private fun assertCode(expected: Int, actual: Int, name: String) =
        assertTrue(abs(actual - expected) <= 2, "$name: $actual, BT.2390 gives $expected")

    @Test
    fun hdrSourcesKeepHighlightsAndNegativeComponentsInRgbaF16() = runBlocking {
        val pq = assertMatchesGolden("hdr10-pq.mp4", FrameFormat.RgbaF16, "hdr10-pq-linear-f16.bin")
        assertTrue(pq.maximum > 1.0, "PQ highlights above 1.0 (203 nits), was ${pq.maximum}")
        // hlg.mp4 has BT.2020 green and red patches, outside sRGB.
        val hlg = assertMatchesGolden("hlg.mp4", FrameFormat.RgbaF16, "hlg-linear-f16.bin")
        assertTrue(hlg.maximum > 1.0, "HLG highlights above 1.0 (203 nits), was ${hlg.maximum}")
        assertTrue(hlg.minimum < 0.0, "Negative components of colours outside sRGB, was ${hlg.minimum}")
    }

    private class Range(val minimum: Double, val maximum: Double)

    /** Decodes the reference's frames all three ways and compares them; returns the last frame's component range. */
    private suspend fun assertMatchesGolden(clip: String, format: FrameFormat, reference: String): Range {
        val golden = Golden.parse(readVideoDecoderFixture("golden/$reference"))
        var range = Range(0.0, 0.0)
        decoder(clip, VideoOutput.Memory(format)).use { converting ->
            decoder(clip, VideoOutput.Memory()).use { asDecoded ->
                for ((index, expected) in golden.frames.withIndex()) {
                    val position = expected.ptsNs.nanoseconds
                    converting.frameAt(position).use { frame ->
                        assertEquals(format, frame.format)
                        range = assertMatches(frame, golden, index, "$reference on the decoder's thread")
                        asDecoded.frameAt(position).use { decoded ->
                            decoded.convert(format).use { converted ->
                                assertEquals(format, converted.format)
                                assertMatches(converted, golden, index, "$reference through convert")
                            }
                            // Into the other decoder's frame, overwriting it.
                            decoded.convertInto(frame)
                            assertMatches(frame, golden, index, "$reference through convertInto")
                        }
                    }
                }
            }
        }
        return range
    }

    private fun assertMatches(frame: VideoFrame, golden: Golden, index: Int, name: String): Range {
        val expected = golden.frames[index]
        val half = frame.format?.layout == PixelLayout.RGBA_F16
        val pixels = frame.pixels(half)
        assertEquals(expected.ptsNs.nanoseconds, frame.pts, "$name frame $index pts")
        assertEquals(golden.width, frame.width, "$name width")
        assertEquals(golden.height, frame.height, "$name height")
        var worst = 0
        val samples = ArrayList<Int>()
        for (y in 0 until frame.height step golden.step) {
            for (x in 0 until frame.width step golden.step) {
                for (channel in 0 until 3) samples += pixels.at(x, y, channel)
            }
        }
        assertEquals(expected.samples.size, samples.size, "$name frame $index samples")
        for (sample in samples.indices) {
            val error = error(samples[sample], expected.samples[sample], half)
            worst = max(worst, error)
            assertTrue(
                error <= if (half) HALF_ULPS else 1,
                "$name frame $index sample $sample: ${samples[sample]}, reference ${expected.samples[sample]}",
            )
        }
        // Over the whole frame: the brightest and darkest components must stay where they were.
        val components = (0 until frame.height).flatMap { y ->
            (0 until frame.width).flatMap { x -> (0 until 3).map { pixels.at(x, y, it) } }
        }
        val minimum = components.minBy { pixels.value(it) }
        val maximum = components.maxBy { pixels.value(it) }
        for ((actual, stored) in listOf(minimum to expected.minimum, maximum to expected.maximum)) {
            val expectedCode = if (half) floatToHalf(stored) else stored.toInt()
            assertTrue(
                error(actual, expectedCode, half) <= if (half) HALF_ULPS else 1,
                "$name frame $index component range: ${pixels.value(actual)}, reference $stored",
            )
        }
        println("$name frame $index: max error $worst ${if (half) "half-float steps" else "codes"}")
        return Range(pixels.value(minimum), pixels.value(maximum))
    }

    private suspend fun decoder(name: String, output: VideoOutput): VideoDecoder = VideoDecoder.open(
        MediaSource(name, CommandIo { input(name, Buffer().write(readVideoDecoderFixture(name))) }),
        output,
        DecoderPreference.SOFTWARE,
        10.seconds,
        DecoderThreads.Auto,
    )

    /** RGB samples of a frame: 8-bit codes, or the bits of half floats. */
    private class Pixels(val width: Int, private val values: IntArray, private val half: Boolean) {
        fun at(x: Int, y: Int, channel: Int): Int = values[(y * width + x) * 4 + channel]

        fun value(sample: Int): Double = if (half) halfToDouble(sample) else sample.toDouble()
    }

    private fun VideoFrame.pixels(half: Boolean): Pixels {
        val bytes = packed(if (half) 8 else 4)
        val values = if (half) {
            IntArray(bytes.size / 2) { index -> (bytes[index * 2].toInt() and 0xff) or (bytes[index * 2 + 1].toInt() and 0xff shl 8) }
        } else {
            IntArray(bytes.size) { index -> bytes[index].toInt() and 0xff }
        }
        return Pixels(width, values, half)
    }

    /** One reference file, as converter_test.c writes it (see there for the layout). */
    private class Golden(val width: Int, val height: Int, val step: Int, val frames: List<Frame>) {
        class Frame(val ptsNs: Long, val minimum: Float, val maximum: Float, val samples: IntArray)

        companion object {
            private const val SAMPLE_RGBA8 = 0
            private const val SAMPLE_RGBA_F16 = 1

            fun parse(bytes: ByteArray): Golden {
                var offset = 0
                fun u8(): Int = bytes[offset++].toInt() and 0xff
                fun u16(): Int = u8() or (u8() shl 8)
                fun u32(): Int = u16() or (u16() shl 16)
                fun u64(): Long = (u32().toLong() and 0xffffffffL) or (u32().toLong() shl 32)
                check(bytes.copyOfRange(0, 4).decodeToString() == "FKG1") { "Not a golden reference" }
                offset = 4
                val format = u32()
                check(format == SAMPLE_RGBA8 || format == SAMPLE_RGBA_F16) { "Sample format $format has no Kotlin reader" }
                val width = u32()
                val height = u32()
                val step = u32()
                val frames = List(u32()) {
                    val pts = u64()
                    u64() // The full frame's hash, which only converter_test.c compares.
                    val minimum = Float.fromBits(u32())
                    val maximum = Float.fromBits(u32())
                    val samples = IntArray(u32()) { if (format == SAMPLE_RGBA_F16) u16() else u8() }
                    Frame(pts, minimum, maximum, samples)
                }
                return Golden(width, height, step, frames)
            }
        }
    }

    private companion object {
        const val HALF_ULPS = 2

        /** Codes apart, or for the bits of half floats, steps apart. */
        fun error(actual: Int, expected: Int, half: Boolean): Int =
            if (half) abs(halfOrder(actual) - halfOrder(expected)) else abs(actual - expected)

        /** A half float's position on the number line, so that neighbours differ by one. */
        fun halfOrder(bits: Int): Int = if (bits and 0x8000 != 0) -(bits and 0x7fff) else bits and 0x7fff

        /** The bits of a float that holds a half float exactly, as the references store their ranges. */
        fun floatToHalf(value: Float): Int {
            val bits = value.toRawBits()
            val sign = bits ushr 16 and 0x8000
            val magnitude = bits and 0x7fffffff
            if (magnitude < 0x38800000) return sign or (abs(value) * 16777216f).toInt()
            return sign or ((magnitude - 0x38000000) ushr 13)
        }
    }
}
