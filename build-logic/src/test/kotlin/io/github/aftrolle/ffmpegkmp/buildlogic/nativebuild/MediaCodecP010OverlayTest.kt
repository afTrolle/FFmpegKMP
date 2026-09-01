package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MediaCodecP010OverlayTest {
    @Test
    fun addsP010ToPinnedMediaCodecEncoder() {
        val sourceFile = listOf(File("ffmpeg"), File("../ffmpeg"))
            .map { it.resolve("libavcodec/mediacodecenc.c") }
            .first(File::isFile)
        val source = sourceFile.readText()
        val patched = addMediaCodecP010Support(source)

        assertContains(patched, "COLOR_FormatYUVP010                                  = 0x36")
        assertContains(patched, "Modified by FFmpegKMP contributors in 2026")
        assertContains(patched, "{ COLOR_FormatYUVP010,             AV_PIX_FMT_P010    }")
        assertContains(patched, "dst_data[1] = dst + 2 * s->width * s->height")
        assertContains(patched, "dst_linesize[1] = 2 * s->width")
        assertContains(patched, "DECLARE_MEDIACODEC_ENCODER(hevc, \"H.265\", AV_CODEC_ID_HEVC, hevc_pix_fmts)")
        assertContains(patched, "DECLARE_MEDIACODEC_ENCODER(h264, \"H.264\", AV_CODEC_ID_H264, avc_pix_fmts)")
        val standardFormats = patched.substringAfter("static const enum AVPixelFormat avc_pix_fmts[]")
            .substringBefore("};")
        val hevcFormats = patched.substringAfter("static const enum AVPixelFormat hevc_pix_fmts[]")
            .substringBefore("};")
        assertFalse("AV_PIX_FMT_P010" in standardFormats)
        assertContains(hevcFormats, "AV_PIX_FMT_P010")
        assertEquals(patched, addMediaCodecP010Support(patched))
    }

    @Test
    fun selectsAndroidHdr10ProfileForPq() {
        val sourceFile = listOf(File("ffmpeg"), File("../ffmpeg"))
            .map { it.resolve("libavcodec/mediacodec_wrapper.c") }
            .first(File::isFile)
        val patched = addMediaCodecHdr10ProfileSupport(sourceFile.readText())

        assertContains(patched, "avctx->color_trc == AVCOL_TRC_SMPTE2084")
        assertContains(patched, "Modified by FFmpegKMP contributors in 2026")
        assertContains(patched, "? HEVCProfileMain10HDR10")
        assertEquals(patched, addMediaCodecHdr10ProfileSupport(patched))
    }

    @Test
    fun propagatesHdrStaticInfoToMediaCodecFormat() {
        val sourceFile = listOf(File("ffmpeg"), File("../ffmpeg"))
            .map { it.resolve("libavcodec/mediacodecenc.c") }
            .first(File::isFile)
        val source = sourceFile.readText()
        val patched = addMediaCodecHdrStaticInfoSupport(source)

        assertContains(patched, "#include \"libavutil/mastering_display_metadata.h\"")
        assertContains(patched, "#include \"libavutil/intreadwrite.h\"")
        assertContains(patched, "Modified by FFmpegKMP contributors in 2026")
        assertContains(patched, "AV_FRAME_DATA_MASTERING_DISPLAY_METADATA")
        assertContains(patched, "AV_FRAME_DATA_CONTENT_LIGHT_LEVEL")
        assertContains(patched, "ff_AMediaFormat_setBuffer(format, \"hdr-static-info\", hdr_static_info, sizeof(hdr_static_info))")
        assertEquals(patched, addMediaCodecHdrStaticInfoSupport(patched))

        // FfmpegBuildTask applies both overlays to the same file; composing them must not conflict.
        val composed = addMediaCodecHdrStaticInfoSupport(addMediaCodecP010Support(source))
        assertContains(composed, "AV_PIX_FMT_P010")
        assertContains(composed, "hdr-static-info")
    }
}
