// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings.javacpp;

import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Properties;
import org.bytedeco.javacpp.tools.InfoMap;
import org.bytedeco.javacpp.tools.InfoMapper;

@Properties(
        inherit = Avformat.class,
        value = @Platform(cinclude = "<libavdevice/avdevice.h>", link = "avdevice"),
        target = "io.github.aftrolle.ffmpegkmp.bindings.generated.avdevice",
        global = "io.github.aftrolle.ffmpegkmp.bindings.generated.avdevice.global.avdevice"
)
public class Avdevice implements InfoMapper {
    @Override public void map(InfoMap infoMap) { BasePreset.common(infoMap); }
}
