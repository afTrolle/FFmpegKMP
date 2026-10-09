// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings.javacpp;

import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.annotation.Cast;
import org.bytedeco.javacpp.annotation.Name;
import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Raw;

/** Android-only raw-JNI seam for converting frames straight into and out of an android.graphics.Bitmap's pixels. */
@Platform(
        value = "android",
        include = {"<ffmpegkmp_frame.h>"},
        link = {"ffmpegkmp_bridge#", "avdevice", "avfilter", "avformat", "avcodec", "swscale", "swresample", "avutil", "z", "jnigraphics"}
)
public final class AndroidBitmapFrames {
    private AndroidBitmapFrames() {}

    @Name("ffmpegkmp_frame_convert_into_android_bitmap")
    public static native int convertInto(
            @Raw(withEnv = true) Object bitmap,
            @Cast("const ffmpegkmp_frame *") Pointer frame);

    @Name("ffmpegkmp_frame_convert_from_android_bitmap")
    public static native int convertFrom(
            @Raw(withEnv = true) Object bitmap,
            @Cast("ffmpegkmp_frame *") Pointer frame);

    @Name("ffmpegkmp_frame_convert_from_android_hardware_buffer")
    public static native int convertFromHardwareBuffer(
            @Raw(withEnv = true) Object buffer,
            @Cast("ffmpegkmp_frame *") Pointer frame);
}
