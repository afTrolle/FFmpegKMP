// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings.javacpp;

import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Properties;
import org.bytedeco.javacpp.tools.InfoMap;
import org.bytedeco.javacpp.tools.InfoMapper;

@Properties(
        value = @Platform(cinclude = {
                "<libavutil/avutil.h>",
                "<libavutil/rational.h>",
                "<libavutil/mathematics.h>",
                "<libavutil/error.h>",
                "<libavutil/log.h>",
                "<libavutil/dict.h>",
                "<libavutil/buffer.h>",
                "<libavutil/channel_layout.h>",
                "<libavutil/samplefmt.h>",
                "<libavutil/pixfmt.h>",
                "<libavutil/csp.h>",
                "<libavutil/frame.h>",
                "<libavutil/opt.h>",
                "<libavutil/bprint.h>",
                "<libavutil/avstring.h>",
                "<libavutil/container_fifo.h>",
                "<libavutil/hwcontext.h>",
                "<libavutil/iamf.h>",
                "<ffmpegkmp_macros.h>"
        }, link = "avutil"),
        target = "io.github.aftrolle.ffmpegkmp.bindings.generated.avutil",
        global = "io.github.aftrolle.ffmpegkmp.bindings.generated.avutil.global.avutil"
)
public class Avutil implements InfoMapper {
    @Override public void map(InfoMap infoMap) { BasePreset.common(infoMap); }
}
