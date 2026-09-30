// SPDX-License-Identifier: LGPL-2.1-or-later
#include "ffmpegkmp_decoder.h"
#include "ffplaykmp_core.h"

#include <errno.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#if defined(__ANDROID__)
#include <libavcodec/mediacodec.h>
#endif
#include <libavformat/avformat.h>
#include <libavutil/display.h>
#include <libavutil/time.h>
#if defined(FFMPEGKMP_PIXEL_BUFFER)
#include <CoreVideo/CoreVideo.h>
#include <libavutil/hwcontext_videotoolbox.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>
#endif

#define FFMPEGKMP_NANOSECONDS ((AVRational){ 1, 1000000000 })
/* How far before a target a re-seek starts when the first seek landed after it. */
#define FFMPEGKMP_SEEK_BACKOFF_NS 1000000000LL
#define FFMPEGKMP_SEEK_ATTEMPTS 8
/* How long MediaCodec waits on a codec that neither takes input nor outputs a frame before
 * returning EAGAIN, so the decode loop gets to check its deadline and abort (FFmpegKMP's FFmpeg
 * overlay). */
#define FFMPEGKMP_MEDIACODEC_WAIT_US "50000"

struct ffmpegkmp_video_decoder {
    char *url;
    int32_t output;
    int32_t preference;
    ffplaykmp_io_host host;
    ffplaykmp_input input;
    ffplaykmp_video_codec codec;
    ffplaykmp_converter converter;
    ffplaykmp_snapshot info;
    int64_t timeout_us;
    int stream_index;
    AVRational time_base;
    /* Stream time of position zero, and the hold time of a last frame without a duration. */
    int64_t origin;
    int64_t default_duration;
    int64_t stream_end;
    AVPacket *packet;
    AVFrame *current;
    AVFrame *next;
    AVFrame *download;
    int has_current;
    int has_next;
    /* The decoder returned EOF: no frame follows `next` (or `current` without one). */
    int drained;
    int demux_ended;
    /* `packet` was read but the decoder could not take it yet. */
    int packet_pending;
    int64_t first_pts;
    int64_t last_pts;
    /* After a seek, frames before the first packet's pts are leading pictures of an
     * open GOP whose references were never decoded. */
    int64_t seek_floor;
    int awaiting_seek_packet;
    uint64_t serial;
    uint64_t presented_serial;
    ffplaykmp_converted_frame converted;
    int started;
    atomic_bool aborted;
    /* av_gettime_relative() bound of the running call, 0 for none. */
    _Atomic int64_t deadline;
    atomic_bool timed_out;
#if defined(__ANDROID__)
    JavaVM *android_vm;
    jobject android_surface;
#endif
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    /* PIXEL_BUFFER output of software frames: the current frame's copy and its pool. */
    CVPixelBufferRef upload;
    CVPixelBufferPoolRef upload_pool;
    int upload_width;
    int upload_height;
    OSType upload_format;
    struct SwsContext *upload_scaler;
#endif
};

/* The AVIOInterruptCB of the input, also polled by the decode loop. */
static int ffmpegkmp_decoder_interrupted(void *opaque) {
    ffmpegkmp_video_decoder *decoder = opaque;
    int64_t deadline;
    if (atomic_load(&decoder->aborted) || atomic_load(&decoder->timed_out))
        return 1;
    deadline = atomic_load(&decoder->deadline);
    if (deadline <= 0 || av_gettime_relative() < deadline)
        return 0;
    atomic_store(&decoder->timed_out, 1);
    return 1;
}

static void ffmpegkmp_arm_deadline(ffmpegkmp_video_decoder *decoder) {
    atomic_store(&decoder->deadline,
            decoder->timeout_us > 0 ? av_gettime_relative() + decoder->timeout_us : 0);
}

static int ffmpegkmp_begin_call(ffmpegkmp_video_decoder *decoder) {
    if (atomic_load(&decoder->timed_out))
        return FFPLAYKMP_ERROR_TIMED_OUT;
    if (atomic_load(&decoder->aborted))
        return AVERROR_EXIT;
    ffmpegkmp_arm_deadline(decoder);
    return 0;
}

/* A call that failed at its deadline leaves the decoder mid-decode, so it stays timed out. */
static int ffmpegkmp_end_call(ffmpegkmp_video_decoder *decoder, int result) {
    atomic_store(&decoder->deadline, 0);
    if (result >= 0)
        atomic_store(&decoder->timed_out, 0);
    return result < 0 && atomic_load(&decoder->timed_out) ? FFPLAYKMP_ERROR_TIMED_OUT : result;
}

