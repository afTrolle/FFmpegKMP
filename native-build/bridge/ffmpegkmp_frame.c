// SPDX-License-Identifier: LGPL-2.1-or-later
#include "ffmpegkmp_frame.h"
#include "ffplaykmp_core.h"

#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <libavutil/buffer.h>
#include <libavutil/hwcontext.h>
#include <libavutil/imgutils.h>
#include <libavutil/mem.h>
#include <libavutil/pixdesc.h>
#include <libavutil/time.h>
#if defined(FFMPEGKMP_PIXEL_BUFFER)
#include <CoreVideo/CoreVideo.h>
#include <libavutil/hwcontext_videotoolbox.h>
#endif

/* Row and plane alignment of pooled frames, as av_frame_get_buffer aligns for SIMD. */
#define FFMPEGKMP_FRAME_ALIGN 64
/* Layouts and sizes a pool keeps at once; the least recently used goes first. */
#define FFMPEGKMP_POOL_ENTRIES 8
/* Idle converters kept for ffmpegkmp_frame_convert_into; each keeps its workers. */
#define FFMPEGKMP_IDLE_CONVERTERS 4

static _Atomic uint64_t ffmpegkmp_allocated_buffers;
static _Atomic uint64_t ffmpegkmp_allocated_bytes;

void ffmpegkmp_frame_count_allocation(size_t bytes) {
    atomic_fetch_add(&ffmpegkmp_allocated_buffers, 1);
    atomic_fetch_add(&ffmpegkmp_allocated_bytes, (uint64_t)bytes);
}

void ffmpegkmp_frame_get_statistics(ffmpegkmp_frame_statistics *statistics) {
    if (!statistics || statistics->size < sizeof(*statistics))
        return;
    statistics->buffers = atomic_load(&ffmpegkmp_allocated_buffers);
    statistics->bytes = atomic_load(&ffmpegkmp_allocated_bytes);
}

void ffmpegkmp_frame_format_init(ffmpegkmp_frame_format *format) {
    if (!format)
        return;
    memset(format, 0, sizeof(*format));
    format->size = sizeof(*format);
    format->layout = FFMPEGKMP_LAYOUT_OTHER;
}

void ffmpegkmp_frame_info_init(ffmpegkmp_frame_info *info) {
    if (!info)
        return;
    memset(info, 0, sizeof(*info));
    info->size = sizeof(*info);
    ffmpegkmp_frame_format_init(&info->format);
}

void ffmpegkmp_frame_planes_init(ffmpegkmp_frame_planes *planes) {
    if (!planes)
        return;
    memset(planes, 0, sizeof(*planes));
    planes->size = sizeof(*planes);
}

void ffmpegkmp_frame_statistics_init(ffmpegkmp_frame_statistics *statistics) {
    if (!statistics)
        return;
    memset(statistics, 0, sizeof(*statistics));
    statistics->size = sizeof(*statistics);
}

/* Layouts and colour */

static enum AVPixelFormat ffmpegkmp_layout_pixel_format(int32_t layout) {
    switch (layout) {
    case FFMPEGKMP_LAYOUT_RGBA8:
        return AV_PIX_FMT_RGBA;
    case FFMPEGKMP_LAYOUT_BGRA8:
        return AV_PIX_FMT_BGRA;
    case FFMPEGKMP_LAYOUT_RGBA_1010102:
        return AV_PIX_FMT_X2BGR10;
    case FFMPEGKMP_LAYOUT_RGBA_F16:
        return AV_PIX_FMT_RGBAF16;
    case FFMPEGKMP_LAYOUT_NV12:
        return AV_PIX_FMT_NV12;
    case FFMPEGKMP_LAYOUT_P010:
        return AV_PIX_FMT_P010;
    case FFMPEGKMP_LAYOUT_YUV420P:
        return AV_PIX_FMT_YUV420P;
    case FFMPEGKMP_LAYOUT_YUV420P10:
        return AV_PIX_FMT_YUV420P10;
    default:
        return AV_PIX_FMT_NONE;
    }
}

static int32_t ffmpegkmp_pixel_format_layout(enum AVPixelFormat format) {
    switch (format) {
    case AV_PIX_FMT_RGBA:
        return FFMPEGKMP_LAYOUT_RGBA8;
    case AV_PIX_FMT_BGRA:
        return FFMPEGKMP_LAYOUT_BGRA8;
    case AV_PIX_FMT_X2BGR10:
        return FFMPEGKMP_LAYOUT_RGBA_1010102;
    case AV_PIX_FMT_RGBAF16:
        return FFMPEGKMP_LAYOUT_RGBA_F16;
    case AV_PIX_FMT_NV12:
        return FFMPEGKMP_LAYOUT_NV12;
    case AV_PIX_FMT_P010:
        return FFMPEGKMP_LAYOUT_P010;
    case AV_PIX_FMT_YUV420P:
    case AV_PIX_FMT_YUVJ420P:
        return FFMPEGKMP_LAYOUT_YUV420P;
    case AV_PIX_FMT_YUV420P10:
        return FFMPEGKMP_LAYOUT_YUV420P10;
    default:
        return FFMPEGKMP_LAYOUT_OTHER;
    }
}

static int ffmpegkmp_is_rgb_format(enum AVPixelFormat format) {
    const AVPixFmtDescriptor *descriptor = av_pix_fmt_desc_get(format);
    return descriptor && (descriptor->flags & AV_PIX_FMT_FLAG_RGB);
}

int ffmpegkmp_frame_format_is_valid(const ffmpegkmp_frame_format *format) {
    return format && format->size >= sizeof(*format) &&
            ffmpegkmp_layout_pixel_format(format->layout) != AV_PIX_FMT_NONE &&
            format->primaries >= FFMPEGKMP_PRIMARIES_BT709 && format->primaries <= FFMPEGKMP_PRIMARIES_DISPLAY_P3 &&
            format->transfer >= FFMPEGKMP_TRANSFER_SRGB && format->transfer <= FFMPEGKMP_TRANSFER_HLG &&
            format->matrix >= FFMPEGKMP_MATRIX_RGB && format->matrix <= FFMPEGKMP_MATRIX_BT601 &&
            format->range >= FFMPEGKMP_RANGE_LIMITED && format->range <= FFMPEGKMP_RANGE_FULL &&
            /* RGB layouts have no matrix, and YUV ones need one. */
            (format->matrix == FFMPEGKMP_MATRIX_RGB) ==
                    ffmpegkmp_is_rgb_format(ffmpegkmp_layout_pixel_format(format->layout));
}

