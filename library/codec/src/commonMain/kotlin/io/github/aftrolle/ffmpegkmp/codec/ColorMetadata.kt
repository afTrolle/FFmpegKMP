// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerHdrType
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerMasteringDisplayMetadata
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerVideoInfo
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi

/** The stream description the bindings report, with its colour values named. */
@InternalFFmpegKmpApi
public fun NativePlayerVideoInfo.toPublicVideoInfo(): VideoInfo = VideoInfo(
    width = width,
    height = height,
    sampleAspectRatio = sampleAspectRatioNumerator
        .takeIf { it > 0 && sampleAspectRatioDenominator > 0 }
        ?.let { "$it:$sampleAspectRatioDenominator" },
    rotationDegrees = rotationDegrees,
    pixelFormat = pixelFormatName ?: pixelFormat.takeIf { it >= 0 }?.let { "ffmpeg:$it" },
    bitDepth = bitDepth.takeIf { it > 0 },
    colorPrimaries = colorPrimariesName(colorPrimaries),
    colorTransfer = colorTransferName(colorTransfer),
    colorMatrix = colorSpaceName(colorSpace),
    colorRange = colorRangeName(colorRange),
    chromaLocation = chromaLocationName(chromaLocation),
    hdrType = when (hdrType) {
        NativePlayerHdrType.SDR -> HdrType.SDR
        NativePlayerHdrType.HDR10 -> HdrType.HDR10
        NativePlayerHdrType.HLG -> HdrType.HLG
        NativePlayerHdrType.HDR10_PLUS -> HdrType.HDR10_PLUS
        NativePlayerHdrType.DOLBY_VISION -> HdrType.DOLBY_VISION
        NativePlayerHdrType.UNKNOWN_HDR -> HdrType.UNKNOWN_HDR
    },
    masteringDisplay = masteringDisplay?.toPublic(),
    contentLight = contentLightOrNull(maxContentLightLevel, maxFrameAverageLightLevel),
    hdrMetadata = hdrMetadataOrNull(
        masteringDisplay?.toMasteringDisplay(),
        contentLightOrNull(maxContentLightLevel, maxFrameAverageLightLevel),
    ),
)

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

private fun NativePlayerMasteringDisplayMetadata.toPublic(): MasteringDisplayMetadata {
    val values = buildMap {
        if (hasPrimaries) {
            put("redX", redX.metadataString())
            put("redY", redY.metadataString())
            put("greenX", greenX.metadataString())
            put("greenY", greenY.metadataString())
            put("blueX", blueX.metadataString())
            put("blueY", blueY.metadataString())
            put("whiteX", whiteX.metadataString())
            put("whiteY", whiteY.metadataString())
        }
        if (hasLuminance) {
            put("minLuminance", minLuminance.metadataString())
            put("maxLuminance", maxLuminance.metadataString())
        }
    }
    return MasteringDisplayMetadata(values)
}

private fun Double.metadataString(): String =
    if (isFinite() && this % 1.0 == 0.0) "${toLong()}.0" else toString()

/** The display name of an FFmpeg `AVColorPrimaries` value; null where it is unspecified. */
@InternalFFmpegKmpApi
public fun colorPrimariesName(value: Int): String? = when (value) {
    1 -> "BT.709"
    2 -> null
    4 -> "BT.470M"
    5 -> "BT.470BG"
    6 -> "SMPTE 170M"
    7 -> "SMPTE 240M"
    8 -> "Film"
    9 -> "BT.2020"
    10 -> "SMPTE ST 428"
    11 -> "DCI-P3"
    12 -> "Display P3"
    22 -> "EBU 3213"
    else -> "FFmpeg:$value"
}

/** The display name of an FFmpeg `AVColorTransferCharacteristic` value; null where it is unspecified. */
@InternalFFmpegKmpApi
public fun colorTransferName(value: Int): String? = when (value) {
    1 -> "BT.709"
    2 -> null
    4 -> "Gamma 2.2"
    5 -> "Gamma 2.8"
    6 -> "SMPTE 170M"
    7 -> "SMPTE 240M"
    8 -> "Linear"
    9 -> "Log"
    10 -> "Log sqrt"
    11 -> "IEC 61966-2-4"
    12 -> "BT.1361 ECG"
    13 -> "sRGB"
    14 -> "BT.2020 10-bit"
    15 -> "BT.2020 12-bit"
    16 -> "PQ"
    17 -> "SMPTE ST 428"
    18 -> "HLG"
    else -> "FFmpeg:$value"
}

/** The display name of an FFmpeg `AVColorSpace` (the YUV matrix) value; null where it is unspecified. */
@InternalFFmpegKmpApi
public fun colorSpaceName(value: Int): String? = when (value) {
    0 -> "RGB"
    1 -> "BT.709"
    2 -> null
    4 -> "FCC"
    5 -> "BT.470BG"
    6 -> "SMPTE 170M"
    7 -> "SMPTE 240M"
    8 -> "YCgCo"
    9 -> "BT.2020 NCL"
    10 -> "BT.2020 CL"
    11 -> "SMPTE ST 2085"
    12 -> "Chroma-derived NCL"
    13 -> "Chroma-derived CL"
    14 -> "ICtCp"
    15 -> "IPT-C2"
    16 -> "YCgCo-Re"
    17 -> "YCgCo-Ro"
    else -> "FFmpeg:$value"
}

/** The display name of an FFmpeg `AVColorRange` value; null where it is unspecified. */
@InternalFFmpegKmpApi
public fun colorRangeName(value: Int): String? = when (value) {
    0 -> null
    1 -> "Limited"
    2 -> "Full"
    else -> "FFmpeg:$value"
}

/** The display name of an FFmpeg `AVChromaLocation` value; null where it is unspecified. */
@InternalFFmpegKmpApi
public fun chromaLocationName(value: Int): String? = when (value) {
    0 -> null
    1 -> "Left"
    2 -> "Center"
    3 -> "Top-left"
    4 -> "Top"
    5 -> "Bottom-left"
    6 -> "Bottom"
    else -> "FFmpeg:$value"
}