void ffmpegkmp_decoded_frame_init(ffmpegkmp_decoded_frame *frame) {
    if (!frame)
        return;
    memset(frame, 0, sizeof(*frame));
    frame->size = sizeof(*frame);
}

/* Drops a decoded frame; MediaCodec output buffers go back to the codec unrendered. */
static void ffmpegkmp_discard(AVFrame *frame) {
#if defined(__ANDROID__)
    if (frame->format == AV_PIX_FMT_MEDIACODEC && frame->data[3])
        av_mediacodec_release_buffer((AVMediaCodecBuffer *)frame->data[3], 0);
#endif
    av_frame_unref(frame);
}

static void ffmpegkmp_clear_frames(ffmpegkmp_video_decoder *decoder) {
    if (decoder->current)
        ffmpegkmp_discard(decoder->current);
    if (decoder->next)
        ffmpegkmp_discard(decoder->next);
    if (decoder->packet)
        av_packet_unref(decoder->packet);
    decoder->packet_pending = 0;
    decoder->has_current = 0;
    decoder->has_next = 0;
    decoder->drained = 0;
    decoder->demux_ended = 0;
    decoder->last_pts = AV_NOPTS_VALUE;
}

static int ffmpegkmp_read_error(ffmpegkmp_video_decoder *decoder, int result) {
    if (ffmpegkmp_decoder_interrupted(decoder))
        return AVERROR_EXIT;
    if (result == AVERROR_EOF ||
            (decoder->input.format->pb && avio_feof(decoder->input.format->pb)))
        return AVERROR_EOF;
    return result;
}

/*
 * Sends the pending packet. One the decoder cannot take yet stays pending: a
 * stalled MediaCodec returns EAGAIN from both send and receive.
 */
static int ffmpegkmp_send_pending(ffmpegkmp_video_decoder *decoder) {
    int result = avcodec_send_packet(decoder->codec.decoder, decoder->packet);
    if (result == AVERROR(EAGAIN))
        return 0;
    av_packet_unref(decoder->packet);
    decoder->packet_pending = 0;
    /* Like ffplay, a damaged packet is dropped; later packets may recover. A hardware
     * decoder rejecting packets before its first frame fails instead, so AUTO can
     * fall back without reading the whole input. */
    return result < 0 && decoder->codec.hardware && decoder->first_pts == AV_NOPTS_VALUE ? result : 0;
}

/*
 * Decodes the next frame in presentation order into `frame`, with its pts in
 * stream time. Returns AVERROR_EOF once the stream is drained.
 */
static int ffmpegkmp_decode_next(ffmpegkmp_video_decoder *decoder, AVFrame *frame) {
    AVCodecContext *codec = decoder->codec.decoder;
    int result;
    for (;;) {
        if (ffmpegkmp_decoder_interrupted(decoder))
            return AVERROR_EXIT;
        result = avcodec_receive_frame(codec, frame);
        if (result >= 0) {
            int64_t pts = frame->best_effort_timestamp != AV_NOPTS_VALUE
                    ? frame->best_effort_timestamp
                    : frame->pts;
            if (pts == AV_NOPTS_VALUE) {
                pts = decoder->last_pts == AV_NOPTS_VALUE
                        ? decoder->origin
                        : decoder->last_pts + decoder->default_duration;
            }
            if ((decoder->last_pts != AV_NOPTS_VALUE && pts <= decoder->last_pts) ||
                    (decoder->seek_floor != AV_NOPTS_VALUE && pts < decoder->seek_floor)) {
                ffmpegkmp_discard(frame);
                continue;
            }
            if (decoder->preference == FFPLAYKMP_DECODER_REQUIRE_HARDWARE &&
                    !ffplaykmp_is_hardware_frame(frame)) {
                ffmpegkmp_discard(frame);
                return FFPLAYKMP_ERROR_UNSUPPORTED;
            }
            frame->pts = pts;
            decoder->last_pts = pts;
            if (decoder->first_pts == AV_NOPTS_VALUE)
                decoder->first_pts = pts;
            return 0;
        }
        if (result == AVERROR_EOF) {
            decoder->drained = 1;
            return AVERROR_EOF;
        }
        if (result != AVERROR(EAGAIN))
            return result;
        if (decoder->packet_pending) {
            if ((result = ffmpegkmp_send_pending(decoder)) < 0)
                return result;
            continue;
        }
        if (decoder->demux_ended) {
            decoder->drained = 1;
            return AVERROR_EOF;
        }
        result = av_read_frame(decoder->input.format, decoder->packet);
        if (result < 0) {
            result = ffmpegkmp_read_error(decoder, result);
            if (result != AVERROR_EOF)
                return result;
            decoder->demux_ended = 1;
            avcodec_send_packet(codec, NULL);
            continue;
        }
        if (decoder->packet->stream_index == decoder->stream_index) {
            if (decoder->awaiting_seek_packet) {
                decoder->awaiting_seek_packet = 0;
                decoder->seek_floor = decoder->packet->pts;
            }
            decoder->packet_pending = 1;
            if ((result = ffmpegkmp_send_pending(decoder)) < 0)
                return result;
            continue;
        }
        av_packet_unref(decoder->packet);
    }
}

