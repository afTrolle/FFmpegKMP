// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings.javacpp;

import org.bytedeco.javacpp.tools.Info;
import org.bytedeco.javacpp.tools.InfoMap;

abstract class BasePreset {
    static void common(InfoMap infoMap) {
        infoMap.put(new Info("__attribute__", "av_printf_format", "av_warn_unused_result").skip());
        infoMap.put(new Info("AV_GCC_VERSION_AT_LEAST", "AV_GCC_VERSION_AT_MOST", "AV_HAS_BUILTIN").define(false));
        infoMap.put(new Info("AVERROR", "AVUNERROR", "FFERRTAG", "MKTAG", "MKBETAG").skip());
        infoMap.put(new Info("AV_TIME_BASE_Q").skip());
        infoMap.put(new Info("av_malloc_attrib", "av_alloc_size", "av_always_inline", "av_warn_unused_result", "av_alias")
                .cppTypes().annotations());
        infoMap.put(new Info("attribute_deprecated").annotations("@Deprecated"));
        infoMap.put(new Info("av_const").annotations("@Const"));
        infoMap.put(new Info("AVDiscard", "AVFieldOrder", "AVAudioServiceType", "AVEscapeMode")
                .cast().valueTypes("int").pointerTypes("org.bytedeco.javacpp.IntPointer"));
        infoMap.put(new Info("AVPROBE_SCORE_RETRY", "AVPROBE_SCORE_STREAM_RETRY")
                .translate(false));
        infoMap.put(new Info("AV_CHANNEL_LAYOUT_MONO", "AV_CHANNEL_LAYOUT_STEREO", "AV_CHANNEL_LAYOUT_2POINT1",
                "AV_CHANNEL_LAYOUT_2_1", "AV_CHANNEL_LAYOUT_SURROUND", "AV_CHANNEL_LAYOUT_3POINT1",
                "AV_CHANNEL_LAYOUT_4POINT0", "AV_CHANNEL_LAYOUT_4POINT1", "AV_CHANNEL_LAYOUT_2_2",
                "AV_CHANNEL_LAYOUT_QUAD", "AV_CHANNEL_LAYOUT_5POINT0", "AV_CHANNEL_LAYOUT_5POINT1",
                "AV_CHANNEL_LAYOUT_5POINT0_BACK", "AV_CHANNEL_LAYOUT_5POINT1_BACK", "AV_CHANNEL_LAYOUT_6POINT0",
                "AV_CHANNEL_LAYOUT_6POINT0_FRONT", "AV_CHANNEL_LAYOUT_3POINT1POINT2", "AV_CHANNEL_LAYOUT_HEXAGONAL",
                "AV_CHANNEL_LAYOUT_6POINT1", "AV_CHANNEL_LAYOUT_6POINT1_BACK", "AV_CHANNEL_LAYOUT_6POINT1_FRONT",
                "AV_CHANNEL_LAYOUT_7POINT0", "AV_CHANNEL_LAYOUT_7POINT0_FRONT", "AV_CHANNEL_LAYOUT_7POINT1",
                "AV_CHANNEL_LAYOUT_7POINT1_WIDE", "AV_CHANNEL_LAYOUT_7POINT1_WIDE_BACK", "AV_CHANNEL_LAYOUT_7POINT1_TOP_BACK",
                "AV_CHANNEL_LAYOUT_5POINT1POINT2", "AV_CHANNEL_LAYOUT_5POINT1POINT2_BACK", "AV_CHANNEL_LAYOUT_OCTAGONAL",
                "AV_CHANNEL_LAYOUT_CUBE", "AV_CHANNEL_LAYOUT_5POINT1POINT4_BACK", "AV_CHANNEL_LAYOUT_7POINT1POINT2",
                "AV_CHANNEL_LAYOUT_7POINT1POINT4_BACK", "AV_CHANNEL_LAYOUT_7POINT2POINT3", "AV_CHANNEL_LAYOUT_9POINT1POINT4_BACK",
                "AV_CHANNEL_LAYOUT_9POINT1POINT6", "AV_CHANNEL_LAYOUT_HEXADECAGONAL", "AV_CHANNEL_LAYOUT_BINAURAL",
                "AV_CHANNEL_LAYOUT_STEREO_DOWNMIX", "AV_CHANNEL_LAYOUT_22POINT2", "AV_CHANNEL_LAYOUT_AMBISONIC_FIRST_ORDER")
                .translate(false).cppTypes("AVChannelLayout"));
    }
}
