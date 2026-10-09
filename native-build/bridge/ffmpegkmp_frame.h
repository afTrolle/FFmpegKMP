// SPDX-License-Identifier: LGPL-2.1-or-later
#ifndef FFMPEGKMP_FRAME_H
#define FFMPEGKMP_FRAME_H

#include <stdint.h>

#include "ffplaykmp_player.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Frame handles: one reference each to an FFmpeg AVFrame, whose memory FFmpeg
 * owns. The decoders hand them out and the converter reads and writes them.
 *
 * - Every handle is one reference. ffmpegkmp_frame_ref makes another to the
 *   same memory, and ffmpegkmp_frame_unref releases one; the memory goes back
 *   to its pool when the last reference is released.
 * - A handle may be used from any thread, one call at a time. Different
 *   handles to the same memory may be used concurrently.
 * - Pixels are read through ffmpegkmp_frame_map, valid until the matching
 *   ffmpegkmp_frame_unmap.
 */
typedef struct ffmpegkmp_frame ffmpegkmp_frame;

/* Frames of one layout and size come from a pool; see ffmpegkmp_frame_pool_get. */
typedef struct ffmpegkmp_frame_pool ffmpegkmp_frame_pool;

/* The pixel layouts the frame model names. The values are the Kotlin PixelLayout ordinals. */
typedef enum ffmpegkmp_pixel_layout {
    /* None of the others: the frame's planes can still be mapped and converted. */
    FFMPEGKMP_LAYOUT_OTHER = -1,
    FFMPEGKMP_LAYOUT_RGBA8 = 0,
    FFMPEGKMP_LAYOUT_BGRA8 = 1,
    /* 10-bit red, green and blue and 2-bit alpha, red in the low bits (AV_PIX_FMT_X2BGR10LE). */
    FFMPEGKMP_LAYOUT_RGBA_1010102 = 2,
    FFMPEGKMP_LAYOUT_RGBA_F16 = 3,
    FFMPEGKMP_LAYOUT_NV12 = 4,
    FFMPEGKMP_LAYOUT_P010 = 5,
    FFMPEGKMP_LAYOUT_YUV420P = 6,
    FFMPEGKMP_LAYOUT_YUV420P10 = 7,
} ffmpegkmp_pixel_layout;

/* Colour, as the Kotlin FrameColor enums name it; the values are their ordinals. */
typedef enum ffmpegkmp_color_primaries {
    FFMPEGKMP_PRIMARIES_BT709 = 0,
    FFMPEGKMP_PRIMARIES_BT2020 = 1,
    FFMPEGKMP_PRIMARIES_DISPLAY_P3 = 2,
} ffmpegkmp_color_primaries;

typedef enum ffmpegkmp_color_transfer {
    FFMPEGKMP_TRANSFER_SRGB = 0,
    FFMPEGKMP_TRANSFER_BT709 = 1,
    FFMPEGKMP_TRANSFER_LINEAR = 2,
    FFMPEGKMP_TRANSFER_PQ = 3,
    FFMPEGKMP_TRANSFER_HLG = 4,
} ffmpegkmp_color_transfer;

typedef enum ffmpegkmp_color_matrix {
    FFMPEGKMP_MATRIX_RGB = 0,
    FFMPEGKMP_MATRIX_BT709 = 1,
    FFMPEGKMP_MATRIX_BT2020_NCL = 2,
    FFMPEGKMP_MATRIX_BT601 = 3,
} ffmpegkmp_color_matrix;

typedef enum ffmpegkmp_color_range {
    FFMPEGKMP_RANGE_LIMITED = 0,
    FFMPEGKMP_RANGE_FULL = 1,
} ffmpegkmp_color_range;

/* A pixel layout and a colour description. */
typedef struct ffmpegkmp_frame_format {
    uint32_t size;
    int32_t layout;
    int32_t primaries;
    int32_t transfer;
    int32_t matrix;
    int32_t range;
} ffmpegkmp_frame_format;

