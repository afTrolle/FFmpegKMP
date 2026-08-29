// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerHdrType
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerMasteringDisplayMetadata
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerVideoInfo

internal fun NativePlayerVideoInfo.toPublicVideoInfo(): FFplayVideoInfo = FFplayVideoInfo(
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
        NativePlayerHdrType.SDR -> FFplayHdrType.SDR
        NativePlayerHdrType.HDR10 -> FFplayHdrType.HDR10
        NativePlayerHdrType.HLG -> FFplayHdrType.HLG
        NativePlayerHdrType.HDR10_PLUS -> FFplayHdrType.HDR10_PLUS
        NativePlayerHdrType.DOLBY_VISION -> FFplayHdrType.DOLBY_VISION
        NativePlayerHdrType.UNKNOWN_HDR -> FFplayHdrType.UNKNOWN_HDR
    },
    masteringDisplay = masteringDisplay?.toPublic(),
    contentLight = if (maxContentLightLevel != null || maxFrameAverageLightLevel != null) {
        FFplayContentLightMetadata(maxContentLightLevel, maxFrameAverageLightLevel)
    } else {
        null
    },
)

private fun NativePlayerMasteringDisplayMetadata.toPublic(): FFplayMasteringDisplayMetadata {
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
    return FFplayMasteringDisplayMetadata(values)
}

private fun Double.metadataString(): String =
    if (isFinite() && this % 1.0 == 0.0) "${toLong()}.0" else toString()

internal fun colorPrimariesName(value: Int): String? = when (value) {
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

internal fun colorTransferName(value: Int): String? = when (value) {
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

internal fun colorSpaceName(value: Int): String? = when (value) {
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

internal fun colorRangeName(value: Int): String? = when (value) {
    0 -> null
    1 -> "Limited"
    2 -> "Full"
    else -> "FFmpeg:$value"
}

internal fun chromaLocationName(value: Int): String? = when (value) {
    0 -> null
    1 -> "Left"
    2 -> "Center"
    3 -> "Top-left"
    4 -> "Top"
    5 -> "Bottom-left"
    6 -> "Bottom"
    else -> "FFmpeg:$value"
}

internal data class FFplayColorDecision(
    val sourceColorSpace: String?,
    val outputColorSpace: String?,
    val hdrResult: FFplayHdrResult,
)

internal fun decideColorOutput(
    video: FFplayVideoInfo?,
    capabilities: FFplayOutputCapabilities,
    policy: FFplayHdrPolicy,
): FFplayColorDecision {
    if (video == null) return FFplayColorDecision(null, null, FFplayHdrResult.NOT_HDR)
    val sourceColorSpace = video.colorPrimaries ?: video.colorMatrix
    if (video.hdrType == FFplayHdrType.SDR) {
        val output = sourceColorSpace?.takeIf(capabilities.colorSpaces::contains) ?: "sRGB"
        return FFplayColorDecision(sourceColorSpace, output, FFplayHdrResult.NOT_HDR)
    }
    val transfer = video.colorTransfer
    val preservesTransfer = transfer != null && transfer in capabilities.hdrTransfers
    val preservesColorSpace = sourceColorSpace != null && sourceColorSpace in capabilities.colorSpaces
    return when {
        policy == FFplayHdrPolicy.PRESERVE_OR_TONE_MAP && preservesTransfer && preservesColorSpace ->
            FFplayColorDecision(sourceColorSpace, sourceColorSpace, FFplayHdrResult.PRESERVED)
        capabilities.toneMapHdrToSdr && transfer in setOf("PQ", "HLG") ->
            FFplayColorDecision(sourceColorSpace, "sRGB", FFplayHdrResult.TONE_MAPPED)
        else -> FFplayColorDecision(sourceColorSpace, "sRGB", FFplayHdrResult.UNSUPPORTED)
    }
}
