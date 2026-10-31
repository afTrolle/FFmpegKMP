// SPDX-License-Identifier: LGPL-2.1-or-later
#include "ffplaykmp_player.h"
#include "ffplaykmp_core.h"
#if defined(__EMSCRIPTEN__)
#include "ffmpegkmp_bridge.h"
#endif

#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#if defined(__ANDROID__)
#include <libavcodec/mediacodec.h>
#endif
#include <libavformat/avformat.h>
#include <libavutil/pixdesc.h>
#include <libavutil/time.h>

/*
 * What decoding the player's input holds. The input is opened and probed once,
 * by prepare, and kept until stop, destroy, reset_cancel or the next prepare.
 * The codec is opened by the first run that needs it and kept for the runs
 * after it while it suits them: its key is whether the output wanted a
 * hardware decoder, the Surface it renders to and the input it decodes. Only
 * the session thread opens, uses and closes it. The packet and frames are the
 * player's, allocated once with it. A run holds session_mutex throughout, so
 * one runs at a time, and so does anything that opens or closes the input.
 */
typedef struct ffplaykmp_session {
    ffplaykmp_input input;
    /* Counts the inputs opened, so that a codec can tell the input it was opened for. */
    uint32_t input_generation;
    /* The input has not been read past its probe, so a run from the start needs no seek. */
    int at_start;
    int video_stream;
    const AVStream *stream;
    int64_t media_start_us;
    ffplaykmp_video_codec codec;
    int codec_open;
    int codec_hardware_wanted;
    void *codec_surface;
    uint32_t codec_input;
    /* AUTO: hardware failed on this source, so its codecs open in software until a new Surface. */
    int hardware_failed;
    /*
     * The decoder goes on from the last frame it gave out and the demuxer from
     * the last packet the decoder took, so a run forward needs no seek. While
     * `showing`, that frame was presented and starts at `shown_us`: it is the
     * frame on screen up to the next one.
     */
    int in_sync;
    int showing;
    int64_t shown_us;
    /* `packet` was read and the decoder has not taken it yet. */
    int packet_pending;
    /* The input ended and the decoder was told so: it gives out the frames it has left, and takes no packet. */
    int draining;
    AVPacket *packet;
    AVFrame *frame;
    /* The latest frame before the target, as ffplaykmp_present_ready_frames keeps it. */
    AVFrame *held;
    /* A frame the decoder gave out and no run has presented: the next in order. */
    AVFrame *next;
    /*
     * The frame on screen while `showing`, so that a preview at a target it
     * covers presents it again; none for a MediaCodec frame, which rendering
     * gave back to its codec.
     */
    AVFrame *shown;
} ffplaykmp_session;

typedef enum ffplaykmp_job_kind {
    FFPLAYKMP_JOB_NONE = 0,
    /*
     * Presents the frame at the start position, decoded or, when it is the
     * one on screen, again; a MediaCodec frame on screen stays as it is. The
     * poster waits for it.
     */
    FFPLAYKMP_JOB_PREVIEW,
    /* A preview for a new output, which needs a frame presented to it whatever is on screen. */
    FFPLAYKMP_JOB_OUTPUT_PREVIEW,
    /* Plays from the snapshot's position until the end, an abort or a failure. */
    FFPLAYKMP_JOB_PLAY,
    /* Closes a codec that renders to a Surface; the poster waits for it. */
    FFPLAYKMP_JOB_RELEASE,
    FFPLAYKMP_JOB_EXIT,
} ffplaykmp_job_kind;

/* A preview's poster waits on its ticket, on its own stack, for the job's result. */
typedef struct ffplaykmp_job_ticket {
    int result;
    int done;
} ffplaykmp_job_ticket;

struct ffplaykmp_player {
    ffplaykmp_configuration configuration;
    ffplaykmp_state_callback callback;
    void *opaque;
    ffplaykmp_io_callback io_callback;
    void *io_opaque;
    ffplaykmp_video_frame_callback video_frame_callback;
    void *video_frame_opaque;
    ffplaykmp_platform_video_frame_callback platform_video_frame_callback;
    void *platform_video_frame_opaque;
    char *input;
    uint32_t source_flags;
    ffplaykmp_snapshot snapshot;
    pthread_mutex_t mutex;
    pthread_mutex_t session_mutex;
    ffplaykmp_session session;
    /* Desktop hardware frames are downloaded into its two buffers while one is free (ffmpegkmp_frame_set_download_pool). */
    ffmpegkmp_frame_pool *download_pool;
    /*
     * The session thread runs every decode, previews included, so a codec kept
     * between runs is only ever used from one thread (MediaCodec requires it).
     * It takes one job at a time from `job`; job_mutex and job_cond guard and
     * signal the job fields both ways.
     */
    pthread_mutex_t job_mutex;
    pthread_cond_t job_cond;
    pthread_t session_thread;
    int session_thread_running;
    ffplaykmp_job_kind job;
    int64_t job_start_us;
    ffplaykmp_job_ticket *job_ticket;
    ffplaykmp_job_kind job_running;
    atomic_bool cancelled;
    /* Ends the running play job; the session thread clears it once that job has ended. */
    atomic_bool worker_abort;
    int play_when_ready;
    int has_output;
    /* Host-reported media time (normally the audible audio position) and when
     * it was reported. While valid it replaces the wall clock for scheduling;
     * between reports it is extrapolated. Guarded by mutex. */
    int master_clock_valid;
    int64_t master_clock_media_us;
    int64_t master_clock_stamp_us;
#if defined(__ANDROID__)
    JavaVM *android_vm;
    jobject android_surface;
#endif
};

static void ffplaykmp_publish(ffplaykmp_player *player);

static int ffplaykmp_is_aborted(const ffplaykmp_player *player) {
    return atomic_load(&player->cancelled) || atomic_load(&player->worker_abort);
}

static int ffplaykmp_interrupt(void *opaque) {
    const ffplaykmp_player *player = opaque;
    return player ? ffplaykmp_is_aborted(player) : 1;
}

static ffplaykmp_io_host ffplaykmp_player_io_host(ffplaykmp_player *player) {
    ffplaykmp_io_host host;
    pthread_mutex_lock(&player->mutex);
    host.callback = player->io_callback;
    host.opaque = player->io_opaque;
    pthread_mutex_unlock(&player->mutex);
    host.interrupted = ffplaykmp_interrupt;
    host.interrupt_opaque = player;
    return host;
}

static int ffplaykmp_player_open_input(
        ffplaykmp_player *player,
        const char *url,
        ffplaykmp_input *input) {
    ffplaykmp_io_host host = ffplaykmp_player_io_host(player);
    return ffplaykmp_open_input(&host, url, input);
}

#if defined(__ANDROID__)
static void ffplaykmp_release_android_surface(ffplaykmp_player *player) {
    jobject surface;
    pthread_mutex_lock(&player->mutex);
    surface = player->android_surface;
    player->android_surface = NULL;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_android_release_global(player->android_vm, surface);
}
#endif

static void ffplaykmp_emit_video_frame(
        ffplaykmp_player *player,
        const AVFrame *decoded,
        int64_t presentation_time_us) {
    ffplaykmp_video_frame frame;
    ffplaykmp_video_frame_callback callback;
    void *callback_opaque;
    uint32_t queue_serial;
    int can_upload;
    ffmpegkmp_frame *handle;
    pthread_mutex_lock(&player->mutex);
    callback = player->video_frame_callback;
    callback_opaque = player->video_frame_opaque;
    queue_serial = player->snapshot.queue_serial;
    can_upload = player->has_output &&
            (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD) &&
            !(player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH);
    pthread_mutex_unlock(&player->mutex);
    if (!callback || !can_upload || ffplaykmp_is_aborted(player))
        return;
    /*
     * The decoded frame itself, not a copy: whoever keeps it converts it once, into its own memory. A
     * hardware frame is downloaded then, once, into one of the player's buffers.
     */
    if (!(handle = ffmpegkmp_frame_from_av(decoded)))
        return;
    if (ffplaykmp_is_hardware_frame(decoded))
        ffmpegkmp_frame_set_download_pool(handle, player->download_pool);
    memset(&frame, 0, sizeof(frame));
    frame.size = sizeof(frame);
    frame.frame = handle;
    frame.width = decoded->width;
    frame.height = decoded->height;
    frame.presentation_time_us = presentation_time_us;
    frame.queue_serial = queue_serial;
    callback(callback_opaque, &frame);
    ffmpegkmp_frame_unref(handle);
}

#if defined(__APPLE__)
static int ffplaykmp_emit_platform_video_frame(
        ffplaykmp_player *player,
        const AVFrame *decoded,
        int64_t presentation_time_us) {
    ffplaykmp_platform_video_frame frame;
    ffplaykmp_platform_video_frame_callback callback;
    void *callback_opaque;
    uint32_t queue_serial;
    int can_import;
    pthread_mutex_lock(&player->mutex);
    callback = player->platform_video_frame_callback;
    callback_opaque = player->platform_video_frame_opaque;
    queue_serial = player->snapshot.queue_serial;
    can_import = player->has_output &&
            (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT);
    pthread_mutex_unlock(&player->mutex);
    if (!callback || !can_import || !decoded->data[3] || ffplaykmp_is_aborted(player))
        return AVERROR(ENOSYS);
    memset(&frame, 0, sizeof(frame));
    frame.size = sizeof(frame);
    frame.kind = FFPLAYKMP_PLATFORM_FRAME_CV_PIXEL_BUFFER;
    frame.handle = decoded->data[3];
    frame.width = decoded->width;
    frame.height = decoded->height;
    frame.presentation_time_us = presentation_time_us;
    frame.queue_serial = queue_serial;
    return callback(callback_opaque, &frame) ? 0 : AVERROR(EAGAIN);
}
#endif

/* Media time now according to the host's master clock; returns 0 when unset. */
static int ffplaykmp_master_clock_now(ffplaykmp_player *player, int64_t now, int64_t *media_us) {
    int valid;
    pthread_mutex_lock(&player->mutex);
    valid = player->master_clock_valid;
    if (valid)
        *media_us = player->master_clock_media_us + (now - player->master_clock_stamp_us);
    pthread_mutex_unlock(&player->mutex);
    return valid;
}

static void ffplaykmp_invalidate_master_clock(ffplaykmp_player *player) {
    pthread_mutex_lock(&player->mutex);
    player->master_clock_valid = 0;
    pthread_mutex_unlock(&player->mutex);
}

static int ffplaykmp_wait_until(
        ffplaykmp_player *player,
        int64_t presentation_time_us,
        int64_t drop_threshold_us,
        int64_t *clock_origin_us) {
    int64_t now;
    int64_t remaining;
    int64_t master_us;
    if (*clock_origin_us == AV_NOPTS_VALUE)
        *clock_origin_us = av_gettime_relative() - presentation_time_us;
    while (!ffplaykmp_is_aborted(player)) {
        now = av_gettime_relative();
        if (ffplaykmp_master_clock_now(player, now, &master_us)) {
            /* Follow the audio clock, and keep the wall-clock origin aligned
             * with it so losing the master (audio ended) causes no jump. */
            *clock_origin_us = now - master_us;
        }
        remaining = *clock_origin_us + presentation_time_us - now;
        if (remaining <= 0)
            return -remaining > drop_threshold_us ? 1 : 0;
        av_usleep((unsigned int)(remaining > 10000 ? 10000 : remaining));
    }
    return AVERROR_EXIT;
}

