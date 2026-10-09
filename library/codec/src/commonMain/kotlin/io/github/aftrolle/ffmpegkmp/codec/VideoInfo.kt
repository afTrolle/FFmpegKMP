// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

/** CTA-861.3 content light levels, in nits. */
public data class ContentLightMetadata(
    val maxContentLightLevel: Int? = null,
    val maxFrameAverageLightLevel: Int? = null,
)

/**
 * A video stream's coded size, aspect ratio, rotation, pixel format and colour. The colour is the
 * same [FrameColor] a frame's [FrameFormat] carries: values the stream leaves unspecified, or that
 * the model does not name, read as the converter reads them, so decoding a frame as it is gives
 * the colour reported here. Whether the stream is HDR, and which kind, follows from
 * [FrameColor.transfer]; [hdrMetadata] adds the static metadata and signalling it carries.
 */
public data class VideoInfo(
    val width: Int,
    val height: Int,
    /** A pixel's width over its height; 1.0 when the stream does not report it. */
    val sampleAspectRatio: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val pixelFormat: String? = null,
    val bitDepth: Int? = null,
    val color: FrameColor = FrameColor.Bt709,
    /**
     * What the stream carries beyond its colour: HDR10's mastering display and content light
     * levels, and the Dolby Vision and HDR10+ signalling. Null when it carries none. Its
     * [HdrMetadata.masteringDisplay] needs both the primaries and the luminance range, as the
     * writer does, and is null when the stream gives only one of them.
     */
    val hdrMetadata: HdrMetadata? = null,
) {
    init {
        require(width > 0 && height > 0) { "Video dimensions must be positive" }
    }
}
