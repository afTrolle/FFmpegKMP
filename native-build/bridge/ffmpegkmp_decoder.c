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
#include <libavutil/mem.h>
#include <libavutil/time.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>

#define FFMPEGKMP_NANOSECONDS ((AVRational){ 1, 1000000000 })
/* How far before a target a re-seek starts when the first seek landed after it. */
#define FFMPEGKMP_SEEK_BACKOFF_NS 1000000000LL
#define FFMPEGKMP_SEEK_ATTEMPTS 8
/* How long MediaCodec waits on a codec that neither takes input nor outputs a frame before
 * returning EAGAIN, so the decode loop gets to check its deadline and abort (FFmpegKMP's FFmpeg
 * overlay). */
#define FFMPEGKMP_MEDIACODEC_WAIT_US "50000"
/* Frames a decoder's pool holds per layout and size: the caller's, the one decoded ahead, and one so the
 * caller can keep the previous frame while taking the next. A fourth waits for one of them to close. */
#define FFMPEGKMP_DECODER_RING 3

struct ffmpegkmp_video_decoder {
    char *url;
    int32_t output;
    int32_t preference;
    int32_t threads;
    ffplaykmp_io_host host;
    ffplaykmp_input input;
    ffplaykmp_video_codec codec;
    ffmpegkmp_converter *converter;
    /* MEMORY output: the frames' format, when has_memory_format, and their pool. */
    ffmpegkmp_frame_format memory_format;
    int has_memory_format;
    /* The memory format's frame size; 0 for the source's. */
    int32_t width;
    int32_t height;
    ffmpegkmp_frame_pool *pool;
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
    /* The presented frame's pixels, NULL when it was rendered to the Surface. */
    ffmpegkmp_frame *presented;
    int started;
    atomic_bool aborted;
    /* Fails the running call only; begin_call clears it. */
    atomic_bool interrupted;
    /* An interrupted call may have stopped mid-seek, so the next one seeks first. */
    int resync;
    /* av_gettime_relative() bound of the running call, 0 for none. */
    _Atomic int64_t deadline;
    atomic_bool timed_out;
#if defined(__ANDROID__)
    JavaVM *android_vm;
    jobject android_surface;
#endif
};

/* The AVIOInterruptCB of the input, also polled by the decode loop. */
static int ffmpegkmp_decoder_interrupted(void *opaque) {
    ffmpegkmp_video_decoder *decoder = opaque;
    int64_t deadline;
    if (atomic_load(&decoder->aborted) || atomic_load(&decoder->timed_out) ||
            atomic_load(&decoder->interrupted))
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
    atomic_store(&decoder->interrupted, 0);
    ffmpegkmp_arm_deadline(decoder);
    return 0;
}

/*
 * A call that failed at its deadline leaves the decoder mid-decode, so it stays
 * timed out. One that was interrupted makes the next call seek first instead.
 */
static int ffmpegkmp_end_call(ffmpegkmp_video_decoder *decoder, int result) {
    atomic_store(&decoder->deadline, 0);
    if (result >= 0) {
        atomic_store(&decoder->timed_out, 0);
        return result;
    }
    if (atomic_load(&decoder->timed_out))
        return FFPLAYKMP_ERROR_TIMED_OUT;
    if (atomic_load(&decoder->interrupted))
        decoder->resync = 1;
    return result;
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
                    !ffplaykmp_video_codec_frame_is_hardware(&decoder->codec, frame)) {
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
    if (!decoder->started)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    target = ffmpegkmp_stream_time(decoder, position_ns);
    if (decoder->resync) {
        AVIOContext *pb = decoder->input.format->pb;
        int result;
        /* A read the interrupt failed stays in the AVIOContext and would fail the seek's reads;
         * clear it as ffplay clears eof_reached before seeking. */
        if (pb) {
            pb->error = 0;
            pb->eof_reached = 0;
        }
        result = ffmpegkmp_seek_to(decoder, target);
        if (result >= 0)
            decoder->resync = 0;
        return result;
    }
    if (!decoder->has_current)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    if (ffmpegkmp_covers(decoder, target))
        return 0;
    if (target < decoder->current->pts || seek || ffmpegkmp_seek_is_shorter(decoder, target))
        return ffmpegkmp_seek_to(decoder, target);
    return ffmpegkmp_advance_to(decoder, target);
}

/*
 * A decoded frame as decoded, for MEMORY output without a format. The Apple
 * runtimes hand out CVPixelBuffers only: software frames are copied, in their
 * own colour, into pooled NV12 or P010 buffers, and RGB ones into BGRA.
 */