/* A frame's media time; one without a timestamp is taken to be at `fallback_us`. */
static int64_t ffplaykmp_frame_time_us(
        const AVFrame *frame,
        AVRational time_base,
        int64_t media_start_us,
        int64_t fallback_us) {
    return frame->best_effort_timestamp == AV_NOPTS_VALUE
            ? fallback_us
            : av_rescale_q(frame->best_effort_timestamp, time_base, AV_TIME_BASE_Q) - media_start_us;
}

static int ffplaykmp_present_decoded_frame(
        ffplaykmp_player *player,
        const AVFrame *frame,
        AVRational time_base,
        int64_t presentation_time_us,
        int continuous,
        int64_t *clock_origin_us) {
    int64_t duration_us = frame->duration > 0
            ? av_rescale_q(frame->duration, time_base, AV_TIME_BASE_Q)
            : 40000;
    int schedule_result;
    if (continuous) {
        if (duration_us < 10000)
            duration_us = 10000;
        else if (duration_us > 100000)
            duration_us = 100000;
        schedule_result = ffplaykmp_wait_until(
                player, presentation_time_us, duration_us, clock_origin_us);
        if (schedule_result < 0)
            return schedule_result;
        if (schedule_result > 0) {
#if defined(__ANDROID__)
            if (frame->format == AV_PIX_FMT_MEDIACODEC) {
                AVMediaCodecBuffer *buffer = (AVMediaCodecBuffer *)frame->data[3];
                if (buffer)
                    av_mediacodec_release_buffer(buffer, 0);
            }
#endif
            player->session.showing = 0;
            pthread_mutex_lock(&player->mutex);
            player->snapshot.position_us = presentation_time_us;
            player->snapshot.dropped_frames++;
            pthread_mutex_unlock(&player->mutex);
            ffplaykmp_publish(player);
            return 0;
        }
    }
    pthread_mutex_lock(&player->mutex);
    ffplaykmp_read_frame_metadata(&player->snapshot, frame);
    pthread_mutex_unlock(&player->mutex);
#if defined(__ANDROID__)
    if (frame->format == AV_PIX_FMT_MEDIACODEC) {
        AVMediaCodecBuffer *buffer = (AVMediaCodecBuffer *)frame->data[3];
        if (!buffer)
            return AVERROR_INVALIDDATA;
        if (av_mediacodec_render_buffer_at_time(
                buffer, av_gettime_relative() * 1000) < 0)
            return AVERROR_EXTERNAL;
    } else
#elif defined(__APPLE__)
    if (ffplaykmp_is_hardware_frame(frame)) {
        /* An output that cannot import it takes it as a software frame, its CVPixelBuffer read in place or downloaded. */
        schedule_result = ffplaykmp_emit_platform_video_frame(
                player, frame, presentation_time_us);
        if (schedule_result == AVERROR(ENOSYS))
            ffplaykmp_emit_video_frame(player, frame, presentation_time_us);
        else if (schedule_result < 0 && schedule_result != AVERROR(EAGAIN))
            return schedule_result;
    } else
#endif
    {
        ffplaykmp_emit_video_frame(player, frame, presentation_time_us);
    }
    /* Playback paused or seeking as the frame went out: the next run presents it again. */
    if (continuous && ffplaykmp_is_aborted(player))
        return AVERROR_EXIT;
    player->session.showing = 1;
    player->session.shown_us = ffplaykmp_frame_time_us(
            frame, time_base, player->session.media_start_us, presentation_time_us);
    if (frame != player->session.shown) {
        av_frame_unref(player->session.shown);
#if defined(__ANDROID__)
        if (frame->format != AV_PIX_FMT_MEDIACODEC)
#endif
            av_frame_ref(player->session.shown, frame);
    }
    if (!continuous)
        return 1;
    pthread_mutex_lock(&player->mutex);
    player->snapshot.position_us = presentation_time_us;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    return 0;
}

/* How long a drain waits for a decoder that is not ready for the end of the input, as FFmpeg's MediaCodec drain does. */
#define FFPLAYKMP_DRAIN_TIMEOUT_US 1000000

/*
 * Receives the decoder's next frame. While draining, FFmpeg's MediaCodec
 * decoder answers EAGAIN when the codec had no input buffer free for the end of
 * the stream yet, where other decoders wait; that answer is asked again until
 * the decoder reports its end, a frame comes, the run is aborted or
 * FFPLAYKMP_DRAIN_TIMEOUT_US passes.
 */
static int ffplaykmp_receive_frame(
        ffplaykmp_player *player,
        AVCodecContext *decoder,
        AVFrame *frame,
        int draining) {
    const int64_t deadline_us = draining ? av_gettime_relative() + FFPLAYKMP_DRAIN_TIMEOUT_US : 0;
    int result;
    while ((result = avcodec_receive_frame(decoder, frame)) == AVERROR(EAGAIN) && draining &&
            !ffplaykmp_is_aborted(player) && av_gettime_relative() < deadline_us)
        av_usleep(1000);
    return result;
}

/*
 * A preview at a target the frame on screen covers: presents that frame again,
 * where the session has it, at the target. Returns 1, as a preview presented.
 */
static int ffplaykmp_present_shown_frame(ffplaykmp_player *player, int64_t start_position_us) {
    ffplaykmp_session *session = &player->session;
    int64_t clock_origin_us = AV_NOPTS_VALUE;
    if (!session->shown->buf[0])
        return 1;
    return ffplaykmp_present_decoded_frame(
            player, session->shown, session->stream->time_base, start_position_us, 0, &clock_origin_us);
}

/*
 * Presents every frame the decoder has ready, or (draining) every frame it has
 * left, the session's next frame first. Returns 1 once a preview
 * (non-continuous) is done, 0 when the decoder needs more input or is drained,
 * or a negative error. Undecodable data is skipped, as ffplay does.
 *
 * Frames before `start_position_us` are decoded from the keyframe a seek
 * lands on. Of those, `held` keeps the latest: it is the frame on screen at the
 * target once the next frame turns out to start after it, as VideoDecoder
 * picks, and it is shown at the target. A frame that starts exactly there
 * replaces it. A preview that goes on from the frame on screen, with none
 * held, is done when a frame starts after the target: the one on screen is
 * the one there, and is presented again. A frame decoded but not presented, because a preview was
 * done before it or playback was stopped while it waited, is kept as the
 * session's next.
 */
static int ffplaykmp_present_ready_frames(
        ffplaykmp_player *player,
        int64_t start_position_us,
        int continuous,
        int require_hardware,
        int draining,
        int *decoded_frames,
        int64_t *clock_origin_us) {
    ffplaykmp_session *session = &player->session;
    AVFrame *frame = session->frame;
    AVFrame *held = session->held;
    const AVRational time_base = session->stream->time_base;
    int result;
    for (;;) {
        int frame_is_hardware;
        int64_t presentation_time_us;
        if (session->next->buf[0])
            av_frame_move_ref(frame, session->next);
        else if (ffplaykmp_receive_frame(player, session->codec.decoder, frame, draining) < 0)
            return 0;
        (*decoded_frames)++;
        frame_is_hardware = ffplaykmp_is_hardware_frame(frame);
        if (require_hardware && !frame_is_hardware) {
            av_frame_unref(frame);
            return FFPLAYKMP_ERROR_UNSUPPORTED;
        }
        pthread_mutex_lock(&player->mutex);
        player->snapshot.active_decoder = frame_is_hardware
                ? FFPLAYKMP_DECODER_HARDWARE
                : FFPLAYKMP_DECODER_SOFTWARE_ACTIVE;
        pthread_mutex_unlock(&player->mutex);
        presentation_time_us = ffplaykmp_frame_time_us(
                frame, time_base, session->media_start_us, start_position_us);
        if (presentation_time_us < start_position_us) {
            /* Unreferencing a MediaCodec frame releases its buffer without rendering it. */
            av_frame_unref(held);
            av_frame_move_ref(held, frame);
            session->showing = 0;
            continue;
        }
        if (!continuous && !held->buf[0] && session->showing && presentation_time_us > start_position_us) {
            av_frame_move_ref(session->next, frame);
            return ffplaykmp_present_shown_frame(player, start_position_us);
        }
        if (held->buf[0] && presentation_time_us > start_position_us) {
            result = ffplaykmp_present_decoded_frame(
                    player, held, time_base, start_position_us, continuous, clock_origin_us);
            av_frame_unref(held);
            if (result > 0) {
                av_frame_move_ref(session->next, frame);
                return result;
            }
            if (result < 0) {
                /* The held frame is gone, so this one cannot follow it. */
                session->in_sync = 0;
                av_frame_unref(frame);
                return result;
            }
        }
        av_frame_unref(held);
        result = ffplaykmp_present_decoded_frame(
                player, frame, time_base, presentation_time_us, continuous, clock_origin_us);
        if (result == AVERROR_EXIT) {
            av_frame_move_ref(session->next, frame);
            return result;
        }
        av_frame_unref(frame);
        if (result != 0)
            return result;
    }
}

/* At the end of the input, the last frame before the target is the one on screen there. */
static int ffplaykmp_present_held_frame(
        ffplaykmp_player *player,
        int64_t start_position_us,
        int continuous,
        int64_t *clock_origin_us) {
    ffplaykmp_session *session = &player->session;
    int result = 0;
    if (session->held->buf[0])
        result = ffplaykmp_present_decoded_frame(
                player, session->held, session->stream->time_base, start_position_us, continuous, clock_origin_us);
    av_frame_unref(session->held);
    return result;
}

/* Forgets what the decoder was in the middle of; the next run seeks. */
static void ffplaykmp_session_drop_frames(ffplaykmp_session *session) {
    av_packet_unref(session->packet);
    av_frame_unref(session->frame);
    av_frame_unref(session->held);
    av_frame_unref(session->next);
    av_frame_unref(session->shown);
    session->packet_pending = 0;
    session->draining = 0;
    session->in_sync = 0;
    session->showing = 0;
}

/*
 * Closes the session's codec, on the session thread, frames first, since a
 * MediaCodec frame holds one of the codec's buffers. The next run opens one.
 */
static void ffplaykmp_session_close_codec(ffplaykmp_session *session) {
    ffplaykmp_session_drop_frames(session);
    ffplaykmp_video_codec_close(&session->codec);
    memset(&session->codec, 0, sizeof(session->codec));
    session->codec_open = 0;
}

/*
 * Closes the session's input, with session_mutex held; the next run opens it
 * again. A codec still open stays for the session thread to close: it no
 * longer matches the input.
 */
static void ffplaykmp_session_close_input(ffplaykmp_session *session) {
    ffplaykmp_close_input(&session->input);
    memset(&session->input, 0, sizeof(session->input));
    session->video_stream = -1;
    session->stream = NULL;
    session->media_start_us = 0;
    session->at_start = 0;
    session->in_sync = 0;
    session->showing = 0;
}

static void ffplaykmp_end_session_thread(ffplaykmp_player *player);

/*
 * Ends the session thread, which closes the codec, and closes the player's
 * input, before the host may unmount what it reads. The next job starts a new
 * thread.
 */
