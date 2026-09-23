// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings.javacpp;

import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.annotation.Cast;
import org.bytedeco.javacpp.annotation.Name;
import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Raw;

/** Android-only raw-JNI seam for retaining an android.view.Surface in the player. */
@Platform(
        value = "android",
        include = "<ffplaykmp_player.h>",
        link = {"ffmpegkmp_bridge#", "avdevice", "avfilter", "avformat", "avcodec", "swscale", "swresample", "avutil", "z", "android"}
)
public final class AndroidPlayerSurface {
    private AndroidPlayerSurface() {}

    @Name("ffplaykmp_player_set_android_surface")
    public static native int setSurface(
            @Raw(withEnv = true) Object surface,
            @Cast("ffplaykmp_player *") Pointer player,
            int secure);
}
