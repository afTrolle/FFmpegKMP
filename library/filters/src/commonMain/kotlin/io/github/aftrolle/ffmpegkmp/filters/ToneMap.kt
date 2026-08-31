// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.filters

public object ToneMap {
    /**
     * Converts HDR frames (PQ or HLG, BT.2020) to SDR BT.709 using swscale's color management
     * (FFmpeg 9+). Perceptual intent tone-maps highlights instead of clipping them. The frame
     * must still carry its real color metadata when this filter runs — strip or override
     * metadata only afterwards. Bit depth is unchanged; append a `format=` filter to pick one.
     */
    public const val ToSdrBt709: String =
        "scale=out_color_matrix=bt709:out_primaries=bt709:out_transfer=bt709:out_range=tv:intent=perceptual"

    /**
     * Converts a color-managed source to HDR10 while preserving absolute luminance. PQ sources
     * remain HDR; HLG is converted to PQ. The input must carry accurate color metadata.
     */
    public const val ToHdr10Bt2020: String =
        "scale=out_color_matrix=bt2020:out_primaries=bt2020:out_transfer=smpte2084:" +
            "out_range=tv:intent=absolute_colorimetric"

    /** Maps BT.709 SDR reference white to 203 nits in an HDR10 signal. */
    public const val SdrBt709ToHdr10: String =
        "scale=in_color_matrix=bt709:in_primaries=bt709:in_transfer=bt709:" +
            "out_color_matrix=bt2020:out_primaries=bt2020:out_transfer=smpte2084:" +
            "out_range=tv:intent=absolute_colorimetric"

    /** Maps an sRGB image or rendered layer into HDR10 at the 203-nit SDR reference white. */
    public const val SrgbToHdr10: String =
        "scale=in_color_matrix=bt709:in_primaries=bt709:in_transfer=iec61966-2-1:" +
            "out_color_matrix=bt2020:out_primaries=bt2020:out_transfer=smpte2084:" +
            "out_range=tv:intent=absolute_colorimetric"

    /**
     * Final HDR10 format and frame metadata expected by a Main10 encoder. On Android's
     * `hevc_mediacodec`, the caller must still pass `-profile:v main10` explicitly: the
     * HDR10 encoder profile is selected from the codec profile, not inferred from pixel
     * format or color metadata, so omitting it silently produces a non-HDR Main10 stream.
     */
    public const val Hdr10P010Output: String =
        "format=p010le,setparams=colorspace=bt2020nc:color_primaries=bt2020:" +
            "color_trc=smpte2084:range=tv"
}
