// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

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