/* Tags `frame` with `format`'s pixel format and colour. */
static void ffmpegkmp_apply_format(AVFrame *frame, const ffmpegkmp_frame_format *format) {
    static const enum AVColorPrimaries primaries[] = { AVCOL_PRI_BT709, AVCOL_PRI_BT2020, AVCOL_PRI_SMPTE432 };
    static const enum AVColorTransferCharacteristic transfers[] = {
        AVCOL_TRC_IEC61966_2_1, AVCOL_TRC_BT709, AVCOL_TRC_LINEAR, AVCOL_TRC_SMPTE2084, AVCOL_TRC_ARIB_STD_B67,
    };
    static const enum AVColorSpace matrices[] = {
        AVCOL_SPC_RGB, AVCOL_SPC_BT709, AVCOL_SPC_BT2020_NCL, AVCOL_SPC_SMPTE170M,
    };
    frame->format = ffmpegkmp_layout_pixel_format(format->layout);
    frame->color_primaries = primaries[format->primaries];
    frame->color_trc = transfers[format->transfer];
    frame->colorspace = matrices[format->matrix];
    frame->color_range = format->range == FFMPEGKMP_RANGE_FULL ? AVCOL_RANGE_JPEG : AVCOL_RANGE_MPEG;
    frame->chroma_location = ffmpegkmp_is_rgb_format(frame->format) ? AVCHROMA_LOC_UNSPECIFIED : AVCHROMA_LOC_LEFT;
}

#if defined(FFMPEGKMP_PIXEL_BUFFER)
/* The software format of a CVPixelBuffer; FFmpeg's own table has no half floats. */
static enum AVPixelFormat ffmpegkmp_pixel_buffer_format(CVPixelBufferRef buffer) {
    const OSType type = CVPixelBufferGetPixelFormatType(buffer);
    return type == kCVPixelFormatType_64RGBAHalf ? AV_PIX_FMT_RGBAF16 : av_map_videotoolbox_format_to_pixfmt(type);
}
#endif

int ffmpegkmp_frame_is_pixel_buffer(const AVFrame *frame) {
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    return frame->format == AV_PIX_FMT_VIDEOTOOLBOX && frame->data[3];
#else
    (void)frame;
    return 0;
#endif
}

/* The layout of the frame's pixels, for hardware frames the one they download to. */
static enum AVPixelFormat ffmpegkmp_software_format(const AVFrame *frame) {
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    if (ffmpegkmp_frame_is_pixel_buffer(frame))
        return ffmpegkmp_pixel_buffer_format((CVPixelBufferRef)frame->data[3]);
#endif
    if (frame->hw_frames_ctx)
        return ((const AVHWFramesContext *)frame->hw_frames_ctx->data)->sw_format;
    return frame->format;
}

static void ffmpegkmp_describe_colour(const AVFrame *frame, enum AVPixelFormat format, ffmpegkmp_frame_format *out) {
    const int rgb = ffmpegkmp_is_rgb_format(format);
    switch (frame->color_primaries) {
    case AVCOL_PRI_BT2020:
        out->primaries = FFMPEGKMP_PRIMARIES_BT2020;
        break;
    case AVCOL_PRI_SMPTE432:
        out->primaries = FFMPEGKMP_PRIMARIES_DISPLAY_P3;
        break;
    default:
        out->primaries = FFMPEGKMP_PRIMARIES_BT709;
        break;
    }
    switch (frame->color_trc) {
    case AVCOL_TRC_IEC61966_2_1:
        out->transfer = FFMPEGKMP_TRANSFER_SRGB;
        break;
    case AVCOL_TRC_LINEAR:
        out->transfer = FFMPEGKMP_TRANSFER_LINEAR;
        break;
    case AVCOL_TRC_SMPTE2084:
        out->transfer = FFMPEGKMP_TRANSFER_PQ;
        break;
    case AVCOL_TRC_ARIB_STD_B67:
        out->transfer = FFMPEGKMP_TRANSFER_HLG;
        break;
    default:
        out->transfer = FFMPEGKMP_TRANSFER_BT709;
        break;
    }
    if (rgb) {
        out->matrix = FFMPEGKMP_MATRIX_RGB;
    } else if (frame->colorspace == AVCOL_SPC_BT2020_NCL || frame->colorspace == AVCOL_SPC_BT2020_CL) {
        out->matrix = FFMPEGKMP_MATRIX_BT2020_NCL;
    } else if (frame->colorspace == AVCOL_SPC_BT470BG || frame->colorspace == AVCOL_SPC_SMPTE170M) {
        out->matrix = FFMPEGKMP_MATRIX_BT601;
    } else {
        out->matrix = FFMPEGKMP_MATRIX_BT709;
    }
    /* Unspecified, RGB is full range and YUV limited, as swscale takes them. */
    if (frame->color_range == AVCOL_RANGE_JPEG || format == AV_PIX_FMT_YUVJ420P)
        out->range = FFMPEGKMP_RANGE_FULL;
    else if (frame->color_range == AVCOL_RANGE_MPEG)
        out->range = FFMPEGKMP_RANGE_LIMITED;
    else
        out->range = rgb ? FFMPEGKMP_RANGE_FULL : FFMPEGKMP_RANGE_LIMITED;
}

/* Handles */

static ffmpegkmp_frame *ffmpegkmp_frame_alloc_handle(void) {
    ffmpegkmp_frame *frame = calloc(1, sizeof(*frame));
    if (!frame)
        return NULL;
    if (pthread_mutex_init(&frame->lock, NULL) != 0) {
        free(frame);
        return NULL;
    }
    if (!(frame->frame = av_frame_alloc())) {
        pthread_mutex_destroy(&frame->lock);
        free(frame);
        return NULL;
    }
    return frame;
}