/* Fills `next` with the frame after `current`, or marks the stream drained. */
static int ffmpegkmp_fill_next(ffmpegkmp_video_decoder *decoder) {
    int result = ffmpegkmp_decode_next(decoder, decoder->next);
    decoder->has_next = result == 0;
    return result == AVERROR_EOF ? 0 : result;
}

/* Decodes `current` and its lookahead from wherever the demuxer stands. */
static int ffmpegkmp_prime(ffmpegkmp_video_decoder *decoder) {
    int result = ffmpegkmp_decode_next(decoder, decoder->current);
    if (result == AVERROR_EOF)
        return AVERROR_INVALIDDATA;
    if (result < 0)
        return result;
    decoder->has_current = 1;
    decoder->serial++;
    return ffmpegkmp_fill_next(decoder);
}

static int ffmpegkmp_shift(ffmpegkmp_video_decoder *decoder) {
    AVFrame *previous = decoder->current;
    ffmpegkmp_discard(previous);
    decoder->current = decoder->next;
    decoder->next = previous;
    decoder->has_next = 0;
    decoder->serial++;
    return ffmpegkmp_fill_next(decoder);
}

static int64_t ffmpegkmp_current_end(const ffmpegkmp_video_decoder *decoder) {
    const AVFrame *current = decoder->current;
    if (decoder->has_next)
        return decoder->next->pts;
    /* Containers store durations in decode order, so with B-frames a frame's own
     * duration can belong to another frame; the stream end is exact for the last one. */
    if (decoder->stream_end != AV_NOPTS_VALUE && decoder->stream_end > current->pts)
        return decoder->stream_end;
    if (current->duration > 0)
        return current->pts + current->duration;
    return current->pts + decoder->default_duration;
}

static int ffmpegkmp_covers(const ffmpegkmp_video_decoder *decoder, int64_t target) {
    const int64_t pts = decoder->current->pts;
    if (target < pts)
        return pts == decoder->first_pts;
    return !decoder->has_next || target < ffmpegkmp_current_end(decoder);
}

static int ffmpegkmp_advance_to(ffmpegkmp_video_decoder *decoder, int64_t target) {
    int result = 0;
    while (result >= 0 && decoder->has_next && decoder->next->pts <= target)
        result = ffmpegkmp_shift(decoder);
    return result;
}

static int ffmpegkmp_seek_to(ffmpegkmp_video_decoder *decoder, int64_t target) {
    const int64_t backoff = av_rescale_q(
            FFMPEGKMP_SEEK_BACKOFF_NS, FFMPEGKMP_NANOSECONDS, decoder->time_base);
    int64_t seek_target = target;
    int attempt;
    int result = 0;
    for (attempt = 0; attempt < FFMPEGKMP_SEEK_ATTEMPTS; attempt++) {
        /* The keyframe at or before the target, never after it. */
        result = avformat_seek_file(
                decoder->input.format, decoder->stream_index,
                INT64_MIN, seek_target, seek_target, AVSEEK_FLAG_BACKWARD);
        if (result < 0) {
            result = avformat_seek_file(
                    decoder->input.format, decoder->stream_index,
                    INT64_MIN, seek_target, INT64_MAX, AVSEEK_FLAG_BACKWARD);
        }
        if (result < 0)
            return ffmpegkmp_decoder_interrupted(decoder) ? AVERROR_EXIT : result;
        avcodec_flush_buffers(decoder->codec.decoder);
        ffmpegkmp_clear_frames(decoder);
        decoder->seek_floor = AV_NOPTS_VALUE;
        decoder->awaiting_seek_packet = 1;
        result = ffmpegkmp_prime(decoder);
        if (result < 0)
            return result;
        /* Some demuxers index keyframes by decode time and land after the target's GOP. */
        if (decoder->current->pts <= target || decoder->current->pts <= decoder->first_pts ||
                seek_target <= decoder->origin)
            break;
        seek_target -= backoff << attempt;
        if (seek_target < decoder->origin)
            seek_target = decoder->origin;
    }
    return ffmpegkmp_advance_to(decoder, target);
}

