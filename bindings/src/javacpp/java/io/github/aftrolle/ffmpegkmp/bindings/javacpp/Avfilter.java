// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings.javacpp;

import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Properties;
import org.bytedeco.javacpp.tools.Info;
import org.bytedeco.javacpp.tools.InfoMap;
import org.bytedeco.javacpp.tools.InfoMapper;

@Properties(
        inherit = Avformat.class,
        value = @Platform(cinclude = {"<libavfilter/avfilter.h>", "<libavfilter/buffersink.h>", "<libavfilter/buffersrc.h>"}, link = "avfilter"),
        target = "io.github.aftrolle.ffmpegkmp.bindings.generated.avfilter",
        global = "io.github.aftrolle.ffmpegkmp.bindings.generated.avfilter.global.avfilter"
)
public class Avfilter implements InfoMapper {
    @Override public void map(InfoMap infoMap) {
        BasePreset.common(infoMap);
        infoMap.put(new Info("AVFilterInternal", "AVFilterGraphInternal").skip());
    }
}
