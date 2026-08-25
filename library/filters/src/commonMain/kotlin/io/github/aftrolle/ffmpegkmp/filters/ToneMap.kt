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
}
