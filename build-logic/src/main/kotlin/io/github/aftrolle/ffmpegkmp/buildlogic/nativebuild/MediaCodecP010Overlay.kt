// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

internal fun addMediaCodecP010Support(source: String): String {
    if ("{ COLOR_FormatYUVP010," in source && "AV_PIX_FMT_P010" in source) return source

    return source
        .replaceRequired(
            "    COLOR_FormatYUV420SemiPlanar                          = 0x15,\n",
            "    COLOR_FormatYUV420SemiPlanar                          = 0x15,\n" +
                "    COLOR_FormatYUVP010                                  = 0x36,\n",
        )
        .replaceRequired(
            "    { COLOR_FormatYUV420SemiPlanar,     AV_PIX_FMT_NV12    },\n",
            "    { COLOR_FormatYUV420SemiPlanar,     AV_PIX_FMT_NV12    },\n" +
                "    { COLOR_FormatYUVP010,             AV_PIX_FMT_P010    },\n",
        )
        .replaceRequired(
            """
            |static const enum AVPixelFormat avc_pix_fmts[] = {
            |    AV_PIX_FMT_MEDIACODEC,
            |    AV_PIX_FMT_YUV420P,
            |    AV_PIX_FMT_NV12,
            |    AV_PIX_FMT_NONE
            |};
            """.trimMargin(),
            """
            |static const enum AVPixelFormat avc_pix_fmts[] = {
            |    AV_PIX_FMT_MEDIACODEC,
            |    AV_PIX_FMT_YUV420P,
            |    AV_PIX_FMT_NV12,
            |    AV_PIX_FMT_NONE
            |};
            |
            |static const enum AVPixelFormat hevc_pix_fmts[] = {
            |    AV_PIX_FMT_MEDIACODEC,
            |    AV_PIX_FMT_YUV420P,
            |    AV_PIX_FMT_NV12,
            |    AV_PIX_FMT_P010,
            |    AV_PIX_FMT_NONE
            |};
            """.trimMargin(),
        )
        .replaceRequired(
            """
            |    } else if (avctx->pix_fmt == AV_PIX_FMT_NV12) {
            |        dst_data[0] = dst;
            |        dst_data[1] = dst + s->width * s->height;
            |
            |        dst_linesize[0] = s->width;
            |        dst_linesize[1] = s->width;
            |    } else {
            """.trimMargin(),
            """
            |    } else if (avctx->pix_fmt == AV_PIX_FMT_NV12) {
            |        dst_data[0] = dst;
            |        dst_data[1] = dst + s->width * s->height;
            |
            |        dst_linesize[0] = s->width;
            |        dst_linesize[1] = s->width;
            |    } else if (avctx->pix_fmt == AV_PIX_FMT_P010) {
            |        dst_data[0] = dst;
            |        dst_data[1] = dst + 2 * s->width * s->height;
            |
            |        dst_linesize[0] = 2 * s->width;
            |        dst_linesize[1] = 2 * s->width;
            |    } else {
            """.trimMargin(),
        )
        .replaceRequired(
            "#define DECLARE_MEDIACODEC_ENCODER(short_name, long_name, codec_id)     \\",
            "#define DECLARE_MEDIACODEC_ENCODER(short_name, long_name, codec_id, pixel_formats) \\",
        )
        .replaceRequired(
            "    CODEC_PIXFMTS_ARRAY(avc_pix_fmts),                                  \\",
            "    CODEC_PIXFMTS_ARRAY(pixel_formats),                                 \\",
        )
        .replaceRequired(
            "DECLARE_MEDIACODEC_ENCODER(h264, \"H.264\", AV_CODEC_ID_H264)",
            "DECLARE_MEDIACODEC_ENCODER(h264, \"H.264\", AV_CODEC_ID_H264, avc_pix_fmts)",
        )
        .replaceRequired(
            "DECLARE_MEDIACODEC_ENCODER(hevc, \"H.265\", AV_CODEC_ID_HEVC)",
            "DECLARE_MEDIACODEC_ENCODER(hevc, \"H.265\", AV_CODEC_ID_HEVC, hevc_pix_fmts)",
        )
        .replaceRequired(
            "DECLARE_MEDIACODEC_ENCODER(vp8, \"VP8\", AV_CODEC_ID_VP8)",
            "DECLARE_MEDIACODEC_ENCODER(vp8, \"VP8\", AV_CODEC_ID_VP8, avc_pix_fmts)",
        )
        .replaceRequired(
            "DECLARE_MEDIACODEC_ENCODER(vp9, \"VP9\", AV_CODEC_ID_VP9)",
            "DECLARE_MEDIACODEC_ENCODER(vp9, \"VP9\", AV_CODEC_ID_VP9, avc_pix_fmts)",
        )
        .replaceRequired(
            "DECLARE_MEDIACODEC_ENCODER(mpeg4, \"MPEG-4\", AV_CODEC_ID_MPEG4)",
            "DECLARE_MEDIACODEC_ENCODER(mpeg4, \"MPEG-4\", AV_CODEC_ID_MPEG4, avc_pix_fmts)",
        )
        .replaceRequired(
            "DECLARE_MEDIACODEC_ENCODER(av1, \"AV1\", AV_CODEC_ID_AV1)",
            "DECLARE_MEDIACODEC_ENCODER(av1, \"AV1\", AV_CODEC_ID_AV1, avc_pix_fmts)",
        )
        .withModificationNotice("P010 input support")
}