static void ffplaykmp_player_close_input(ffplaykmp_player *player) {
    ffplaykmp_end_session_thread(player);
    pthread_mutex_lock(&player->session_mutex);
    ffplaykmp_session_close_input(&player->session);
    pthread_mutex_unlock(&player->session_mutex);
}

/* Opens `url` into the session and finds its video stream, unless it is open already. */
static int ffplaykmp_session_open_input(ffplaykmp_player *player, const char *url) {
    ffplaykmp_session *session = &player->session;
    int result;
    if (session->input.format)
        return 0;
    result = ffplaykmp_player_open_input(player, url, &session->input);
    if (result >= 0) {
        session->video_stream = ffplaykmp_find_video_stream(session->input.format, NULL);
        result = session->video_stream < 0 ? session->video_stream : 0;
    }
    if (result < 0) {
        ffplaykmp_session_close_input(session);
        return result;
    }
    session->input_generation++;
    session->stream = session->input.format->streams[session->video_stream];
    session->media_start_us = ffplaykmp_media_start_us(session->input.format);
    session->at_start = 1;
    return 0;
}

/*
 * Makes the session's codec the one this run's output wants, keeping the open
 * one when it is: a hardware decoder when `preference` allows it and the
 * output takes hardware frames, unless AUTO's hardware attempt failed on this
 * source. Under AUTO a hardware decoder that will not open gives way to
 * software, for the source's later runs too. Publishes why a wanted hardware
 * decoder is not the one open, and what a preview learns from a new codec.
 */
static int ffplaykmp_session_open_codec(
        ffplaykmp_player *player,
        ffplaykmp_decoder_preference preference,
        int continuous) {
    ffplaykmp_session *session = &player->session;
    ffplaykmp_decoder_fallback fallback = FFPLAYKMP_DECODER_FALLBACK_NONE;
    int hardware_wanted = 0;
    int hardware;
    void *android_surface = NULL;
    int result;
    if (preference != FFPLAYKMP_DECODER_SOFTWARE) {
        pthread_mutex_lock(&player->mutex);
#if defined(__ANDROID__)
        android_surface = player->android_surface;
        hardware_wanted = player->has_output && android_surface &&
                (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT);
#else
        hardware_wanted = player->has_output &&
                (player->snapshot.output_flags & (FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD
#if defined(__APPLE__)
                        | FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT
#endif
                ));
#endif
        pthread_mutex_unlock(&player->mutex);
    }
    if (!hardware_wanted)
        android_surface = NULL;
    if (session->codec_open && session->codec_hardware_wanted == hardware_wanted &&
            session->codec_surface == android_surface && session->codec_input == session->input_generation)
        return 0;
    ffplaykmp_session_close_codec(session);
    hardware = hardware_wanted && !(preference == FFPLAYKMP_DECODER_AUTO && session->hardware_failed);
    result = ffplaykmp_video_codec_open(
            &session->codec, session->input.format, session->video_stream, hardware,
            preference == FFPLAYKMP_DECODER_REQUIRE_HARDWARE, player->configuration.decoder_threads,
            FF_THREAD_FRAME | FF_THREAD_SLICE, hardware ? android_surface : NULL, NULL);
    if (result < 0 && session->codec.hardware && preference == FFPLAYKMP_DECODER_AUTO) {
        ffplaykmp_session_close_codec(session);
        session->hardware_failed = 1;
        result = ffplaykmp_video_codec_open(
                &session->codec, session->input.format, session->video_stream, 0, 0,
                player->configuration.decoder_threads, FF_THREAD_FRAME | FF_THREAD_SLICE, NULL, NULL);
    }
    if (result < 0) {
        ffplaykmp_session_close_codec(session);
        return result;
    }
    session->codec_open = 1;
    session->codec_hardware_wanted = hardware_wanted;
    session->codec_surface = android_surface;
    session->codec_input = session->input_generation;
    if (hardware_wanted && !session->codec.hardware)
        fallback = session->hardware_failed
                ? FFPLAYKMP_DECODER_FALLBACK_FAILED
                : FFPLAYKMP_DECODER_FALLBACK_UNSUPPORTED;
    pthread_mutex_lock(&player->mutex);
    /* A configured device is not proof of hardware decoding: the first frame decides. */
    player->snapshot.active_decoder = FFPLAYKMP_DECODER_UNKNOWN;
    player->snapshot.decoder_fallback = fallback;
    if (!continuous) {
        player->snapshot.video_width = session->codec.decoder->width;
        player->snapshot.video_height = session->codec.decoder->height;
        player->snapshot.duration_us = session->input.format->duration == AV_NOPTS_VALUE
                ? -1
                : session->input.format->duration;
    }
    pthread_mutex_unlock(&player->mutex);
    return 0;
}

/* `timestamp`, in the session stream's time base, as a media time. */
static int64_t ffplaykmp_session_time_us(const ffplaykmp_session *session, int64_t timestamp) {
    return av_rescale_q(timestamp, session->stream->time_base, AV_TIME_BASE_Q) - session->media_start_us;
}

/* How far past the next frame a run decodes on, rather than seeking, in an input without a keyframe index. */
#define FFPLAYKMP_UNINDEXED_DECODE_AHEAD_US 2000000

/*
 * Whether a run to `start_position_us` can go on decoding from where the last
 * one stopped instead of seeking, as VideoDecoder's ffmpegkmp_position
 * decides: only forward from the frame on screen, and only while the index has
 * no keyframe between the next frame and the target, from which a seek would
 * decode less. Without an index, only a short way forward. A preview for a new
 * output must present a frame, so it goes on only when the session keeps the
 * one on screen.
 */
static int ffplaykmp_session_continues(
        const ffplaykmp_session *session,
        int64_t start_position_us,
        int presents) {
    AVStream *stream = session->input.format->streams[session->video_stream];
    const AVIndexEntry *entry;
    int64_t next_us;
    int index;
    if ((presents && !session->shown->buf[0]) || !session->in_sync || !session->showing ||
            start_position_us < session->shown_us)
        return 0;
    next_us = session->next->buf[0] && session->next->best_effort_timestamp != AV_NOPTS_VALUE
            ? ffplaykmp_session_time_us(session, session->next->best_effort_timestamp)
            : session->shown_us;
    index = av_index_search_timestamp(stream,
            av_rescale_q(session->media_start_us + start_position_us, AV_TIME_BASE_Q, stream->time_base),
            AVSEEK_FLAG_BACKWARD);
    entry = index >= 0 ? avformat_index_get_entry(stream, index) : NULL;
    if (!entry)
        return start_position_us - next_us <= FFPLAYKMP_UNINDEXED_DECODE_AHEAD_US;
    return ffplaykmp_session_time_us(session, entry->timestamp) <= next_us;
}

/*
 * Seeks to the keyframe at or before `start_position_us`, forgetting what the
 * decoder was in the middle of; an input not yet read past its probe is
 * already at the start.
 */
static int ffplaykmp_session_seek(ffplaykmp_session *session, int64_t start_position_us) {
    AVIOContext *pb = session->input.format->pb;
    int64_t target;
    int result;
    ffplaykmp_session_drop_frames(session);
    if (start_position_us <= 0 && session->at_start) {
        session->in_sync = 1;
        return 0;
    }
    session->at_start = 0;
    /* A read an interrupt failed (a pause or a seek ends the run that way) stays in the
     * AVIOContext and would fail the seek's reads; clear it, as ffplay clears eof_reached. */
    if (pb) {
        pb->error = 0;
        pb->eof_reached = 0;
    }
    target = av_rescale_q(session->media_start_us + start_position_us, AV_TIME_BASE_Q, session->stream->time_base);
    result = avformat_seek_file(
            session->input.format, session->video_stream, INT64_MIN, target, INT64_MAX, AVSEEK_FLAG_BACKWARD);
    if (result < 0)
        return result;
    avcodec_flush_buffers(session->codec.decoder);
    session->in_sync = 1;
    return 0;
}

/*
 * Decodes from where the session is: one preview frame at
 * `start_position_us`, or (continuous) every frame on the playback clock,
 * counting them in `decoded_frames`. `continuing` says the decoder goes on
 * from the frame on screen. Returns 0 once done, AVERROR_INVALIDDATA when
 * nothing decoded, or another negative error. A run stopped between packets,
 * or while a frame waited for its time, leaves the session in sync for the
 * next one.
 */
static int ffplaykmp_session_decode(
        ffplaykmp_player *player,
        int64_t start_position_us,
        int continuous,
        int require_hardware,
        int continuing,
        int *decoded_frames) {
    ffplaykmp_session *session = &player->session;
    AVCodecContext *decoder = session->codec.decoder;
    AVPacket *packet = session->packet;
    int64_t clock_origin_us = AV_NOPTS_VALUE;
    int result = ffplaykmp_present_ready_frames(
            player, start_position_us, continuous, require_hardware, session->draining, decoded_frames,
            &clock_origin_us);
    while (result == 0 && !session->draining && !ffplaykmp_is_aborted(player)) {
        int sent;
        if (!session->packet_pending) {
            if ((result = av_read_frame(session->input.format, packet)) < 0) {
                /* The read may have lost its packet; an abort leaves the next run to seek. */
                if (ffplaykmp_is_aborted(player))
                    session->in_sync = 0;
                break;
            }
            if (packet->stream_index != session->video_stream) {
                av_packet_unref(packet);
                continue;
            }
            ffplaykmp_count_video_packet();
            session->packet_pending = 1;
        }
        sent = avcodec_send_packet(decoder, packet);
        if (sent == AVERROR(EAGAIN)) {
            /* The decoder wants its ready frames taken first; then it accepts the packet. */
            result = ffplaykmp_present_ready_frames(
                    player, start_position_us, continuous, require_hardware, 0, decoded_frames, &clock_origin_us);
            if (result != 0)
                break;
            sent = avcodec_send_packet(decoder, packet);
        }
        /* Like ffplay, a damaged packet is dropped; later packets may recover. */
        av_packet_unref(packet);
        session->packet_pending = 0;
        if (sent >= 0)
            result = ffplaykmp_present_ready_frames(
                    player, start_position_us, continuous, require_hardware, 0, decoded_frames, &clock_origin_us);
    }
    if (result > 0)
        return 0;  /* The preview frame was presented, or was on screen already. */
    if (ffplaykmp_is_aborted(player))
        return AVERROR_EXIT;
    if (result == AVERROR_EOF || (result == 0 && session->draining)) {
        /* Draining, the decoder takes no more input until a seek flushes it; the frames it has left follow on. */
        if (!session->draining)
            avcodec_send_packet(decoder, NULL);
        session->draining = 1;
        result = ffplaykmp_present_ready_frames(
                player, start_position_us, continuous, require_hardware, 1, decoded_frames, &clock_origin_us);
        if (result == 0)
            result = ffplaykmp_present_held_frame(player, start_position_us, continuous, &clock_origin_us);
        if (result > 0)
            return 0;
    }
    /* Going on from the last frame, at the end: the frame on screen is the one at the target. */
    if (result >= 0 && *decoded_frames == 0 && continuing) {
        if (!continuous)
            ffplaykmp_present_shown_frame(player, start_position_us);
        return 0;
    }
    /* An abort can also end the drain early, before anything decoded. */
    if (result >= 0 && *decoded_frames == 0)
        return ffplaykmp_is_aborted(player) ? AVERROR_EXIT : AVERROR_INVALIDDATA;
    return result;
}

