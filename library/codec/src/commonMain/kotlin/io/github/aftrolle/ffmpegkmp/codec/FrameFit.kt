// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import kotlin.math.roundToInt

/** How [VideoDecoder.extractFrame] fits a picture to the size it is asked for. */
public enum class FrameScale {
    /** The whole picture, its aspect kept, as large as fits inside the size. */
    FIT,

    /** The picture covers the size, its aspect kept, and the part outside it is cut off evenly on both sides. */
    CROP,
}

/** A rectangle of a frame's pixels. */
internal data class FrameRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * How a frame is made upright at a size: decoded and scaled to [decodeSize], in the source's own
 * orientation, turned upright (by its display matrix, or clockwise by [clockwiseQuarterTurns] when
 * the frame carries none), then [crop] cut from the upright picture (null for all of it), which
 * gives [size].
 */
internal data class FrameFit(
    val decodeSize: FrameSize,
    val crop: FrameRect?,
    val clockwiseQuarterTurns: Int,
    val size: FrameSize,
)

/**
 * The clockwise quarter turns, 0 to 3, that show a frame reported at [rotationDegrees] upright.
 * FFmpeg reports the rotation anticlockwise (`av_display_rotation_get`), as [VideoInfo] does, and
 * its tools turn the other way to show it; an angle between quarter turns goes to the nearest one.
 * A mirrored display matrix reports the rotation of its mirror image, which turns it sideways or
 * not just as FFmpeg's autorotate does, so this always tells whether the picture is sideways.
 */
internal fun uprightQuarterTurns(rotationDegrees: Double): Int {
    if (!rotationDegrees.isFinite()) return 0
    return (-rotationDegrees / 90.0).roundToInt().mod(4)
}

/**
 * The [FrameFit] for a [codedWidth] x [codedHeight] source of [sampleAspectRatio] and
 * [rotationDegrees], fitted to [box] by [scale]; [box] null is the upright source at its display
 * size.
 *
 * All sizes are whole pixels, from the upright display size W x H: the coded width times the
 * sample aspect ratio, rounded (at least 1), by the coded height, swapped for an odd number of
 * quarter turns. Every division rounds to the nearest pixel, a half up, and no side is ever below
 * 1 or above the box's or the picture's.
 * - [FrameScale.FIT]: W x H when it fits in the box. Otherwise the side the box limits takes the
 *   box's, and the other one keeps the aspect: a box of BW x BH gives BW x round(H * BW / W)
 *   when W / H >= BW / BH, else round(W * BH / H) x BH.
 * - [FrameScale.CROP]: when the picture is larger than the box in both directions, it is scaled
 *   so its limiting side is the box's (BH, and round(W * BH / H) but at least BW, when W / H >=
 *   BW / BH; the other way round otherwise), and the box's size is cut from its centre. When it
 *   is not, nothing is scaled, and the largest rectangle of the box's aspect (round(H * BW / BH) x
 *   H, or W x round(W * BH / BW)) is cut from its centre.
 * A centred cut leaves floor(spare / 2) pixels before it and the rest after it, upright. A sample
 * aspect ratio outside 1/16 to 16 is taken as that bound, and an unknown one as 1.
 */
internal fun frameFit(
    codedWidth: Int,
    codedHeight: Int,
    sampleAspectRatio: Double,
    rotationDegrees: Double,
    box: FrameSize?,
    scale: FrameScale,
): FrameFit {
    require(codedWidth > 0 && codedHeight > 0) { "The source size must be positive: ${codedWidth}x$codedHeight" }
    val turns = uprightQuarterTurns(rotationDegrees)
    val aspect = sampleAspectRatio.takeIf { it > 0.0 && it.isFinite() }?.coerceIn(1.0 / MAX_ASPECT, MAX_ASPECT) ?: 1.0
    val displayWidth = (codedWidth * aspect).roundToInt().coerceAtLeast(1)
    val sideways = turns % 2 == 1
    val width = if (sideways) codedHeight else displayWidth
    val height = if (sideways) displayWidth else codedHeight

    // The upright picture scaled (to scaledWidth x scaledHeight), and the upright rectangle cut from it.
    var scaledWidth = width
    var scaledHeight = height
    var cut: FrameRect? = null
    if (box != null) {
        // Whether the picture is at least as wide as the box, for its height.
        val wider = width.toLong() * box.height >= height.toLong() * box.width
        when (scale) {
            FrameScale.FIT -> if (width > box.width || height > box.height) {
                if (wider) {
                    scaledWidth = box.width
                    scaledHeight = rounded(height, box.width, width).coerceIn(1, box.height)
                } else {
                    scaledWidth = rounded(width, box.height, height).coerceIn(1, box.width)
                    scaledHeight = box.height
                }
            }

            FrameScale.CROP -> {
                val cutWidth: Int
                val cutHeight: Int
                if (width > box.width && height > box.height) {
                    if (wider) {
                        scaledWidth = rounded(width, box.height, height).coerceIn(box.width, width)
                        scaledHeight = box.height
                    } else {
                        scaledWidth = box.width
                        scaledHeight = rounded(height, box.width, width).coerceIn(box.height, height)
                    }
                    cutWidth = box.width
                    cutHeight = box.height
                } else if (wider) {
                    cutWidth = rounded(height, box.width, box.height).coerceIn(1, width)
                    cutHeight = height
                } else {
                    cutWidth = width
                    cutHeight = rounded(width, box.height, box.width).coerceIn(1, height)
                }
                if (cutWidth != scaledWidth || cutHeight != scaledHeight) {
                    cut = FrameRect((scaledWidth - cutWidth) / 2, (scaledHeight - cutHeight) / 2, cutWidth, cutHeight)
                }
            }
        }
    }

    // The decoder scales in the source's own orientation; the cut is in upright pixels, after the turn.
    return FrameFit(
        decodeSize = if (sideways) FrameSize(scaledHeight, scaledWidth) else FrameSize(scaledWidth, scaledHeight),
        crop = cut,
        clockwiseQuarterTurns = turns,
        size = cut?.let { FrameSize(it.width, it.height) } ?: FrameSize(scaledWidth, scaledHeight),
    )
}

/** The widest a pixel may be, and the inverse the narrowest: far beyond any real anamorphic format. */
private const val MAX_ASPECT = 16.0

/** [value] * [numerator] / [denominator] to the nearest whole number, a half up. */
private fun rounded(value: Int, numerator: Int, denominator: Int): Int =
    ((2L * value * numerator + denominator) / (2L * denominator)).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
