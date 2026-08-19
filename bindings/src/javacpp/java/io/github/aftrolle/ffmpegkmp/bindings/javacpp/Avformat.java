// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings.javacpp;

import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Properties;
import org.bytedeco.javacpp.tools.InfoMap;
import org.bytedeco.javacpp.tools.Info;
import org.bytedeco.javacpp.tools.InfoMapper;

@Properties(
        inherit = Avcodec.class,
        value = @Platform(cinclude = {"<libavformat/avformat.h>", "<libavformat/avio.h>"}, link = "avformat"),
        target = "io.github.aftrolle.ffmpegkmp.bindings.generated.avformat",
        global = "io.github.aftrolle.ffmpegkmp.bindings.generated.avformat.global.avformat"
)
public class Avformat implements InfoMapper {
    @Override public void map(InfoMap infoMap) {
        BasePreset.common(infoMap);
        infoMap.put(new Info("AVBPrint")
                .pointerTypes("io.github.aftrolle.ffmpegkmp.bindings.generated.avutil.AVBPrint"));
        infoMap.put(new Info("AVStreamGroupLCEVC").skip());
    }
}
