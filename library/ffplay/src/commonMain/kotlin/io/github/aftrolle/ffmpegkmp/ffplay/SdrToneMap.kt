// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import kotlin.math.pow

/**
 * How a PQ or HLG frame in GPU memory is drawn on an SDR canvas, as the CPU converter
 * (`ffplaykmp_core.c`) tone maps the same frame to sRGB: decoded to light in units of SDR white,
 * 203 nits, in the source's own [primaries]; scaled on its brightest component through BT.2390's
 * EETF in PQ, which keeps light below the knee and rolls highlights off to SDR white at the clip's
 * [peakNits]; clipped to 0..1 and sRGB encoded; then, from other primaries than BT.709's, taken
 * through the converter's gamut map, swscale's perceptual mapping sampled on a lattice. A canvas
 * wider than sRGB, as a wide-gamut or HDR window draws on, takes the map into Display P3 instead, as
 * the converter maps the frame into Display P3 frames.
 */
internal class SdrToneMap(val peakNits: Double, val primaries: ColorPrimaries) {
    /** The clip's peak as a PQ signal: what the EETF's signals are fractions of. */
    val sourceMax: Double = pqSignal(peakNits)

    /** SDR white as a fraction of [sourceMax]: the EETF's target peak. */
    val target: Double = pqSignal(REFERENCE_WHITE_NITS) / sourceMax

    /** Where the EETF starts rolling off, as a fraction of [sourceMax]. */
    val knee: Double = 1.5 * target - 0.5

    /**
     * The light SDR shows, in units of its white, of light [x], a fraction of the clip's peak: the
     * converter's tone map table, which it reads at the brightest component times 203 / peak. Light
     * beyond the peak shows as the peak does.
     */
    fun eetf(x: Double): Double {
        val e1 = pqSignal(x.coerceIn(0.0, 1.0) * peakNits) / sourceMax
        var e2 = e1
        if (target < 1.0 && e1 > knee) {
            val t = (e1 - knee) / (1.0 - knee)
            e2 = (2 * t * t * t - 3 * t * t + 1) * knee + (t * t * t - 2 * t * t + t) * (1.0 - knee) +
                (-2 * t * t * t + 3 * t * t) * target
        }
        return pqNits(e2 * sourceMax) / REFERENCE_WHITE_NITS
    }

    override fun equals(other: Any?): Boolean = other is SdrToneMap && other.peakNits == peakNits && other.primaries == primaries

    override fun hashCode(): Int = peakNits.hashCode() * 31 + primaries.hashCode()

    override fun toString(): String = "SdrToneMap(peak $peakNits nits, $primaries)"

    companion object {
        /** SDR white, BT.2408's 203 nits: what HDR video's light is measured in. */
        const val REFERENCE_WHITE_NITS = 203.0

        /** For [video]'s PQ or HLG frames; null for SDR. */
        fun of(video: VideoInfo): SdrToneMap? =
            if (video.color.isHdr) SdrToneMap(clipPeak(video), video.color.primaries) else null

        /**
         * The brightest light a clip says it holds, as the converter takes it (`ffmpegkmp_clip_peak`),
         * here from the stream's metadata rather than the frame's: the lower of MaxCLL and its
         * mastering display's peak, 1,000 nits when it gives neither, and no more than HLG's
         * 1,000-nit reference display shows; held within 203 to 10,000 nits.
         */
        fun clipPeak(video: VideoInfo): Double {
            val metadata = video.hdrMetadata
            var peak = metadata?.masteringDisplay?.maxLuminance ?: 0.0
            val light = metadata?.contentLight?.maxContentLightLevel
            if (light != null && light > 0 && (peak <= 0.0 || light < peak)) peak = light.toDouble()
            if (peak <= 0.0 || video.color.transfer == ColorTransfer.HLG) {
                peak = if (peak > 0.0 && peak < HLG_DISPLAY_NITS) peak else HLG_DISPLAY_NITS
            }
            return peak.coerceIn(REFERENCE_WHITE_NITS, PQ_PEAK_NITS)
        }

        /** PQ's inverse EOTF: the signal of [nits]. */
        fun pqSignal(nits: Double): Double {
            val power = (nits / PQ_PEAK_NITS).coerceIn(0.0, 1.0).pow(PQ_M1)
            return ((PQ_C1 + PQ_C2 * power) / (1.0 + PQ_C3 * power)).pow(PQ_M2)
        }

        /** PQ's EOTF: the nits of [signal]. */
        fun pqNits(signal: Double): Double {
            val root = signal.coerceIn(0.0, 1.0).pow(1.0 / PQ_M2)
            val denominator = PQ_C2 - PQ_C3 * root
            if (denominator <= 0.0) return PQ_PEAK_NITS
            return (maxOf(root - PQ_C1, 0.0) / denominator).pow(1.0 / PQ_M1) * PQ_PEAK_NITS
        }

        private const val HLG_DISPLAY_NITS = 1000.0
        private const val PQ_PEAK_NITS = 10000.0
        private const val PQ_M1 = 2610.0 / 16384.0
        private const val PQ_M2 = 2523.0 / 32.0
        private const val PQ_C1 = 3424.0 / 4096.0
        private const val PQ_C2 = 2413.0 / 128.0
        private const val PQ_C3 = 2392.0 / 128.0
    }
}