static int ffmpegkmp_as_decoded(ffmpegkmp_video_decoder *decoder, const AVFrame *current, ffmpegkmp_frame **frame) {
    AVFrame *download;
    int result;
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    if (!ffmpegkmp_frame_is_pixel_buffer(current) && !ffplaykmp_is_hardware_frame(current)) {
        const AVPixFmtDescriptor *descriptor = av_pix_fmt_desc_get(current->format);
        const int rgb = descriptor && (descriptor->flags & AV_PIX_FMT_FLAG_RGB);
        ffmpegkmp_frame_format format;
        if (!descriptor)
            return FFPLAYKMP_ERROR_UNSUPPORTED;
        ffmpegkmp_frame_format_init(&format);
        /* The layout and the range, which picks the CoreVideo format; the colour follows the source. */
        format.layout = rgb ? FFMPEGKMP_LAYOUT_BGRA8
                : descriptor->comp[0].depth > 8 ? FFMPEGKMP_LAYOUT_P010 : FFMPEGKMP_LAYOUT_NV12;
        format.matrix = rgb ? FFMPEGKMP_MATRIX_RGB : FFMPEGKMP_MATRIX_BT709;
        format.range = rgb || current->color_range == AVCOL_RANGE_JPEG || current->format == AV_PIX_FMT_YUVJ420P
                ? FFMPEGKMP_RANGE_FULL
                : FFMPEGKMP_RANGE_LIMITED;
        if ((result = ffmpegkmp_frame_pool_take(decoder->pool, &format, current->width, current->height,
                ffmpegkmp_decoder_interrupted, decoder, frame)) < 0)
            return result;
        if ((result = ffmpegkmp_frame_convert_with(decoder->converter, *frame, current, 1)) < 0) {
            ffmpegkmp_frame_unref(*frame);
            *frame = NULL;
        }
        return result;
    }
#endif
    if (!ffplaykmp_is_hardware_frame(current) || ffmpegkmp_frame_is_pixel_buffer(current)) {
        *frame = ffmpegkmp_frame_from_av(current);
        return *frame ? 0 : AVERROR(ENOMEM);
    }
    if (!(download = av_frame_alloc()))
        return AVERROR(ENOMEM);
    result = ffplaykmp_download_frame(download, current);
    if (result >= 0) {
        ffmpegkmp_frame_count_allocation(
                (size_t)av_image_get_buffer_size(download->format, download->width, download->height, 1));
        if (!(*frame = ffmpegkmp_frame_from_av(download)))
            result = AVERROR(ENOMEM);
    }
    av_frame_free(&download);
    return result;
}

/* Renders a MediaCodec frame into the Surface, stamped with its pts; other frames stay in memory. */
static int ffmpegkmp_present(ffmpegkmp_video_decoder *decoder) {
#if defined(__ANDROID__)
    AVFrame *current = decoder->current;
    if (current->format == AV_PIX_FMT_MEDIACODEC) {
        AVMediaCodecBuffer *buffer = (AVMediaCodecBuffer *)current->data[3];
        int64_t time_ns = av_rescale_q(current->pts - decoder->origin, decoder->time_base,
                FFMPEGKMP_NANOSECONDS);
        if (!buffer)
            return AVERROR_INVALIDDATA;
        if (av_mediacodec_render_buffer_at_time(buffer, time_ns) < 0)
            return AVERROR_EXTERNAL;
    }
#else
    (void)decoder;
#endif
    return 0;
}

/*
 * Makes the frame the current one hands out, once per decoded frame: converted
 * into the memory format, or as decoded. A frame rendered to the Surface has
 * none.
 */
static int ffmpegkmp_hand_out(ffmpegkmp_video_decoder *decoder) {
    AVFrame *current = decoder->current;
    ffmpegkmp_frame *presented = NULL;
    int result;
    /* The previous frame first: when the caller has closed it, its pooled memory takes this one. */
    ffmpegkmp_frame_unref(decoder->presented);
    decoder->presented = NULL;
    decoder->presented_serial = 0;
#if defined(__ANDROID__)
    if (current->format == AV_PIX_FMT_MEDIACODEC) {
        decoder->presented_serial = decoder->serial;
        return 0;
    }
#endif
    if (decoder->has_memory_format) {
        /* Converted here, on the decoder's thread, so it overlaps with the caller's work. */
        /* From the decoder's ring: while the caller holds all of it, this waits for a frame to close, until the
         * call's deadline, interrupt or abort. */
        result = ffmpegkmp_frame_pool_take(decoder->pool, &decoder->memory_format,
                decoder->width > 0 ? decoder->width : current->width,
                decoder->height > 0 ? decoder->height : current->height,
                ffmpegkmp_decoder_interrupted, decoder, &presented);
        if (result >= 0)
            result = ffmpegkmp_frame_convert_with(decoder->converter, presented, current, 0);
    } else {
        result = ffmpegkmp_as_decoded(decoder, current, &presented);
    }
    if (result < 0) {
        ffmpegkmp_frame_unref(presented);
        return result;
    }
    decoder->presented = presented;
    decoder->presented_serial = decoder->serial;
    return 0;
}

static int ffmpegkmp_describe(const ffmpegkmp_video_decoder *decoder, ffmpegkmp_decoded_frame *frame) {
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
    frame->width = decoder->width > 0 ? decoder->width : current->width;
    frame->height = decoder->height > 0 ? decoder->height : current->height;
    aspect = ffmpegkmp_scaled_aspect(aspect, current->width, current->height, frame->width, frame->height);
    frame->sample_aspect_ratio_num = aspect.num;
    frame->sample_aspect_ratio_den = aspect.den;
    frame->rotation_degrees = display_matrix && display_matrix->size >= 9 * sizeof(int32_t)
            ? av_display_rotation_get((const int32_t *)display_matrix->data)
            : decoder->info.rotation_degrees;
    frame->hardware = ffplaykmp_video_codec_frame_is_hardware(&decoder->codec, current);
    if (decoder->presented && !(frame->frame = ffmpegkmp_frame_ref(decoder->presented)))
        return AVERROR(ENOMEM);
    return 0;
}