/* Whether the index has a keyframe after the lookahead and at or before the target. */
static int ffmpegkmp_seek_is_shorter(const ffmpegkmp_video_decoder *decoder, int64_t target) {
    AVStream *stream = decoder->input.format->streams[decoder->stream_index];
    const AVIndexEntry *entry;
    int index;
    if (!decoder->has_next)
        return 0;
    index = av_index_search_timestamp(stream, target, AVSEEK_FLAG_BACKWARD);
    if (index < 0)
        return 0;
    entry = avformat_index_get_entry(stream, index);
    return entry && entry->timestamp > decoder->next->pts;
}

static int64_t ffmpegkmp_stream_time(const ffmpegkmp_video_decoder *decoder, int64_t position_ns) {
    int64_t offset = av_rescale_q_rnd(
            position_ns, FFMPEGKMP_NANOSECONDS, decoder->time_base,
            AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX);
    return decoder->origin > 0 && offset > INT64_MAX - decoder->origin ? INT64_MAX : decoder->origin + offset;
}

static int ffmpegkmp_position(ffmpegkmp_video_decoder *decoder, int64_t position_ns, int seek) {
    int64_t target;
    if (!decoder || position_ns < 0)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if (!decoder->started || !decoder->has_current)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    target = ffmpegkmp_stream_time(decoder, position_ns);
    if (ffmpegkmp_covers(decoder, target))
        return 0;
    if (target < decoder->current->pts || seek || ffmpegkmp_seek_is_shorter(decoder, target))
        return ffmpegkmp_seek_to(decoder, target);
    return ffmpegkmp_advance_to(decoder, target);
}

#if defined(FFMPEGKMP_PIXEL_BUFFER)
static void ffmpegkmp_set_int(CFMutableDictionaryRef dictionary, CFStringRef key, int32_t value) {
    CFNumberRef number = CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt32Type, &value);
    if (number) {
        CFDictionarySetValue(dictionary, key, number);
        CFRelease(number);
    }
}

/*
 * The pool software frames of this size and format are copied into. A
 * CVPixelBufferPool, not FFmpeg's hwcontext pool: it only recycles a buffer
 * once every retain on it is released, so frames handed out stay intact.
 */
