// SPDX-License-Identifier: LGPL-2.1-or-later
/*
 * Conversion into and out of an android.graphics.Bitmap's own pixels, and out
 * of a HardwareBuffer's. A file of its own, so only the JNI library that calls
 * it links libjnigraphics.
 */
#include "ffmpegkmp_frame.h"

#if defined(__ANDROID__)
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>

#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <libavutil/error.h>

/* Android 13's 10-bit bitmaps, which older NDK headers do not name. */
#ifndef ANDROID_BITMAP_FORMAT_RGBA_1010102
#define ANDROID_BITMAP_FORMAT_RGBA_1010102 10
#endif

/* An RGBA layout as Android draws it: 8- and 10-bit as sRGB, half floats as linear extended sRGB. */
static void ffmpegkmp_android_rgba_format(int32_t layout, ffmpegkmp_frame_format *format) {
    ffmpegkmp_frame_format_init(format);
    format->layout = layout;
    format->primaries = FFMPEGKMP_PRIMARIES_BT709;
    format->transfer = layout == FFMPEGKMP_LAYOUT_RGBA_F16 ? FFMPEGKMP_TRANSFER_LINEAR : FFMPEGKMP_TRANSFER_SRGB;
    format->matrix = FFMPEGKMP_MATRIX_RGB;
    format->range = FFMPEGKMP_RANGE_FULL;
}

/* The format of a bitmap's pixels: ARGB_8888, RGBA_1010102 or RGBA_F16. */
static int ffmpegkmp_bitmap_format(const AndroidBitmapInfo *info, ffmpegkmp_frame_format *format) {
    if (info->format == ANDROID_BITMAP_FORMAT_RGBA_8888)
        ffmpegkmp_android_rgba_format(FFMPEGKMP_LAYOUT_RGBA8, format);
    else if (info->format == ANDROID_BITMAP_FORMAT_RGBA_1010102)
        ffmpegkmp_android_rgba_format(FFMPEGKMP_LAYOUT_RGBA_1010102, format);
    else if (info->format == ANDROID_BITMAP_FORMAT_RGBA_F16)
        ffmpegkmp_android_rgba_format(FFMPEGKMP_LAYOUT_RGBA_F16, format);
    else
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    return 0;
}