/*
 * What a frame is. FFmpeg colour values outside the model are reported as the
 * converter takes them: unknown primaries as BT.709, SDR curves without a
 * value of their own as BT.709, and an unspecified YUV matrix as BT.709.
 */
typedef struct ffmpegkmp_frame_info {
    uint32_t size;
    int32_t width;
    int32_t height;
    ffmpegkmp_frame_format format;
    /* Non-zero when ffmpegkmp_frame_map can reach the pixels. */
    int32_t mappable;
    /* Apple: the CVPixelBufferRef that holds the frame, NULL when none does. */
    void *pixel_buffer;
} ffmpegkmp_frame_info;

#define FFMPEGKMP_FRAME_MAX_PLANES 4

/* A frame's planes, for reading. */
typedef struct ffmpegkmp_frame_planes {
    uint32_t size;
    int32_t count;
    uint8_t *data[FFMPEGKMP_FRAME_MAX_PLANES];
    int32_t row_bytes[FFMPEGKMP_FRAME_MAX_PLANES];
    int32_t rows[FFMPEGKMP_FRAME_MAX_PLANES];
} ffmpegkmp_frame_planes;

/* Pixel memory the bridge allocated for frames, pooled or not, over the process's life. */
typedef struct ffmpegkmp_frame_statistics {
    uint32_t size;
    uint64_t buffers;
    uint64_t bytes;
} ffmpegkmp_frame_statistics;

FFPLAYKMP_EXPORT void ffmpegkmp_frame_format_init(ffmpegkmp_frame_format *format);
FFPLAYKMP_EXPORT void ffmpegkmp_frame_info_init(ffmpegkmp_frame_info *info);
FFPLAYKMP_EXPORT void ffmpegkmp_frame_planes_init(ffmpegkmp_frame_planes *planes);
FFPLAYKMP_EXPORT void ffmpegkmp_frame_statistics_init(ffmpegkmp_frame_statistics *statistics);

/* Another reference to the memory of `frame`; NULL when out of memory. */
FFPLAYKMP_EXPORT ffmpegkmp_frame *ffmpegkmp_frame_ref(const ffmpegkmp_frame *frame);
/* Releases one reference; NULL is a no-op. */
FFPLAYKMP_EXPORT void ffmpegkmp_frame_unref(ffmpegkmp_frame *frame);
FFPLAYKMP_EXPORT int ffmpegkmp_frame_get_info(const ffmpegkmp_frame *frame, ffmpegkmp_frame_info *info);

/*
 * Makes the frame's pixels readable and describes its planes. A CVPixelBuffer
 * is locked for reading, and another hardware frame is downloaded, once per
 * handle. Every successful map needs one ffmpegkmp_frame_unmap.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_frame_map(ffmpegkmp_frame *frame, ffmpegkmp_frame_planes *planes);
FFPLAYKMP_EXPORT void ffmpegkmp_frame_unmap(ffmpegkmp_frame *frame);
/*
 * Makes the frame's pixels writable in place, for drawing into it: a frame
 * whose memory no other reference shares, such as a fresh one from a pool. A
 * CVPixelBuffer is locked for writing. Returns FFPLAYKMP_ERROR_INVALID_STATE
 * for shared or hardware memory and while another map is open. Every
 * successful map needs one ffmpegkmp_frame_unmap_writable.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_frame_map_writable(ffmpegkmp_frame *frame, ffmpegkmp_frame_planes *planes);
FFPLAYKMP_EXPORT void ffmpegkmp_frame_unmap_writable(ffmpegkmp_frame *frame);

/*
 * A pool of frames keyed by layout and size. Frames are pooled AVBuffers, or
 * on Apple, for NV12, P010, BGRA8 and RGBA_F16, IOSurface-backed buffers from
 * a CVPixelBufferPool.
 *
 * With `capacity` 0 the pool makes a buffer whenever none is free. With a
 * capacity, at most 8, it is a ring: it makes at most that many buffers per
 * layout and size, once, and hands them out again as their frames close (a
 * CVPixelBufferPool takes the capacity as its allocation threshold).
 *
 * Freeing the pool leaves the frames it handed out valid; their memory is
 * released with their last reference, and a ring's bookkeeping with the last
 * of its frames. NULL when out of memory or for a capacity out of range.
 */