static int ffmpegkmp_upload_pool(ffmpegkmp_video_decoder *decoder, int width, int height, OSType format) {
    CFMutableDictionaryRef attributes;
    CFDictionaryRef surface;
    CVReturn status;
    if (decoder->upload_pool && decoder->upload_width == width && decoder->upload_height == height &&
            decoder->upload_format == format)
        return 0;
    CVPixelBufferPoolRelease(decoder->upload_pool);
    decoder->upload_pool = NULL;
    attributes = CFDictionaryCreateMutable(
            kCFAllocatorDefault, 5, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    surface = CFDictionaryCreate(
            kCFAllocatorDefault, NULL, NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    if (!attributes || !surface) {
        if (attributes)
            CFRelease(attributes);
        if (surface)
            CFRelease(surface);
        return AVERROR(ENOMEM);
    }
    ffmpegkmp_set_int(attributes, kCVPixelBufferPixelFormatTypeKey, (int32_t)format);
    ffmpegkmp_set_int(attributes, kCVPixelBufferWidthKey, width);
    ffmpegkmp_set_int(attributes, kCVPixelBufferHeightKey, height);
    CFDictionarySetValue(attributes, kCVPixelBufferIOSurfacePropertiesKey, surface);
    CFDictionarySetValue(attributes, kCVPixelBufferMetalCompatibilityKey, kCFBooleanTrue);
    status = CVPixelBufferPoolCreate(kCFAllocatorDefault, NULL, attributes, &decoder->upload_pool);
    CFRelease(surface);
    CFRelease(attributes);
    if (status != kCVReturnSuccess) {
        decoder->upload_pool = NULL;
        return AVERROR_EXTERNAL;
    }
    decoder->upload_width = width;
    decoder->upload_height = height;
    decoder->upload_format = format;
    return 0;
}

/*
 * Copies a software frame into a pooled IOSurface-backed CVPixelBuffer: NV12
 * for 8-bit sources and P010 for deeper ones, in the source's range, with the
 * frame's colour attached.
 */
static int ffmpegkmp_upload_pixel_buffer(ffmpegkmp_video_decoder *decoder, const AVFrame *source) {
    const AVPixFmtDescriptor *descriptor = av_pix_fmt_desc_get(source->format);
    const enum AVPixelFormat format = descriptor && descriptor->comp[0].depth > 8
            ? AV_PIX_FMT_P010
            : AV_PIX_FMT_NV12;
    const int full_range = source->color_range == AVCOL_RANGE_JPEG;
    uint8_t *planes[4] = { NULL };
    int strides[4] = { 0 };
    CVPixelBufferRef buffer = NULL;
    size_t plane;
    int result;
    if (!descriptor || (descriptor->flags & (AV_PIX_FMT_FLAG_HWACCEL | AV_PIX_FMT_FLAG_RGB)))
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    if ((result = ffmpegkmp_upload_pool(decoder, source->width, source->height,
            av_map_videotoolbox_format_from_pixfmt2(format, full_range))) < 0)
        return result;
    decoder->upload_scaler = sws_getCachedContext(
            decoder->upload_scaler,
            source->width, source->height, source->format,
            source->width, source->height, format,
            SWS_BILINEAR, NULL, NULL, NULL);
    if (!decoder->upload_scaler)
        return AVERROR(EINVAL);
    /* YUV to YUV: keep the range instead of swscale's default of converting to limited. */
    sws_setColorspaceDetails(
            decoder->upload_scaler,
            sws_getCoefficients(SWS_CS_DEFAULT), full_range,
            sws_getCoefficients(SWS_CS_DEFAULT), full_range,
            0, 1 << 16, 1 << 16);
    if (CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, decoder->upload_pool, &buffer) != kCVReturnSuccess)
        return AVERROR(ENOMEM);
    if (CVPixelBufferLockBaseAddress(buffer, 0) != kCVReturnSuccess) {
        CVPixelBufferRelease(buffer);
        return AVERROR_EXTERNAL;
    }
    for (plane = 0; plane < CVPixelBufferGetPlaneCount(buffer) && plane < 4; plane++) {
        planes[plane] = CVPixelBufferGetBaseAddressOfPlane(buffer, plane);
        strides[plane] = (int)CVPixelBufferGetBytesPerRowOfPlane(buffer, plane);
    }
    result = sws_scale(
            decoder->upload_scaler,
            (const uint8_t *const *)source->data,
            source->linesize,
            0,
            source->height,
            planes,
            strides) == source->height ? 0 : AVERROR(EINVAL);
    CVPixelBufferUnlockBaseAddress(buffer, 0);
    if (result >= 0)
        result = av_vt_pixbuf_set_attachments(NULL, buffer, source);
    if (result < 0) {
        CVPixelBufferRelease(buffer);
        return result;
    }
    CVPixelBufferRelease(decoder->upload);
    decoder->upload = buffer;
    return 0;
}
#endif

static int ffmpegkmp_present(ffmpegkmp_video_decoder *decoder) {
    AVFrame *current = decoder->current;
    const AVFrame *source = current;
    int result;
    if (decoder->presented_serial == decoder->serial)
        return 0;
#if defined(__ANDROID__)
    if (current->format == AV_PIX_FMT_MEDIACODEC) {
        AVMediaCodecBuffer *buffer = (AVMediaCodecBuffer *)current->data[3];
        int64_t time_ns = av_rescale_q(current->pts - decoder->origin, decoder->time_base,
                FFMPEGKMP_NANOSECONDS);
        if (!buffer)
            return AVERROR_INVALIDDATA;
        if (av_mediacodec_render_buffer_at_time(buffer, time_ns) < 0)
            return AVERROR_EXTERNAL;
        decoder->converted.pixels = NULL;
        decoder->presented_serial = decoder->serial;
        return 0;
    }
#endif
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    if (decoder->output == FFMPEGKMP_VIDEO_OUTPUT_PIXEL_BUFFER) {
        if (current->format != AV_PIX_FMT_VIDEOTOOLBOX &&
                (result = ffmpegkmp_upload_pixel_buffer(decoder, current)) < 0)
            return result;
        decoder->converted.pixels = NULL;
        decoder->presented_serial = decoder->serial;
        return 0;
    }
#endif
    if (ffplaykmp_is_hardware_frame(current)) {
        av_frame_unref(decoder->download);
        if ((result = ffplaykmp_download_frame(decoder->download, current)) < 0)
            return result;
        source = decoder->download;
    }
    result = decoder->output == FFMPEGKMP_VIDEO_OUTPUT_LINEAR_F16
            ? ffplaykmp_convert_linear_f16(&decoder->converter, source, &decoder->converted)
            : ffplaykmp_convert_rgba(&decoder->converter, source, 1, &decoder->converted);
    if (result < 0)
        return result;
    decoder->presented_serial = decoder->serial;
    return 0;
}

static void ffmpegkmp_describe(const ffmpegkmp_video_decoder *decoder, ffmpegkmp_decoded_frame *frame) {
    const AVFrame *current = decoder->current;
    const AVFrameSideData *display_matrix =
            av_frame_get_side_data(current, AV_FRAME_DATA_DISPLAYMATRIX);
    AVRational aspect = av_guess_sample_aspect_ratio(
            decoder->input.format,
            decoder->input.format->streams[decoder->stream_index],
            (AVFrame *)current);
    ffmpegkmp_decoded_frame_init(frame);
    frame->serial = decoder->serial;
    frame->pts_ns = av_rescale_q(current->pts - decoder->origin, decoder->time_base,
            FFMPEGKMP_NANOSECONDS);
    frame->duration_ns = av_rescale_q(ffmpegkmp_current_end(decoder) - current->pts,
            decoder->time_base, FFMPEGKMP_NANOSECONDS);
    frame->width = current->width;
    frame->height = current->height;
    frame->sample_aspect_ratio_num = aspect.num;
    frame->sample_aspect_ratio_den = aspect.den;
    frame->rotation_degrees = display_matrix && display_matrix->size >= 9 * sizeof(int32_t)
            ? av_display_rotation_get((const int32_t *)display_matrix->data)
            : decoder->info.rotation_degrees;
    frame->hardware = ffplaykmp_is_hardware_frame(current);
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    if (decoder->output == FFMPEGKMP_VIDEO_OUTPUT_PIXEL_BUFFER) {
        CVPixelBufferRef buffer = current->format == AV_PIX_FMT_VIDEOTOOLBOX
                ? (CVPixelBufferRef)current->data[3]
                : decoder->upload;
        frame->pixel_buffer = buffer;
        frame->pixel_format = CVPixelBufferGetPixelFormatType(buffer);
        frame->io_surface = CVPixelBufferGetIOSurface(buffer) != NULL;
        frame->width = (int32_t)CVPixelBufferGetWidth(buffer);
        frame->height = (int32_t)CVPixelBufferGetHeight(buffer);
        frame->color_primaries = current->color_primaries;
        frame->color_transfer = current->color_trc;
        frame->color_space = current->colorspace;
        frame->color_range = current->color_range;
    }
#endif
    if (decoder->converted.pixels) {
        frame->pixels = decoder->converted.pixels;
        frame->pixels_size = (uint64_t)decoder->converted.size;
        frame->width = decoder->converted.width;
        frame->height = decoder->converted.height;
        frame->stride = decoder->converted.stride;
    }
}

static int ffmpegkmp_open_stream(ffmpegkmp_video_decoder *decoder) {
    const AVCodec *codec;
    AVStream *stream;
    int64_t start_us;
    int result = ffplaykmp_open_input(&decoder->host, decoder->url, &decoder->input);
    if (result < 0)
        return result;
    decoder->stream_index = ffplaykmp_find_video_stream(decoder->input.format, &codec);
    if (decoder->stream_index < 0)
        return decoder->stream_index;
    stream = decoder->input.format->streams[decoder->stream_index];
    decoder->time_base = stream->time_base;
    start_us = ffplaykmp_media_start_us(decoder->input.format);
    decoder->origin = av_rescale_q_rnd(
            start_us, AV_TIME_BASE_Q, stream->time_base, AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX);
    /* The container start is this stream's start rounded to microseconds: prefer the exact value. */
    if (stream->start_time != AV_NOPTS_VALUE &&
            llabs(av_rescale_q(stream->start_time, stream->time_base, AV_TIME_BASE_Q) - start_us) <= 1)
        decoder->origin = stream->start_time;
    if (stream->avg_frame_rate.num > 0 && stream->avg_frame_rate.den > 0)
        decoder->default_duration = av_rescale_q(1, av_inv_q(stream->avg_frame_rate), stream->time_base);
    else if (stream->r_frame_rate.num > 0 && stream->r_frame_rate.den > 0)
        decoder->default_duration = av_rescale_q(1, av_inv_q(stream->r_frame_rate), stream->time_base);
    else
        decoder->default_duration = av_rescale_q(40000, AV_TIME_BASE_Q, stream->time_base);
    if (decoder->default_duration <= 0)
        decoder->default_duration = 1;
    decoder->stream_end = stream->start_time != AV_NOPTS_VALUE && stream->duration > 0
            ? stream->start_time + stream->duration
            : AV_NOPTS_VALUE;
    decoder->info.video_width = stream->codecpar->width;
    decoder->info.video_height = stream->codecpar->height;
    ffplaykmp_read_stream_metadata(&decoder->info, stream);
    decoder->info.duration_us = decoder->input.format->duration == AV_NOPTS_VALUE
            ? -1
            : decoder->input.format->duration;
    return 0;
}

ffmpegkmp_video_decoder *ffmpegkmp_video_decoder_create(
        const char *input,
        int32_t output,
        int32_t decoder_preference,
        int64_t timeout_us,
        ffplaykmp_io_callback io_callback,
        void *io_opaque,
        int32_t *error) {
    ffmpegkmp_video_decoder *decoder;
    if (error)
        *error = 0;
    if (!input || !*input || timeout_us < 0 ||
            output < FFMPEGKMP_VIDEO_OUTPUT_RGBA8 || output > FFMPEGKMP_VIDEO_OUTPUT_PIXEL_BUFFER ||
            decoder_preference < FFPLAYKMP_DECODER_AUTO ||
            decoder_preference > FFPLAYKMP_DECODER_SOFTWARE) {
        if (error)
            *error = FFPLAYKMP_ERROR_INVALID_ARGUMENT;
        return NULL;
    }
#if !defined(__ANDROID__)
    if (output == FFMPEGKMP_VIDEO_OUTPUT_SURFACE) {
        if (error)
            *error = FFPLAYKMP_ERROR_UNSUPPORTED;
        return NULL;
    }
#endif
#if !defined(FFMPEGKMP_PIXEL_BUFFER)
    if (output == FFMPEGKMP_VIDEO_OUTPUT_PIXEL_BUFFER) {
        if (error)
            *error = FFPLAYKMP_ERROR_UNSUPPORTED;
        return NULL;
    }
#endif
    decoder = calloc(1, sizeof(*decoder));
    if (!decoder || !(decoder->url = strdup(input))) {
        free(decoder);
        if (error)
            *error = AVERROR(ENOMEM);
        return NULL;
    }
    atomic_init(&decoder->aborted, 0);
    atomic_init(&decoder->deadline, 0);
    atomic_init(&decoder->timed_out, 0);
    decoder->timeout_us = timeout_us;
    decoder->output = output;
    decoder->preference = decoder_preference;
    decoder->host.callback = io_callback;
    decoder->host.opaque = io_opaque;
    decoder->host.interrupted = ffmpegkmp_decoder_interrupted;
    decoder->host.interrupt_opaque = decoder;
    decoder->first_pts = AV_NOPTS_VALUE;
    decoder->last_pts = AV_NOPTS_VALUE;
    decoder->seek_floor = AV_NOPTS_VALUE;
    ffplaykmp_snapshot_init(&decoder->info);
    decoder->packet = av_packet_alloc();
    decoder->current = av_frame_alloc();
    decoder->next = av_frame_alloc();
    decoder->download = av_frame_alloc();
    if (!decoder->packet || !decoder->current || !decoder->next || !decoder->download) {
        ffmpegkmp_video_decoder_destroy(decoder);
        if (error)
            *error = AVERROR(ENOMEM);
        return NULL;
    }
    return decoder;
}

#if defined(__ANDROID__)
int ffmpegkmp_video_decoder_set_android_surface(
        JNIEnv *env,
        jclass owner,
        jobject surface,
        ffmpegkmp_video_decoder *decoder) {
    jobject retained = NULL;
    JavaVM *vm = NULL;
    int result;
    (void)owner;
    if (!env || !decoder || !surface)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if (decoder->started)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    if ((result = ffplaykmp_android_retain_global(env, surface, &vm, &retained)) < 0)
        return result;
    ffplaykmp_android_release_global(decoder->android_vm, decoder->android_surface);
    decoder->android_vm = vm;
    decoder->android_surface = retained;
    return 0;
}
#endif

static int ffmpegkmp_open_codec(ffmpegkmp_video_decoder *decoder, int preference) {
    void *surface = NULL;
    AVDictionary *options = NULL;
    int hardware = preference != FFPLAYKMP_DECODER_SOFTWARE;
    int result;
#if defined(__ANDROID__)
    /* MediaCodec only runs Surface-bound here; CPU outputs decode in software. */
    surface = decoder->android_surface;
    hardware = hardware && decoder->output == FFMPEGKMP_VIDEO_OUTPUT_SURFACE && surface;
    if (hardware &&
            (result = av_dict_set(&options, "ffmpegkmp_wait_timeout", FFMPEGKMP_MEDIACODEC_WAIT_US, 0)) < 0)
        return result;
#endif
    result = ffplaykmp_video_codec_open(
            &decoder->codec, decoder->input.format, decoder->stream_index, hardware,
            preference == FFPLAYKMP_DECODER_REQUIRE_HARDWARE, surface, &options);
    av_dict_free(&options);
    return result;
}

int ffmpegkmp_video_decoder_start(ffmpegkmp_video_decoder *decoder) {
    int result;
    if (!decoder)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if (decoder->started || decoder->input.format)
        return FFPLAYKMP_ERROR_INVALID_STATE;
#if defined(__ANDROID__)
    if (decoder->output == FFMPEGKMP_VIDEO_OUTPUT_SURFACE && !decoder->android_surface)
        return FFPLAYKMP_ERROR_INVALID_STATE;
#endif
    if ((result = ffmpegkmp_begin_call(decoder)) < 0)
        return result;
    result = ffmpegkmp_open_stream(decoder);
    if (result >= 0)
        result = ffmpegkmp_open_codec(decoder, decoder->preference);
    if (result >= 0)
        result = ffmpegkmp_prime(decoder);
    if (result < 0 && decoder->codec.hardware && decoder->first_pts == AV_NOPTS_VALUE &&
            decoder->preference == FFPLAYKMP_DECODER_AUTO && !atomic_load(&decoder->aborted)) {
        /* Rewind by reopening: the failed attempt may have consumed packets. A hardware
         * decoder that stalled gets a software one with a deadline of its own. */
        ffmpegkmp_clear_frames(decoder);
        ffplaykmp_video_codec_close(&decoder->codec);
        ffplaykmp_close_input(&decoder->input);
        atomic_store(&decoder->timed_out, 0);
        ffmpegkmp_arm_deadline(decoder);
        result = ffmpegkmp_open_stream(decoder);
        if (result >= 0)
            result = ffmpegkmp_open_codec(decoder, FFPLAYKMP_DECODER_SOFTWARE);
        if (result >= 0)
            result = ffmpegkmp_prime(decoder);
    }
    if ((result = ffmpegkmp_end_call(decoder, result)) < 0)
        return result;
    decoder->started = 1;
    ffplaykmp_read_frame_metadata(&decoder->info, decoder->current);
    decoder->info.active_decoder = ffplaykmp_is_hardware_frame(decoder->current)
            ? FFPLAYKMP_DECODER_HARDWARE
            : FFPLAYKMP_DECODER_SOFTWARE_ACTIVE;
    return 0;
}

int ffmpegkmp_video_decoder_get_info(
        const ffmpegkmp_video_decoder *decoder,
        ffplaykmp_snapshot *info) {
    if (!decoder || !info || info->size < sizeof(*info))
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    *info = decoder->info;
    return 0;
}

int ffmpegkmp_video_decoder_seek(ffmpegkmp_video_decoder *decoder, int64_t position_ns) {
    int result;
    if (!decoder)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if ((result = ffmpegkmp_begin_call(decoder)) < 0)
        return result;
    return ffmpegkmp_end_call(decoder, ffmpegkmp_position(decoder, position_ns, 1));
}

int ffmpegkmp_video_decoder_frame_at(
        ffmpegkmp_video_decoder *decoder,
        int64_t position_ns,
        ffmpegkmp_decoded_frame *frame) {
    int result;
    if (!decoder || !frame || frame->size < sizeof(*frame))
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if ((result = ffmpegkmp_begin_call(decoder)) < 0)
        return result;
    result = ffmpegkmp_position(decoder, position_ns, 0);
    if (result >= 0)
        result = ffmpegkmp_present(decoder);
    if ((result = ffmpegkmp_end_call(decoder, result)) < 0)
        return result;
    ffmpegkmp_describe(decoder, frame);
    return 0;
}

void ffmpegkmp_video_decoder_abort(ffmpegkmp_video_decoder *decoder) {
    if (decoder)
        atomic_store(&decoder->aborted, 1);
}

int64_t ffmpegkmp_video_decoder_time_left(const ffmpegkmp_video_decoder *decoder) {
    int64_t deadline = decoder ? atomic_load(&decoder->deadline) : 0;
    return deadline > 0 ? deadline - av_gettime_relative() : 0;
}

void ffmpegkmp_video_decoder_destroy(ffmpegkmp_video_decoder *decoder) {
    if (!decoder)
        return;
    ffmpegkmp_clear_frames(decoder);
    av_frame_free(&decoder->current);
    av_frame_free(&decoder->next);
    av_frame_free(&decoder->download);
    av_packet_free(&decoder->packet);
    ffplaykmp_video_codec_close(&decoder->codec);
    ffplaykmp_close_input(&decoder->input);
    ffplaykmp_converter_free(&decoder->converter);
#if defined(__ANDROID__)
    ffplaykmp_android_release_global(decoder->android_vm, decoder->android_surface);
#endif
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    CVPixelBufferRelease(decoder->upload);
    CVPixelBufferPoolRelease(decoder->upload_pool);
    sws_freeContext(decoder->upload_scaler);
#endif
    free(decoder->url);
    free(decoder);
}