internal fun addMediaCodecHdr10ProfileSupport(source: String): String {
    val replacement = """
        |        case AV_PROFILE_HEVC_MAIN_10:
        |            return avctx->color_trc == AVCOL_TRC_SMPTE2084
        |                ? HEVCProfileMain10HDR10
        |                : HEVCProfileMain10;
    """.trimMargin()
    if (replacement in source) return source

    return source.replaceRequired(
        """
        |        case AV_PROFILE_HEVC_MAIN_10:
        |            return HEVCProfileMain10;
        """.trimMargin(),
        replacement,
    ).withModificationNotice("PQ Main10 HDR10 profile selection")
}

internal fun addMediaCodecHdrStaticInfoSupport(source: String): String {
    if ("\"hdr-static-info\"" in source) return source

    return source
        .replaceRequired(
            "#include \"libavutil/imgutils.h\"\n#include \"libavutil/mem.h\"\n",
            "#include \"libavutil/imgutils.h\"\n" +
                "#include \"libavutil/intreadwrite.h\"\n" +
                "#include \"libavutil/mastering_display_metadata.h\"\n" +
                "#include \"libavutil/mem.h\"\n",
        )
        .replaceRequired(
            """
            |    ret = ff_AMediaFormatColorTransfer_from_AVColorTransfer(avctx->color_trc);
            |    if (ret != COLOR_TRANSFER_UNSPECIFIED)
            |        ff_AMediaFormat_setInt32(format, "color-transfer", ret);
            """.trimMargin(),
            """
            |    ret = ff_AMediaFormatColorTransfer_from_AVColorTransfer(avctx->color_trc);
            |    if (ret != COLOR_TRANSFER_UNSPECIFIED)
            |        ff_AMediaFormat_setInt32(format, "color-transfer", ret);
            |
            |    {
            |        const AVFrameSideData *mdcv_sd = av_frame_side_data_get(avctx->decoded_side_data,
            |                                                                  avctx->nb_decoded_side_data,
            |                                                                  AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
            |        const AVFrameSideData *cll_sd = av_frame_side_data_get(avctx->decoded_side_data,
            |                                                                 avctx->nb_decoded_side_data,
            |                                                                 AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
            |        const AVMasteringDisplayMetadata *mdcv =
            |            mdcv_sd ? (const AVMasteringDisplayMetadata *)mdcv_sd->data : NULL;
            |        const AVContentLightMetadata *cll =
            |            cll_sd ? (const AVContentLightMetadata *)cll_sd->data : NULL;
            |
            |        // CTA-861.3 Static Metadata Descriptor Type 1: byte layout expected by
            |        // Android's MediaFormat KEY_HDR_STATIC_INFO ("hdr-static-info"). Primaries
            |        // are R,G,B order (distinct from the G,B,R order HEVC SEI / ISOBMFF mdcv
            |        // boxes use), chromaticity in 0.00002 units, max luminance in 1 cd/m^2,
            |        // min luminance/MaxCLL/MaxFALL as noted below.
            |        if (mdcv && mdcv->has_primaries && mdcv->has_luminance) {
            |            uint8_t hdr_static_info[25] = { 0 };
            |            AV_WL16(hdr_static_info + 1,  av_rescale_q(1, mdcv->display_primaries[0][0], (AVRational){ 1, 50000 }));
            |            AV_WL16(hdr_static_info + 3,  av_rescale_q(1, mdcv->display_primaries[0][1], (AVRational){ 1, 50000 }));
            |            AV_WL16(hdr_static_info + 5,  av_rescale_q(1, mdcv->display_primaries[1][0], (AVRational){ 1, 50000 }));
            |            AV_WL16(hdr_static_info + 7,  av_rescale_q(1, mdcv->display_primaries[1][1], (AVRational){ 1, 50000 }));
            |            AV_WL16(hdr_static_info + 9,  av_rescale_q(1, mdcv->display_primaries[2][0], (AVRational){ 1, 50000 }));
            |            AV_WL16(hdr_static_info + 11, av_rescale_q(1, mdcv->display_primaries[2][1], (AVRational){ 1, 50000 }));
            |            AV_WL16(hdr_static_info + 13, av_rescale_q(1, mdcv->white_point[0], (AVRational){ 1, 50000 }));
            |            AV_WL16(hdr_static_info + 15, av_rescale_q(1, mdcv->white_point[1], (AVRational){ 1, 50000 }));
            |            AV_WL16(hdr_static_info + 17, av_rescale_q(1, mdcv->max_luminance, (AVRational){ 1, 1 }));
            |            AV_WL16(hdr_static_info + 19, av_rescale_q(1, mdcv->min_luminance, (AVRational){ 1, 10000 }));
            |            if (cll) {
            |                AV_WL16(hdr_static_info + 21, cll->MaxCLL);
            |                AV_WL16(hdr_static_info + 23, cll->MaxFALL);
            |            }
            |            ff_AMediaFormat_setBuffer(format, "hdr-static-info", hdr_static_info, sizeof(hdr_static_info));
            |        }
            |    }
            """.trimMargin(),
        )
        .withModificationNotice("HDR static info (mastering display / content light level) propagation")
}

private fun String.withModificationNotice(change: String): String =
    "/* Modified by FFmpegKMP contributors in 2026: $change. */\n$this"

private fun String.replaceRequired(marker: String, replacement: String): String {
    require(marker in this) { "FFmpeg MediaCodec source changed; P010 overlay marker was not found" }
    return replace(marker, replacement)
}