/* One conversion between a frame and a bitmap's locked pixels, in the direction `into_bitmap` says. */
static int ffmpegkmp_convert_with_bitmap(JNIEnv *env, jobject bitmap, ffmpegkmp_frame *frame, int into_bitmap) {
    AndroidBitmapInfo info;
    ffmpegkmp_frame_format format;
    ffmpegkmp_frame *pixels_frame;
    void *pixels = NULL;
    int result;
    if (!env || !bitmap || !frame || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if ((result = ffmpegkmp_bitmap_format(&info, &format)) < 0)
        return result;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    pixels_frame = ffmpegkmp_frame_wrap(&format, (int32_t)info.width, (int32_t)info.height, pixels, (int32_t)info.stride);
    if (!pixels_frame)
        result = AVERROR(ENOMEM);
    else if (into_bitmap)
        result = ffmpegkmp_frame_convert_into(pixels_frame, frame);
    else
        result = ffmpegkmp_frame_convert_into(frame, pixels_frame);
    ffmpegkmp_frame_unref(pixels_frame);
    AndroidBitmap_unlockPixels(env, bitmap);
    return result;
}

int ffmpegkmp_frame_convert_into_android_bitmap(
        JNIEnv *env,
        jclass owner,
        jobject bitmap,
        const ffmpegkmp_frame *source) {
    (void)owner;
    /* The frame is only read; the bitmap is the destination. */
    return ffmpegkmp_convert_with_bitmap(env, bitmap, (ffmpegkmp_frame *)source, 1);
}

/*
 * AHardwareBuffer's functions arrive in API 26 and the bridge targets 24, so
 * they are looked up once at run time.
 */
static struct {
    AHardwareBuffer *(*from_java)(JNIEnv *env, jobject buffer);
    void (*describe)(const AHardwareBuffer *buffer, AHardwareBuffer_Desc *description);
    int (*lock)(AHardwareBuffer *buffer, uint64_t usage, int32_t fence, const ARect *rect, void **address);
    int (*unlock)(AHardwareBuffer *buffer, int32_t *fence);
} ffmpegkmp_hardware_buffer;
static pthread_once_t ffmpegkmp_hardware_buffer_once = PTHREAD_ONCE_INIT;

static void ffmpegkmp_load_hardware_buffer(void) {
    void *android = dlopen("libandroid.so", RTLD_NOW);
    void *window = dlopen("libnativewindow.so", RTLD_NOW);
    if (android)
        *(void **)&ffmpegkmp_hardware_buffer.from_java = dlsym(android, "AHardwareBuffer_fromHardwareBuffer");
    if (window) {
        *(void **)&ffmpegkmp_hardware_buffer.describe = dlsym(window, "AHardwareBuffer_describe");
        *(void **)&ffmpegkmp_hardware_buffer.lock = dlsym(window, "AHardwareBuffer_lock");
        *(void **)&ffmpegkmp_hardware_buffer.unlock = dlsym(window, "AHardwareBuffer_unlock");
    }
}

int ffmpegkmp_frame_convert_from_android_hardware_buffer(
        JNIEnv *env,
        jclass owner,
        jobject buffer,
        ffmpegkmp_frame *target) {
    AHardwareBuffer *native;
    AHardwareBuffer_Desc description;
    ffmpegkmp_frame_format format;
    ffmpegkmp_frame *pixels_frame;
    void *pixels = NULL;
    int bytes_per_pixel;
    int result;
    (void)owner;
    pthread_once(&ffmpegkmp_hardware_buffer_once, ffmpegkmp_load_hardware_buffer);
    if (!ffmpegkmp_hardware_buffer.from_java || !ffmpegkmp_hardware_buffer.describe ||
            !ffmpegkmp_hardware_buffer.lock || !ffmpegkmp_hardware_buffer.unlock)
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    if (!env || !buffer || !target || !(native = ffmpegkmp_hardware_buffer.from_java(env, buffer)))
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    ffmpegkmp_hardware_buffer.describe(native, &description);
    if (description.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM)
        ffmpegkmp_android_rgba_format(FFMPEGKMP_LAYOUT_RGBA8, &format);
    else if (description.format == AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM)
        ffmpegkmp_android_rgba_format(FFMPEGKMP_LAYOUT_RGBA_1010102, &format);
    else if (description.format == AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT)
        ffmpegkmp_android_rgba_format(FFMPEGKMP_LAYOUT_RGBA_F16, &format);
    else
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    bytes_per_pixel = format.layout == FFMPEGKMP_LAYOUT_RGBA_F16 ? 8 : 4;
    /* The caller has waited for the GPU's fence, so the lock takes none. */
    if (ffmpegkmp_hardware_buffer.lock(native, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, &pixels) != 0 || !pixels)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    pixels_frame = ffmpegkmp_frame_wrap(&format, (int32_t)description.width, (int32_t)description.height, pixels,
            (int32_t)(description.stride * bytes_per_pixel));
    result = pixels_frame ? ffmpegkmp_frame_convert_into(target, pixels_frame) : AVERROR(ENOMEM);
    ffmpegkmp_frame_unref(pixels_frame);
    ffmpegkmp_hardware_buffer.unlock(native, NULL);
    return result;
}

int ffmpegkmp_frame_convert_from_android_bitmap(
        JNIEnv *env,
        jclass owner,
        jobject bitmap,
        ffmpegkmp_frame *target) {
    (void)owner;
    return ffmpegkmp_convert_with_bitmap(env, bitmap, target, 0);
}
#endif