/*
 * Decodes the video of `url`, opening it unless prepare left it open, and the
 * codec unless the last run left one that suits: one preview frame at
 * `start_position_us`, or (continuous) every frame on the playback clock. A
 * preview that `presents` shows a frame even when the one on screen is it. A
 * hardware attempt under AUTO that fails before decoding anything is retried
 * in software, on the same input. Any other failure but an abort closes the
 * codec and the input, so the next run opens them afresh. Runs one at a time,
 * on the session thread, holding session_mutex.
 */
static int ffplaykmp_run_job(
        ffplaykmp_player *player,
        const char *url,
        int64_t start_position_us,
        int continuous,
        int presents) {
    ffplaykmp_session *session = &player->session;
    const ffplaykmp_decoder_preference preference = player->configuration.decoder_preference;
    ffplaykmp_decoder_kind reported;
    int decoded_frames;
    int result;
    pthread_mutex_lock(&player->session_mutex);
    pthread_mutex_lock(&player->mutex);
    reported = player->snapshot.active_decoder;
    pthread_mutex_unlock(&player->mutex);
    for (;;) {
        int hardware = 0;
        int continuing = 0;
        decoded_frames = 0;
        result = ffplaykmp_session_open_input(player, url);
        if (result >= 0)
            result = ffplaykmp_session_open_codec(player, preference, continuous);
        if (result >= 0) {
            hardware = session->codec.hardware;
            continuing = ffplaykmp_session_continues(session, start_position_us, presents);
            if (!continuing)
                result = ffplaykmp_session_seek(session, start_position_us);
        }
        if (result >= 0) {
            session->at_start = 0;
            result = ffplaykmp_session_decode(player, start_position_us, continuous,
                    preference == FFPLAYKMP_DECODER_REQUIRE_HARDWARE, continuing, &decoded_frames);
        }
        if (result >= 0 || ffplaykmp_is_aborted(player))
            break;
        ffplaykmp_session_close_codec(session);
        if (!hardware || decoded_frames > 0 || preference != FFPLAYKMP_DECODER_AUTO) {
            ffplaykmp_session_close_input(session);
            break;
        }
        /* The software retry keeps the input; it seeks back to the start position. */
        session->hardware_failed = 1;
    }
    /* Opening the codec forgot the decoder kind until a frame decided it; a run a pause or
     * seek stopped before then would reopen the same kind, so the last one reported stands. */
    if (decoded_frames == 0 && ffplaykmp_is_aborted(player)) {
        pthread_mutex_lock(&player->mutex);
        if (player->snapshot.active_decoder == FFPLAYKMP_DECODER_UNKNOWN)
            player->snapshot.active_decoder = reported;
        pthread_mutex_unlock(&player->mutex);
    }
    pthread_mutex_unlock(&player->session_mutex);
    return result;
}

#if defined(__ANDROID__)
/* Closes the codec, on the session thread, if it renders to a Surface. */
static void ffplaykmp_run_release_job(ffplaykmp_player *player) {
    pthread_mutex_lock(&player->session_mutex);
    if (player->session.codec.mediacodec_context)
        ffplaykmp_session_close_codec(&player->session);
    pthread_mutex_unlock(&player->session_mutex);
}
#endif

/*
 * Opens and probes the input, reads its container and stream headers, and keeps
 * it open for the runs that follow. This keeps prepare independent from an
 * output target and never crosses the decoded-pixel boundary for protected
 * sources.
 */
static int ffplaykmp_inspect_source(ffplaykmp_player *player, const char *url) {
    ffplaykmp_session *session = &player->session;
    int result;
    pthread_mutex_lock(&player->session_mutex);
    ffplaykmp_session_close_input(session);
    session->hardware_failed = 0;
    result = ffplaykmp_session_open_input(player, url);
    if (result >= 0) {
        pthread_mutex_lock(&player->mutex);
        player->snapshot.video_width = session->stream->codecpar->width;
        player->snapshot.video_height = session->stream->codecpar->height;
        ffplaykmp_read_stream_metadata(&player->snapshot, session->stream);
        player->snapshot.duration_us = session->input.format->duration == AV_NOPTS_VALUE
                ? -1
                : session->input.format->duration;
        pthread_mutex_unlock(&player->mutex);
    }
    pthread_mutex_unlock(&player->session_mutex);
    return result;
}

void ffplaykmp_configuration_default(ffplaykmp_configuration *configuration) {
    if (!configuration)
        return;
    memset(configuration, 0, sizeof(*configuration));
    configuration->size = sizeof(*configuration);
    configuration->decoder_preference = FFPLAYKMP_DECODER_AUTO;
}

void ffplaykmp_output_capabilities_init(ffplaykmp_output_capabilities *capabilities) {
    if (!capabilities)
        return;
    memset(capabilities, 0, sizeof(*capabilities));
    capabilities->size = sizeof(*capabilities);
}

void ffplaykmp_snapshot_init(ffplaykmp_snapshot *snapshot) {
    if (!snapshot)
        return;
    memset(snapshot, 0, sizeof(*snapshot));
    snapshot->size = sizeof(*snapshot);
    snapshot->duration_us = -1;
    ffplaykmp_reset_video_metadata(snapshot);
}

const char *ffplaykmp_pixel_format_name(int32_t pixel_format) {
    return av_get_pix_fmt_name(pixel_format);
}

static void ffplaykmp_publish(ffplaykmp_player *player) {
    ffplaykmp_snapshot snapshot;
    ffplaykmp_state_callback callback;
    void *opaque;
    if (!player)
        return;
    pthread_mutex_lock(&player->mutex);
    snapshot = player->snapshot;
    callback = player->callback;
    opaque = player->opaque;
    pthread_mutex_unlock(&player->mutex);
    if (callback)
        callback(opaque, &snapshot);
}

/* Records a failure, publishes it, and returns it. */
static int ffplaykmp_fail(ffplaykmp_player *player, int error) {
    pthread_mutex_lock(&player->mutex);
    player->snapshot.last_error = error;
    player->snapshot.state = FFPLAYKMP_STATE_FAILED;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    return error;
}

/* Plays from the snapshot's position, then publishes ENDED or FAILED unless it was aborted. */
static void ffplaykmp_run_play_job(ffplaykmp_player *player) {
    const char *input;
    int64_t start_position_us;
    int result;
    pthread_mutex_lock(&player->mutex);
    input = player->input;
    start_position_us = player->snapshot.position_us;
    pthread_mutex_unlock(&player->mutex);
    result = ffplaykmp_run_job(player, input, start_position_us, 1, 0);
    if (ffplaykmp_is_aborted(player))
        return;
    ffplaykmp_invalidate_master_clock(player);
    pthread_mutex_lock(&player->mutex);
    if (result < 0) {
        player->snapshot.last_error = result;
        player->snapshot.state = FFPLAYKMP_STATE_FAILED;
    } else {
        if (player->snapshot.duration_us >= 0)
            player->snapshot.position_us = player->snapshot.duration_us;
        player->snapshot.state = FFPLAYKMP_STATE_ENDED;
        player->play_when_ready = 0;
    }
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
}

static int ffplaykmp_run_preview_job(ffplaykmp_player *player, int64_t start_position_us, int presents) {
    const char *input;
    pthread_mutex_lock(&player->mutex);
    input = player->input;
    pthread_mutex_unlock(&player->mutex);
    return ffplaykmp_run_job(player, input, start_position_us, 0, presents);
}

static void *ffplaykmp_session_main(void *opaque) {
    ffplaykmp_player *player = opaque;
    pthread_mutex_lock(&player->job_mutex);
    for (;;) {
        ffplaykmp_job_kind kind;
        ffplaykmp_job_ticket *ticket;
        int64_t start_us;
        int result = 0;
        while (player->job == FFPLAYKMP_JOB_NONE)
            pthread_cond_wait(&player->job_cond, &player->job_mutex);
        kind = player->job;
        player->job = FFPLAYKMP_JOB_NONE;
        if (kind == FFPLAYKMP_JOB_EXIT)
            break;
        start_us = player->job_start_us;
        ticket = player->job_ticket;
        player->job_ticket = NULL;
        player->job_running = kind;
        pthread_mutex_unlock(&player->job_mutex);
        if (kind == FFPLAYKMP_JOB_PREVIEW || kind == FFPLAYKMP_JOB_OUTPUT_PREVIEW)
            result = ffplaykmp_run_preview_job(player, start_us, kind == FFPLAYKMP_JOB_OUTPUT_PREVIEW);
#if defined(__ANDROID__)
        else if (kind == FFPLAYKMP_JOB_RELEASE)
            ffplaykmp_run_release_job(player);
#endif
        else
            ffplaykmp_run_play_job(player);
        pthread_mutex_lock(&player->job_mutex);
        player->job_running = FFPLAYKMP_JOB_NONE;
        /* An abort meant for this job must not reach the next one. */
        atomic_store(&player->worker_abort, 0);
        if (ticket) {
            ticket->result = result;
            ticket->done = 1;
        }
        pthread_cond_broadcast(&player->job_cond);
    }
    pthread_mutex_unlock(&player->job_mutex);
    /* The codec goes with the thread that used it. */
    pthread_mutex_lock(&player->session_mutex);
    ffplaykmp_session_close_codec(&player->session);
    pthread_mutex_unlock(&player->session_mutex);
    return NULL;
}

/* Waits, with job_mutex held, until no job is queued or running. */
static void ffplaykmp_wait_idle(ffplaykmp_player *player) {
    while (player->job != FFPLAYKMP_JOB_NONE || player->job_running != FFPLAYKMP_JOB_NONE)
        pthread_cond_wait(&player->job_cond, &player->job_mutex);
}

/*
 * Queues a job on the session thread, starting the thread if needed, once the
 * job before it has finished. With a ticket it waits for the job and returns
 * its result; without one it returns as soon as the job is queued.
 */
static int ffplaykmp_post_job(
        ffplaykmp_player *player,
        ffplaykmp_job_kind kind,
        int64_t start_us,
        ffplaykmp_job_ticket *ticket) {
    int result = 0;
    pthread_mutex_lock(&player->job_mutex);
    if (!player->session_thread_running) {
        result = pthread_create(&player->session_thread, NULL, ffplaykmp_session_main, player);
        if (result != 0) {
            pthread_mutex_unlock(&player->job_mutex);
            return -result;
        }
        player->session_thread_running = 1;
    }
    ffplaykmp_wait_idle(player);
    player->job = kind;
    player->job_start_us = start_us;
    player->job_ticket = ticket;
    pthread_cond_broadcast(&player->job_cond);
    if (ticket) {
        while (!ticket->done)
            pthread_cond_wait(&player->job_cond, &player->job_mutex);
        result = ticket->result;
    }
    pthread_mutex_unlock(&player->job_mutex);
    return result;
}

/*
 * Decodes and presents the frame at `start_position_us` on the session thread,
 * waiting for it. A preview that `presents`, for a new output, presents a
 * frame even when the frame on screen there is a MediaCodec one.
 */
