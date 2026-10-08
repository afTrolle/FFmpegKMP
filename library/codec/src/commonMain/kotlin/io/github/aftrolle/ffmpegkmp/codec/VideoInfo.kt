// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

/** How a video stream's HDR is signalled; [UNKNOWN_HDR] is an HDR transfer none of the others name. */
public enum class HdrType { SDR, HDR10, HLG, HDR10_PLUS, DOLBY_VISION, UNKNOWN_HDR }

/**
 * SMPTE ST 2086 mastering display metadata, by name: `redX` … `whiteY` for the primaries and white
 * point in CIE 1931 xy, `minLuminance` and `maxLuminance` in nits. Keys the stream does not carry
 * are absent.
 */
public data class MasteringDisplayMetadata(
    val raw: Map<String, String> = emptyMap(),
)

/** CTA-861.3 content light levels, in nits. */
public data class ContentLightMetadata(
    val maxContentLightLevel: Int? = null,
    val maxFrameAverageLightLevel: Int? = null,
)

/**
 * A video stream's coded size, aspect ratio, rotation, pixel format and colour description. The
 * colour fields use display names such as `"BT.2020"`, `"PQ"` or `"Limited"`, are null where the
 * stream leaves them unspecified, and are `"FFmpeg:<n>"` for values without a name here.
 *
 * [masteringDisplay] and [contentLight] are the HDR10 metadata as the stream carries it, and
 * [hdrMetadata] is the same, typed for [VideoEncoderConfig.hdrMetadata].
 */
public data class VideoInfo(
    val width: Int,
    val height: Int,
    /** `"num:den"`, or null when the stream does not report it. */
    val sampleAspectRatio: String? = null,
    val rotationDegrees: Double = 0.0,
    val pixelFormat: String? = null,
    val bitDepth: Int? = null,
    val colorPrimaries: String? = null,
    val colorTransfer: String? = null,
    val colorMatrix: String? = null,
    val colorRange: String? = null,
    val chromaLocation: String? = null,
    val hdrType: HdrType = HdrType.SDR,
    val masteringDisplay: MasteringDisplayMetadata? = null,
    val contentLight: ContentLightMetadata? = null,
    /**
     * The mastering display and content light levels, or null when the stream carries neither. Its
     * [HdrMetadata.masteringDisplay] needs both the primaries and the luminance range, as the writer
     * does, and is null when the stream gives only one of them.
     */
    val hdrMetadata: HdrMetadata? = null,
) {
    init {
        require(width > 0 && height > 0) { "Video dimensions must be positive" }
    }
}
