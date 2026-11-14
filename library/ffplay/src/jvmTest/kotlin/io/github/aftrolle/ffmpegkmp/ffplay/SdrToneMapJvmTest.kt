// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.GAMUT_MAP_POINTS
import io.github.aftrolle.ffmpegkmp.bindings.allocateNativeFrame
import io.github.aftrolle.ffmpegkmp.bindings.nativeGamutMap
import io.github.aftrolle.ffmpegkmp.codec.ColorMatrix
import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.ColorRange
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.ContentLightMetadata
import io.github.aftrolle.ffmpegkmp.codec.FrameColor
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.HdrMetadata
import io.github.aftrolle.ffmpegkmp.codec.MasteringDisplay
import io.github.aftrolle.ffmpegkmp.codec.PixelLayout
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import io.github.aftrolle.ffmpegkmp.codec.toNative
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hardware Canvas's tone map to SDR against the CPU converter's: a Kotlin port of the AGSL shader
 * (`TONE_MAPPED_IMAGE_SHADER` in VideoFrameImage.android.kt), step for step, fed the codes of an
 * RGBA_1010102 frame that the converter maps to RGBA8 itself, with the gamut map the bridge hands out.
 */
class SdrToneMapJvmTest {
    @Test
    fun theShadersPqToneMapMatchesTheConvertersWithinTwoEightBitCodes() = assertPortMatchesConverter(ColorTransfer.PQ)

    @Test
    fun theShadersHlgToneMapMatchesTheConvertersWithinTwoEightBitCodes() = assertPortMatchesConverter(ColorTransfer.HLG)

    @Test
    fun onAWideCanvasTheShadersPqToneMapMatchesTheConvertersIntoDisplayP3() =
        assertPortMatchesConverter(ColorTransfer.PQ, FrameColor.DisplayP3)

    @Test
    fun onAWideCanvasTheShadersHlgToneMapMatchesTheConvertersIntoDisplayP3() =
        assertPortMatchesConverter(ColorTransfer.HLG, FrameColor.DisplayP3)

    @Test
    fun theClipPeakIsTheConvertersFromTheStreamsMetadata() {
        fun video(transfer: ColorTransfer, mastering: Double? = null, maxCll: Int? = null) = VideoInfo(
            width = 64,
            height = 64,
            color = FrameColor(ColorPrimaries.BT2020, transfer, ColorMatrix.BT2020_NCL, ColorRange.LIMITED),
            hdrMetadata = HdrMetadata(
                masteringDisplay = mastering?.let { MasteringDisplay.DisplayP3At1000Nits.copy(maxLuminance = it) },
                contentLight = maxCll?.let { ContentLightMetadata(maxContentLightLevel = it) },
            ),
        )
        assertEquals(1000.0, SdrToneMap.clipPeak(video(ColorTransfer.PQ)), "PQ without metadata")
        assertEquals(1500.0, SdrToneMap.clipPeak(video(ColorTransfer.PQ, mastering = 4000.0, maxCll = 1500)), "the lower of the two")
        assertEquals(4000.0, SdrToneMap.clipPeak(video(ColorTransfer.PQ, mastering = 4000.0)), "the mastering display's")
        assertEquals(1000.0, SdrToneMap.clipPeak(video(ColorTransfer.HLG, mastering = 4000.0)), "HLG's reference display at most")
        assertEquals(600.0, SdrToneMap.clipPeak(video(ColorTransfer.HLG, maxCll = 600)), "HLG below it")
        assertEquals(203.0, SdrToneMap.clipPeak(video(ColorTransfer.PQ, maxCll = 100)), "no darker than SDR white")
        assertEquals(null, SdrToneMap.of(video(ColorTransfer.BT709)), "SDR is not tone mapped")
    }

    /** The port, gamut mapped into [target]'s primaries as the shader maps for its canvas, against the converter into [target]. */
    private fun assertPortMatchesConverter(transfer: ColorTransfer, target: FrameColor = FrameColor.Srgb) {
        val codes = testCodes()
        val format = FrameFormat(PixelLayout.RGBA_1010102, FrameColor(ColorPrimaries.BT2020, transfer, ColorMatrix.RGB, ColorRange.FULL))
        val converted = allocateNativeFrame(format.toNative(), WIDTH, HEIGHT).use { source ->
            source.usePlanes { planes ->
                val plane = planes.single()
                val bytes = (plane.memory as ByteBuffer).duplicate().order(ByteOrder.LITTLE_ENDIAN)
                for (pixel in 0 until WIDTH * HEIGHT) {
                    val (red, green, blue) = codes[pixel]
                    bytes.putInt(pixel / WIDTH * plane.rowBytes + pixel % WIDTH * 4, red or (green shl 10) or (blue shl 20) or (3 shl 30))
                }
            }
            source.convert(FrameFormat(PixelLayout.RGBA8, target).toNative()).use { rgba ->
                rgba.usePlanes { planes ->
                    val plane = planes.single()
                    val bytes = (plane.memory as ByteBuffer).duplicate()
                    IntArray(WIDTH * HEIGHT * 3) { index ->
                        val pixel = index / 3
                        bytes.get(pixel / WIDTH * plane.rowBytes + pixel % WIDTH * 4 + index % 3).toInt() and 0xff
                    }
                }
            }
        }
        // Without metadata the converter takes 1,000 nits, as the stream's would give here.
        val toneMap = SdrToneMap(1000.0, ColorPrimaries.BT2020)
        val to = if (target.primaries == ColorPrimaries.DISPLAY_P3) AVCOL_PRI_SMPTE432 else AVCOL_PRI_BT709
        val lattice = nativeGamutMap(AVCOL_PRI_BT2020, to, AVCOL_TRC_IEC61966_2_1)
        var worst = 0
        var worstAt = ""
        var sum = 0L
        for (pixel in 0 until WIDTH * HEIGHT) {
            val code = codes[pixel].toList().map { it / 1023.0 }.toDoubleArray()
            val drawn = shaderPort(code, transfer == ColorTransfer.HLG, toneMap, lattice)
            for (channel in 0 until 3) {
                val error = abs((drawn[channel] * 255).roundToInt() - converted[pixel * 3 + channel])
                sum += error
                if (error > worst) {
                    worst = error
                    worstAt = "codes ${codes[pixel].toList()} channel $channel: shader ${drawn[channel] * 255}, " +
                        "converter ${converted[pixel * 3 + channel]}"
                }
            }
        }
        println("SdrToneMapJvmTest: $transfer into ${target.primaries} worst $worst 8-bit codes at $worstAt, mean %.3f".format(sum / (WIDTH * HEIGHT * 3.0)))
        assertTrue(worst <= TOLERANCE, "$transfer into ${target.primaries}: $worst codes apart at $worstAt")
        val mean = sum / (WIDTH * HEIGHT * 3.0)
        assertTrue(mean <= MEAN_TOLERANCE, "$transfer into ${target.primaries}: $mean codes apart on average")
    }

