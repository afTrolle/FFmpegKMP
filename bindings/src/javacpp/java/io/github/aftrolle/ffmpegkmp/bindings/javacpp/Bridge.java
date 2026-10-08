// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings.javacpp;

import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Properties;
import org.bytedeco.javacpp.tools.InfoMap;
import org.bytedeco.javacpp.tools.Info;
import org.bytedeco.javacpp.tools.InfoMapper;

@Properties(
        value = {
                @Platform(
                        cinclude = {"<ffmpegkmp_bridge.h>", "<ffplaykmp_player.h>", "<ffmpegkmp_frame.h>", "<ffmpegkmp_decoder.h>", "<ffmpegkmp_writer.h>"},
                        link = {"ffmpegkmp_bridge#", "avdevice", "avfilter", "avformat", "avcodec", "swscale", "swresample", "avutil"}
                ),
                // Platform-specific link lists replace the default one, so repeat it in full.
                // The static bridge archive embeds fftools, which reference zlib when the
                // Android profile enables it; libz ships in every Android sysroot.
                @Platform(
                        value = "android",
                        link = {"ffmpegkmp_bridge#", "avdevice", "avfilter", "avformat", "avcodec", "swscale", "swresample", "avutil", "z"}
                ),
        },
        target = "io.github.aftrolle.ffmpegkmp.bindings.generated.bridge",
        global = "io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge"
)
public class Bridge implements InfoMapper {
    @Override public void map(InfoMap infoMap) {
        BasePreset.common(infoMap);
        infoMap.put(new Info("FFMPEGKMP_EXPORT").cppText("#define FFMPEGKMP_EXPORT"));
        infoMap.put(new Info("FFPLAYKMP_EXPORT").cppText("#define FFPLAYKMP_EXPORT"));
        // Kept out of the platform-neutral generated declarations. AndroidPlayerSurface
        // and AndroidBitmapFrames expose these functions with JavaCPP's raw-JNI object
        // handling instead.
        infoMap.put(new Info("ffplaykmp_player_set_android_surface").skip());
        infoMap.put(new Info("ffmpegkmp_video_decoder_set_android_surface").skip());
        // AndroidBitmapFrames reaches it, and only its library links libjnigraphics.
        infoMap.put(new Info("ffmpegkmp_frame_convert_into_android_bitmap").skip());
        infoMap.put(new Info("ffmpegkmp_frame_convert_from_android_bitmap").skip());
        infoMap.put(new Info("ffmpegkmp_frame_convert_from_android_hardware_buffer").skip());
    }
}