static int ffplaykmp_preview(ffplaykmp_player *player, int64_t start_position_us, int presents) {
    ffplaykmp_job_ticket ticket = { 0, 0 };
    return ffplaykmp_post_job(player, presents ? FFPLAYKMP_JOB_OUTPUT_PREVIEW : FFPLAYKMP_JOB_PREVIEW,
            start_position_us, &ticket);
}

#if defined(__ANDROID__)
/*
 * Closes a codec that renders to a Surface, on the session thread, and waits
 * for it: MediaCodec holds its Surface until it closes, and the Surface is
 * being replaced, or the output that drew on it is.
 */
static void ffplaykmp_release_surface_codec(ffplaykmp_player *player) {
    ffplaykmp_job_ticket ticket = { 0, 0 };
    int running;
    pthread_mutex_lock(&player->job_mutex);
    running = player->session_thread_running;
    pthread_mutex_unlock(&player->job_mutex);
    /* No session thread, no codec. */
    if (running)
        ffplaykmp_post_job(player, FFPLAYKMP_JOB_RELEASE, 0, &ticket);
}
#endif

/* Ends playback, queued or running, and waits for it to stop; a preview runs to its end. */
static void ffplaykmp_stop_worker(ffplaykmp_player *player) {
    pthread_mutex_lock(&player->job_mutex);
    if (player->job == FFPLAYKMP_JOB_PLAY) {
        player->job = FFPLAYKMP_JOB_NONE;
        pthread_cond_broadcast(&player->job_cond);
    }
    if (player->job_running == FFPLAYKMP_JOB_PLAY) {
        atomic_store(&player->worker_abort, 1);
        while (player->job_running == FFPLAYKMP_JOB_PLAY)
            pthread_cond_wait(&player->job_cond, &player->job_mutex);
    }
    pthread_mutex_unlock(&player->job_mutex);
}

/* Starts playback on the session thread, after ending any that runs. */
static int ffplaykmp_start_worker(ffplaykmp_player *player) {
    ffplaykmp_stop_worker(player);
    return ffplaykmp_post_job(player, FFPLAYKMP_JOB_PLAY, 0, NULL);
}

/* Ends playback, waits for any preview, and ends the session thread. */
static void ffplaykmp_end_session_thread(ffplaykmp_player *player) {
    pthread_t thread;
    ffplaykmp_stop_worker(player);
    pthread_mutex_lock(&player->job_mutex);
    if (!player->session_thread_running) {
        pthread_mutex_unlock(&player->job_mutex);
        return;
    }
    ffplaykmp_wait_idle(player);
    player->job = FFPLAYKMP_JOB_EXIT;
    pthread_cond_broadcast(&player->job_cond);
    thread = player->session_thread;
    player->session_thread_running = 0;
    pthread_mutex_unlock(&player->job_mutex);
    pthread_join(thread, NULL);
}

static int ffplaykmp_require_prepared(ffplaykmp_player *player) {
    if (!player)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if (!player->input)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    return 0;
}

/*
 * Whether this player can decode and present frames itself through an output
 * with these capabilities. Desktop hardware decoding downloads frames into the
 * software-upload path, so every desktop preference needs that path.
 */
static int ffplaykmp_can_decode(const ffplaykmp_player *player, uint32_t flags) {
#if defined(__ANDROID__) || defined(__APPLE__)
    const int preference = player->configuration.decoder_preference;
#endif
    if (player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH)
        return 0;
#if defined(__ANDROID__)
    if ((flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT) &&
            preference != FFPLAYKMP_DECODER_SOFTWARE && player->android_surface)
        return 1;
    return (flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD) &&
            preference != FFPLAYKMP_DECODER_REQUIRE_HARDWARE;
#elif defined(__APPLE__)
    if (flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD)
        return 1;
    return (flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT) &&
            preference != FFPLAYKMP_DECODER_SOFTWARE;
#else
    return (flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD) != 0;
#endif
}

static int ffplaykmp_validate_output(
        const ffplaykmp_player *player,
        uint32_t output_flags) {
    if ((player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) &&
            !(output_flags & FFPLAYKMP_OUTPUT_PROTECTED_CONTENT))
        return FFPLAYKMP_ERROR_ACCESS_DENIED;
    if (player->configuration.decoder_preference == FFPLAYKMP_DECODER_REQUIRE_HARDWARE &&
#if defined(__ANDROID__)
            !(output_flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT))
#elif defined(__APPLE__)
            !(output_flags & (FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT |
                    FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD)))
#else
            !(output_flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD))
#endif
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    if (!(output_flags & (FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT |
            FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD)))
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    return 0;
}

ffplaykmp_player *ffplaykmp_player_create(
        const ffplaykmp_configuration *configuration,
        ffplaykmp_state_callback callback,
        void *opaque) {
    ffplaykmp_player *player = calloc(1, sizeof(*player));
    if (!player)
        return NULL;
    if (configuration && (configuration->size < sizeof(*configuration) || configuration->decoder_threads < 0)) {
        free(player);
        return NULL;
    }
    if (pthread_mutex_init(&player->mutex, NULL) != 0) {
        free(player);
        return NULL;
    }
    if (pthread_mutex_init(&player->session_mutex, NULL) != 0) {
        pthread_mutex_destroy(&player->mutex);
        free(player);
        return NULL;
    }
    if (pthread_mutex_init(&player->job_mutex, NULL) != 0) {
        pthread_mutex_destroy(&player->session_mutex);
        pthread_mutex_destroy(&player->mutex);
        free(player);
        return NULL;
    }
    if (pthread_cond_init(&player->job_cond, NULL) != 0) {
        pthread_mutex_destroy(&player->job_mutex);
        pthread_mutex_destroy(&player->session_mutex);
        pthread_mutex_destroy(&player->mutex);
        free(player);
        return NULL;
    }
    player->session.video_stream = -1;
    player->session.packet = av_packet_alloc();
    player->session.frame = av_frame_alloc();
    player->session.held = av_frame_alloc();
    player->session.next = av_frame_alloc();
    player->session.shown = av_frame_alloc();
    player->download_pool = ffmpegkmp_frame_pool_alloc(2);
    if (!player->session.packet || !player->session.frame || !player->session.held || !player->session.next ||
            !player->session.shown ||
            !player->download_pool) {
        ffplaykmp_player_destroy(player);
        return NULL;
    }
    ffplaykmp_configuration_default(&player->configuration);
    if (configuration)
        player->configuration = *configuration;
    player->callback = callback;
    player->opaque = opaque;
    ffplaykmp_snapshot_init(&player->snapshot);
    player->snapshot.state = FFPLAYKMP_STATE_IDLE;
    atomic_init(&player->cancelled, 0);
    atomic_init(&player->worker_abort, 0);
    return player;
}

void ffplaykmp_player_set_io_callback(
        ffplaykmp_player *player,
        ffplaykmp_io_callback callback,
        void *opaque) {
    if (!player)
        return;
    pthread_mutex_lock(&player->mutex);
    player->io_callback = callback;
    player->io_opaque = opaque;
    pthread_mutex_unlock(&player->mutex);
}

void ffplaykmp_player_set_video_frame_callback(
        ffplaykmp_player *player,
        ffplaykmp_video_frame_callback callback,
        void *opaque) {
    if (!player)
        return;
    pthread_mutex_lock(&player->mutex);
    player->video_frame_callback = callback;
    player->video_frame_opaque = opaque;
    pthread_mutex_unlock(&player->mutex);
}

void ffplaykmp_player_set_platform_video_frame_callback(
        ffplaykmp_player *player,
        ffplaykmp_platform_video_frame_callback callback,
        void *opaque) {
    if (!player)
        return;
    pthread_mutex_lock(&player->mutex);
    player->platform_video_frame_callback = callback;
    player->platform_video_frame_opaque = opaque;
    pthread_mutex_unlock(&player->mutex);
}

#if defined(__ANDROID__)
int ffplaykmp_player_set_android_surface(
        JNIEnv *env,
        jclass owner,
        jobject surface,
        ffplaykmp_player *player,
        int secure) {
    jobject retained = NULL;
    jobject previous;
    JavaVM *vm = NULL;
    if (!env || !player)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    (void)owner;
    /* A secure Surface alone is not a DRM session. Never claim a protected
     * path until MediaCrypto/secure-input integration is supplied. */
    if (secure)
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    if (surface) {
        int result = ffplaykmp_android_retain_global(env, surface, &vm, &retained);
        if (result < 0)
            return result;
    }
    ffplaykmp_stop_worker(player);
    ffplaykmp_release_surface_codec(player);
    pthread_mutex_lock(&player->mutex);
    previous = player->android_surface;
    player->android_surface = retained;
    if (vm)
        player->android_vm = vm;
    pthread_mutex_unlock(&player->mutex);
    if (previous)
        (*env)->DeleteGlobalRef(env, previous);
    /* A hardware decoder that failed on another Surface may open on this one. */
    if (retained) {
        pthread_mutex_lock(&player->session_mutex);
        player->session.hardware_failed = 0;
        pthread_mutex_unlock(&player->session_mutex);
    }
    return 0;
}
#endif

void ffplaykmp_player_destroy(ffplaykmp_player *player) {
    if (!player)
        return;
    atomic_store(&player->cancelled, 1);
    /* The cancel ends any job, so this returns once the thread has exited. */
    ffplaykmp_end_session_thread(player);
#if defined(__ANDROID__)
    ffplaykmp_release_android_surface(player);
#endif
    free(player->input);
    pthread_mutex_lock(&player->session_mutex);
    ffplaykmp_session_close_input(&player->session);
    av_packet_free(&player->session.packet);
    av_frame_free(&player->session.frame);
    av_frame_free(&player->session.held);
    av_frame_free(&player->session.next);
    av_frame_free(&player->session.shown);
    pthread_mutex_unlock(&player->session_mutex);
    /* Frames handed out keep the pool until they go. */
    ffmpegkmp_frame_pool_free(player->download_pool);
    pthread_cond_destroy(&player->job_cond);
    pthread_mutex_destroy(&player->job_mutex);
    pthread_mutex_destroy(&player->session_mutex);
    pthread_mutex_destroy(&player->mutex);
    free(player);
}

/* Forgets the source a prepare failed on, closing its input before the host unmounts it. */
static int ffplaykmp_prepare_failed(ffplaykmp_player *player, int result) {
    ffplaykmp_player_close_input(player);
    pthread_mutex_lock(&player->mutex);
    free(player->input);
    player->input = NULL;
    player->source_flags = 0;
    player->play_when_ready = 0;
    player->snapshot.last_error = result;
    player->snapshot.state = FFPLAYKMP_STATE_FAILED;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    return result;
}