    /** Greys, each channel alone and in pairs, ramped, then colours at random: 10-bit codes per pixel. */
    private fun testCodes(): List<Triple<Int, Int, Int>> {
        val random = Random(2390)
        return List(WIDTH * HEIGHT) { pixel ->
            val step = pixel % 1024
            when (pixel / 1024) {
                0 -> Triple(step, step, step)
                1 -> Triple(step, 0, 0)
                2 -> Triple(0, step, 0)
                3 -> Triple(0, 0, step)
                4 -> Triple(step, step, 0)
                5 -> Triple(0, step, step)
                6 -> Triple(step, 0, step)
                else -> Triple(random.nextInt(1024), random.nextInt(1024), random.nextInt(1024))
            }
        }
    }

    /** TONE_MAPPED_IMAGE_SHADER's main for one sample's [code], line for line. */
    private fun shaderPort(code: DoubleArray, hlg: Boolean, toneMap: SdrToneMap, lattice: FloatArray): DoubleArray {
        val light = if (hlg) {
            val scene = DoubleArray(3) { val c = code[it]; if (c < 0.5) c * c / 3.0 else (exp((c - 0.55991073) / 0.17883277) + 0.28466892) / 12.0 }
            val gain = (0.2627 * scene[0] + 0.6780 * scene[1] + 0.0593 * scene[2]).pow(0.2) * (1000.0 / 203.0)
            DoubleArray(3) { scene[it] * gain }
        } else {
            DoubleArray(3) { SdrToneMap.pqNits(code[it]) / 203.0 }
        }
        val brightest = max(light[0], max(light[1], light[2]))
        val scale = if (brightest > 0.0) toneMap.eetf(brightest * 203.0 / toneMap.peakNits) / brightest else 0.0
        val srgb = DoubleArray(3) {
            val mapped = (light[it] * scale).coerceIn(0.0, 1.0)
            if (mapped < 0.0031308) mapped * 12.92 else 1.055 * mapped.pow(1.0 / 2.4) - 0.055
        }
        return mapGamut(srgb, lattice)
    }

    /** The shader's mapGamut: tetrahedral, from four points of the lattice. */
    private fun mapGamut(code: DoubleArray, lattice: FloatArray): DoubleArray {
        val last = GAMUT_MAP_POINTS - 1.0
        val position = DoubleArray(3) { code[it] * last }
        val cell = IntArray(3) { min(floor(position[it]), last - 1).toInt() }
        val f = DoubleArray(3) { position[it] - cell[it] }
        val high = max(f[0], max(f[1], f[2]))
        val low = min(f[0], min(f[1], f[2]))
        val middle = f[0] + f[1] + f[2] - high - low
        val first = if (f[0] >= f[1] && f[0] >= f[2]) 0 else if (f[1] >= f[2]) 1 else 2
        val lastAxis = if (f[0] < f[1] && f[0] < f[2]) 0 else if (f[1] < f[2]) 1 else 2
        fun point(red: Int, green: Int, blue: Int, channel: Int): Double =
            lattice[((blue * GAMUT_MAP_POINTS + green) * GAMUT_MAP_POINTS + red) * 3 + channel].toDouble()
        fun corner(step: IntArray, channel: Int) = point(cell[0] + step[0], cell[1] + step[1], cell[2] + step[2], channel)
        val toFirst = IntArray(3) { if (it == first) 1 else 0 }
        val toThird = IntArray(3) { if (it == lastAxis) 0 else 1 }
        return DoubleArray(3) { channel ->
            (1 - high) * corner(IntArray(3), channel) + (high - middle) * corner(toFirst, channel) +
                (middle - low) * corner(toThird, channel) + low * corner(IntArray(3) { 1 }, channel)
        }
    }

    private companion object {
        const val WIDTH = 256
        const val HEIGHT = 64

        /**
         * Of an 8-bit code: both round, swscale rounds the converter's 16-bit intermediate into 8 bits, and the
         * converter reads its tone map and curves from tables where the shader computes them. Measured: 2 at worst,
         * in bright saturated greens and yellows, a third of a code on average.
         */
        const val TOLERANCE = 2

        /** The mean error over every channel, in 8-bit codes: no drift across the picture. */
        const val MEAN_TOLERANCE = 0.5

        const val AVCOL_PRI_BT709 = 1
        const val AVCOL_PRI_BT2020 = 9
        const val AVCOL_PRI_SMPTE432 = 12
        const val AVCOL_TRC_IEC61966_2_1 = 13
    }
}
