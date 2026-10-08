// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

/**
 * Aligns swscale's slice threading to the source's chroma rows for an unscaled pass. FFmpeg 9 aligns slices to the
 * destination only, so NV12 or YUV420P to RGBA on eight threads cuts 1080 rows into odd slices of 135, and the
 * aarch64 converter, which reads chroma in row pairs, writes one row past the last slice: a SIGSEGV on a phone.
 */
internal fun alignUnscaledSlicesToSourceChroma(source: String): String {
    if (SLICE_ALIGN_OVERLAY_MARK in source) return source
    val marker = "    int align = c->dst_slice_align;\n    SwsPass *pass = NULL;\n"
    require(marker in source) { "FFmpeg swscale graph source changed; overlay marker was not found: $marker" }
    return source.replace(
        marker,
        "    int align = c->dst_slice_align;\n    SwsPass *pass = NULL;\n" +
            "    /* $SLICE_ALIGN_OVERLAY_MARK: an unscaled converter reads the source's chroma in row pairs, so a\n" +
            "     * slice must end on the source's chroma row grid, or the aarch64 path writes one row past an odd slice. */\n" +
            "    if (unscaled) {\n" +
            "        const AVPixFmtDescriptor *src_desc = av_pix_fmt_desc_get(sws->src_format);\n" +
            "        if (src_desc)\n" +
            "            align = FFMAX(align, 1 << src_desc->log2_chroma_h);\n" +
            "    }\n",
    )
}

private const val SLICE_ALIGN_OVERLAY_MARK = "Modified by FFmpegKMP contributors in 2026"
