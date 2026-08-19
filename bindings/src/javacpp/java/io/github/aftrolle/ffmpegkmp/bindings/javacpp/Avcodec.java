// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings.javacpp;

import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Properties;
import org.bytedeco.javacpp.tools.Info;
import org.bytedeco.javacpp.tools.InfoMap;
import org.bytedeco.javacpp.tools.InfoMapper;

@Properties(
        inherit = Avutil.class,
        value = @Platform(cinclude = {"<libavcodec/codec_id.h>", "<libavcodec/codec.h>", "<libavcodec/codec_desc.h>", "<libavcodec/avcodec.h>", "<libavcodec/codec_par.h>", "<libavcodec/packet.h>"}, link = "avcodec"),
        target = "io.github.aftrolle.ffmpegkmp.bindings.generated.avcodec",
        global = "io.github.aftrolle.ffmpegkmp.bindings.generated.avcodec.global.avcodec"
)
public class Avcodec implements InfoMapper {
    @Override public void map(InfoMap infoMap) {
        BasePreset.common(infoMap);
        infoMap.put(new Info("AVCodecInternal", "AVCodecDefault", "AVCodecHWConfigInternal").skip());
    }
}
