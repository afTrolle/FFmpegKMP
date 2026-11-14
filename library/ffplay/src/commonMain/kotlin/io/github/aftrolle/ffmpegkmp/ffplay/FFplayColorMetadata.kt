// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo

internal data class FFplayColorDecision(
    val sourcePrimaries: ColorPrimaries?,
    val outputPrimaries: ColorPrimaries?,
    val hdrResult: FFplayHdrResult,
)

internal fun decideColorOutput(
    video: VideoInfo?,
    capabilities: FFplayOutputCapabilities,
    policy: FFplayHdrPolicy,
): FFplayColorDecision {
    if (video == null) return FFplayColorDecision(null, null, FFplayHdrResult.NOT_HDR)
    val sourcePrimaries = video.color.primaries
    if (!video.color.isHdr) {
        val output = sourcePrimaries.takeIf(capabilities.colorSpaces::contains) ?: ColorPrimaries.BT709
        return FFplayColorDecision(sourcePrimaries, output, FFplayHdrResult.NOT_HDR)
    }
    val preservesTransfer = video.color.transfer in capabilities.hdrTransfers
    val preservesColorSpace = sourcePrimaries in capabilities.colorSpaces
    return when {
        policy == FFplayHdrPolicy.PRESERVE_OR_TONE_MAP && preservesTransfer && preservesColorSpace ->
            FFplayColorDecision(sourcePrimaries, sourcePrimaries, FFplayHdrResult.PRESERVED)
        capabilities.toneMapHdrToSdr ->
            FFplayColorDecision(sourcePrimaries, ColorPrimaries.BT709, FFplayHdrResult.TONE_MAPPED)
        else -> FFplayColorDecision(sourcePrimaries, ColorPrimaries.BT709, FFplayHdrResult.UNSUPPORTED)
    }
}