int ffplaykmp_player_prepare(
        ffplaykmp_player *player,
        const char *input,
        uint32_t source_flags) {
    char *owned_input;
    uint32_t output_flags;
    int has_output;
    int result;
    if (!player || !input || !*input)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    owned_input = malloc(strlen(input) + 1);
    if (!owned_input)
        return -ENOMEM;
    strcpy(owned_input, input);
    /* The session thread closes the codec of the source this one replaces as it ends. */
    ffplaykmp_end_session_thread(player);
    pthread_mutex_lock(&player->mutex);
    free(player->input);
    player->input = owned_input;
    player->source_flags = source_flags;
    player->play_when_ready = 0;
    player->snapshot.position_us = 0;
    player->snapshot.duration_us = -1;
    player->snapshot.dropped_frames = 0;
    ffplaykmp_reset_video_metadata(&player->snapshot);
    player->snapshot.active_decoder = FFPLAYKMP_DECODER_UNKNOWN;
    player->snapshot.decoder_fallback = FFPLAYKMP_DECODER_FALLBACK_NONE;
    player->snapshot.last_error = 0;
    player->snapshot.queue_serial++;
    player->snapshot.state = FFPLAYKMP_STATE_PREPARING;
    has_output = player->has_output;
    output_flags = player->snapshot.output_flags;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    result = ffplaykmp_inspect_source(player, input);
    if (result < 0)
        return ffplaykmp_prepare_failed(player, result);
    /* Publish inspected stream metadata before output negotiation decodes its preview frame. */
    ffplaykmp_publish(player);
    if (has_output) {
        result = ffplaykmp_validate_output(player, output_flags);
        if (result < 0) {
            return ffplaykmp_prepare_failed(player, result);
        }
    }
    if (has_output && ffplaykmp_can_decode(player, output_flags)) {
        result = ffplaykmp_preview(player, 0, 1);
        if (result < 0) {
            return ffplaykmp_prepare_failed(player, result);
        }
    }
    pthread_mutex_lock(&player->mutex);
    player->snapshot.state = has_output
            ? FFPLAYKMP_STATE_READY
            : FFPLAYKMP_STATE_WAITING_FOR_OUTPUT;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    return 0;
}

int ffplaykmp_player_set_output(
        ffplaykmp_player *player,
        const ffplaykmp_output_capabilities *capabilities) {
    int result;
    const char *input;
    int64_t position_us;
    int play_when_ready;
    int native_playback;
    if (!player || !capabilities || capabilities->size < sizeof(*capabilities))
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    ffplaykmp_stop_worker(player);
    result = ffplaykmp_validate_output(player, capabilities->flags);
    if (result < 0)
        return ffplaykmp_fail(player, result);
    pthread_mutex_lock(&player->mutex);
    player->has_output = 1;
    player->snapshot.output_flags = capabilities->flags;
    player->snapshot.active_decoder = FFPLAYKMP_DECODER_UNKNOWN;
    input = player->input;
    position_us = player->snapshot.position_us;
    play_when_ready = player->play_when_ready;
    native_playback = input && ffplaykmp_can_decode(player, capabilities->flags);
    pthread_mutex_unlock(&player->mutex);
    if (native_playback) {
        result = ffplaykmp_preview(player, position_us, 1);
        if (result < 0)
            return ffplaykmp_fail(player, result);
    }
    pthread_mutex_lock(&player->mutex);
    if (input)
        player->snapshot.state = play_when_ready
                ? FFPLAYKMP_STATE_PLAYING
                : FFPLAYKMP_STATE_READY;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    if (play_when_ready && native_playback) {
        result = ffplaykmp_start_worker(player);
        if (result < 0)
            return ffplaykmp_fail(player, result);
    }
    return 0;
}

void ffplaykmp_player_clear_output(ffplaykmp_player *player) {
    if (!player)
        return;
    ffplaykmp_stop_worker(player);
#if defined(__ANDROID__)
    /* The output that goes may draw on the Surface next; a software codec stays for the next output. */
    ffplaykmp_release_surface_codec(player);
#endif
    pthread_mutex_lock(&player->mutex);
    player->has_output = 0;
    player->snapshot.output_flags = 0;
    player->snapshot.active_decoder = FFPLAYKMP_DECODER_UNKNOWN;
    if (player->input)
        player->snapshot.state = FFPLAYKMP_STATE_WAITING_FOR_OUTPUT;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
}

int ffplaykmp_player_play(ffplaykmp_player *player) {
    int result = ffplaykmp_require_prepared(player);
    int native_playback;
    if (result < 0)
        return result;
    ffplaykmp_stop_worker(player);
    /* A clock left over from the previous run (e.g. one that played to the end)
     * would place a replay far ahead; the host reports a fresh one. */
    ffplaykmp_invalidate_master_clock(player);
    pthread_mutex_lock(&player->mutex);
    if (player->snapshot.duration_us >= 0 &&
            player->snapshot.position_us >= player->snapshot.duration_us) {
        player->snapshot.position_us = 0;
        player->snapshot.queue_serial++;
    }
    player->play_when_ready = 1;
    player->snapshot.state = player->has_output
            ? FFPLAYKMP_STATE_PLAYING
            : FFPLAYKMP_STATE_WAITING_FOR_OUTPUT;
    native_playback = player->has_output &&
            ffplaykmp_can_decode(player, player->snapshot.output_flags);
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    if (native_playback) {
        result = ffplaykmp_start_worker(player);
        if (result < 0)
            return ffplaykmp_fail(player, result);
    }
    return 0;
}

int ffplaykmp_player_pause(ffplaykmp_player *player) {
    int result = ffplaykmp_require_prepared(player);
    if (result < 0)
        return result;
    ffplaykmp_stop_worker(player);
    ffplaykmp_invalidate_master_clock(player);
    pthread_mutex_lock(&player->mutex);
    player->play_when_ready = 0;
    player->snapshot.state = FFPLAYKMP_STATE_PAUSED;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    return 0;
}

int ffplaykmp_player_seek(ffplaykmp_player *player, int64_t position_us) {
    int result = ffplaykmp_require_prepared(player);
    int play_when_ready;
    int native_playback;
    if (result < 0)
        return result;
    if (position_us < 0)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    ffplaykmp_stop_worker(player);
    ffplaykmp_invalidate_master_clock(player);
    pthread_mutex_lock(&player->mutex);
    player->snapshot.state = FFPLAYKMP_STATE_SEEKING;
    player->snapshot.position_us = position_us;
    player->snapshot.queue_serial++;
    play_when_ready = player->play_when_ready;
    native_playback = player->has_output &&
            ffplaykmp_can_decode(player, player->snapshot.output_flags);
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    if (native_playback) {
        result = ffplaykmp_preview(player, position_us, 0);
        if (result < 0)
            return ffplaykmp_fail(player, result);
    }
    pthread_mutex_lock(&player->mutex);
    player->snapshot.state = !player->has_output
            ? FFPLAYKMP_STATE_WAITING_FOR_OUTPUT
            : play_when_ready ? FFPLAYKMP_STATE_PLAYING : FFPLAYKMP_STATE_PAUSED;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    if (play_when_ready && native_playback) {
        result = ffplaykmp_start_worker(player);
        if (result < 0)
            return result;
    }
    return 0;
}

int ffplaykmp_player_stop(ffplaykmp_player *player) {
    if (!player)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    ffplaykmp_stop_worker(player);
    ffplaykmp_player_close_input(player);
    ffplaykmp_invalidate_master_clock(player);
    pthread_mutex_lock(&player->mutex);
    free(player->input);
    player->input = NULL;
    player->play_when_ready = 0;
    player->snapshot.state = FFPLAYKMP_STATE_STOPPED;
    player->snapshot.position_us = 0;
    player->snapshot.duration_us = -1;
    player->snapshot.dropped_frames = 0;
    ffplaykmp_reset_video_metadata(&player->snapshot);
    player->snapshot.active_decoder = FFPLAYKMP_DECODER_UNKNOWN;
    player->snapshot.decoder_fallback = FFPLAYKMP_DECODER_FALLBACK_NONE;
    player->snapshot.queue_serial++;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    return 0;
}

void ffplaykmp_player_cancel(ffplaykmp_player *player) {
    if (player) {
        atomic_store(&player->cancelled, 1);
        ffplaykmp_stop_worker(player);
    }
}

/* Also closes the input, so its IO_CLOSE reaches the mounts it was opened from before a remount. */
void ffplaykmp_player_reset_cancel(ffplaykmp_player *player) {
    if (player) {
        ffplaykmp_stop_worker(player);
        ffplaykmp_player_close_input(player);
        atomic_store(&player->cancelled, 0);
    }
}

void ffplaykmp_player_set_master_clock(ffplaykmp_player *player, int64_t media_time_us) {
    if (!player)
        return;
    pthread_mutex_lock(&player->mutex);
    player->master_clock_valid = media_time_us >= 0;
    player->master_clock_media_us = media_time_us;
    player->master_clock_stamp_us = av_gettime_relative();
    pthread_mutex_unlock(&player->mutex);
}

int ffplaykmp_player_get_snapshot(
        const ffplaykmp_player *player,
        ffplaykmp_snapshot *snapshot) {
    if (!player || !snapshot || snapshot->size < sizeof(*snapshot))
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    pthread_mutex_lock((pthread_mutex_t *)&player->mutex);
    *snapshot = player->snapshot;
    pthread_mutex_unlock((pthread_mutex_t *)&player->mutex);
    return 0;
}

typedef struct ffplaykmp_web_packet_reader {
    ffplaykmp_input input;
    int video_stream;
} ffplaykmp_web_packet_reader;

typedef struct ffplaykmp_web_callbacks {
    ffplaykmp_web_state_callback state;
    ffplaykmp_web_video_frame_callback frame;
    void *opaque;
    ffplaykmp_player *player;
    /* The page gets RGBA bytes, converted here on the decode thread; only it touches these. */
    ffmpegkmp_converter *converter;
    AVFrame *converted;
    pthread_mutex_t mutex;
    char snapshot_json[4096];
    uint32_t snapshot_json_size;
    int snapshot_pending;
    uint8_t *rgba;
    uint32_t rgba_size;
    uint32_t rgba_capacity;
    int32_t width;
    int32_t height;
    int32_t stride;
    int64_t presentation_time_us;
    uint32_t queue_serial;
    int frame_pending;
    uint8_t *input;
    uint32_t input_size;
    ffplaykmp_web_packet_reader *packet_reader;
} ffplaykmp_web_callbacks;

static void ffplaykmp_web_close_packet_reader(ffplaykmp_player *player) {
    ffplaykmp_web_callbacks *callbacks;
    ffplaykmp_web_packet_reader *reader;
    if (!player)
        return;
    callbacks = player->opaque;
    if (!callbacks)
        return;
    reader = callbacks->packet_reader;
    callbacks->packet_reader = NULL;
    if (!reader)
        return;
    ffplaykmp_close_input(&reader->input);
    free(reader);
}

static int ffplaykmp_web_codec_string(
        const AVCodecParameters *parameters,
        char *codec,
        size_t codec_size) {
    const uint8_t *extra = parameters->extradata;
    switch (parameters->codec_id) {
    case AV_CODEC_ID_H264:
        if (parameters->extradata_size >= 4 && extra && extra[0] == 1) {
            return snprintf(
                    codec, codec_size, "avc1.%02X%02X%02X",
                    extra[1], extra[2], extra[3]) > 0 ? 0 : AVERROR(EINVAL);
        }
        return snprintf(codec, codec_size, "avc1.42E01E") > 0 ? 0 : AVERROR(EINVAL);
    case AV_CODEC_ID_HEVC:
        /* Main/Main10 Level 5.1 is a conservative RFC 6381 capability probe.
         * The hvcC description remains authoritative for the actual stream. */
        return snprintf(codec, codec_size, "hvc1.1.6.L153.B0") > 0 ? 0 : AVERROR(EINVAL);
    case AV_CODEC_ID_VP8:
        return snprintf(codec, codec_size, "vp8") > 0 ? 0 : AVERROR(EINVAL);
    case AV_CODEC_ID_VP9:
        return snprintf(
                codec, codec_size,
                parameters->bits_per_raw_sample > 8 ? "vp09.02.10.10" : "vp09.00.10.08") > 0
                ? 0 : AVERROR(EINVAL);
    case AV_CODEC_ID_AV1:
        return snprintf(
                codec, codec_size,
                parameters->bits_per_raw_sample > 8 ? "av01.0.08M.10" : "av01.0.08M.08") > 0
                ? 0 : AVERROR(EINVAL);
    default:
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    }
}