ffmpegkmp_frame *ffmpegkmp_frame_from_av(const AVFrame *source) {
    ffmpegkmp_frame *frame;
    if (!source || !(frame = ffmpegkmp_frame_alloc_handle()))
        return NULL;
    if (av_frame_ref(frame->frame, source) < 0) {
        ffmpegkmp_frame_unref(frame);
        return NULL;
    }
    return frame;
}

ffmpegkmp_frame *ffmpegkmp_frame_ref(const ffmpegkmp_frame *frame) {
    return frame ? ffmpegkmp_frame_from_av(frame->frame) : NULL;
}

void ffmpegkmp_frame_unref(ffmpegkmp_frame *frame) {
    if (!frame)
        return;
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    for (; frame->locks > 0; frame->locks--)
        CVPixelBufferUnlockBaseAddress((CVPixelBufferRef)frame->frame->data[3], kCVPixelBufferLock_ReadOnly);
#endif
    av_frame_free(&frame->frame);
    av_frame_free(&frame->download);
    pthread_mutex_destroy(&frame->lock);
    free(frame);
}

int ffmpegkmp_frame_get_info(const ffmpegkmp_frame *frame, ffmpegkmp_frame_info *info) {
    enum AVPixelFormat format;
    if (!frame || !info || info->size < sizeof(*info))
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    ffmpegkmp_frame_info_init(info);
    format = ffmpegkmp_software_format(frame->frame);
    info->width = frame->frame->width;
    info->height = frame->frame->height;
    info->format.layout = ffmpegkmp_pixel_format_layout(format);
    ffmpegkmp_describe_colour(frame->frame, format, &info->format);
    /* MediaCodec's Surface frames never become handles; every other frame maps. */
    info->mappable = format != AV_PIX_FMT_NONE;
#if defined(__APPLE__)
    if (frame->frame->format == AV_PIX_FMT_VIDEOTOOLBOX)
        info->pixel_buffer = frame->frame->data[3];
#endif
    return 0;
}

/* Mapping */

#if defined(FFMPEGKMP_PIXEL_BUFFER)
/*
 * Points `view` at the locked planes of the CVPixelBuffer in `frame`. `view`
 * owns nothing: the caller fills in the rest and unlocks with `flags` after.
 */
static int ffmpegkmp_pixel_buffer_planes(const AVFrame *frame, AVFrame *view, CVPixelBufferLockFlags flags) {
    CVPixelBufferRef buffer = (CVPixelBufferRef)frame->data[3];
    const enum AVPixelFormat format = ffmpegkmp_pixel_buffer_format(buffer);
    size_t plane;
    if (format == AV_PIX_FMT_NONE)
        return AVERROR(ENOSYS);
    if (CVPixelBufferLockBaseAddress(buffer, flags) != kCVReturnSuccess)
        return AVERROR_EXTERNAL;
    view->format = format;
    memset(view->data, 0, sizeof(view->data));
    memset(view->linesize, 0, sizeof(view->linesize));
    if (!CVPixelBufferIsPlanar(buffer)) {
        view->data[0] = CVPixelBufferGetBaseAddress(buffer);
        view->linesize[0] = (int)CVPixelBufferGetBytesPerRow(buffer);
    }
    for (plane = 0; plane < CVPixelBufferGetPlaneCount(buffer) && plane < 4; plane++) {
        view->data[plane] = CVPixelBufferGetBaseAddressOfPlane(buffer, plane);
        view->linesize[plane] = (int)CVPixelBufferGetBytesPerRowOfPlane(buffer, plane);
    }
    return 0;
}
#endif

/*
 * A view of `frame`'s pixels in software memory: the frame itself, its
 * CVPixelBuffer locked for reading (*locked is set; unlock it after), or its
 * download into *download, made once. The view shares the frame's properties
 * and side data and owns nothing.
 */
static int ffmpegkmp_source_view(const AVFrame *frame, AVFrame *view, AVFrame **download, int *locked) {
    int result;
    *locked = 0;
    *view = *frame;
    if (!ffplaykmp_is_hardware_frame(frame))
        return 0;
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    if (ffmpegkmp_frame_is_pixel_buffer(frame)) {
        if ((result = ffmpegkmp_pixel_buffer_planes(frame, view, kCVPixelBufferLock_ReadOnly)) < 0)
            return result;
        memset(view->buf, 0, sizeof(view->buf));
        view->extended_data = view->data;
        view->extended_buf = NULL;
        view->nb_extended_buf = 0;
        view->hw_frames_ctx = NULL;
        *locked = 1;
        return 0;
    }
#endif
    if (!*download) {
        if (!(*download = av_frame_alloc()))
            return AVERROR(ENOMEM);
        if ((result = ffplaykmp_download_frame(*download, frame)) < 0) {
            av_frame_free(download);
            return result;
        }
        ffmpegkmp_frame_count_allocation(
                (size_t)av_image_get_buffer_size((*download)->format, (*download)->width, (*download)->height, 1));
    }
    *view = **download;
    /* The download is the hardware pool's coded size (MediaCodec gives 1920x1088 for 1080p); the picture is the
     * frame's. Rows and columns past it would overrun a destination of the picture's size. */
    if (view->width > frame->width && frame->width > 0)
        view->width = frame->width;
    if (view->height > frame->height && frame->height > 0)
        view->height = frame->height;
    return 0;
}

static void ffmpegkmp_end_source_view(const AVFrame *frame, int locked) {
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    if (locked)
        CVPixelBufferUnlockBaseAddress((CVPixelBufferRef)frame->data[3], kCVPixelBufferLock_ReadOnly);
#else
    (void)frame;
    (void)locked;
#endif
}

/* Describes `view`'s planes; its pixels must stay where they are while `planes` is used. */
static int ffmpegkmp_describe_planes(
        const AVFrame *view,
        const AVPixFmtDescriptor *descriptor,
        ffmpegkmp_frame_planes *planes) {
    int plane;
    ffmpegkmp_frame_planes_init(planes);
    planes->count = av_pix_fmt_count_planes(view->format);
    for (plane = 0; plane < planes->count && plane < FFMPEGKMP_FRAME_MAX_PLANES; plane++) {
        /* Chroma planes are subsampled vertically; an alpha plane (the fourth) is not. */
        const int chroma = plane == 1 || plane == 2;
        if (view->linesize[plane] < 0 || !view->data[plane])
            return AVERROR(ENOSYS);
        planes->data[plane] = view->data[plane];
        planes->row_bytes[plane] = view->linesize[plane];
        planes->rows[plane] = chroma ? AV_CEIL_RSHIFT(view->height, descriptor->log2_chroma_h) : view->height;
    }
    return 0;
}

