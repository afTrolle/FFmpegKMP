// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings.javacpp;

import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Properties;
import org.bytedeco.javacpp.tools.InfoMap;
import org.bytedeco.javacpp.tools.InfoMapper;

@Properties(
        inherit = Avutil.class,
        value = @Platform(cinclude = "<libswresample/swresample.h>", link = "swresample"),
        target = "io.github.aftrolle.ffmpegkmp.bindings.generated.swresample",
        global = "io.github.aftrolle.ffmpegkmp.bindings.generated.swresample.global.swresample"
)
public class Swresample implements InfoMapper {
    @Override public void map(InfoMap infoMap) { BasePreset.common(infoMap); }
}