static int64_t ffplaykmp_web_io(
        void *opaque,
        int64_t resource_id,
        uint32_t operation,
        int64_t offset,
        uint8_t *data,
        uint64_t size) {
    ffplaykmp_web_callbacks *callbacks = opaque;
    uint64_t available;
    uint64_t count;
    if (!callbacks || resource_id != 1)
        return -1;
    if (operation == FFPLAYKMP_IO_OPEN)
        return FFPLAYKMP_IO_CAP_READ | FFPLAYKMP_IO_CAP_SEEK;
    if (operation == FFPLAYKMP_IO_SIZE)
        return callbacks->input_size;
    if (operation == FFPLAYKMP_IO_CLOSE)
        return 0;
    if (operation != FFPLAYKMP_IO_READ || offset < 0 || !data)
        return -1;
    if ((uint64_t)offset >= callbacks->input_size)
        return 0;
    available = callbacks->input_size - (uint64_t)offset;
    count = size < available ? size : available;
    memcpy(data, callbacks->input + offset, (size_t)count);
    return (int64_t)count;
}

static void ffplaykmp_web_publish(
        void *opaque,
        const ffplaykmp_snapshot *snapshot) {
    ffplaykmp_web_callbacks *callbacks = opaque;
    char json[4096];
    const char *pixel_format_name;
    int length;
    if (!callbacks || !callbacks->state || !snapshot)
        return;
    pixel_format_name = ffplaykmp_pixel_format_name(snapshot->pixel_format);
    length = snprintf(
            json,
            sizeof(json),
            "{\"state\":%d,\"positionUs\":%lld,\"durationUs\":%lld,"
            "\"queueSerial\":%u,\"outputFlags\":%u,\"errorCode\":%d,"
            "\"videoWidth\":%d,\"videoHeight\":%d,\"activeDecoder\":%d,"
            "\"pixelFormat\":%d,\"pixelFormatName\":\"%s\",\"bitDepth\":%d,"
            "\"sarNum\":%d,\"sarDen\":%d,\"rotation\":%.17g,"
            "\"colorPrimaries\":%d,\"colorTransfer\":%d,\"colorSpace\":%d,"
            "\"colorRange\":%d,\"chromaLocation\":%d,\"hdrFlags\":%d,"
            "\"masteringHasPrimaries\":%d,\"masteringHasLuminance\":%d,"
            "\"masteringRedX\":%.17g,\"masteringRedY\":%.17g,"
            "\"masteringGreenX\":%.17g,\"masteringGreenY\":%.17g,"
            "\"masteringBlueX\":%.17g,\"masteringBlueY\":%.17g,"
            "\"masteringWhiteX\":%.17g,\"masteringWhiteY\":%.17g,"
            "\"masteringMinLuminance\":%.17g,\"masteringMaxLuminance\":%.17g,"
            "\"contentLightPresent\":%d,\"maxContentLightLevel\":%u,"
            "\"maxFrameAverageLightLevel\":%u,\"droppedFrames\":%llu}",
            snapshot->state,
            (long long)snapshot->position_us,
            (long long)snapshot->duration_us,
            snapshot->queue_serial,
            snapshot->output_flags,
            snapshot->last_error,
            snapshot->video_width,
            snapshot->video_height,
            snapshot->active_decoder,
            snapshot->pixel_format,
            pixel_format_name ? pixel_format_name : "",
            snapshot->bit_depth,
            snapshot->sample_aspect_ratio_num,
            snapshot->sample_aspect_ratio_den,
            snapshot->rotation_degrees,
            snapshot->color_primaries,
            snapshot->color_transfer,
            snapshot->color_space,
            snapshot->color_range,
            snapshot->chroma_location,
            snapshot->hdr_flags,
            snapshot->mastering_has_primaries,
            snapshot->mastering_has_luminance,
            snapshot->mastering_red_x,
            snapshot->mastering_red_y,
            snapshot->mastering_green_x,
            snapshot->mastering_green_y,
            snapshot->mastering_blue_x,
            snapshot->mastering_blue_y,
            snapshot->mastering_white_x,
            snapshot->mastering_white_y,
            snapshot->mastering_min_luminance,
            snapshot->mastering_max_luminance,
            snapshot->content_light_present,
            snapshot->max_content_light_level,
            snapshot->max_frame_average_light_level,
            (unsigned long long)snapshot->dropped_frames);
    if (length < 0)
        return;
    if ((size_t)length >= sizeof(json))
        length = (int)sizeof(json) - 1;
    pthread_mutex_lock(&callbacks->mutex);
    memcpy(callbacks->snapshot_json, json, (size_t)length);
    callbacks->snapshot_json[length] = '\0';
    callbacks->snapshot_json_size = (uint32_t)length;
    callbacks->snapshot_pending = 1;
    pthread_mutex_unlock(&callbacks->mutex);
}

static void ffplaykmp_web_frame(
        void *opaque,
        const ffplaykmp_video_frame *frame) {
    ffplaykmp_web_callbacks *callbacks = opaque;
    const AVFrame *decoded;
    AVFrame *converted;
    uint64_t rgba_size;
    int tone_map_hdr;
    if (!callbacks || !callbacks->frame || !callbacks->player || !frame || !frame->frame)
        return;
    decoded = frame->frame->frame;
    pthread_mutex_lock(&callbacks->player->mutex);
    tone_map_hdr = (callbacks->player->snapshot.output_flags & FFPLAYKMP_OUTPUT_TONE_MAP_HDR_TO_SDR) != 0;
    pthread_mutex_unlock(&callbacks->player->mutex);
    if (!callbacks->converter)
        callbacks->converter = ffmpegkmp_converter_alloc(0);
    if (!callbacks->converted)
        callbacks->converted = av_frame_alloc();
    converted = callbacks->converted;
    if (!callbacks->converter || !converted ||
            ffplaykmp_rgb_output(converted, decoded, AV_PIX_FMT_RGBA, tone_map_hdr) < 0 ||
            ffmpegkmp_frame_convert(callbacks->converter, converted, decoded) < 0)
        return;
    rgba_size = (uint64_t)converted->linesize[0] * (uint64_t)converted->height;
    if (rgba_size > UINT32_MAX)
        return;
    pthread_mutex_lock(&callbacks->mutex);
    if (callbacks->rgba_capacity < rgba_size) {
        uint8_t *grown = realloc(callbacks->rgba, (size_t)rgba_size);
        if (!grown) {
            pthread_mutex_unlock(&callbacks->mutex);
            return;
        }
        callbacks->rgba = grown;
        callbacks->rgba_capacity = (uint32_t)rgba_size;
    }
    memcpy(callbacks->rgba, converted->data[0], (size_t)rgba_size);
    callbacks->rgba_size = (uint32_t)rgba_size;
    callbacks->width = converted->width;
    callbacks->height = converted->height;
    callbacks->stride = converted->linesize[0];
    callbacks->presentation_time_us = frame->presentation_time_us;
    callbacks->queue_serial = frame->queue_serial;
    callbacks->frame_pending = 1;
    pthread_mutex_unlock(&callbacks->mutex);
}

ffplaykmp_player *ffplaykmp_web_player_create(
        int32_t decoder_preference,
        int32_t decoder_threads,
        ffplaykmp_web_state_callback state_callback,
        ffplaykmp_web_video_frame_callback frame_callback,
        void *opaque) {
    ffplaykmp_configuration configuration;
    ffplaykmp_web_callbacks *callbacks;
    ffplaykmp_player *player;
    if (decoder_preference < FFPLAYKMP_DECODER_AUTO ||
            decoder_preference > FFPLAYKMP_DECODER_SOFTWARE || decoder_threads < 0)
        return NULL;
    callbacks = calloc(1, sizeof(*callbacks));
    if (!callbacks)
        return NULL;
    callbacks->state = state_callback;
    callbacks->frame = frame_callback;
    callbacks->opaque = opaque;
    if (pthread_mutex_init(&callbacks->mutex, NULL) != 0) {
        free(callbacks);
        return NULL;
    }
    ffplaykmp_configuration_default(&configuration);
    configuration.decoder_preference = decoder_preference;
    configuration.decoder_threads = decoder_threads;
    player = ffplaykmp_player_create(
            &configuration,
            ffplaykmp_web_publish,
            callbacks);
    if (!player) {
        pthread_mutex_destroy(&callbacks->mutex);
        free(callbacks);
        return NULL;
    }
    callbacks->player = player;
    ffplaykmp_player_set_video_frame_callback(
            player,
            ffplaykmp_web_frame,
            callbacks);
    return player;
}

void ffplaykmp_web_player_destroy(ffplaykmp_player *player) {
    ffplaykmp_web_callbacks *callbacks;
    if (!player)
        return;
    callbacks = player->opaque;
    ffplaykmp_web_close_packet_reader(player);
    ffplaykmp_player_destroy(player);
    pthread_mutex_destroy(&callbacks->mutex);
    ffmpegkmp_converter_free(&callbacks->converter);
    av_frame_free(&callbacks->converted);
    free(callbacks->rgba);
    free(callbacks->input);
    free(callbacks);
}

int ffplaykmp_web_player_prepare_bytes(
        ffplaykmp_player *player,
        const uint8_t *bytes,
        uint32_t size,
        const char *extension,
        uint32_t source_flags) {
    ffplaykmp_web_callbacks *callbacks;
    uint8_t *copied;
    char input[64];
    char safe_extension[17];
    size_t index = 0;
    if (!player || !bytes || size == 0)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    callbacks = player->opaque;
    if (!callbacks)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    ffplaykmp_player_reset_cancel(player);
    ffplaykmp_web_close_packet_reader(player);
    copied = malloc(size);
    if (!copied)
        return -ENOMEM;
    memcpy(copied, bytes, size);
    while (extension && extension[index] && index < sizeof(safe_extension) - 1) {
        char value = extension[index];
        if (!((value >= 'a' && value <= 'z') ||
                (value >= 'A' && value <= 'Z') ||
                (value >= '0' && value <= '9')))
            break;
        safe_extension[index++] = value;
    }
    safe_extension[index] = '\0';
    pthread_mutex_lock(&callbacks->mutex);
    free(callbacks->input);
    callbacks->input = copied;
    callbacks->input_size = size;
    pthread_mutex_unlock(&callbacks->mutex);
    ffplaykmp_player_set_io_callback(player, ffplaykmp_web_io, callbacks);
    snprintf(
            input,
            sizeof(input),
            index > 0 ? "ffmpegkmp:1.%s" : "ffmpegkmp:1",
            safe_extension);
    return ffplaykmp_player_prepare(player, input, source_flags);
}

