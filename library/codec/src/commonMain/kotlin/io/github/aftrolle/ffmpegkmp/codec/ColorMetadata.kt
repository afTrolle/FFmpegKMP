// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_TRC_ARIB_STD_B67
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_TRC_SMPTE2084
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerMasteringDisplayMetadata
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerVideoInfo
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi

/** The stream description the bindings report, with its colour typed. */
@InternalFFmpegKmpApi
public fun NativePlayerVideoInfo.toPublicVideoInfo(): VideoInfo = VideoInfo(
    width = width,
    height = height,
    sampleAspectRatio = if (sampleAspectRatioNumerator > 0 && sampleAspectRatioDenominator > 0) {
        sampleAspectRatioNumerator.toDouble() / sampleAspectRatioDenominator
    } else {
        1.0
    },
    rotationDegrees = rotationDegrees,
    pixelFormat = pixelFormatName ?: pixelFormat.takeIf { it >= 0 }?.let { "ffmpeg:$it" },
    bitDepth = bitDepth.takeIf { it > 0 },
    color = color(),
    hdrMetadata = hdrMetadataOrNull(
        masteringDisplay?.toMasteringDisplay(),
        contentLightOrNull(maxContentLightLevel, maxFrameAverageLightLevel),
        dolbyVision,
        hdr10Plus,
    ),
)

/**
 * The stream's colour as the converter reads it, so a frame decoded as it is and its [VideoInfo]
 * agree: values outside [FrameColor]'s model take the closest one, and unspecified ones the
 * defaults swscale and players use.
 */
private fun NativePlayerVideoInfo.color(): FrameColor {
    val rgb = colorSpace == AVCOL_SPC_RGB
    return FrameColor(
        primaries = when (colorPrimaries) {
            AVCOL_PRI_BT2020 -> ColorPrimaries.BT2020
            AVCOL_PRI_SMPTE432 -> ColorPrimaries.DISPLAY_P3
            else -> ColorPrimaries.BT709
        },
        transfer = when (colorTransfer) {
            AVCOL_TRC_IEC61966_2_1 -> ColorTransfer.SRGB
            AVCOL_TRC_LINEAR -> ColorTransfer.LINEAR
            AVCOL_TRC_SMPTE2084 -> ColorTransfer.PQ
            AVCOL_TRC_ARIB_STD_B67 -> ColorTransfer.HLG
            else -> ColorTransfer.BT709
        },
        matrix = when (colorSpace) {
            AVCOL_SPC_RGB -> ColorMatrix.RGB
            AVCOL_SPC_BT2020_NCL, AVCOL_SPC_BT2020_CL -> ColorMatrix.BT2020_NCL
            AVCOL_SPC_BT470BG, AVCOL_SPC_SMPTE170M -> ColorMatrix.BT601
            else -> ColorMatrix.BT709
        },
        range = when {
            colorRange == AVCOL_RANGE_JPEG || pixelFormatName?.startsWith("yuvj") == true -> ColorRange.FULL
            colorRange == AVCOL_RANGE_MPEG -> ColorRange.LIMITED
            rgb -> ColorRange.FULL
            else -> ColorRange.LIMITED
        },
    )
}

/** Null unless the stream gives both the primaries and a luminance range [MasteringDisplay] takes. */
private fun NativePlayerMasteringDisplayMetadata.toMasteringDisplay(): MasteringDisplay? {
    if (!hasPrimaries || !hasLuminance || !(minLuminance >= 0 && maxLuminance > minLuminance)) return null
    return MasteringDisplay(
        red = Chromaticity(redX, redY),
        green = Chromaticity(greenX, greenY),
        blue = Chromaticity(blueX, blueY),
        whitePoint = Chromaticity(whiteX, whiteY),
        minLuminance = minLuminance,
        maxLuminance = maxLuminance,
    )
}

private const val AVCOL_PRI_BT2020 = 9
private const val AVCOL_PRI_SMPTE432 = 12
private const val AVCOL_TRC_LINEAR = 8
private const val AVCOL_TRC_IEC61966_2_1 = 13
private const val AVCOL_SPC_RGB = 0
private const val AVCOL_SPC_BT470BG = 5
private const val AVCOL_SPC_SMPTE170M = 6
private const val AVCOL_SPC_BT2020_NCL = 9
private const val AVCOL_SPC_BT2020_CL = 10
private const val AVCOL_RANGE_MPEG = 1
private const val AVCOL_RANGE_JPEG = 2
