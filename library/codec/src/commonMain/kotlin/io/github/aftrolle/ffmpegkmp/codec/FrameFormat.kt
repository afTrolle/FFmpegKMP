// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.NativeFrameFormat
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi

/**
 * How a frame's pixels are laid out in memory: the layouts swscale, Skia and the platform encoders
 * have in common.
 */
public enum class PixelLayout {
    /** Packed 8-bit red, green, blue and alpha. */
    RGBA8,

    /** Packed 8-bit blue, green, red and alpha. */
    BGRA8,

    /** Packed 10-bit red, green and blue and 2-bit alpha in 32 bits, red in the low bits. */
    RGBA_1010102,

    /** Packed half-float red, green, blue and alpha. */
    RGBA_F16,

    /** 8-bit 4:2:0: a luma plane, then one of interleaved Cb and Cr. */
    NV12,

    /** 10-bit 4:2:0 as NV12 lays it out, each sample in the high bits of 16. */
    P010,

    /** 8-bit 4:2:0 in three planes. */
    YUV420P,

    /** 10-bit 4:2:0 in three planes, each sample in the low bits of 16. */
    YUV420P10,
    ;

    /** Whether the layout holds RGB rather than YUV. */
    internal val isRgb: Boolean get() = ordinal <= RGBA_F16.ordinal
}

/** The colour primaries a frame's RGB is relative to. */
public enum class ColorPrimaries { BT709, BT2020, DISPLAY_P3 }

/**
 * How a frame's values encode light. [SRGB] and [BT709] are both shown as coded: SDR video is
 * displayed as sRGB, so converting between them changes no values.
 */
public enum class ColorTransfer { SRGB, BT709, LINEAR, PQ, HLG }

/** How YUV derives from RGB; [RGB] for the RGB layouts. [BT601] is standard-definition video's. */
public enum class ColorMatrix { RGB, BT709, BT2020_NCL, BT601 }

/** Whether YUV and RGB codes use their full range or the video (limited) one. */
public enum class ColorRange { LIMITED, FULL }

/** A frame's colour description. */
public data class FrameColor(
    val primaries: ColorPrimaries,
    val transfer: ColorTransfer,
    val matrix: ColorMatrix,
    val range: ColorRange,
) {
    /** Whether the transfer is PQ or HLG, the two HDR ones: it alone decides, whatever metadata a stream adds. */
    public val isHdr: Boolean get() = transfer == ColorTransfer.PQ || transfer == ColorTransfer.HLG

    public companion object {
        /** sRGB, full-range RGB: what an 8-bit canvas holds. */
        public val Srgb: FrameColor = FrameColor(ColorPrimaries.BT709, ColorTransfer.SRGB, ColorMatrix.RGB, ColorRange.FULL)

        /**
         * Linear light, sRGB primaries, values beyond 0..1 allowed; 1.0 is 203 nits (BT.2408),
         * where HDR video puts SDR white. Colours outside sRGB have negative components.
         */
        public val LinearExtendedSrgb: FrameColor =
            FrameColor(ColorPrimaries.BT709, ColorTransfer.LINEAR, ColorMatrix.RGB, ColorRange.FULL)

        /** Display P3 primaries with the sRGB curve, full-range RGB. */
        public val DisplayP3: FrameColor =
            FrameColor(ColorPrimaries.DISPLAY_P3, ColorTransfer.SRGB, ColorMatrix.RGB, ColorRange.FULL)

        /** BT.709 video: limited-range YUV. */
        public val Bt709: FrameColor =
            FrameColor(ColorPrimaries.BT709, ColorTransfer.BT709, ColorMatrix.BT709, ColorRange.LIMITED)

        /** HDR10 video: BT.2020, PQ, limited-range YUV. */
        public val Bt2020Pq: FrameColor =
            FrameColor(ColorPrimaries.BT2020, ColorTransfer.PQ, ColorMatrix.BT2020_NCL, ColorRange.LIMITED)

        /** HLG video: BT.2020, HLG, limited-range YUV. */
        public val Bt2020Hlg: FrameColor =
            FrameColor(ColorPrimaries.BT2020, ColorTransfer.HLG, ColorMatrix.BT2020_NCL, ColorRange.LIMITED)
    }
}

/**
 * A pixel layout plus a colour description: everything a conversion needs to know about one side.
 * RGB layouts take [ColorMatrix.RGB], and YUV ones one of the others.
 */
public data class FrameFormat(val layout: PixelLayout, val color: FrameColor) {
    init {
        require(layout.isRgb == (color.matrix == ColorMatrix.RGB)) {
            if (layout.isRgb) "$layout is RGB and takes the RGB matrix, not ${color.matrix}" else "$layout is YUV and needs a YUV matrix"
        }
    }

    public companion object {
        /** 8-bit sRGB: what Compose draws for SDR. */
        public val Rgba8: FrameFormat = FrameFormat(PixelLayout.RGBA8, FrameColor.Srgb)

        /** Half floats in linear extended sRGB: HDR highlights keep their values above 1.0. */
        public val RgbaF16: FrameFormat = FrameFormat(PixelLayout.RGBA_F16, FrameColor.LinearExtendedSrgb)

        /** 8-bit BT.709 video, as hardware encoders take it. */
        public val Nv12: FrameFormat = FrameFormat(PixelLayout.NV12, FrameColor.Bt709)

        /** 10-bit HDR10 video. */
        public val P010Hdr10: FrameFormat = FrameFormat(PixelLayout.P010, FrameColor.Bt2020Pq)

        /** 10-bit HLG video. */
        public val P010Hlg: FrameFormat = FrameFormat(PixelLayout.P010, FrameColor.Bt2020Hlg)
    }
}

/**
 * A frame's size in pixels as stored, before its rotation and sample aspect ratio apply: what
 * [VideoOutput.Memory] scales frames to.
 */
public data class FrameSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "The size must be positive: ${width}x$height" }
    }
}

/** The format as the bindings take it; the C enums share the ordinals. */
@InternalFFmpegKmpApi
public fun FrameFormat.toNative(): NativeFrameFormat = NativeFrameFormat(
    layout = layout.ordinal,
    primaries = color.primaries.ordinal,
    transfer = color.transfer.ordinal,
    matrix = color.matrix.ordinal,
    range = color.range.ordinal,
)

internal fun NativeFrameFormat.toFrameFormat(): FrameFormat? {
    val layout = PixelLayout.entries.getOrNull(layout) ?: return null
    return FrameFormat(
        layout,
        FrameColor(
            primaries = ColorPrimaries.entries.getOrNull(primaries) ?: return null,
            transfer = ColorTransfer.entries.getOrNull(transfer) ?: return null,
            matrix = ColorMatrix.entries.getOrNull(matrix) ?: return null,
            range = ColorRange.entries.getOrNull(range) ?: return null,
        ),
    )
}