int ffmpegkmp_frame_map(ffmpegkmp_frame *frame, ffmpegkmp_frame_planes *planes) {
    const AVPixFmtDescriptor *descriptor;
    AVFrame view;
    int locked;
    int result;
    if (!frame || !planes || planes->size < sizeof(*planes))
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    pthread_mutex_lock(&frame->lock);
    result = ffmpegkmp_source_view(frame->frame, &view, &frame->download, &locked);
    if (result >= 0 && !(descriptor = av_pix_fmt_desc_get(view.format)))
        result = AVERROR(ENOSYS);
    if (result < 0) {
        pthread_mutex_unlock(&frame->lock);
        return result;
    }
    result = ffmpegkmp_describe_planes(&view, descriptor, planes);
    if (result < 0) {
        ffmpegkmp_end_source_view(frame->frame, locked);
    } else if (locked) {
        frame->locks++;
    }
    pthread_mutex_unlock(&frame->lock);
    return result;
}

int ffmpegkmp_frame_map_writable(ffmpegkmp_frame *frame, ffmpegkmp_frame_planes *planes) {
    const AVPixFmtDescriptor *descriptor;
    AVFrame view;
    int result = 0;
    if (!frame || !planes || planes->size < sizeof(*planes))
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    pthread_mutex_lock(&frame->lock);
    view = *frame->frame;
    /* Shared memory would change under another reference; av_frame_is_writable sees every buffer's references. */
    if (frame->write_mapped || frame->locks || !av_frame_is_writable(frame->frame)) {
        result = FFPLAYKMP_ERROR_INVALID_STATE;
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    } else if (ffmpegkmp_frame_is_pixel_buffer(frame->frame)) {
        if ((result = ffmpegkmp_pixel_buffer_planes(frame->frame, &view, 0)) >= 0)
            frame->write_mapped = 1;
        view.width = frame->frame->width;
        view.height = frame->frame->height;
#endif
    } else if (ffplaykmp_is_hardware_frame(frame->frame)) {
        result = FFPLAYKMP_ERROR_INVALID_STATE;
    } else {
        frame->write_mapped = 1;
    }
    if (result >= 0 && !(descriptor = av_pix_fmt_desc_get(view.format)))
        result = AVERROR(ENOSYS);
    if (result >= 0)
        result = ffmpegkmp_describe_planes(&view, descriptor, planes);
    pthread_mutex_unlock(&frame->lock);
    if (result < 0 && frame->write_mapped)
        ffmpegkmp_frame_unmap_writable(frame);
    return result;
}

void ffmpegkmp_frame_unmap_writable(ffmpegkmp_frame *frame) {
    if (!frame)
        return;
    pthread_mutex_lock(&frame->lock);
    if (frame->write_mapped) {
#if defined(FFMPEGKMP_PIXEL_BUFFER)
        if (ffmpegkmp_frame_is_pixel_buffer(frame->frame))
            CVPixelBufferUnlockBaseAddress((CVPixelBufferRef)frame->frame->data[3], 0);
#endif
        frame->write_mapped = 0;
    }
    pthread_mutex_unlock(&frame->lock);
}

void ffmpegkmp_frame_unmap(ffmpegkmp_frame *frame) {
    if (!frame)
        return;
    pthread_mutex_lock(&frame->lock);
    if (frame->locks > 0) {
        ffmpegkmp_end_source_view(frame->frame, 1);
        frame->locks--;
    }
    pthread_mutex_unlock(&frame->lock);
}

/* Pools */

/* The most frames a bounded pool keeps per layout and size. */
#define FFMPEGKMP_POOL_MAX_CAPACITY 8
/*
 * How long a decoder's take of a full ring waits for a frame to come back, in
 * microseconds: enough for one closing on another thread, as a collector's
 * does while the frame ahead of it is taken, and well short of any timeout.
 */
#define FFMPEGKMP_RING_GRACE_US 250000

typedef struct ffmpegkmp_pool_entry {
    enum AVPixelFormat format;
    int width;
    int height;
    /* Non-zero for a CVPixelBufferPool entry: the CoreVideo pixel format. */
    uint32_t pixel_buffer_format;
    uint64_t used;
    /* An unbounded pool's buffers. */
    AVBufferPool *buffers;
    /* A bounded pool's ring: the buffers of `size` made so far, the bits of `out` those handed out. */
    size_t size;
    uint8_t *ring[FFMPEGKMP_POOL_MAX_CAPACITY];
    int made;
    unsigned out;
    int linesize[4];
    size_t offset[4];
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    CVPixelBufferPoolRef pixel_buffers;
    /* A bounded pool's allocation threshold, the auxiliary attributes of each buffer it makes. */
    CFDictionaryRef threshold;
#endif
} ffmpegkmp_pool_entry;

struct ffmpegkmp_frame_pool {
    pthread_mutex_t lock;
    /* Broadcast whenever a frame of a bounded pool comes back. */
    pthread_cond_t returned;
    ffmpegkmp_pool_entry entries[FFMPEGKMP_POOL_ENTRIES];
    int count;
    uint64_t clock;
    /* Frames per layout and size, 0 for no bound. */
    int capacity;
    /* The owner's, and one for each frame a bounded pool has out: the pool goes with the last. */
    int references;
};

static ffmpegkmp_frame_pool ffmpegkmp_shared_pool = { PTHREAD_MUTEX_INITIALIZER, PTHREAD_COND_INITIALIZER };

static AVBufferRef *ffmpegkmp_pool_buffer_alloc(void *opaque, size_t size) {
    AVBufferRef *buffer = av_buffer_alloc(size);
    (void)opaque;
    if (buffer)
        ffmpegkmp_frame_count_allocation(size);
    return buffer;
}

