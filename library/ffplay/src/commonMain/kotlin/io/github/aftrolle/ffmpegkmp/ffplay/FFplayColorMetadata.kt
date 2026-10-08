// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo

internal data class FFplayColorDecision(
    val sourceColorSpace: ColorPrimaries?,
    val outputColorSpace: ColorPrimaries?,
    val hdrResult: FFplayHdrResult,
)

internal fun decideColorOutput(
    video: VideoInfo?,
    capabilities: FFplayOutputCapabilities,
    policy: FFplayHdrPolicy,
): FFplayColorDecision {
    if (video == null) return FFplayColorDecision(null, null, FFplayHdrResult.NOT_HDR)
    val sourceColorSpace = video.color.primaries
    if (!video.color.isHdr) {
        val output = sourceColorSpace.takeIf(capabilities.colorSpaces::contains) ?: ColorPrimaries.BT709
        return FFplayColorDecision(sourceColorSpace, output, FFplayHdrResult.NOT_HDR)
    }
    val preservesTransfer = video.color.transfer in capabilities.hdrTransfers
    val preservesColorSpace = sourceColorSpace in capabilities.colorSpaces
    return when {
        policy == FFplayHdrPolicy.PRESERVE_OR_TONE_MAP && preservesTransfer && preservesColorSpace ->
            FFplayColorDecision(sourceColorSpace, sourceColorSpace, FFplayHdrResult.PRESERVED)
        capabilities.toneMapHdrToSdr ->
            FFplayColorDecision(sourceColorSpace, ColorPrimaries.BT709, FFplayHdrResult.TONE_MAPPED)
        else -> FFplayColorDecision(sourceColorSpace, ColorPrimaries.BT709, FFplayHdrResult.UNSUPPORTED)
    }
}