FFPLAYKMP_EXPORT ffmpegkmp_frame_pool *ffmpegkmp_frame_pool_alloc(int32_t capacity);
FFPLAYKMP_EXPORT void ffmpegkmp_frame_pool_free(ffmpegkmp_frame_pool *pool);
/*
 * A frame of `format` at this size, its pixels unspecified. `pool` NULL is the
 * process-wide pool, which has no bound. It never waits: while all of a ring's
 * frames of this layout and size are out, it fails with AVERROR(EAGAIN).
 */
FFPLAYKMP_EXPORT int ffmpegkmp_frame_pool_get(
        ffmpegkmp_frame_pool *pool,
        const ffmpegkmp_frame_format *format,
        int32_t width,
        int32_t height,
        ffmpegkmp_frame **frame);

/*
 * Converts `source` into the memory of `destination`: one threaded,
 * colour-managed pass (ffmpegkmp_frame_convert), from the process-wide set of
 * converters, which scales when the sizes differ. The destination keeps its
 * format and size and takes the source's other properties and side data.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_frame_convert_into(ffmpegkmp_frame *destination, const ffmpegkmp_frame *source);
/* Converts `source` into a new frame of `format` from the process-wide pool. */
FFPLAYKMP_EXPORT int ffmpegkmp_frame_convert_to(
        const ffmpegkmp_frame *source,
        const ffmpegkmp_frame_format *format,
        ffmpegkmp_frame **destination);

/*
 * A frame over memory the caller owns, such as a bitmap's, for use as a
 * conversion's destination: one packed plane of `row_bytes` per row, in an
 * RGB layout. The memory must outlive the frame, and ffmpegkmp_frame_ref of
 * it copies the pixels.
 */
FFPLAYKMP_EXPORT ffmpegkmp_frame *ffmpegkmp_frame_wrap(
        const ffmpegkmp_frame_format *format,
        int32_t width,
        int32_t height,
        uint8_t *pixels,
        int32_t row_bytes);

FFPLAYKMP_EXPORT void ffmpegkmp_frame_get_statistics(ffmpegkmp_frame_statistics *statistics);

#if defined(__ANDROID__)
/*
 * Converts `source` straight into an android.graphics.Bitmap's pixels, locked
 * through AndroidBitmap_lockPixels: an ARGB_8888 bitmap as sRGB RGBA8, an
 * RGBA_F16 one as linear extended sRGB. (ffmpegkmp_android_bitmap.c)
 */
FFPLAYKMP_EXPORT int ffmpegkmp_frame_convert_into_android_bitmap(
        JNIEnv *env,
        jclass owner,
        jobject bitmap,
        const ffmpegkmp_frame *source);
/*
 * The other way: converts a Bitmap's pixels, read in place, into `target`,
 * which keeps its format; the same bitmap configurations.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_frame_convert_from_android_bitmap(
        JNIEnv *env,
        jclass owner,
        jobject bitmap,
        ffmpegkmp_frame *target);
/*
 * Converts an android.hardware.HardwareBuffer's pixels into `target`, which
 * keeps its format: RGBA_8888 as sRGB RGBA8, RGBA_FP16 as linear extended
 * sRGB, locked for reading once the caller has waited for the GPU. Needs API
 * 26; FFPLAYKMP_ERROR_UNSUPPORTED before it.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_frame_convert_from_android_hardware_buffer(
        JNIEnv *env,
        jclass owner,
        jobject buffer,
        ffmpegkmp_frame *target);
#endif

#ifdef __cplusplus
}
#endif

#endif