static int ffmpegkmp_open_stream(ffmpegkmp_video_decoder *decoder) {
    const AVCodec *codec;
    AVStream *stream;
    int result = ffplaykmp_open_input(&decoder->host, decoder->url, &decoder->input);
    if (result < 0)
        return result;
    decoder->stream_index = ffplaykmp_find_video_stream(decoder->input.format, &codec);
    if (decoder->stream_index < 0)
        return decoder->stream_index;
    stream = decoder->input.format->streams[decoder->stream_index];
    decoder->time_base = stream->time_base;
    ffplaykmp_stream_timing(decoder->input.format, stream,
            &decoder->origin, &decoder->default_duration, &decoder->stream_end);
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
        const ffmpegkmp_frame_format *memory_format,
        int32_t width,
        int32_t height,
        int32_t decoder_preference,
        int32_t decoder_threads,
        int64_t timeout_us,
        ffplaykmp_io_callback io_callback,
        void *io_opaque,
        int32_t *error) {
    ffmpegkmp_video_decoder *decoder;
    if (error)
        *error = 0;
    if (!input || !*input || timeout_us < 0 || decoder_threads < 0 ||
            output < FFMPEGKMP_VIDEO_OUTPUT_MEMORY || output > FFMPEGKMP_VIDEO_OUTPUT_SURFACE ||
            (memory_format && (output != FFMPEGKMP_VIDEO_OUTPUT_MEMORY ||
                    !ffmpegkmp_frame_format_is_valid(memory_format))) ||
            width < 0 || height < 0 || (width > 0) != (height > 0) || (width > 0 && !memory_format) ||
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
    decoder = calloc(1, sizeof(*decoder));
    if (!decoder || !(decoder->url = av_strdup(input))) {
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
    if (memory_format) {
        decoder->memory_format = *memory_format;
        decoder->memory_format.size = sizeof(decoder->memory_format);
        decoder->has_memory_format = 1;
        decoder->width = width;
        decoder->height = height;
    }
    decoder->preference = decoder_preference;
    decoder->threads = decoder_threads;
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
    /* One video's threads are a budget: the converter works within the decoder's count. */
    decoder->converter = ffmpegkmp_converter_alloc(ffplaykmp_decoder_thread_count(decoder_threads));
    decoder->pool = ffmpegkmp_frame_pool_alloc(FFMPEGKMP_DECODER_RING);
    if (!decoder->packet || !decoder->current || !decoder->next || !decoder->converter || !decoder->pool) {
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
    /* MediaCodec renders into the Surface, or for MEMORY output decodes into memory (ByteBuffer
     * mode). There FFmpeg copies 8-bit frames only, and a codec converting deeper ones to 8 bits
     * would drop their precision, so deeper sources decode in software. */
    if (decoder->output == FFMPEGKMP_VIDEO_OUTPUT_SURFACE)
        surface = decoder->android_surface;
    else if (decoder->info.bit_depth > 8)
        hardware = 0;
    if (hardware &&
            (result = av_dict_set(&options, "ffmpegkmp_wait_timeout", FFMPEGKMP_MEDIACODEC_WAIT_US, 0)) < 0)
        return result;
#endif
    result = ffplaykmp_video_codec_open(
            &decoder->codec, decoder->input.format, decoder->stream_index, hardware,
            preference == FFPLAYKMP_DECODER_REQUIRE_HARDWARE, decoder->threads, FF_THREAD_SLICE, surface, &options);
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
    decoder->info.active_decoder = ffplaykmp_video_codec_frame_is_hardware(&decoder->codec, decoder->current)
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
    if (result >= 0 && decoder->presented_serial != decoder->serial) {
        result = ffmpegkmp_present(decoder);
        if (result >= 0)
            result = ffmpegkmp_hand_out(decoder);
    }
    if ((result = ffmpegkmp_end_call(decoder, result)) < 0)
        return result;
    return ffmpegkmp_describe(decoder, frame);
}

void ffmpegkmp_video_decoder_interrupt(ffmpegkmp_video_decoder *decoder) {
    if (decoder)
        atomic_store(&decoder->interrupted, 1);
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
    ffmpegkmp_frame_unref(decoder->presented);
    /* Frames handed out keep their memory; the pool goes with the last of them. */
    ffmpegkmp_frame_pool_free(decoder->pool);
    av_packet_free(&decoder->packet);
    ffplaykmp_video_codec_close(&decoder->codec);
    ffplaykmp_close_input(&decoder->input);
    ffmpegkmp_converter_free(&decoder->converter);
#if defined(__ANDROID__)
    ffplaykmp_android_release_global(decoder->android_vm, decoder->android_surface);
#endif
    av_free(decoder->url);
    free(decoder);
}