static void ffmpegkmp_pool_destroy(ffmpegkmp_frame_pool *pool) {
    pthread_cond_destroy(&pool->returned);
    pthread_mutex_destroy(&pool->lock);
    free(pool);
}

/*
 * A bounded pool's frame came back, with `data` its ring buffer, or NULL for a
 * CVPixelBuffer: the buffer is free again, or freed when its entry has gone,
 * and a waiting get wakes. Drops the frame's reference on the pool.
 */
static void ffmpegkmp_pool_returned(ffmpegkmp_frame_pool *pool, uint8_t *data) {
    int found = !data;
    int index;
    int slot;
    int last;
    pthread_mutex_lock(&pool->lock);
    for (index = 0; index < pool->count && !found; index++) {
        ffmpegkmp_pool_entry *entry = &pool->entries[index];
        for (slot = 0; slot < entry->made && !found; slot++) {
            if (entry->ring[slot] == data) {
                entry->out &= ~(1u << slot);
                found = 1;
            }
        }
    }
    /* Its entry was recycled or its pool freed: the buffer goes with it. */
    if (!found)
        av_free(data);
    pthread_cond_broadcast(&pool->returned);
    last = --pool->references == 0;
    pthread_mutex_unlock(&pool->lock);
    if (last)
        ffmpegkmp_pool_destroy(pool);
}

static void ffmpegkmp_release_ring_buffer(void *opaque, uint8_t *data) {
    ffmpegkmp_pool_returned(opaque, data);
}

static void ffmpegkmp_pool_entry_free(ffmpegkmp_pool_entry *entry) {
    int slot;
    /* Outstanding frames keep their buffers; the pools go when the last one returns. */
    av_buffer_pool_uninit(&entry->buffers);
    /* Ring buffers that are out are freed as they come back. */
    for (slot = 0; slot < entry->made; slot++) {
        if (!(entry->out & (1u << slot)))
            av_free(entry->ring[slot]);
    }
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    CVPixelBufferPoolRelease(entry->pixel_buffers);
    entry->pixel_buffers = NULL;
    if (entry->threshold)
        CFRelease(entry->threshold);
#endif
    memset(entry, 0, sizeof(*entry));
}

/* The planes' layout in one buffer, and for an unbounded pool the buffers. */
static int ffmpegkmp_pool_entry_init_buffers(ffmpegkmp_pool_entry *entry, int bounded) {
    ptrdiff_t linesizes[4];
    size_t sizes[4];
    size_t total = 0;
    int plane;
    int result;
    if ((result = av_image_fill_linesizes(entry->linesize, entry->format, entry->width)) < 0)
        return result;
    for (plane = 0; plane < 4; plane++) {
        entry->linesize[plane] = FFALIGN(entry->linesize[plane], FFMPEGKMP_FRAME_ALIGN);
        linesizes[plane] = entry->linesize[plane];
    }
    if ((result = av_image_fill_plane_sizes(sizes, entry->format, entry->height, linesizes)) < 0)
        return result;
    for (plane = 0; plane < 4; plane++) {
        entry->offset[plane] = total;
        total += FFALIGN(sizes[plane], FFMPEGKMP_FRAME_ALIGN);
    }
    /* Room for SIMD reads past the last row, as FFmpeg's own frame buffers have. */
    entry->size = total + AV_INPUT_BUFFER_PADDING_SIZE;
    if (bounded)
        return 0;
    entry->buffers = av_buffer_pool_init2(entry->size, NULL, ffmpegkmp_pool_buffer_alloc, NULL);
    return entry->buffers ? 0 : AVERROR(ENOMEM);
}

#if defined(FFMPEGKMP_PIXEL_BUFFER)
static void ffmpegkmp_set_number(CFMutableDictionaryRef dictionary, CFStringRef key, int32_t value) {
    CFNumberRef number = CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt32Type, &value);
    if (number) {
        CFDictionarySetValue(dictionary, key, number);
        CFRelease(number);
    }
}