int ffplaykmp_web_player_set_output(
        ffplaykmp_player *player,
        uint32_t output_flags) {
    ffplaykmp_output_capabilities capabilities;
    ffplaykmp_output_capabilities_init(&capabilities);
    capabilities.flags = output_flags;
    return ffplaykmp_player_set_output(player, &capabilities);
}

void ffplaykmp_web_player_poll(ffplaykmp_player *player) {
    ffplaykmp_web_callbacks *callbacks;
    if (!player)
        return;
    callbacks = player->opaque;
    if (!callbacks)
        return;
    pthread_mutex_lock(&callbacks->mutex);
    if (callbacks->snapshot_pending && callbacks->state) {
        callbacks->snapshot_pending = 0;
        callbacks->state(
                callbacks->opaque,
                callbacks->snapshot_json,
                callbacks->snapshot_json_size);
    }
    if (callbacks->frame_pending && callbacks->frame) {
        callbacks->frame_pending = 0;
        callbacks->frame(
                callbacks->opaque,
                callbacks->rgba,
                callbacks->rgba_size,
                callbacks->width,
                callbacks->height,
                callbacks->stride,
                callbacks->presentation_time_us,
                callbacks->queue_serial);
    }
    pthread_mutex_unlock(&callbacks->mutex);
}

int ffplaykmp_web_player_open_packets(
        ffplaykmp_player *player,
        ffplaykmp_web_decoder_config_callback callback,
        void *opaque) {
    ffplaykmp_web_callbacks *callbacks;
    ffplaykmp_web_packet_reader *reader;
    const AVCodecParameters *parameters;
    char codec[64];
    int result;
    if (!player || !callback)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    callbacks = player->opaque;
    if (!callbacks || !callbacks->input || callbacks->input_size == 0)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    ffplaykmp_web_close_packet_reader(player);
    reader = calloc(1, sizeof(*reader));
    if (!reader)
        return AVERROR(ENOMEM);
    /* The worker mounts the prepared bytes as resource 1. */
    result = ffplaykmp_player_open_input(player, "ffmpegkmp:1", &reader->input);
    if (result < 0)
        goto fail;
    reader->video_stream = av_find_best_stream(
            reader->input.format, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (reader->video_stream < 0) {
        result = reader->video_stream;
        goto fail;
    }
    parameters = reader->input.format->streams[reader->video_stream]->codecpar;
    result = ffplaykmp_web_codec_string(parameters, codec, sizeof(codec));
    if (result < 0)
        goto fail;
    callbacks->packet_reader = reader;
    callback(
            opaque,
            codec,
            parameters->extradata,
            parameters->extradata_size > 0 ? (uint32_t)parameters->extradata_size : 0,
            parameters->width,
            parameters->height,
            parameters->color_primaries,
            parameters->color_trc,
            parameters->color_space);
    return 0;
fail:
    callbacks->packet_reader = reader;
    ffplaykmp_web_close_packet_reader(player);
    return result;
}

int ffplaykmp_web_player_read_packet(
        ffplaykmp_player *player,
        ffplaykmp_web_encoded_packet_callback callback,
        void *opaque) {
    ffplaykmp_web_callbacks *callbacks;
    ffplaykmp_web_packet_reader *reader;
    AVPacket *packet;
    AVStream *stream;
    int64_t timestamp;
    int64_t duration;
    uint32_t queue_serial;
    int result;
    if (!player || !callback)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    callbacks = player->opaque;
    reader = callbacks ? callbacks->packet_reader : NULL;
    if (!reader || !reader->input.format)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    packet = av_packet_alloc();
    if (!packet)
        return AVERROR(ENOMEM);
    while ((result = av_read_frame(reader->input.format, packet)) >= 0) {
        if (packet->stream_index != reader->video_stream) {
            av_packet_unref(packet);
            continue;
        }
        stream = reader->input.format->streams[reader->video_stream];
        timestamp = packet->pts != AV_NOPTS_VALUE ? packet->pts : packet->dts;
        if (timestamp == AV_NOPTS_VALUE)
            timestamp = 0;
        timestamp = av_rescale_q(timestamp, stream->time_base, AV_TIME_BASE_Q) -
                ffplaykmp_media_start_us(reader->input.format);
        if (timestamp < 0)
            timestamp = 0;
        duration = packet->duration > 0
                ? av_rescale_q(packet->duration, stream->time_base, AV_TIME_BASE_Q)
                : 0;
        pthread_mutex_lock(&player->mutex);
        queue_serial = player->snapshot.queue_serial;
        pthread_mutex_unlock(&player->mutex);
        callback(
                opaque,
                packet->data,
                packet->size > 0 ? (uint32_t)packet->size : 0,
                timestamp,
                duration,
                (packet->flags & AV_PKT_FLAG_KEY) != 0,
                queue_serial,
                packet->pts != AV_NOPTS_VALUE ? packet->pts : packet->dts,
                packet->duration > 0 ? packet->duration : 0);
        av_packet_free(&packet);
        return 0;
    }
    av_packet_free(&packet);
    return result == AVERROR_EOF ? 1 : result;
}

void ffplaykmp_web_player_close_packets(ffplaykmp_player *player) {
    ffplaykmp_web_close_packet_reader(player);
}

static AVStream *ffplaykmp_web_packet_stream(ffplaykmp_player *player, AVFormatContext **format) {
    ffplaykmp_web_callbacks *callbacks = player ? player->opaque : NULL;
    ffplaykmp_web_packet_reader *reader = callbacks ? callbacks->packet_reader : NULL;
    if (!reader || !reader->input.format)
        return NULL;
    *format = reader->input.format;
    return reader->input.format->streams[reader->video_stream];
}

int ffplaykmp_web_player_packet_timing(ffplaykmp_player *player, int64_t *values) {
    AVFormatContext *format;
    AVStream *stream = ffplaykmp_web_packet_stream(player, &format);
    if (!stream || !values)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    values[0] = stream->time_base.num;
    values[1] = stream->time_base.den;
    ffplaykmp_stream_timing(format, stream, &values[2], &values[3], &values[4]);
    return 0;
}

int ffplaykmp_web_player_seek_packets(ffplaykmp_player *player, int64_t target) {
    AVFormatContext *format;
    AVStream *stream = ffplaykmp_web_packet_stream(player, &format);
    int result;
    if (!stream)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    /* The keyframe at or before the target, never after it, as the pull decoder seeks. */
    result = avformat_seek_file(format, stream->index, INT64_MIN, target, target, AVSEEK_FLAG_BACKWARD);
    if (result < 0)
        result = avformat_seek_file(format, stream->index, INT64_MIN, target, INT64_MAX, AVSEEK_FLAG_BACKWARD);
    return result;
}

int64_t ffplaykmp_web_player_keyframe_before(ffplaykmp_player *player, int64_t target) {
    AVFormatContext *format;
    AVStream *stream = ffplaykmp_web_packet_stream(player, &format);
    const AVIndexEntry *entry;
    int index;
    if (!stream)
        return AV_NOPTS_VALUE;
    index = av_index_search_timestamp(stream, target, AVSEEK_FLAG_BACKWARD);
    entry = index >= 0 ? avformat_index_get_entry(stream, index) : NULL;
    return entry ? entry->timestamp : AV_NOPTS_VALUE;
}

int ffplaykmp_web_player_set_webcodecs_output(
        ffplaykmp_player *player,
        uint32_t output_flags) {
    int result;
    if (!player)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    result = ffplaykmp_validate_output(player, output_flags);
    if (result < 0)
        return result;
    pthread_mutex_lock(&player->mutex);
    player->has_output = 1;
    player->snapshot.output_flags = output_flags;
    /* WebCodecs reports an acceleration preference, not the decoder it chose. */
    player->snapshot.active_decoder = FFPLAYKMP_DECODER_UNKNOWN;
    player->snapshot.last_error = 0;
    player->snapshot.state = player->input
            ? FFPLAYKMP_STATE_READY
            : player->snapshot.state;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    return 0;
}

int ffplaykmp_web_player_webcodecs_play(ffplaykmp_player *player) {
    if (ffplaykmp_require_prepared(player) < 0)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    pthread_mutex_lock(&player->mutex);
    player->play_when_ready = 1;
    player->snapshot.state = player->has_output
            ? FFPLAYKMP_STATE_PLAYING
            : FFPLAYKMP_STATE_WAITING_FOR_OUTPUT;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    return 0;
}

int ffplaykmp_web_player_webcodecs_pause(ffplaykmp_player *player) {
    if (ffplaykmp_require_prepared(player) < 0)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    pthread_mutex_lock(&player->mutex);
    player->play_when_ready = 0;
    player->snapshot.state = FFPLAYKMP_STATE_PAUSED;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    return 0;
}

int ffplaykmp_web_player_webcodecs_seek(
        ffplaykmp_player *player,
        int64_t position_us) {
    ffplaykmp_web_callbacks *callbacks;
    ffplaykmp_web_packet_reader *reader;
    AVStream *stream;
    int64_t target;
    int play_when_ready;
    int result;
    if (!player || position_us < 0 || ffplaykmp_require_prepared(player) < 0)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    callbacks = player->opaque;
    reader = callbacks ? callbacks->packet_reader : NULL;
    if (!reader || !reader->input.format)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    stream = reader->input.format->streams[reader->video_stream];
    target = av_rescale_q(
            ffplaykmp_media_start_us(reader->input.format) + position_us,
            AV_TIME_BASE_Q,
            stream->time_base);
    pthread_mutex_lock(&player->mutex);
    play_when_ready = player->play_when_ready;
    player->snapshot.state = FFPLAYKMP_STATE_SEEKING;
    player->snapshot.position_us = position_us;
    player->snapshot.queue_serial++;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    result = avformat_seek_file(
            reader->input.format,
            reader->video_stream,
            INT64_MIN,
            target,
            INT64_MAX,
            AVSEEK_FLAG_BACKWARD);
    pthread_mutex_lock(&player->mutex);
    if (result < 0) {
        player->snapshot.last_error = result;
        player->snapshot.state = FFPLAYKMP_STATE_FAILED;
    } else {
        player->snapshot.state = play_when_ready
                ? FFPLAYKMP_STATE_PLAYING
                : FFPLAYKMP_STATE_PAUSED;
    }
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    return result;
}

int ffplaykmp_web_player_webcodecs_presented(
        ffplaykmp_player *player,
        int64_t position_us,
        uint32_t queue_serial,
        int32_t dropped) {
    if (!player)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    pthread_mutex_lock(&player->mutex);
    if (queue_serial != player->snapshot.queue_serial) {
        pthread_mutex_unlock(&player->mutex);
        return FFPLAYKMP_ERROR_STALE;
    }
    player->snapshot.position_us = position_us;
    if (dropped)
        player->snapshot.dropped_frames++;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    return 0;
}

void ffplaykmp_web_player_webcodecs_end(ffplaykmp_player *player) {
    if (!player)
        return;
    pthread_mutex_lock(&player->mutex);
    if (player->snapshot.duration_us >= 0)
        player->snapshot.position_us = player->snapshot.duration_us;
    player->snapshot.state = FFPLAYKMP_STATE_ENDED;
    player->play_when_ready = 0;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
}