static CFMutableDictionaryRef ffmpegkmp_dictionary(void) {
    return CFDictionaryCreateMutable(kCFAllocatorDefault, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
}

/*
 * The CoreVideo format a pool hands out for this layout and range, 0 for none.
 * A CVPixelBufferPool, not FFmpeg's hwcontext pool: it only recycles a buffer
 * once every retain on it is released, so frames handed out stay intact.
 */
static uint32_t ffmpegkmp_pixel_buffer_type(enum AVPixelFormat format, int full_range) {
    switch (format) {
    case AV_PIX_FMT_NV12:
    case AV_PIX_FMT_P010:
    case AV_PIX_FMT_BGRA:
        return av_map_videotoolbox_format_from_pixfmt2(format, full_range);
    case AV_PIX_FMT_RGBAF16:
        return kCVPixelFormatType_64RGBAHalf;
    default:
        return 0;
    }
}

/*
 * A CVPixelBufferPool for the entry. A bounded one is a ring made once: it
 * makes at most `capacity` buffers, and those back in it never age out.
 */
static int ffmpegkmp_pool_entry_init_pixel_buffers(ffmpegkmp_pool_entry *entry, int capacity) {
    CFMutableDictionaryRef attributes = ffmpegkmp_dictionary();
    CFDictionaryRef surface = CFDictionaryCreate(
            kCFAllocatorDefault, NULL, NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFMutableDictionaryRef pool_attributes = capacity ? ffmpegkmp_dictionary() : NULL;
    CFMutableDictionaryRef threshold = capacity ? ffmpegkmp_dictionary() : NULL;
    CVReturn status = kCVReturnAllocationFailed;
    if (attributes && surface && (!capacity || (pool_attributes && threshold))) {
        ffmpegkmp_set_number(attributes, kCVPixelBufferPixelFormatTypeKey, (int32_t)entry->pixel_buffer_format);
        ffmpegkmp_set_number(attributes, kCVPixelBufferWidthKey, entry->width);
        ffmpegkmp_set_number(attributes, kCVPixelBufferHeightKey, entry->height);
        CFDictionarySetValue(attributes, kCVPixelBufferIOSurfacePropertiesKey, surface);
        CFDictionarySetValue(attributes, kCVPixelBufferMetalCompatibilityKey, kCFBooleanTrue);
        if (capacity) {
            ffmpegkmp_set_number(pool_attributes, kCVPixelBufferPoolMaximumBufferAgeKey, 0);
            ffmpegkmp_set_number(threshold, kCVPixelBufferPoolAllocationThresholdKey, capacity);
        }
        status = CVPixelBufferPoolCreate(kCFAllocatorDefault, pool_attributes, attributes, &entry->pixel_buffers);
    }
    if (surface)
        CFRelease(surface);
    if (attributes)
        CFRelease(attributes);
    if (pool_attributes)
        CFRelease(pool_attributes);
    if (status != kCVReturnSuccess) {
        if (threshold)
            CFRelease(threshold);
        entry->pixel_buffers = NULL;
        return AVERROR_EXTERNAL;
    }
    entry->threshold = threshold;
    return 0;
}

static void ffmpegkmp_release_pixel_buffer(void *opaque, uint8_t *data) {
    CVPixelBufferRelease((CVPixelBufferRef)data);
    if (opaque)
        ffmpegkmp_pool_returned(opaque, NULL);
}
#endif

/* The entry for this layout and size, made or recycled as needed. Called with the pool locked. */
static int ffmpegkmp_pool_find(
        ffmpegkmp_frame_pool *pool,
        enum AVPixelFormat format,
        uint32_t pixel_buffer_format,
        int width,
        int height,
        ffmpegkmp_pool_entry **found) {
    ffmpegkmp_pool_entry *entry = NULL;
    int index;
    int result;
    for (index = 0; index < pool->count; index++) {
        ffmpegkmp_pool_entry *candidate = &pool->entries[index];
        if (candidate->format == format && candidate->pixel_buffer_format == pixel_buffer_format &&
                candidate->width == width && candidate->height == height) {
            entry = candidate;
            break;
        }
    }
    if (!entry) {
        if (pool->count < FFMPEGKMP_POOL_ENTRIES) {
            entry = &pool->entries[pool->count++];
        } else {
            entry = &pool->entries[0];
            for (index = 1; index < pool->count; index++) {
                if (pool->entries[index].used < entry->used)
                    entry = &pool->entries[index];
            }
            ffmpegkmp_pool_entry_free(entry);
        }
        entry->format = format;
        entry->pixel_buffer_format = pixel_buffer_format;
        entry->width = width;
        entry->height = height;
#if defined(FFMPEGKMP_PIXEL_BUFFER)
        result = pixel_buffer_format ? ffmpegkmp_pool_entry_init_pixel_buffers(entry, pool->capacity)
                : ffmpegkmp_pool_entry_init_buffers(entry, pool->capacity > 0);
#else
        result = ffmpegkmp_pool_entry_init_buffers(entry, pool->capacity > 0);
#endif
        if (result < 0) {
            ffmpegkmp_pool_entry_free(entry);
            /* Keep the entries packed: the freed one takes the last one's place. */
            *entry = pool->entries[--pool->count];
            memset(&pool->entries[pool->count], 0, sizeof(pool->entries[pool->count]));
            return result;
        }
    }
    entry->used = ++pool->clock;
    *found = entry;
    return 0;
}

/* Gives `frame` a buffer of `entry`; AVERROR(EAGAIN) when a ring has none free. Called with the pool locked. */
static int ffmpegkmp_pool_take(ffmpegkmp_frame_pool *pool, ffmpegkmp_pool_entry *entry, AVFrame *frame) {
    int plane;
    int slot;
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    if (entry->pixel_buffer_format) {
        CVPixelBufferRef buffer = NULL;
        CVReturn status = CVPixelBufferPoolCreatePixelBufferWithAuxAttributes(
                kCFAllocatorDefault, entry->pixel_buffers, entry->threshold, &buffer);
        if (status == kCVReturnWouldExceedAllocationThreshold)
            return AVERROR(EAGAIN);
        if (status != kCVReturnSuccess)
            return AVERROR(ENOMEM);
        if (!(frame->buf[0] = av_buffer_create((uint8_t *)buffer, sizeof(buffer), ffmpegkmp_release_pixel_buffer,
                pool->capacity ? pool : NULL, 0))) {
            CVPixelBufferRelease(buffer);
            return AVERROR(ENOMEM);
        }
        if (pool->capacity)
            pool->references++;
        frame->format = AV_PIX_FMT_VIDEOTOOLBOX;
        frame->data[3] = (uint8_t *)buffer;
        return 0;
    }
#endif
    if (!pool->capacity) {
        if (!(frame->buf[0] = av_buffer_pool_get(entry->buffers)))
            return AVERROR(ENOMEM);
    } else {
        for (slot = 0; slot < entry->made && (entry->out & (1u << slot)); slot++)
            continue;
        if (slot == entry->made) {
            if (slot == pool->capacity)
                return AVERROR(EAGAIN);
            if (!(entry->ring[slot] = av_malloc(entry->size)))
                return AVERROR(ENOMEM);
            ffmpegkmp_frame_count_allocation(entry->size);
            entry->made++;
        }
        if (!(frame->buf[0] = av_buffer_create(entry->ring[slot], entry->size, ffmpegkmp_release_ring_buffer, pool, 0)))
            return AVERROR(ENOMEM);
        entry->out |= 1u << slot;
        pool->references++;
    }
    for (plane = 0; plane < 4 && entry->linesize[plane]; plane++) {
        frame->data[plane] = frame->buf[0]->data + entry->offset[plane];
        frame->linesize[plane] = entry->linesize[plane];
    }
    frame->format = entry->format;
    return 0;
}

/* Waits, with the pool locked, until one of its frames comes back or the wall clock passes `until_us`. */
static void ffmpegkmp_pool_wait(ffmpegkmp_frame_pool *pool, int64_t until_us) {
    struct timespec until;
    until.tv_sec = (time_t)(until_us / 1000000);
    until.tv_nsec = (long)(until_us % 1000000) * 1000;
    pthread_cond_timedwait(&pool->returned, &pool->lock, &until);
}

/*
 * Gives `frame` pooled memory of `format` at this size; its colour is left to
 * the caller. A full ring fails with AVERROR(EAGAIN), after `grace_us` of
 * waiting for a frame to come back.
 */
static int ffmpegkmp_pool_fill(ffmpegkmp_frame_pool *pool, AVFrame *frame, enum AVPixelFormat format, int width,
        int height, int full_range, int64_t grace_us) {
    ffmpegkmp_pool_entry *entry;
    uint32_t pixel_buffer_format = 0;
    /* The wall clock, which pthread_cond_timedwait measures by default. */
    const int64_t until_us = av_gettime() + grace_us;
    int result;
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    pixel_buffer_format = ffmpegkmp_pixel_buffer_type(format, full_range);
#else
    (void)full_range;
#endif
    pthread_mutex_lock(&pool->lock);
    for (;;) {
        /* Looked up again after each wait, which unlocks the pool. */
        result = ffmpegkmp_pool_find(pool, format, pixel_buffer_format, width, height, &entry);
        if (result >= 0)
            result = ffmpegkmp_pool_take(pool, entry, frame);
        if (result != AVERROR(EAGAIN) || av_gettime() >= until_us)
            break;
        ffmpegkmp_pool_wait(pool, until_us);
    }
    pthread_mutex_unlock(&pool->lock);
    frame->width = width;
    frame->height = height;
    return result;
}

ffmpegkmp_frame_pool *ffmpegkmp_frame_pool_alloc(int32_t capacity) {
    ffmpegkmp_frame_pool *pool;
    if (capacity < 0 || capacity > FFMPEGKMP_POOL_MAX_CAPACITY || !(pool = calloc(1, sizeof(*pool))))
        return NULL;
    if (pthread_mutex_init(&pool->lock, NULL) != 0) {
        free(pool);
        return NULL;
    }
    if (pthread_cond_init(&pool->returned, NULL) != 0) {
        pthread_mutex_destroy(&pool->lock);
        free(pool);
        return NULL;
    }
    pool->capacity = capacity;
    pool->references = 1;
    return pool;
}

void ffmpegkmp_frame_pool_free(ffmpegkmp_frame_pool *pool) {
    int index;
    int last;
    if (!pool || pool == &ffmpegkmp_shared_pool)
        return;
    pthread_mutex_lock(&pool->lock);
    for (index = 0; index < pool->count; index++)
        ffmpegkmp_pool_entry_free(&pool->entries[index]);
    pool->count = 0;
    last = --pool->references == 0;
    pthread_mutex_unlock(&pool->lock);
    if (last)
        ffmpegkmp_pool_destroy(pool);
}

static int ffmpegkmp_pool_frame(
        ffmpegkmp_frame_pool *pool,
        const ffmpegkmp_frame_format *format,
        int32_t width,
        int32_t height,
        int64_t grace_us,
        ffmpegkmp_frame **frame) {
    ffmpegkmp_frame *created;
    int filled;
    int result;
    if (!frame)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    *frame = NULL;
    if (!ffmpegkmp_frame_format_is_valid(format) || width <= 0 || height <= 0)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if (!(created = ffmpegkmp_frame_alloc_handle()))
        return AVERROR(ENOMEM);
    result = ffmpegkmp_pool_fill(pool ? pool : &ffmpegkmp_shared_pool, created->frame,
            ffmpegkmp_layout_pixel_format(format->layout), width, height, format->range == FFMPEGKMP_RANGE_FULL,
            grace_us);
    if (result < 0) {
        ffmpegkmp_frame_unref(created);
        return result;
    }
    /* The colour, keeping the pixel format the pool gave: a CVPixelBuffer's is VideoToolbox's. */
    filled = created->frame->format;
    ffmpegkmp_apply_format(created->frame, format);
    created->frame->format = filled;
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    if (ffmpegkmp_frame_is_pixel_buffer(created->frame) &&
            (result = av_vt_pixbuf_set_attachments(NULL, (CVPixelBufferRef)created->frame->data[3], created->frame)) < 0) {
        ffmpegkmp_frame_unref(created);
        return result;
    }
#endif
    *frame = created;
    return 0;
}

int ffmpegkmp_frame_pool_get(
        ffmpegkmp_frame_pool *pool,
        const ffmpegkmp_frame_format *format,
        int32_t width,
        int32_t height,
        ffmpegkmp_frame **frame) {
    return ffmpegkmp_pool_frame(pool, format, width, height, 0, frame);
}

int ffmpegkmp_frame_pool_take(
        ffmpegkmp_frame_pool *pool,
        const ffmpegkmp_frame_format *format,
        int32_t width,
        int32_t height,
        ffmpegkmp_frame **frame) {
    const int result = ffmpegkmp_pool_frame(pool, format, width, height, FFMPEGKMP_RING_GRACE_US, frame);
    return result == AVERROR(EAGAIN) ? FFPLAYKMP_ERROR_RING_FULL : result;
}

/* Conversion */

static int ffmpegkmp_is_sdr_curve(enum AVColorTransferCharacteristic transfer) {
    return transfer != AVCOL_TRC_SMPTE2084 && transfer != AVCOL_TRC_ARIB_STD_B67 && transfer != AVCOL_TRC_LINEAR;
}

/* Primaries the converter distinguishes; it takes the others as BT.709. */
static enum AVColorPrimaries ffmpegkmp_frame_primaries(enum AVColorPrimaries primaries) {
    return primaries == AVCOL_PRI_BT2020 || primaries == AVCOL_PRI_SMPTE432 ? primaries : AVCOL_PRI_BT709;
}

/*
 * The colour `target` converts into from `source`. SDR curves convert as they
 * are shown, without a curve change, so BT.709-coded video becomes sRGB as the
 * bridge has always shown it, and primaries the converter takes as the
 * target's convert as the target's.
 */
static void ffmpegkmp_conversion_colour(AVFrame *target, const AVFrame *source) {
    if (ffmpegkmp_is_sdr_curve(target->color_trc) && ffmpegkmp_is_sdr_curve(source->color_trc))
        target->color_trc = source->color_trc;
    if (ffmpegkmp_frame_primaries(source->color_primaries) == target->color_primaries)
        target->color_primaries = source->color_primaries;
}

int ffmpegkmp_frame_convert_with(
        ffmpegkmp_converter *converter,
        ffmpegkmp_frame *destination,
        const AVFrame *source,
        int keep_colour) {
    AVFrame *real = destination->frame;
    AVFrame *target = real;
    AVFrame input;
    AVFrame *download = NULL;
    AVFrame **cached = &download;
    enum AVColorPrimaries primaries;
    enum AVColorTransferCharacteristic transfer;
    int locked;
    int result;
    if (keep_colour) {
        real->color_primaries = source->color_primaries;
        real->color_trc = source->color_trc;
        real->colorspace = source->colorspace;
        real->color_range = source->color_range;
        real->chroma_location = source->chroma_location;
    }
    primaries = real->color_primaries;
    transfer = real->color_trc;
    if ((result = ffmpegkmp_source_view(source, &input, cached, &locked)) < 0)
        return result;
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    if (ffmpegkmp_frame_is_pixel_buffer(real)) {
        /* The locked buffer's planes, in a frame of its own: the converter replaces its side data. */
        if (!(target = av_frame_alloc())) {
            result = AVERROR(ENOMEM);
        } else if ((result = ffmpegkmp_pixel_buffer_planes(real, target, 0)) < 0) {
            av_frame_free(&target);
        } else {
            target->width = real->width;
            target->height = real->height;
            target->color_primaries = real->color_primaries;
            target->color_trc = real->color_trc;
            target->colorspace = real->colorspace;
            target->color_range = real->color_range;
            target->chroma_location = real->chroma_location;
        }
    }
#endif
    if (result >= 0) {
        ffmpegkmp_conversion_colour(target, &input);
        result = ffmpegkmp_frame_convert(converter, target, &input);
        target->color_primaries = primaries;
        target->color_trc = transfer;
    }
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    if (target && target != real) {
        CVPixelBufferUnlockBaseAddress((CVPixelBufferRef)real->data[3], 0);
        if (result >= 0) {
            av_frame_side_data_free(&real->side_data, &real->nb_side_data);
            av_dict_free(&real->metadata);
            result = av_frame_copy_props(real, target);
        }
        /* Frees the copied side data; the frame owns no buffers. */
        av_frame_free(&target);
        if (result >= 0)
            result = av_vt_pixbuf_set_attachments(NULL, (CVPixelBufferRef)real->data[3], real);
    }
#endif
    ffmpegkmp_end_source_view(source, locked);
    av_frame_free(&download);
    return result;
}

static pthread_mutex_t ffmpegkmp_converters_lock = PTHREAD_MUTEX_INITIALIZER;
static ffmpegkmp_converter *ffmpegkmp_idle_converters[FFMPEGKMP_IDLE_CONVERTERS];
static int ffmpegkmp_idle_converter_count;

static ffmpegkmp_converter *ffmpegkmp_take_converter(void) {
    ffmpegkmp_converter *converter = NULL;
    pthread_mutex_lock(&ffmpegkmp_converters_lock);
    if (ffmpegkmp_idle_converter_count > 0)
        converter = ffmpegkmp_idle_converters[--ffmpegkmp_idle_converter_count];
    pthread_mutex_unlock(&ffmpegkmp_converters_lock);
    return converter ? converter : ffmpegkmp_converter_alloc(0);
}

static void ffmpegkmp_return_converter(ffmpegkmp_converter *converter) {
    pthread_mutex_lock(&ffmpegkmp_converters_lock);
    if (ffmpegkmp_idle_converter_count < FFMPEGKMP_IDLE_CONVERTERS) {
        ffmpegkmp_idle_converters[ffmpegkmp_idle_converter_count++] = converter;
        converter = NULL;
    }
    pthread_mutex_unlock(&ffmpegkmp_converters_lock);
    ffmpegkmp_converter_free(&converter);
}

int ffmpegkmp_frame_convert_into(ffmpegkmp_frame *destination, const ffmpegkmp_frame *source) {
    ffmpegkmp_frame *handle = (ffmpegkmp_frame *)source;
    ffmpegkmp_converter *converter;
    AVFrame *input;
    int result;
    if (!destination || !source || destination == source)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if (!(converter = ffmpegkmp_take_converter()))
        return AVERROR(ENOMEM);
    /* A hardware source is downloaded once per handle, as its maps are. */
    pthread_mutex_lock(&handle->lock);
    input = handle->frame;
    if (ffplaykmp_is_hardware_frame(input) && !ffmpegkmp_frame_is_pixel_buffer(input)) {
        AVFrame view;
        int locked;
        result = ffmpegkmp_source_view(input, &view, &handle->download, &locked);
        input = result >= 0 ? handle->download : NULL;
    } else {
        result = 0;
    }
    pthread_mutex_unlock(&handle->lock);
    if (result >= 0)
        result = ffmpegkmp_frame_convert_with(converter, destination, input, 0);
    ffmpegkmp_return_converter(converter);
    return result;
}

int ffmpegkmp_frame_convert_to(
        const ffmpegkmp_frame *source,
        const ffmpegkmp_frame_format *format,
        ffmpegkmp_frame **destination) {
    int result;
    if (!source || !destination)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if ((result = ffmpegkmp_frame_pool_get(NULL, format, source->frame->width, source->frame->height,
            destination)) < 0)
        return result;
    if ((result = ffmpegkmp_frame_convert_into(*destination, source)) < 0) {
        ffmpegkmp_frame_unref(*destination);
        *destination = NULL;
    }
    return result;
}

ffmpegkmp_frame *ffmpegkmp_frame_wrap(
        const ffmpegkmp_frame_format *format,
        int32_t width,
        int32_t height,
        uint8_t *pixels,
        int32_t row_bytes) {
    ffmpegkmp_frame *frame;
    if (!ffmpegkmp_frame_format_is_valid(format) || !ffmpegkmp_is_rgb_format(ffmpegkmp_layout_pixel_format(format->layout)) ||
            width <= 0 || height <= 0 || !pixels ||
            row_bytes < av_image_get_linesize(ffmpegkmp_layout_pixel_format(format->layout), width, 0))
        return NULL;
    if (!(frame = ffmpegkmp_frame_alloc_handle()))
        return NULL;
    ffmpegkmp_apply_format(frame->frame, format);
    frame->frame->width = width;
    frame->frame->height = height;
    frame->frame->data[0] = pixels;
    frame->frame->linesize[0] = row_bytes;
    return frame;
}
