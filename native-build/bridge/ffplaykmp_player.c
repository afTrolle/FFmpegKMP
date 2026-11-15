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
#include <time.h>

#include <libavcodec/avcodec.h>
#if defined(__ANDROID__)
#include <libavcodec/mediacodec.h>
#endif
#include <libavformat/avformat.h>
#include <libavutil/avstring.h>
#include <libavutil/mem.h>
#include <libavutil/pixdesc.h>
#include <libavutil/time.h>

/*
 * What decoding the player's input holds. The input is opened and probed once,
 * by prepare, and kept until stop, destroy, reset_cancel or the next prepare.
 * The codec is opened by the first run that needs it and kept for the runs
 * after it while it suits them: its key is whether the output wanted a
 * hardware decoder, the Surface it renders to and the input it decodes, and
 * released once the player has been idle for codec_release_delay_us. Only
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
    /* The stream time of position zero, as VideoDecoder counts it (ffplaykmp_stream_timing). */
    int64_t origin;
    ffplaykmp_video_codec codec;
    int codec_open;
    int codec_hardware_wanted;
    void *codec_surface;
    uint32_t codec_input;
    /*
     * AUTO: why codecs open in software, FAILED or BUDGET, until the next
     * startup (ffplaykmp_session_forget_fallback): hardware is chosen only
     * then, never in the middle of playback.
     */
    ffplaykmp_decoder_fallback fallback;
    /* A startup forgot the fallback a software codec still open was chosen for: it reopens, trying hardware. */
    int choose_again;
#if defined(__ANDROID__)
    /*
     * AUTO: the hardware decoder budget was full when a codec for this Surface
     * opened, so the canvas drew the software frames on it, after which
     * MediaCodec can no longer connect to it: its codecs open in software,
     * reporting the budget, until a new Surface. Not for the output's own
     * Surface, which software frames do not reach.
     */
    int budget_fallback;
#endif
    /*
     * The decoder goes on from the last frame it gave out and the demuxer from
     * the last packet the decoder took, so a run forward needs no seek: the
     * frames after the one on screen are the queued ones, then `next`, then the
     * decoder's.
     */
    int in_sync;
    /*
     * Counts the unbroken runs of decoded frames: it moves on whenever the
     * decoder gives out a frame that is neither shown nor queued, or forgets
     * where it was. The frame on screen continues the decoder only while it
     * was presented in this chain (ffplaykmp_frame_queue.shown_chain).
     */
    uint32_t chain;
    /* `packet` was read and the decoder has not taken it yet. */
    int packet_pending;
    /* The input ended and the decoder was told so: it gives out the frames it has left, and takes no packet. */
    int draining;
    /* A frame's duration from the stream's frame rate, as ffplay takes it; 0 when unknown. */
    int64_t frame_duration_us;
    /* ffplay's max_frame_duration: a timestamp step longer than this is a discontinuity, not a duration. */
    int64_t max_frame_duration_us;
    AVPacket *packet;
    AVFrame *frame;
    /* The latest frame before the target, as ffplaykmp_present_ready_frames keeps it. */
    AVFrame *held;
    /*
     * A frame the decoder gave out that neither went on screen nor into the
     * queue: the one after a preview's target, or the one a play run held when
     * it was stopped while the queue was full. The next run takes it first.
     */
    AVFrame *next;
    /*
     * Catch-up (ffplaykmp_session_catch_up): the keyframe a skip asked for, in
     * the stream's time base, and the earliest it may land on, the master
     * clock's position then; `skipped` once a skip went to `skipped_keyframe`,
     * which the next must pass. While `landing`, the first frame decoded after
     * the skip counts the frames it passed over from `skipped_from_us`, under
     * `skipped_serial`.
     */
    int64_t catch_up_ts;
    int64_t catch_up_min_ts;
    int skipped;
    int64_t skipped_keyframe;
    int landing;
    int64_t skipped_from_us;
    uint32_t skipped_serial;
    /* Set by the landing frame for the next frame queued, which is timed from the master clock. */
    int discontinuity;
} ffplaykmp_session;

/*
 * ffplay's picture queue (FrameQueue) and the display state video_refresh
 * keeps beside it.
 *
 * Locking. `mutex` guards everything here. Lock order is session_mutex, then
 * this `mutex`, then the player's `mutex`; job_mutex may be held while taking
 * this one. Nothing holds this mutex across a callback: whoever takes frames
 * out of the ring to present or release them marks them `in_flight`, unlocks,
 * calls out, and clears `in_flight` again under the mutex, broadcasting
 * `cond`. Only one taker works at a time (each waits for in_flight == 0
 * first), so a frame being presented is never released under it, and a seek
 * that flushes waits until a frame taken before it has been presented. Calls of
 * ffplaykmp_player_refresh are serialized by `refresh_mutex`, held across its
 * callbacks and taken by nothing else.
 *
 * The session thread is the only producer. It waits on `cond` while the ring
 * holds `capacity` frames, counting those in flight; a refresh that takes
 * frames, a flush, and the abort that pause, seek, stop, cancel and destroy
 * raise (ffplaykmp_wake_queue) wake it. At the end of the input it waits the
 * same way until the ring is empty, so ENDED follows the last frame shown.
 * refresh never takes session_mutex or job_mutex, so it never waits for
 * decoding or I/O; while it presents, a seek or stop waits for it (in_flight).
 * Destroying the player ends the session thread, which flushes the ring; the
 * caller must not refresh concurrently with or after destroy (the Kotlin
 * bridge's handle guard ensures it).
 */
typedef enum ffplaykmp_queued_kind {
    /* Nothing to show: no output took it. It still moves the clock and the position. */
    FFPLAYKMP_QUEUED_NONE = 0,
    /* The software output prepared it under `token`. */
    FFPLAYKMP_QUEUED_SOFTWARE,
    /* A hardware frame the platform output imports as it is shown (Apple). */
    FFPLAYKMP_QUEUED_PLATFORM,
    /* A MediaCodec output buffer, rendered to the Surface as it is shown. */
    FFPLAYKMP_QUEUED_MEDIACODEC,
} ffplaykmp_queued_kind;

typedef struct ffplaykmp_queued_frame {
    /* The decoded frame; allocated once per slot. */
    AVFrame *frame;
    /* When it is shown, as a position: a frame decoded before a run's start is shown at the start. */
    int64_t pts_us;
    /* The frame's own media time. */
    int64_t media_us;
    /* The frame's own timestamp, in the stream's time base: what a followed clock's position is compared with. */
    int64_t stream_pts;
    int64_t duration_us;
    /* The serial the frame was decoded under. */
    uint32_t serial;
    uint32_t chain;
    ffplaykmp_queued_kind kind;
    uint64_t token;
    /* While in flight: presented rather than released. */
    int presented;
    /* While in flight and presented: when it is due on screen, on the refresh caller's clock. */
    int64_t display_us;
    /*
     * The first frame queued after a catch-up skip: due when the master clock
     * reaches it, not a frame duration after the frame consumed before the skip.
     */
    int discontinuity;
} ffplaykmp_queued_frame;

/* ffplay's Clock, in microseconds on the refresh caller's clock. */
typedef struct ffplaykmp_clock {
    int valid;
    int64_t pts_us;
    int64_t updated_us;
} ffplaykmp_clock;

typedef struct ffplaykmp_frame_queue {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    pthread_mutex_t refresh_mutex;
    ffplaykmp_queued_frame *slots;
    int capacity;
    /* The oldest queued frame; `count` follow it, and the `in_flight` before it are being presented or released. */
    int rindex;
    int count;
    int in_flight;
    /* A taker is presenting or releasing frames (the in-flight ones, or a preview's) outside the mutex. */
    int busy;
    /* A play run is on: refresh shows frames only meanwhile. */
    int playing;
    uint64_t next_token;
    /*
     * The frame consumed last, shown or dropped (ffplay's lastvp): the next
     * one is due a frame duration after it.
     */
    int last_valid;
    int64_t last_pts_us;
    int64_t last_duration_us;
    uint32_t last_serial;
    /*
     * The frame on screen: its media time, the chain it continues, and a
     * reference to it, so that a preview at a target it covers shows it again;
     * none for a MediaCodec frame, which would hold its codec open.
     */
    int shown_valid;
    int64_t shown_us;
    uint32_t shown_chain;
    AVFrame *shown;
    /* ffplay's frame_timer: when the frame consumed last was due. Invalid until a play run's first refresh. */
    int frame_timer_valid;
    int64_t frame_timer_us;
    ffplaykmp_clock video_clock;
    /* The master clock without audio: set from the video clock, then running on its own (ffplay's extclk). */
    ffplaykmp_clock external_clock;
    int64_t max_frame_duration_us;
    /*
     * The last refresh's time, on its caller's clock (`refresh_time_us`), and
     * on av_gettime_relative's then: the session thread reads the clocks on
     * the caller's clock through them.
     */
    int refresh_time_valid;
    int64_t refresh_time_us;
    int64_t refresh_real_us;
    /*
     * While the last refresh followed an external clock, and until a play,
     * pause, seek or stop: the position it gave, the master clock catching up
     * compares with. Not extrapolated: the clock's own next position says
     * where it went.
     */
    int following;
    int64_t follow_position_us;
    /* The queued frames' time base and the stream time of position zero, as the session thread queues them. */
    AVRational time_base;
    int64_t origin;
} ffplaykmp_frame_queue;

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
    /*
     * The session thread's own, never posted: closes the codec of a player
     * idle for its configuration's codec_release_delay_us.
     */
    FFPLAYKMP_JOB_RELEASE_IDLE,
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
    ffplaykmp_frame_queue queue;
    /*
     * Desktop hardware frames are downloaded into its two buffers while one is
     * free (ffmpegkmp_frame_set_download_pool). Only the session thread uses
     * it, and replaces it with an empty one as it releases an idle codec.
     */
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
    /* What prepare's probe found, while the source it probed is prepared. Guarded by mutex. */
    ffplaykmp_input_hint input_hint;
    int input_hint_valid;
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

static void ffplaykmp_invalidate_master_clock(ffplaykmp_player *player) {
    /* A followed position from before a seek would make the frames after it look late. */
    pthread_mutex_lock(&player->queue.mutex);
    player->queue.following = 0;
    pthread_mutex_unlock(&player->queue.mutex);
    pthread_mutex_lock(&player->mutex);
    player->master_clock_valid = 0;
    pthread_mutex_unlock(&player->mutex);
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

static int ffplaykmp_is_mediacodec_frame(const AVFrame *frame) {
#if defined(__ANDROID__)
    return frame->format == AV_PIX_FMT_MEDIACODEC;
#else
    (void)frame;
    return 0;
#endif
}

/* Raises nothing itself: wakes whoever waits on the queue, after an abort was raised. */
static void ffplaykmp_wake_queue(ffplaykmp_player *player) {
    pthread_mutex_lock(&player->queue.mutex);
    pthread_cond_broadcast(&player->queue.cond);
    pthread_mutex_unlock(&player->queue.mutex);
}

/* Becomes the queue's only taker, with its mutex held. */
static void ffplaykmp_queue_begin_take(ffplaykmp_frame_queue *queue) {
    while (queue->busy)
        pthread_cond_wait(&queue->cond, &queue->mutex);
    queue->busy = 1;
}

/* Ends a take, with the queue's mutex held: the frames in flight are gone. */
static void ffplaykmp_queue_end_take(ffplaykmp_frame_queue *queue) {
    queue->in_flight = 0;
    queue->busy = 0;
    pthread_cond_broadcast(&queue->cond);
}

static ffplaykmp_queued_frame *ffplaykmp_queue_slot(ffplaykmp_frame_queue *queue, int index) {
    return &queue->slots[((index % queue->capacity) + queue->capacity) % queue->capacity];
}

/*
 * Calls the software output's callback for `decoded` with `event`. For PREPARE
 * returns whether the output took the frame.
 */
static int ffplaykmp_call_video_frame(
        ffplaykmp_player *player,
        ffplaykmp_video_frame_callback callback,
        void *callback_opaque,
        const AVFrame *decoded,
        uint32_t event,
        uint64_t token,
        int64_t presentation_time_us,
        uint32_t serial,
        int64_t display_time_us) {
    ffplaykmp_video_frame frame;
    ffmpegkmp_frame *handle = NULL;
    int result;
    memset(&frame, 0, sizeof(frame));
    frame.size = sizeof(frame);
    if (decoded) {
        /*
         * The decoded frame itself, not a copy: whoever keeps it converts it once, into its own
         * memory. A hardware frame is downloaded then, once, into one of the player's buffers.
         */
        if (!(handle = ffmpegkmp_frame_from_av(decoded)))
            return 0;
        if (ffplaykmp_is_hardware_frame(decoded))
            ffmpegkmp_frame_set_download_pool(handle, player->download_pool);
        /* Whoever converts it works with the threads the player was given for its video. */
        ffmpegkmp_frame_set_converter_threads(handle, player->configuration.decoder_threads);
        frame.frame = handle;
        frame.width = decoded->width;
        frame.height = decoded->height;
    }
    frame.presentation_time_us = presentation_time_us;
    frame.queue_serial = serial;
    frame.event = event;
    frame.token = token;
    frame.display_time_us = display_time_us;
    result = callback(callback_opaque, &frame);
    if (handle)
        ffmpegkmp_frame_unref(handle);
    return result;
}

/* The software output's callback, while it takes this player's frames; else NULL. */
static ffplaykmp_video_frame_callback ffplaykmp_software_output(ffplaykmp_player *player, void **opaque) {
    ffplaykmp_video_frame_callback callback;
    pthread_mutex_lock(&player->mutex);
    callback = player->video_frame_callback;
    *opaque = player->video_frame_opaque;
    if (!player->has_output || !(player->snapshot.output_flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD) ||
            (player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH))
        callback = NULL;
    pthread_mutex_unlock(&player->mutex);
    return callback;
}

#if defined(__APPLE__)
/* The platform output's callback, while it imports this player's hardware frames; else NULL. */
static ffplaykmp_platform_video_frame_callback ffplaykmp_platform_output(ffplaykmp_player *player, void **opaque) {
    ffplaykmp_platform_video_frame_callback callback;
    pthread_mutex_lock(&player->mutex);
    callback = player->platform_video_frame_callback;
    *opaque = player->platform_video_frame_opaque;
    if (!player->has_output || !(player->snapshot.output_flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT))
        callback = NULL;
    pthread_mutex_unlock(&player->mutex);
    return callback;
}

static void ffplaykmp_emit_platform_video_frame(
        ffplaykmp_player *player,
        const AVFrame *decoded,
        int64_t presentation_time_us,
        uint32_t serial) {
    ffplaykmp_platform_video_frame frame;
    void *callback_opaque;
    ffplaykmp_platform_video_frame_callback callback = ffplaykmp_platform_output(player, &callback_opaque);
    if (!callback || !decoded->data[3])
        return;
    memset(&frame, 0, sizeof(frame));
    frame.size = sizeof(frame);
    frame.kind = FFPLAYKMP_PLATFORM_FRAME_CV_PIXEL_BUFFER;
    frame.handle = decoded->data[3];
    frame.width = decoded->width;
    frame.height = decoded->height;
    frame.presentation_time_us = presentation_time_us;
    frame.queue_serial = serial;
    callback(callback_opaque, &frame);
}
#endif

/*
 * How `frame` reaches the output: a MediaCodec buffer rendered to the Surface,
 * a hardware frame the platform output imports, or, prepared by the software
 * output under `token` (the callback said it took it), a software frame; else
 * nothing. Runs on the session thread.
 */
static ffplaykmp_queued_kind ffplaykmp_prepare_frame(
        ffplaykmp_player *player,
        const AVFrame *frame,
        uint64_t token,
        int64_t presentation_time_us,
        uint32_t serial) {
    ffplaykmp_video_frame_callback callback;
    void *callback_opaque;
#if defined(__ANDROID__)
    if (ffplaykmp_is_mediacodec_frame(frame)) {
        uint32_t flags;
        pthread_mutex_lock(&player->mutex);
        flags = player->snapshot.output_flags;
        pthread_mutex_unlock(&player->mutex);
        if (!(flags & FFPLAYKMP_OUTPUT_PREPARE_HARDWARE_FRAMES))
            return FFPLAYKMP_QUEUED_MEDIACODEC;
        /* Rendered now, into the output's own Surface, which the callback waits on for its buffer. */
        callback = ffplaykmp_software_output(player, &callback_opaque);
        if (callback && av_mediacodec_render_buffer_at_time(
                    (AVMediaCodecBuffer *)frame->data[3], presentation_time_us * 1000) >= 0 &&
                ffplaykmp_call_video_frame(player, callback, callback_opaque, frame,
                        FFPLAYKMP_VIDEO_FRAME_PREPARE, token, presentation_time_us, serial, -1))
            return FFPLAYKMP_QUEUED_SOFTWARE;
        return FFPLAYKMP_QUEUED_NONE;
    }
#endif
#if defined(__APPLE__)
    if (ffplaykmp_is_hardware_frame(frame) && frame->data[3]) {
        void *platform_opaque;
        /* An output that cannot import it takes it as a software frame, its CVPixelBuffer read in place or downloaded. */
        if (ffplaykmp_platform_output(player, &platform_opaque))
            return FFPLAYKMP_QUEUED_PLATFORM;
    }
#endif
    callback = ffplaykmp_software_output(player, &callback_opaque);
    if (callback && ffplaykmp_call_video_frame(player, callback, callback_opaque, frame,
            FFPLAYKMP_VIDEO_FRAME_PREPARE, token, presentation_time_us, serial, -1))
        return FFPLAYKMP_QUEUED_SOFTWARE;
    return FFPLAYKMP_QUEUED_NONE;
}

/*
 * Shows a prepared frame. `display_us` is when it is due on screen, on the
 * refresh caller's clock, as a software output's PRESENT says it, or -1 for at
 * once (a preview); `render_us` is the same moment on av_gettime_relative's
 * clock, when MediaCodec renders a buffer. Returns 0 or a negative error.
 */
static int ffplaykmp_present_prepared(
        ffplaykmp_player *player,
        const AVFrame *frame,
        ffplaykmp_queued_kind kind,
        uint64_t token,
        int64_t presentation_time_us,
        uint32_t serial,
        int64_t display_us,
        int64_t render_us) {
    void *callback_opaque;
    ffplaykmp_video_frame_callback callback;
    (void)render_us;
    pthread_mutex_lock(&player->mutex);
    ffplaykmp_read_frame_metadata(&player->snapshot, frame);
    pthread_mutex_unlock(&player->mutex);
    switch (kind) {
    case FFPLAYKMP_QUEUED_SOFTWARE:
        pthread_mutex_lock(&player->mutex);
        callback = player->video_frame_callback;
        callback_opaque = player->video_frame_opaque;
        pthread_mutex_unlock(&player->mutex);
        /* The output made what it shows at PREPARE: no frame now, so nothing here can fail to call it. */
        if (callback)
            ffplaykmp_call_video_frame(player, callback, callback_opaque, NULL,
                    FFPLAYKMP_VIDEO_FRAME_PRESENT, token, presentation_time_us, serial, display_us);
        return 0;
#if defined(__APPLE__)
    case FFPLAYKMP_QUEUED_PLATFORM:
        ffplaykmp_emit_platform_video_frame(player, frame, presentation_time_us, serial);
        return 0;
#endif
#if defined(__ANDROID__)
    case FFPLAYKMP_QUEUED_MEDIACODEC: {
        AVMediaCodecBuffer *buffer = (AVMediaCodecBuffer *)frame->data[3];
        if (!buffer)
            return AVERROR_INVALIDDATA;
        /*
         * Handed over ahead of its time, the buffer is queued to the Surface with its due time and
         * SurfaceFlinger latches it at the vsync for that time, not at the next vsync after this
         * call. MediaCodec's render time is System.nanoTime's, the clock av_gettime_relative reads.
         */
        return av_mediacodec_render_buffer_at_time(buffer, render_us * 1000) < 0 ? AVERROR_EXTERNAL : 0;
    }
#endif
    default:
        return 0;
    }
}

/* Gives up a prepared frame that will not be shown; the caller unreferences the frame, which gives a MediaCodec buffer back. */
static void ffplaykmp_release_prepared(
        ffplaykmp_player *player,
        ffplaykmp_queued_kind kind,
        uint64_t token,
        int64_t presentation_time_us,
        uint32_t serial) {
    ffplaykmp_video_frame_callback callback;
    void *callback_opaque;
    if (kind != FFPLAYKMP_QUEUED_SOFTWARE)
        return;
    pthread_mutex_lock(&player->mutex);
    callback = player->video_frame_callback;
    callback_opaque = player->video_frame_opaque;
    pthread_mutex_unlock(&player->mutex);
    if (callback)
        ffplaykmp_call_video_frame(player, callback, callback_opaque, NULL,
                FFPLAYKMP_VIDEO_FRAME_RELEASE, token, presentation_time_us, serial, -1);
}

/*
 * Releases every queued frame, waiting for a refresh that is presenting one;
 * unless `keep_clocks`, the display clocks start again with the next play run.
 * Returns how many frames were queued. With session_mutex held, or on the
 * session thread.
 */
static int ffplaykmp_queue_release_all(ffplaykmp_player *player, int keep_clocks) {
    ffplaykmp_frame_queue *queue = &player->queue;
    int flushed;
    pthread_mutex_lock(&queue->mutex);
    ffplaykmp_queue_begin_take(queue);
    flushed = queue->count;
    queue->rindex = (queue->rindex + flushed) % queue->capacity;
    queue->count = 0;
    queue->in_flight = flushed;
    if (!keep_clocks) {
        queue->frame_timer_valid = 0;
        queue->video_clock.valid = 0;
        queue->external_clock.valid = 0;
    }
    pthread_mutex_unlock(&queue->mutex);
    for (int i = 0; i < flushed; i++) {
        ffplaykmp_queued_frame *slot = ffplaykmp_queue_slot(queue, queue->rindex - flushed + i);
        ffplaykmp_release_prepared(player, slot->kind, slot->token, slot->pts_us, slot->serial);
        av_frame_unref(slot->frame);
    }
    pthread_mutex_lock(&queue->mutex);
    ffplaykmp_queue_end_take(queue);
    pthread_mutex_unlock(&queue->mutex);
    return flushed;
}

/* Releases every queued frame; the display clocks start again with the next play run. */
static int ffplaykmp_queue_flush(ffplaykmp_player *player) {
    return ffplaykmp_queue_release_all(player, 0);
}

/* A queued frame's duration, ffplay's frame-rate one where the stream has a rate. */
static int64_t ffplaykmp_frame_duration_us(const ffplaykmp_session *session, const AVFrame *frame) {
    if (session->frame_duration_us > 0)
        return session->frame_duration_us;
    return frame->duration > 0 ? av_rescale_q(frame->duration, session->stream->time_base, AV_TIME_BASE_Q) : 0;
}

/* A play run's queue stayed full with no refresh for the codec's release delay. */
#define FFPLAYKMP_RELEASE_IDLE FFERRTAG('I', 'D', 'L', 'E')
/* The least time without a refresh that counts as the output no longer drawn, whatever the release delay: many refresh intervals. */
#define FFPLAYKMP_REFRESH_STALL_US 1000000

/*
 * Queues `frame`, shown at `presentation_time_us`, for ffplaykmp_player_refresh,
 * once the queue has room: taking it on success, which the output prepares now.
 * Returns 0, AVERROR_EXIT when the run was aborted while it waited (the caller
 * keeps the frame), FFPLAYKMP_RELEASE_IDLE when nothing refreshed for the
 * codec's release delay, and FFPLAYKMP_REFRESH_STALL_US at least, while it
 * waited, as for an output no longer drawn, or another negative error. On the
 * session thread.
 */
static int ffplaykmp_queue_frame(ffplaykmp_player *player, AVFrame *frame, int64_t presentation_time_us) {
    ffplaykmp_session *session = &player->session;
    ffplaykmp_frame_queue *queue = &player->queue;
    const int mediacodec = ffplaykmp_is_mediacodec_frame(frame);
    const int64_t release_delay_us = player->configuration.codec_release_delay_us < 0
            ? -1
            : FFMAX(player->configuration.codec_release_delay_us, FFPLAYKMP_REFRESH_STALL_US);
    int64_t waiting_since_us;
    int limit = queue->capacity;
    uint32_t flags;
    ffplaykmp_queued_frame *slot;
    uint32_t serial;
    uint64_t token;
    if (mediacodec && !frame->data[3])
        return AVERROR_INVALIDDATA;
    /* The serial the frame was decoded under. */
    pthread_mutex_lock(&player->mutex);
    serial = player->snapshot.queue_serial;
    flags = player->snapshot.output_flags;
    pthread_mutex_unlock(&player->mutex);
    /* An output that prepares hardware frames says how many of its buffers they may hold. */
    if (mediacodec && (flags & FFPLAYKMP_OUTPUT_PREPARE_HARDWARE_FRAMES)) {
        const int held = (int)((flags >> FFPLAYKMP_OUTPUT_HARDWARE_FRAMES_SHIFT) & FFPLAYKMP_OUTPUT_HARDWARE_FRAMES_MASK);
        if (held > 0 && held < limit)
            limit = held;
    }
    pthread_mutex_lock(&queue->mutex);
    waiting_since_us = av_gettime_relative();
    while (queue->count + queue->in_flight >= limit) {
        int64_t idle_us;
        int64_t wake_us;
        struct timespec until;
        if (ffplaykmp_is_aborted(player)) {
            pthread_mutex_unlock(&queue->mutex);
            return AVERROR_EXIT;
        }
        if (release_delay_us < 0) {
            pthread_cond_wait(&queue->cond, &queue->mutex);
            continue;
        }
        idle_us = av_gettime_relative() - FFMAX(waiting_since_us, queue->refresh_real_us);
        if (idle_us >= release_delay_us) {
            pthread_mutex_unlock(&queue->mutex);
            return FFPLAYKMP_RELEASE_IDLE;
        }
        /* The wall clock, which pthread_cond_timedwait measures by default. */
        wake_us = av_gettime() + release_delay_us - idle_us;
        until.tv_sec = (time_t)(wake_us / 1000000);
        until.tv_nsec = (long)(wake_us % 1000000) * 1000;
        pthread_cond_timedwait(&queue->cond, &queue->mutex, &until);
    }
    /* Taking frames moves rindex on by as many as it takes from count, so this slot stays free. */
    slot = ffplaykmp_queue_slot(queue, queue->rindex + queue->count);
    token = queue->next_token++;
    queue->max_frame_duration_us = session->max_frame_duration_us;
    queue->time_base = session->stream->time_base;
    queue->origin = session->origin;
    pthread_mutex_unlock(&queue->mutex);
    av_frame_move_ref(slot->frame, frame);
    slot->pts_us = presentation_time_us;
    slot->media_us = ffplaykmp_frame_time_us(
            slot->frame, session->stream->time_base, session->media_start_us, presentation_time_us);
    /* As VideoDecoder times a frame: its best-effort timestamp, else its pts, else where the player put it. */
    slot->stream_pts = slot->frame->best_effort_timestamp != AV_NOPTS_VALUE
            ? slot->frame->best_effort_timestamp
            : slot->frame->pts != AV_NOPTS_VALUE
            ? slot->frame->pts
            : av_rescale_q(session->media_start_us + presentation_time_us, AV_TIME_BASE_Q, session->stream->time_base);
    slot->duration_us = ffplaykmp_frame_duration_us(session, slot->frame);
    slot->serial = serial;
    slot->chain = session->chain;
    slot->token = token;
    slot->presented = 0;
    slot->discontinuity = session->discontinuity;
    session->discontinuity = 0;
    slot->kind = ffplaykmp_prepare_frame(player, slot->frame, token, presentation_time_us, serial);
    pthread_mutex_lock(&queue->mutex);
    queue->count++;
    pthread_mutex_unlock(&queue->mutex);
    return 0;
}

/*
 * A preview's frame: shows `frame` at `presentation_time_us` now, on the
 * session thread, whatever the queue holds (a preview flushes it first), and
 * makes it the frame on screen. Returns 1, as a preview presented, or a
 * negative error.
 */
static int ffplaykmp_show_now(ffplaykmp_player *player, AVFrame *frame, int64_t presentation_time_us) {
    ffplaykmp_session *session = &player->session;
    ffplaykmp_frame_queue *queue = &player->queue;
    ffplaykmp_queued_kind kind;
    uint32_t serial;
    uint64_t token;
    int result;
    pthread_mutex_lock(&player->mutex);
    serial = player->snapshot.queue_serial;
    pthread_mutex_unlock(&player->mutex);
    pthread_mutex_lock(&queue->mutex);
    ffplaykmp_queue_begin_take(queue);
    token = queue->next_token++;
    pthread_mutex_unlock(&queue->mutex);
    kind = ffplaykmp_prepare_frame(player, frame, token, presentation_time_us, serial);
    result = ffplaykmp_present_prepared(
            player, frame, kind, token, presentation_time_us, serial, -1, av_gettime_relative());
    pthread_mutex_lock(&queue->mutex);
    if (result >= 0) {
        queue->shown_valid = 1;
        queue->shown_us = ffplaykmp_frame_time_us(
                frame, session->stream->time_base, session->media_start_us, presentation_time_us);
        queue->shown_chain = session->chain;
        if (frame != queue->shown) {
            av_frame_unref(queue->shown);
            if (!ffplaykmp_is_mediacodec_frame(frame))
                av_frame_ref(queue->shown, frame);
        }
        /* The next play run's first frame is due a frame after this one. */
        queue->last_valid = 1;
        queue->last_pts_us = presentation_time_us;
        queue->last_duration_us = ffplaykmp_frame_duration_us(session, frame);
        queue->last_serial = serial;
    }
    ffplaykmp_queue_end_take(queue);
    pthread_mutex_unlock(&queue->mutex);
    return result < 0 ? result : 1;
}

/* Shows `frame` at `presentation_time_us`: at once for a preview, through the queue for a play run. */
static int ffplaykmp_deliver_frame(
        ffplaykmp_player *player,
        AVFrame *frame,
        int64_t presentation_time_us,
        int continuous) {
    return continuous
            ? ffplaykmp_queue_frame(player, frame, presentation_time_us)
            : ffplaykmp_show_now(player, frame, presentation_time_us);
}

/* ffplay's AV_SYNC_THRESHOLD_MIN, AV_SYNC_THRESHOLD_MAX, AV_SYNC_FRAMEDUP_THRESHOLD, AV_NOSYNC_THRESHOLD and REFRESH_RATE. */
#define FFPLAYKMP_SYNC_THRESHOLD_MIN_US 40000
#define FFPLAYKMP_SYNC_THRESHOLD_MAX_US 100000
#define FFPLAYKMP_SYNC_FRAMEDUP_THRESHOLD_US 100000
#define FFPLAYKMP_NOSYNC_THRESHOLD_US 10000000
#define FFPLAYKMP_REFRESH_RATE_US 10000

static int ffplaykmp_clock_get(const ffplaykmp_clock *clock, int64_t time_us, int64_t *value_us) {
    if (clock->valid)
        *value_us = clock->pts_us + (time_us - clock->updated_us);
    return clock->valid;
}

static void ffplaykmp_clock_set(ffplaykmp_clock *clock, int64_t pts_us, int64_t time_us) {
    clock->valid = 1;
    clock->pts_us = pts_us;
    clock->updated_us = time_us;
}

/* ffplay's vp_duration. */
static int64_t ffplaykmp_vp_duration(
        const ffplaykmp_frame_queue *queue,
        int64_t pts_us,
        int64_t duration_us,
        uint32_t serial,
        const ffplaykmp_queued_frame *next) {
    int64_t duration;
    if (serial != next->serial)
        return 0;
    /* The next frame is the one a skip landed on: due by the clock, not after this one. */
    if (next->discontinuity)
        return duration_us;
    duration = next->pts_us - pts_us;
    if (duration <= 0 || duration > queue->max_frame_duration_us)
        return duration_us;
    return duration;
}

/* The master clock's reading for a refresh: the host's audio clock while it reports one, else ffplay's external clock. */
typedef struct ffplaykmp_master {
    int audio_valid;
    int64_t audio_media_us;
    int64_t audio_stamp_us;
} ffplaykmp_master;

static int ffplaykmp_master_get(
        const ffplaykmp_frame_queue *queue,
        const ffplaykmp_master *master,
        int64_t time_us,
        int64_t *value_us) {
    if (master->audio_valid) {
        *value_us = master->audio_media_us + (time_us - master->audio_stamp_us);
        return 1;
    }
    return ffplaykmp_clock_get(&queue->external_clock, time_us, value_us);
}

/* ffplay's compute_target_delay: the master clock is never the video clock here. */
static int64_t ffplaykmp_target_delay(
        const ffplaykmp_frame_queue *queue,
        const ffplaykmp_master *master,
        int64_t time_us,
        int64_t delay_us) {
    int64_t video_us;
    int64_t master_us;
    int64_t diff;
    int64_t sync_threshold;
    if (!ffplaykmp_clock_get(&queue->video_clock, time_us, &video_us) ||
            !ffplaykmp_master_get(queue, master, time_us, &master_us))
        return delay_us;
    diff = video_us - master_us;
    sync_threshold = FFMAX(FFPLAYKMP_SYNC_THRESHOLD_MIN_US, FFMIN(FFPLAYKMP_SYNC_THRESHOLD_MAX_US, delay_us));
    if (FFABS(diff) < queue->max_frame_duration_us) {
        if (diff <= -sync_threshold)
            delay_us = FFMAX(0, delay_us + diff);
        else if (diff >= sync_threshold && delay_us > FFPLAYKMP_SYNC_FRAMEDUP_THRESHOLD_US)
            delay_us = delay_us + diff;
        else if (diff >= sync_threshold)
            delay_us = 2 * delay_us;
    }
    return delay_us;
}

/* ffplay's update_video_pts, with the external clock following the video clock as sync_clock_to_slave keeps it. */
static void ffplaykmp_update_video_pts(ffplaykmp_frame_queue *queue, int64_t pts_us, int64_t time_us) {
    int64_t external_us;
    ffplaykmp_clock_set(&queue->video_clock, pts_us, time_us);
    if (!ffplaykmp_clock_get(&queue->external_clock, time_us, &external_us) ||
            FFABS(external_us - pts_us) > FFPLAYKMP_NOSYNC_THRESHOLD_US)
        ffplaykmp_clock_set(&queue->external_clock, pts_us, time_us);
}

/*
 * How far ahead of its due time a refresh hands a MediaCodec buffer to its
 * Surface: about two 60 Hz display frames. MediaCodec queues a buffer released
 * with a render time to the Surface at once, and SurfaceFlinger shows it at the
 * vsync nearest that time, so a buffer handed over a vsync or two early is on
 * screen exactly when due instead of at the first vsync after a refresh that
 * found it due; Android's documentation for releaseOutputBuffer(index, time)
 * suggests about two vsyncs ahead. One queued frame covers it at any frame rate.
 */
#define FFPLAYKMP_MEDIACODEC_RENDER_AHEAD_US 33333

/*
 * When the frame refresh is presenting at `time_us` is due on screen, on the
 * caller's clock: when the host's audio clock reaches it while there is one,
 * else when ffplay's frame timer says. A late frame is due now (`now_us`),
 * and a frame the sync threshold lets through early is due no later than
 * `time_us`, the latest time it may be handed over for.
 */
static int64_t ffplaykmp_display_time(
        const ffplaykmp_frame_queue *queue,
        const ffplaykmp_master *master,
        int64_t pts_us,
        int64_t now_us,
        int64_t time_us) {
    int64_t due_us = master->audio_valid
            ? master->audio_stamp_us + (pts_us - master->audio_media_us)
            : queue->frame_timer_us;
    return FFMIN(FFMAX(due_us, now_us), time_us);
}

/* Records `slot` as the frame consumed last, shown or dropped (ffplay's frame_queue_next). */
static void ffplaykmp_queue_consume(ffplaykmp_frame_queue *queue, const ffplaykmp_queued_frame *slot) {
    queue->last_valid = 1;
    queue->last_pts_us = slot->pts_us;
    queue->last_duration_us = slot->duration_us;
    queue->last_serial = slot->serial;
}

/*
 * ffplaykmp_player_refresh, and, with `position_ns` not negative,
 * ffplaykmp_player_refresh_at: the frame for that position rather than the
 * one the clocks make due.
 */
static int64_t ffplaykmp_refresh(ffplaykmp_player *player, int64_t now_us, int64_t lead_us, int64_t position_ns) {
    const int following = position_ns >= 0;
    int64_t target = 0;
    ffplaykmp_frame_queue *queue;
    ffplaykmp_master master;
    uint32_t serial;
    int64_t time_us;
    int64_t handover_us;
    int64_t real_us;
    int64_t remaining_us = FFPLAYKMP_REFRESH_RATE_US;
    int taken = 0;
    int dropped = 0;
    ffplaykmp_queued_frame *presented = NULL;
    if (!player)
        return FFPLAYKMP_REFRESH_IDLE;
    queue = &player->queue;
    pthread_mutex_lock(&queue->refresh_mutex);
    pthread_mutex_lock(&queue->mutex);
    if (!queue->playing) {
        pthread_mutex_unlock(&queue->mutex);
        pthread_mutex_unlock(&queue->refresh_mutex);
        return FFPLAYKMP_REFRESH_IDLE;
    }
    ffplaykmp_queue_begin_take(queue);
    /* begin_take may have waited for a seek's flush: the serial and master clock are read after it. */
    if (!queue->playing) {
        ffplaykmp_queue_end_take(queue);
        pthread_mutex_unlock(&queue->mutex);
        pthread_mutex_unlock(&queue->refresh_mutex);
        return FFPLAYKMP_REFRESH_IDLE;
    }
    /* Read after begin_take, which may have waited for a flush. */
    real_us = av_gettime_relative();
    queue->refresh_real_us = real_us;
    if (now_us < 0)
        now_us = real_us;
    if (lead_us < 0)
        lead_us = 0;
    /*
     * The time the frames are chosen for: the caller's display time. A codec rendering to a Surface
     * is handed its buffers ahead of that (one codec's frames fill the queue), up to `handover_us`,
     * each with its own due time; only which frames go early depends on it. The clocks, the frame
     * timer and the late drop do not, so they do not move by the render-ahead as the queue's head
     * changes kind. A followed clock says itself which frame each display frame shows.
     */
    time_us = now_us + lead_us;
    handover_us = time_us;
    if (!following && queue->count > 0 &&
            ffplaykmp_queue_slot(queue, queue->rindex)->kind == FFPLAYKMP_QUEUED_MEDIACODEC)
        handover_us = now_us + FFMAX(lead_us, FFPLAYKMP_MEDIACODEC_RENDER_AHEAD_US);
    queue->refresh_time_valid = 1;
    queue->refresh_time_us = time_us;
    /*
     * The followed position is the master clock: catching up compares decoded frames with it, and
     * the frame timer starts again from the next frame should the player's own clocks take over.
     * The host's audio clock steps aside.
     */
    queue->following = following;
    queue->follow_position_us = following ? position_ns / 1000 : 0;
    if (following) {
        queue->frame_timer_valid = 0;
        /* The position in the frames' time base, as VideoDecoder.frameAt takes it; none is queued before the first frame. */
        target = queue->time_base.num > 0 && queue->time_base.den > 0
                ? ffplaykmp_stream_time(queue->origin, queue->time_base, position_ns)
                : INT64_MIN;
    }
    pthread_mutex_lock(&player->mutex);
    if (following)
        player->master_clock_valid = 0;
    serial = player->snapshot.queue_serial;
    master.audio_valid = player->master_clock_valid;
    master.audio_media_us = player->master_clock_media_us;
    master.audio_stamp_us = player->master_clock_stamp_us;
    pthread_mutex_unlock(&player->mutex);
    /* ffplay's video_refresh: its retry loop, over the queued frames. */
    while (taken < queue->count) {
        ffplaykmp_queued_frame *slot = ffplaykmp_queue_slot(queue, queue->rindex + taken);
        int64_t last_duration_us;
        int64_t delay_us;
        slot->presented = 0;
        if (slot->serial != serial) {
            /* Decoded before a seek: released, not counted as dropped. */
            taken++;
            continue;
        }
        if (following) {
            /* The newest frame the position has reached, as VideoDecoder.frameAt picks it; older ones are dropped. */
            if (!ffplaykmp_frame_reached(slot->stream_pts, target))
                break;
            if (presented) {
                presented->presented = 0;
                dropped++;
            }
            ffplaykmp_queue_consume(queue, slot);
            slot->presented = 1;
            slot->display_us = time_us;
            presented = slot;
            taken++;
            continue;
        }
        if (!queue->frame_timer_valid || !queue->last_valid || queue->last_serial != slot->serial ||
                slot->discontinuity) {
            queue->frame_timer_us = time_us;
            queue->frame_timer_valid = 1;
        }
        if (slot->discontinuity) {
            /* The frame a skip landed on, at or after the clock: due when the clock reaches it. */
            int64_t master_us;
            delay_us = ffplaykmp_master_get(queue, &master, time_us, &master_us)
                    ? FFMAX(0, slot->pts_us - master_us)
                    : 0;
        } else {
            last_duration_us = queue->last_valid
                    ? ffplaykmp_vp_duration(queue, queue->last_pts_us, queue->last_duration_us, queue->last_serial, slot)
                    : 0;
            delay_us = ffplaykmp_target_delay(queue, &master, time_us, last_duration_us);
        }
        if (handover_us < queue->frame_timer_us + delay_us) {
            remaining_us = FFMIN(queue->frame_timer_us + delay_us - handover_us, remaining_us);
            break;
        }
        queue->frame_timer_us += delay_us;
        if (delay_us > 0 && time_us - queue->frame_timer_us > FFPLAYKMP_SYNC_THRESHOLD_MAX_US)
            queue->frame_timer_us = time_us;
        slot->display_us = ffplaykmp_display_time(queue, &master, slot->pts_us, now_us, handover_us);
        /* A frame handed over early is on screen at its due time, not before. */
        ffplaykmp_update_video_pts(queue, slot->pts_us, FFMAX(time_us, slot->display_us));
        if (taken + 1 < queue->count) {
            const ffplaykmp_queued_frame *next = ffplaykmp_queue_slot(queue, queue->rindex + taken + 1);
            const int64_t duration_us =
                    ffplaykmp_vp_duration(queue, slot->pts_us, slot->duration_us, slot->serial, next);
            /* Late now, not at a time it might be handed over for. */
            if (now_us > queue->frame_timer_us + duration_us) {
                /* Late, and the next frame is due too: dropped, as ffplay's framedrop does. */
                ffplaykmp_queue_consume(queue, slot);
                dropped++;
                taken++;
                continue;
            }
        }
        ffplaykmp_queue_consume(queue, slot);
        slot->presented = 1;
        presented = slot;
        taken++;
        break;
    }
    if (taken == 0) {
        queue->busy = 0;
        pthread_cond_broadcast(&queue->cond);
        pthread_mutex_unlock(&queue->mutex);
        pthread_mutex_unlock(&queue->refresh_mutex);
        return remaining_us;
    }
    queue->rindex = (queue->rindex + taken) % queue->capacity;
    queue->count -= taken;
    queue->in_flight = taken;
    pthread_mutex_unlock(&queue->mutex);
    for (int i = 0; i < taken; i++) {
        ffplaykmp_queued_frame *slot = ffplaykmp_queue_slot(queue, queue->rindex - taken + i);
        if (slot->presented) {
            /* The due time on av_gettime_relative's clock, for MediaCodec, whatever clock the caller runs on. */
            ffplaykmp_present_prepared(player, slot->frame, slot->kind, slot->token, slot->pts_us, slot->serial,
                    slot->display_us, real_us + (slot->display_us - now_us));
            /*
             * A MediaCodec frame holds FFmpeg's codec context, so the frame on screen keeps no reference
             * to it, as in ffplaykmp_show_now: its buffer is rendered already.
             */
            if (ffplaykmp_is_mediacodec_frame(slot->frame))
                av_frame_unref(slot->frame);
        } else {
            ffplaykmp_release_prepared(player, slot->kind, slot->token, slot->pts_us, slot->serial);
            av_frame_unref(slot->frame);
        }
    }
    pthread_mutex_lock(&queue->mutex);
    if (presented) {
        /* The presented frame becomes the one on screen; the one before it goes. */
        AVFrame *shown = queue->shown;
        queue->shown = presented->frame;
        presented->frame = shown;
        queue->shown_valid = 1;
        queue->shown_us = presented->media_us;
        queue->shown_chain = presented->chain;
    }
    pthread_mutex_unlock(&queue->mutex);
    if (presented)
        av_frame_unref(presented->frame);
    pthread_mutex_lock(&player->mutex);
    if (presented && presented->serial == player->snapshot.queue_serial)
        player->snapshot.position_us = presented->pts_us;
    if (serial == player->snapshot.queue_serial)
        player->snapshot.dropped_frames += (uint64_t)dropped;
    pthread_mutex_unlock(&player->mutex);
    if (presented || dropped)
        ffplaykmp_publish(player);
    pthread_mutex_lock(&queue->mutex);
    ffplaykmp_queue_end_take(queue);
    pthread_mutex_unlock(&queue->mutex);
    pthread_mutex_unlock(&queue->refresh_mutex);
    return remaining_us;
}

int64_t ffplaykmp_player_refresh(ffplaykmp_player *player, int64_t now_us, int64_t lead_us) {
    return ffplaykmp_refresh(player, now_us, lead_us, -1);
}

int64_t ffplaykmp_player_refresh_at(ffplaykmp_player *player, int64_t now_us, int64_t lead_us, int64_t position_ns) {
    return ffplaykmp_refresh(player, now_us, lead_us, position_ns);
}

int64_t ffplaykmp_clock_now_us(void) {
    return av_gettime_relative();
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

/* Whether the frame on screen is the one the decoder goes on from: presented in the current chain. */
static int ffplaykmp_shown_continues(ffplaykmp_player *player) {
    int continues;
    pthread_mutex_lock(&player->queue.mutex);
    continues = player->queue.shown_valid && player->queue.shown_chain == player->session.chain;
    pthread_mutex_unlock(&player->queue.mutex);
    return continues;
}

/*
 * A preview at a target the frame on screen covers: presents that frame again,
 * where the session has it, at the target. Returns 1, as a preview presented.
 */
static int ffplaykmp_present_shown_frame(ffplaykmp_player *player, int64_t start_position_us) {
    int kept;
    pthread_mutex_lock(&player->queue.mutex);
    kept = player->queue.shown->buf[0] != NULL;
    pthread_mutex_unlock(&player->queue.mutex);
    /* Only the session thread replaces the frame on screen while no play run is on. */
    if (!kept)
        return 1;
    return ffplaykmp_show_now(player, player->queue.shown, start_position_us);
}

/*
 * Catch-up, the plan's forward keyframe skip: a frame decoded more than this
 * far behind the master clock is not shown, and the session skips forward to
 * the first keyframe at or after the clock (ffplaykmp_session_catch_up).
 */
#define FFPLAYKMP_CATCH_UP_LAG_US 500000

/* What ffplaykmp_present_ready_frames returns when the session is to skip forward. */
#define FFPLAYKMP_CATCH_UP 2

/*
 * The master clock's position now: the host's audio clock, else the external
 * clock, read on the last refresh caller's clock. 0 while there is none, as
 * before a play run's first frame is shown.
 */
static int ffplaykmp_master_now(ffplaykmp_player *player, int64_t *master_us) {
    ffplaykmp_frame_queue *queue = &player->queue;
    const int64_t real_us = av_gettime_relative();
    ffplaykmp_master master;
    int valid;
    pthread_mutex_lock(&queue->mutex);
    if (queue->following) {
        *master_us = queue->follow_position_us;
        valid = queue->playing;
        pthread_mutex_unlock(&queue->mutex);
        return valid;
    }
    pthread_mutex_unlock(&queue->mutex);
    pthread_mutex_lock(&player->mutex);
    master.audio_valid = player->master_clock_valid;
    master.audio_media_us = player->master_clock_media_us;
    master.audio_stamp_us = player->master_clock_stamp_us;
    pthread_mutex_unlock(&player->mutex);
    if (master.audio_valid) {
        *master_us = master.audio_media_us + (real_us - master.audio_stamp_us);
        return 1;
    }
    pthread_mutex_lock(&queue->mutex);
    valid = queue->playing && queue->refresh_time_valid && ffplaykmp_clock_get(&queue->external_clock,
            queue->refresh_time_us + (real_us - queue->refresh_real_us), master_us);
    pthread_mutex_unlock(&queue->mutex);
    return valid;
}

/*
 * Whether a frame just decoded at `presentation_time_us` is too far behind the
 * master clock to show, and there is a keyframe to skip forward to: the first
 * one the index has at or after the clock's position, later than the one the
 * last skip went to. Following a clock, it is the last one at or before the
 * position instead, past the frame decoded: the frame for a position that then
 * stands still is still decoded. Without one (a long GOP, near the end, or no
 * index) the session decodes on. Records the skip's target for
 * ffplaykmp_session_catch_up.
 */
static int ffplaykmp_wants_catch_up(ffplaykmp_player *player, int64_t presentation_time_us) {
    ffplaykmp_session *session = &player->session;
    AVStream *stream = session->stream;
    const AVIndexEntry *entry;
    int64_t master_us;
    int64_t target;
    int following;
    int index;
    const int64_t lag_us = player->configuration.catch_up_lag_us;
    if (lag_us < 0 || !ffplaykmp_master_now(player, &master_us) || master_us - presentation_time_us <= lag_us)
        return 0;
    pthread_mutex_lock(&player->queue.mutex);
    following = player->queue.following;
    pthread_mutex_unlock(&player->queue.mutex);
    target = av_rescale_q(session->media_start_us + master_us, AV_TIME_BASE_Q, stream->time_base);
    index = av_index_search_timestamp(stream, target, following ? AVSEEK_FLAG_BACKWARD : 0);
    entry = index >= 0 ? avformat_index_get_entry(stream, index) : NULL;
    if (!entry || (session->skipped && entry->timestamp <= session->skipped_keyframe))
        return 0;
    if (following && entry->timestamp <= av_rescale_q(
            session->media_start_us + presentation_time_us, AV_TIME_BASE_Q, stream->time_base))
        return 0;
    session->catch_up_ts = entry->timestamp;
    session->catch_up_min_ts = following ? entry->timestamp : target;
    return 1;
}

/*
 * Delivers every frame the decoder has ready, or (draining) every frame it has
 * left, the session's next frame first: a preview (non-continuous) shows its
 * frame at once, a play run queues them for ffplaykmp_player_refresh. Returns 1
 * once a preview is done, 0 when the decoder needs more input or is drained,
 * FFPLAYKMP_CATCH_UP when a play run's frame came too late and the session is
 * to skip forward (the frame is dropped and counted), or a negative error.
 * Undecodable data is skipped, as ffplay does.
 *
 * Frames before `start_position_us` are decoded from the keyframe a seek
 * lands on. Of those, `held` keeps the latest: it is the frame on screen at the
 * target once the next frame turns out to start after it, as VideoDecoder
 * picks, and it is shown at the target. A frame that starts exactly there
 * replaces it. A preview that goes on from the frame on screen, with none
 * held, is done when a frame starts after the target: the one on screen is
 * the one there, and is presented again. A frame decoded but not delivered,
 * because a preview was done before it or playback was stopped while it waited
 * for room in the queue, is kept as the session's next.
 */
static int ffplaykmp_present_ready_frames(
        ffplaykmp_player *player,
        int64_t start_position_us,
        int continuous,
        int require_hardware,
        int draining,
        int *decoded_frames) {
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
        if (session->landing) {
            /* The first frame after a skip: the frames between it and the one too late, passed over. */
            session->landing = 0;
            session->discontinuity = 1;
            if (session->frame_duration_us > 0 && presentation_time_us > session->skipped_from_us) {
                const int64_t passed = (presentation_time_us - session->skipped_from_us) / session->frame_duration_us - 1;
                pthread_mutex_lock(&player->mutex);
                if (passed > 0 && session->skipped_serial == player->snapshot.queue_serial)
                    player->snapshot.dropped_frames += (uint64_t)passed;
                pthread_mutex_unlock(&player->mutex);
            }
        }
        if (presentation_time_us < start_position_us) {
            /* Unreferencing a MediaCodec frame releases its buffer without rendering it. */
            av_frame_unref(held);
            av_frame_move_ref(held, frame);
            /* The frame on screen no longer continues the decoder. */
            session->chain++;
            continue;
        }
        if (!continuous && !held->buf[0] && presentation_time_us > start_position_us &&
                ffplaykmp_shown_continues(player)) {
            av_frame_move_ref(session->next, frame);
            return ffplaykmp_present_shown_frame(player, start_position_us);
        }
        if (held->buf[0] && presentation_time_us > start_position_us) {
            result = ffplaykmp_deliver_frame(player, held, start_position_us, continuous);
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
        if (continuous && !draining && ffplaykmp_wants_catch_up(player, presentation_time_us)) {
            pthread_mutex_lock(&player->mutex);
            session->skipped_serial = player->snapshot.queue_serial;
            player->snapshot.dropped_frames++;
            player->snapshot.catch_ups++;
            pthread_mutex_unlock(&player->mutex);
            session->skipped_from_us = presentation_time_us;
            /* Unreferencing a MediaCodec frame releases its buffer without rendering it. */
            av_frame_unref(frame);
            /* The frame on screen no longer continues the decoder. */
            session->chain++;
            return FFPLAYKMP_CATCH_UP;
        }
        result = ffplaykmp_deliver_frame(player, frame, presentation_time_us, continuous);
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
        int continuous) {
    ffplaykmp_session *session = &player->session;
    int result = 0;
    if (session->held->buf[0])
        result = ffplaykmp_deliver_frame(player, session->held, start_position_us, continuous);
    av_frame_unref(session->held);
    return result;
}

/*
 * Forgets what the decoder was in the middle of, the queued frames and the
 * frame on screen included; the next run seeks. With session_mutex held.
 */
static void ffplaykmp_session_drop_frames(ffplaykmp_player *player) {
    ffplaykmp_session *session = &player->session;
    av_packet_unref(session->packet);
    av_frame_unref(session->frame);
    av_frame_unref(session->held);
    av_frame_unref(session->next);
    ffplaykmp_queue_flush(player);
    pthread_mutex_lock(&player->queue.mutex);
    av_frame_unref(player->queue.shown);
    player->queue.shown_valid = 0;
    pthread_mutex_unlock(&player->queue.mutex);
    session->packet_pending = 0;
    session->draining = 0;
    session->in_sync = 0;
    session->chain++;
    session->skipped = 0;
    session->landing = 0;
    session->discontinuity = 0;
}

/*
 * Closes the session's codec, on the session thread, frames first, since a
 * MediaCodec frame holds one of the codec's buffers. The next run opens one.
 */
static void ffplaykmp_session_close_codec(ffplaykmp_player *player) {
    ffplaykmp_session *session = &player->session;
    ffplaykmp_session_drop_frames(player);
    ffplaykmp_video_codec_close(&session->codec);
    memset(&session->codec, 0, sizeof(session->codec));
    session->codec_open = 0;
}

/*
 * A startup: prepare, an output change, a new Surface, or the codec reopening
 * after an idle release. The next codec tries hardware again, and a software
 * one open for a fallback reopens. With session_mutex held.
 */
static void ffplaykmp_session_forget_fallback(ffplaykmp_session *session) {
    if (session->fallback != FFPLAYKMP_DECODER_FALLBACK_NONE)
        session->choose_again = 1;
    session->fallback = FFPLAYKMP_DECODER_FALLBACK_NONE;
}

/*
 * Closes an idle player's codec, on the session thread with session_mutex
 * held, with what holds its memory: the frame on screen, the frames decoded
 * ahead and the packet not yet taken, which also ends going on from the frame
 * on screen, so the next run seeks and decodes from the keyframe. The
 * download buffers go too; frames still out keep theirs. The codec opened
 * next is a startup's.
 */
static void ffplaykmp_session_release_idle(ffplaykmp_player *player) {
    ffmpegkmp_frame_pool *download_pool;
    ffplaykmp_session_close_codec(player);
    ffplaykmp_session_forget_fallback(&player->session);
    download_pool = ffmpegkmp_frame_pool_alloc(2);
    if (download_pool) {
        ffmpegkmp_frame_pool_free(player->download_pool);
        player->download_pool = download_pool;
    }
}

/*
 * A play run whose output stopped refreshing (FFPLAYKMP_RELEASE_IDLE):
 * releases the codec as an idle player's, waits for the next refresh, and
 * sets where the run goes on from, the master clock's position, else the last
 * frame shown's. AVERROR_EXIT when the run was aborted meanwhile.
 */
static int ffplaykmp_session_release_stalled(ffplaykmp_player *player, int64_t *start_position_us) {
    ffplaykmp_frame_queue *queue = &player->queue;
    int64_t refreshed_us;
    ffplaykmp_session_release_idle(player);
    pthread_mutex_lock(&queue->mutex);
    refreshed_us = queue->refresh_real_us;
    while (queue->refresh_real_us == refreshed_us && !ffplaykmp_is_aborted(player))
        pthread_cond_wait(&queue->cond, &queue->mutex);
    pthread_mutex_unlock(&queue->mutex);
    if (ffplaykmp_is_aborted(player))
        return AVERROR_EXIT;
    if (!ffplaykmp_master_now(player, start_position_us)) {
        pthread_mutex_lock(&player->mutex);
        *start_position_us = player->snapshot.position_us;
        pthread_mutex_unlock(&player->mutex);
    }
    return 0;
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
    session->chain++;
}

static void ffplaykmp_end_session_thread(ffplaykmp_player *player);

/*
 * Ends the session thread, which closes the codec, and closes the player's
 * input, before the host may unmount what it reads. The next job starts a new
 * thread.
 */
static void ffplaykmp_player_close_input(ffplaykmp_player *player) {
    pthread_mutex_lock(&player->mutex);
    player->input_hint_valid = 0;
    pthread_mutex_unlock(&player->mutex);
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
    {
        int64_t default_duration;
        int64_t stream_end;
        ffplaykmp_stream_timing(session->input.format, session->stream,
                &session->origin, &default_duration, &stream_end);
    }
    session->at_start = 1;
    /* As ffplay times frames: by the stream's frame rate, and a step past max_frame_duration is a discontinuity. */
    {
        const AVRational frame_rate = av_guess_frame_rate(
                session->input.format, session->input.format->streams[session->video_stream], NULL);
        session->frame_duration_us = frame_rate.num && frame_rate.den
                ? av_rescale(AV_TIME_BASE, frame_rate.den, frame_rate.num)
                : 0;
        session->max_frame_duration_us =
                (session->input.format->iformat->flags & AVFMT_TS_DISCONT) ? 10000000 : 3600000000LL;
    }
    return 0;
}

/* Whether the run has said nothing yet about why it fails. */
static int ffplaykmp_error_message_empty(ffplaykmp_player *player) {
    int empty;
    pthread_mutex_lock(&player->mutex);
    empty = !player->snapshot.error_message[0];
    pthread_mutex_unlock(&player->mutex);
    return empty;
}

/* Says why the run is failing, in words, in the snapshot the failure publishes. */
static void ffplaykmp_set_error_message(ffplaykmp_player *player, const char *message) {
    pthread_mutex_lock(&player->mutex);
    av_strlcpy(player->snapshot.error_message, message ? message : "", sizeof(player->snapshot.error_message));
    pthread_mutex_unlock(&player->mutex);
}

/*
 * Makes the session's codec the one this run's output wants, keeping the open
 * one when it is: a hardware decoder when `preference` allows it and the
 * output takes hardware frames. Under AUTO a hardware decoder that will not
 * open, or that the hardware decoder budget has no place for, gives way to
 * software until the next startup (ffplaykmp_session_forget_fallback): a
 * seek, a loop, a play or a pause keeps the choice, so players do not take
 * hardware decoders from each other mid-playback. On Android a budget
 * fallback onto a Surface the canvas draws on holds until a new Surface. A
 * configured decoder_name opens that decoder alone, its reason in the
 * snapshot when it does not. Publishes why a wanted hardware decoder is not
 * the one open, and what a preview learns from a new codec.
 */
static int ffplaykmp_session_open_codec(
        ffplaykmp_player *player,
        ffplaykmp_decoder_preference preference,
        int continuous) {
    ffplaykmp_session *session = &player->session;
    ffplaykmp_decoder_fallback fallback = FFPLAYKMP_DECODER_FALLBACK_NONE;
    int hardware_wanted = 0;
    int threads;
    int thread_type;
    void *android_surface = NULL;
    AVDictionary *hardware_options = NULL;
    int result;
#if defined(__ANDROID__)
    int own_surface = 0;
#endif
    if (preference != FFPLAYKMP_DECODER_SOFTWARE) {
        pthread_mutex_lock(&player->mutex);
#if defined(__ANDROID__)
        own_surface = (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_PREPARE_HARDWARE_FRAMES) != 0;
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
    if (session->codec_open && !session->choose_again && session->codec_hardware_wanted == hardware_wanted &&
            session->codec_surface == android_surface && session->codec_input == session->input_generation)
        return 0;
    session->choose_again = 0;
    ffplaykmp_session_close_codec(player);
    threads = player->configuration.decoder_threads;
    thread_type = player->configuration.decoder_thread_type;
    /* A stalled MediaCodec returns to the decode loop, which checks for a stop, pause or seek. */
    if ((result = ffplaykmp_mediacodec_wait_options(&hardware_options)) < 0)
        return result;
    if (player->configuration.decoder_name) {
        char error[sizeof(player->snapshot.error_message)] = "";
        /* The named decoder alone: no other takes its place, and its reason is the player's. */
        result = ffplaykmp_video_codec_open_named(
                &session->codec, session->input.format, session->video_stream, player->configuration.decoder_name,
                player->configuration.decoder_options, player->configuration.decoder_options_size, threads,
                thread_type, android_surface, hardware_options, error, sizeof(error));
        av_dict_free(&hardware_options);
        if (result < 0) {
            ffplaykmp_set_error_message(player, error);
            ffplaykmp_session_close_codec(player);
            return result;
        }
    } else {
        ffplaykmp_decoder_fallback earlier = session->fallback;
#if defined(__ANDROID__)
        if (earlier == FFPLAYKMP_DECODER_FALLBACK_NONE && session->budget_fallback)
            earlier = FFPLAYKMP_DECODER_FALLBACK_BUDGET;
#endif
        result = ffplaykmp_video_codec_open_preferred(
                &session->codec, session->input.format, session->video_stream, preference, hardware_wanted,
                earlier, threads, thread_type, android_surface, hardware_options, &fallback);
        av_dict_free(&hardware_options);
        if (result < 0) {
            ffplaykmp_session_close_codec(player);
            return result;
        }
        if (earlier == FFPLAYKMP_DECODER_FALLBACK_NONE &&
                (fallback == FFPLAYKMP_DECODER_FALLBACK_FAILED || fallback == FFPLAYKMP_DECODER_FALLBACK_BUDGET))
            session->fallback = fallback;
#if defined(__ANDROID__)
        if (fallback == FFPLAYKMP_DECODER_FALLBACK_BUDGET && !own_surface)
            session->budget_fallback = 1;
#endif
    }
    session->codec_open = 1;
    session->codec_hardware_wanted = hardware_wanted;
    session->codec_surface = android_surface;
    session->codec_input = session->input_generation;
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
        ffplaykmp_player *player,
        int64_t start_position_us,
        int presents) {
    const ffplaykmp_session *session = &player->session;
    const ffplaykmp_frame_queue *queue = &player->queue;
    AVStream *stream = session->input.format->streams[session->video_stream];
    const AVIndexEntry *entry;
    int64_t next_us;
    int index;
    int continues;
    int queued;
    pthread_mutex_lock(&player->queue.mutex);
    queued = queue->count;
    continues = session->in_sync && queue->shown_valid && queue->shown_chain == session->chain &&
            !(presents && !queue->shown->buf[0]) && start_position_us >= queue->shown_us;
    /* The frame after the one on screen: the oldest queued, else the session's next. */
    next_us = queue->count > 0
            ? ffplaykmp_queue_slot(&player->queue, queue->rindex)->media_us
            : queue->shown_us;
    pthread_mutex_unlock(&player->queue.mutex);
    if (!continues)
        return 0;
    if (!queued && session->next->buf[0] && session->next->best_effort_timestamp != AV_NOPTS_VALUE)
        next_us = ffplaykmp_session_time_us(session, session->next->best_effort_timestamp);
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
static int ffplaykmp_session_seek(ffplaykmp_player *player, int64_t start_position_us) {
    ffplaykmp_session *session = &player->session;
    AVIOContext *pb = session->input.format->pb;
    int64_t target;
    int result;
    ffplaykmp_session_drop_frames(player);
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
 * Skips forward to the keyframe ffplaykmp_wants_catch_up chose, as a play run
 * goes on: not a seek of the player's, so the serial stays, nothing is
 * published, and the run's clocks run on. The queued frames are older than
 * the frame that came too late, so they go too, counted as dropped, and a
 * MediaCodec decoder's buffers among them go back before it is flushed. The
 * demuxer lands on that keyframe or a later one, never before the clock's
 * position then, or, following a clock, never before that keyframe. When the
 * seek fails the decoder goes on where it was.
 */
static void ffplaykmp_session_catch_up(ffplaykmp_player *player) {
    ffplaykmp_session *session = &player->session;
    AVIOContext *pb = session->input.format->pb;
    const int released = ffplaykmp_queue_release_all(player, 1);
    pthread_mutex_lock(&player->mutex);
    if (session->skipped_serial == player->snapshot.queue_serial)
        player->snapshot.dropped_frames += (uint64_t)released;
    pthread_mutex_unlock(&player->mutex);
    session->skipped = 1;
    session->skipped_keyframe = session->catch_up_ts;
    if (pb) {
        pb->error = 0;
        pb->eof_reached = 0;
    }
    if (avformat_seek_file(session->input.format, session->video_stream,
            session->catch_up_min_ts, session->catch_up_ts, INT64_MAX, 0) < 0)
        return;
    av_packet_unref(session->packet);
    session->packet_pending = 0;
    avcodec_flush_buffers(session->codec.decoder);
    session->landing = 1;
}

/*
 * How long a decoder may refuse every packet without giving a frame before the
 * run fails: a MediaCodec decoder answers EAGAIN after each bounded wait with
 * no input buffer free and no output, and one that never recovers would
 * otherwise hold the session thread for good. Long enough for a codec that is
 * slow to start, or one waiting for its Surface to take buffers back.
 */
#define FFPLAYKMP_DECODER_STALL_US 5000000

/*
 * Decodes from where the session is: one preview frame at
 * `start_position_us`, or (continuous) every frame on the playback clock,
 * counting them in `decoded_frames`. `continuing` says the decoder goes on
 * from the frame on screen. Returns 0 once done, AVERROR_INVALIDDATA when
 * nothing decoded, FFPLAYKMP_RELEASE_IDLE when the output stopped refreshing,
 * or another negative error. A run stopped between packets, or while a frame
 * waited for its time, leaves the session in sync for the next one.
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
    int64_t stalled_since_us = -1;
    int stalled_frames = 0;
    int result;
    if (continuous) {
        /* refresh shows frames from now on; its clocks start again from the first one. */
        pthread_mutex_lock(&player->queue.mutex);
        player->queue.playing = 1;
        player->queue.frame_timer_valid = 0;
        player->queue.video_clock.valid = 0;
        player->queue.external_clock.valid = 0;
        pthread_mutex_unlock(&player->queue.mutex);
        /* Refresh stops answering IDLE: a caller that waits for a snapshot after IDLE refreshes again. */
        ffplaykmp_publish(player);
    }
    result = ffplaykmp_present_ready_frames(
            player, start_position_us, continuous, require_hardware, session->draining, decoded_frames);
    for (;;) {
        int sent;
        if (result == FFPLAYKMP_CATCH_UP) {
            ffplaykmp_session_catch_up(player);
            result = 0;
        }
        if (result != 0 || session->draining || ffplaykmp_is_aborted(player))
            break;
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
                    player, start_position_us, continuous, require_hardware, 0, decoded_frames);
            /* A skip forward drops the packet, unless its seek failed: then it is sent again. */
            if (result != 0)
                continue;
            sent = avcodec_send_packet(decoder, packet);
            /*
             * Still no room: a MediaCodec decoder waited its timeout with no input buffer free and
             * no frame out. The packet stays pending, and the loop checks for an abort before it
             * offers it again, so a stalled codec holds the session thread one wait at a time,
             * for FFPLAYKMP_DECODER_STALL_US in all with no frame decoded; then the run fails.
             */
            if (sent == AVERROR(EAGAIN)) {
                const int64_t now_us = av_gettime_relative();
                int queued;
                pthread_mutex_lock(&player->queue.mutex);
                queued = player->queue.count + player->queue.in_flight;
                pthread_mutex_unlock(&player->queue.mutex);
                /*
                 * Not stalled while frames are queued: a codec gives its client only so many
                 * output buffers, and a queue larger than that fills as far as the codec lets it
                 * and goes on as frames are shown.
                 */
                if (queued > 0) {
                    stalled_since_us = -1;
                    continue;
                }
                if (stalled_since_us < 0 || stalled_frames != *decoded_frames) {
                    stalled_since_us = now_us;
                    stalled_frames = *decoded_frames;
                } else if (now_us - stalled_since_us >= FFPLAYKMP_DECODER_STALL_US) {
                    char message[sizeof(player->snapshot.error_message)];
                    snprintf(message, sizeof(message), "decoder %s took no packet and gave no frame for %d s",
                            decoder->codec ? decoder->codec->name : "?", FFPLAYKMP_DECODER_STALL_US / 1000000);
                    ffplaykmp_set_error_message(player, message);
                    result = AVERROR(ETIMEDOUT);
                    break;
                }
                continue;
            }
        }
        stalled_since_us = -1;
        /* Like ffplay, a damaged packet is dropped; later packets may recover. */
        av_packet_unref(packet);
        session->packet_pending = 0;
        if (sent >= 0)
            result = ffplaykmp_present_ready_frames(
                    player, start_position_us, continuous, require_hardware, 0, decoded_frames);
    }
    if (result == FFPLAYKMP_RELEASE_IDLE)
        return result;
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
                player, start_position_us, continuous, require_hardware, 1, decoded_frames);
        if (result == 0)
            result = ffplaykmp_present_held_frame(player, start_position_us, continuous);
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
    player->snapshot.error_message[0] = '\0';
    pthread_mutex_unlock(&player->mutex);
    /* A preview shows its frame at once: frames queued ahead of the one on screen go, and the decoder is past them. */
    if (!continuous && ffplaykmp_queue_flush(player) > 0)
        session->in_sync = 0;
    for (;;) {
        int hardware = 0;
        int continuing = 0;
        decoded_frames = 0;
        result = ffplaykmp_session_open_input(player, url);
        if (result >= 0)
            result = ffplaykmp_session_open_codec(player, preference, continuous);
        if (result >= 0) {
            hardware = session->codec.hardware;
            continuing = ffplaykmp_session_continues(player, start_position_us, presents);
            if (!continuing)
                result = ffplaykmp_session_seek(player, start_position_us);
        }
        if (result >= 0) {
            session->at_start = 0;
            result = ffplaykmp_session_decode(player, start_position_us, continuous,
                    preference == FFPLAYKMP_DECODER_REQUIRE_HARDWARE, continuing, &decoded_frames);
        }
        if (result == FFPLAYKMP_RELEASE_IDLE) {
            if ((result = ffplaykmp_session_release_stalled(player, &start_position_us)) < 0)
                break;
            continue;
        }
        if (result >= 0 || ffplaykmp_is_aborted(player))
            break;
        ffplaykmp_session_close_codec(player);
        if (!hardware || decoded_frames > 0 || preference != FFPLAYKMP_DECODER_AUTO ||
                player->configuration.decoder_name) {
            /* A named decoder that opened and then failed says so: no other was tried. */
            if (player->configuration.decoder_name && ffplaykmp_error_message_empty(player)) {
                char message[sizeof(player->snapshot.error_message)];
                snprintf(message, sizeof(message), "decoder %s failed: %s",
                        player->configuration.decoder_name, av_err2str(result));
                ffplaykmp_set_error_message(player, message);
            }
            ffplaykmp_session_close_input(session);
            break;
        }
        /* The software retry keeps the input; it seeks back to the start position. A hardware decoder
         * that stalled before its first frame is a failed one too; its reason goes with it. */
        session->fallback = FFPLAYKMP_DECODER_FALLBACK_FAILED;
        ffplaykmp_set_error_message(player, NULL);
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
        ffplaykmp_session_close_codec(player);
    pthread_mutex_unlock(&player->session_mutex);
}
#endif

static void ffplaykmp_run_release_idle_job(ffplaykmp_player *player) {
    pthread_mutex_lock(&player->session_mutex);
    ffplaykmp_session_release_idle(player);
    pthread_mutex_unlock(&player->session_mutex);
}

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
    pthread_mutex_lock(&player->mutex);
    player->input_hint_valid = 0;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_session_close_input(session);
    ffplaykmp_session_forget_fallback(session);
    result = ffplaykmp_session_open_input(player, url);
    if (result >= 0) {
        const AVFormatContext *format = session->input.format;
        pthread_mutex_lock(&player->mutex);
        player->snapshot.video_width = session->stream->codecpar->width;
        player->snapshot.video_height = session->stream->codecpar->height;
        ffplaykmp_read_stream_metadata(&player->snapshot, session->stream);
        player->snapshot.duration_us = format->duration == AV_NOPTS_VALUE ? -1 : format->duration;
        ffplaykmp_input_hint_init(&player->input_hint);
        snprintf(player->input_hint.format_name, sizeof(player->input_hint.format_name), "%s", format->iformat->name);
        player->input_hint.start_us = session->media_start_us;
        player->input_hint.duration_us = player->snapshot.duration_us;
        for (unsigned i = 0; i < format->nb_streams; i++) {
            if (format->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO)
                player->input_hint.audio_streams++;
        }
        player->input_hint_valid = 1;
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
    configuration->codec_release_delay_us = 2000000;
    configuration->frame_queue_size = 3;
    configuration->catch_up_lag_us = FFPLAYKMP_CATCH_UP_LAG_US;
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

/*
 * Ends refresh's part in playback: from here on it answers IDLE without
 * publishing, and one that was taking frames has finished, its snapshot
 * published. So what the caller publishes next (ENDED, FAILED, PAUSED,
 * SEEKING, STOPPED, an output change) is never followed by a refresh's older
 * snapshot. With the queue's mutex not held.
 */
static void ffplaykmp_quiesce_refresh(ffplaykmp_player *player) {
    pthread_mutex_lock(&player->queue.mutex);
    player->queue.playing = 0;
    ffplaykmp_queue_begin_take(&player->queue);
    ffplaykmp_queue_end_take(&player->queue);
    pthread_mutex_unlock(&player->queue.mutex);
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
    pthread_mutex_lock(&player->queue.mutex);
    /* The input ended: ENDED follows the last frame shown, once refresh has taken them all. */
    while (result >= 0 && player->queue.count + player->queue.in_flight > 0 && !ffplaykmp_is_aborted(player))
        pthread_cond_wait(&player->queue.cond, &player->queue.mutex);
    pthread_mutex_unlock(&player->queue.mutex);
    ffplaykmp_quiesce_refresh(player);
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

/*
 * When the session thread, idle since `idle_since_us` on the wall clock,
 * releases its codec: codec_release_delay_us later, or never (INT64_MAX) with
 * no codec open or a negative delay. Read on the session thread, the only one
 * that opens and closes the codec, with job_mutex held; whatever else should
 * make an idle codec go sooner decides here, and signals job_cond.
 */
static int64_t ffplaykmp_idle_release_us(const ffplaykmp_player *player, int64_t idle_since_us) {
    const int64_t delay_us = player->configuration.codec_release_delay_us;
    if (!player->session.codec_open || delay_us < 0)
        return INT64_MAX;
    return delay_us > INT64_MAX - idle_since_us ? INT64_MAX : idle_since_us + delay_us;
}

/*
 * Waits, with job_mutex held, for the next job: a posted one, or
 * FFPLAYKMP_JOB_RELEASE_IDLE once the player has been idle for its codec's
 * release delay. Takes the job off the mailbox.
 */
static ffplaykmp_job_kind ffplaykmp_next_job(ffplaykmp_player *player, int64_t idle_since_us) {
    ffplaykmp_job_kind kind;
    while (player->job == FFPLAYKMP_JOB_NONE) {
        /* The wall clock, which pthread_cond_timedwait measures by default. */
        const int64_t release_us = ffplaykmp_idle_release_us(player, idle_since_us);
        struct timespec until;
        if (release_us == INT64_MAX) {
            pthread_cond_wait(&player->job_cond, &player->job_mutex);
            continue;
        }
        if (av_gettime() >= release_us)
            return FFPLAYKMP_JOB_RELEASE_IDLE;
        until.tv_sec = (time_t)(release_us / 1000000);
        until.tv_nsec = (long)(release_us % 1000000) * 1000;
        pthread_cond_timedwait(&player->job_cond, &player->job_mutex, &until);
    }
    kind = player->job;
    player->job = FFPLAYKMP_JOB_NONE;
    return kind;
}

static void *ffplaykmp_session_main(void *opaque) {
    ffplaykmp_player *player = opaque;
    int64_t idle_since_us = av_gettime();
    pthread_mutex_lock(&player->job_mutex);
    for (;;) {
        ffplaykmp_job_kind kind = ffplaykmp_next_job(player, idle_since_us);
        ffplaykmp_job_ticket *ticket = NULL;
        int64_t start_us = 0;
        int result = 0;
        if (kind == FFPLAYKMP_JOB_EXIT)
            break;
        if (kind != FFPLAYKMP_JOB_RELEASE_IDLE) {
            start_us = player->job_start_us;
            ticket = player->job_ticket;
            player->job_ticket = NULL;
        }
        player->job_running = kind;
        pthread_mutex_unlock(&player->job_mutex);
        if (kind == FFPLAYKMP_JOB_PREVIEW || kind == FFPLAYKMP_JOB_OUTPUT_PREVIEW)
            result = ffplaykmp_run_preview_job(player, start_us, kind == FFPLAYKMP_JOB_OUTPUT_PREVIEW);
#if defined(__ANDROID__)
        else if (kind == FFPLAYKMP_JOB_RELEASE)
            ffplaykmp_run_release_job(player);
#endif
        else if (kind == FFPLAYKMP_JOB_RELEASE_IDLE)
            ffplaykmp_run_release_idle_job(player);
        else
            ffplaykmp_run_play_job(player);
        pthread_mutex_lock(&player->job_mutex);
        player->job_running = FFPLAYKMP_JOB_NONE;
        idle_since_us = av_gettime();
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
    ffplaykmp_session_close_codec(player);
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
        /* The run may wait for room in the queue, or for it to drain. */
        ffplaykmp_wake_queue(player);
        while (player->job_running == FFPLAYKMP_JOB_PLAY)
            pthread_cond_wait(&player->job_cond, &player->job_mutex);
    }
    pthread_mutex_unlock(&player->job_mutex);
    ffplaykmp_quiesce_refresh(player);
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

/*
 * Releases the frames the output being replaced prepared; the decoder is past
 * them, so the next run seeks. An output change is a startup.
 */
static void ffplaykmp_flush_for_output(ffplaykmp_player *player) {
    pthread_mutex_lock(&player->session_mutex);
    if (ffplaykmp_queue_flush(player) > 0)
        player->session.in_sync = 0;
    ffplaykmp_session_forget_fallback(&player->session);
    pthread_mutex_unlock(&player->session_mutex);
}

/*
 * Starts a command: the reason an earlier run or command failed is not this
 * one's, so a caller that reads the snapshot after this command fails finds
 * only what this command said.
 */
static void ffplaykmp_begin_command(ffplaykmp_player *player) {
    pthread_mutex_lock(&player->mutex);
    player->snapshot.error_message[0] = '\0';
    pthread_mutex_unlock(&player->mutex);
}

/* Begins a command (ffplaykmp_begin_command) that needs a prepared source. */
static int ffplaykmp_require_prepared(ffplaykmp_player *player) {
    if (!player)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    ffplaykmp_begin_command(player);
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
    if (configuration && (configuration->size < sizeof(*configuration) || configuration->decoder_threads < 0 ||
            configuration->frame_queue_size < 1 || configuration->decoder_thread_type < 0 ||
            (configuration->decoder_thread_type & ~(FF_THREAD_FRAME | FF_THREAD_SLICE)) ||
            (configuration->decoder_name && !*configuration->decoder_name) ||
            ffmpegkmp_codec_options_check(configuration->decoder_options, configuration->decoder_options_size,
                    NULL, 0) < 0)) {
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
    if (pthread_mutex_init(&player->queue.mutex, NULL) != 0) {
        pthread_cond_destroy(&player->job_cond);
        pthread_mutex_destroy(&player->job_mutex);
        pthread_mutex_destroy(&player->session_mutex);
        pthread_mutex_destroy(&player->mutex);
        free(player);
        return NULL;
    }
    if (pthread_cond_init(&player->queue.cond, NULL) != 0) {
        pthread_mutex_destroy(&player->queue.mutex);
        pthread_cond_destroy(&player->job_cond);
        pthread_mutex_destroy(&player->job_mutex);
        pthread_mutex_destroy(&player->session_mutex);
        pthread_mutex_destroy(&player->mutex);
        free(player);
        return NULL;
    }
    if (pthread_mutex_init(&player->queue.refresh_mutex, NULL) != 0) {
        pthread_cond_destroy(&player->queue.cond);
        pthread_mutex_destroy(&player->queue.mutex);
        pthread_cond_destroy(&player->job_cond);
        pthread_mutex_destroy(&player->job_mutex);
        pthread_mutex_destroy(&player->session_mutex);
        pthread_mutex_destroy(&player->mutex);
        free(player);
        return NULL;
    }
    ffplaykmp_configuration_default(&player->configuration);
    if (configuration)
        player->configuration = *configuration;
    /* The caller's name and options need not outlive this call: the player keeps its own. */
    player->configuration.decoder_name = NULL;
    player->configuration.decoder_options = NULL;
    player->configuration.decoder_options_size = 0;
    if (configuration && configuration->decoder_name) {
        player->configuration.decoder_name = av_strdup(configuration->decoder_name);
        if (configuration->decoder_options_size > 0) {
            player->configuration.decoder_options =
                    av_memdup(configuration->decoder_options, configuration->decoder_options_size);
            player->configuration.decoder_options_size = configuration->decoder_options_size;
        }
        if (!player->configuration.decoder_name ||
                (configuration->decoder_options_size > 0 && !player->configuration.decoder_options)) {
            ffplaykmp_player_destroy(player);
            return NULL;
        }
    }
    player->session.video_stream = -1;
    player->session.packet = av_packet_alloc();
    player->session.frame = av_frame_alloc();
    player->session.held = av_frame_alloc();
    player->session.next = av_frame_alloc();
    player->queue.shown = av_frame_alloc();
    player->queue.next_token = 1;
    player->queue.max_frame_duration_us = 3600000000LL;
    player->queue.capacity = player->configuration.frame_queue_size;
    player->queue.slots = calloc((size_t)player->queue.capacity, sizeof(*player->queue.slots));
    for (int i = 0; player->queue.slots && i < player->queue.capacity; i++) {
        if (!(player->queue.slots[i].frame = av_frame_alloc())) {
            ffplaykmp_player_destroy(player);
            return NULL;
        }
    }
    player->download_pool = ffmpegkmp_frame_pool_alloc(2);
    if (!player->session.packet || !player->session.frame || !player->session.held || !player->session.next ||
            !player->queue.shown || !player->queue.slots ||
            !player->download_pool) {
        ffplaykmp_player_destroy(player);
        return NULL;
    }
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
    if (retained) {
        pthread_mutex_lock(&player->session_mutex);
        ffplaykmp_session_forget_fallback(&player->session);
        player->session.budget_fallback = 0;
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
    pthread_mutex_unlock(&player->session_mutex);
    /* The session thread flushed the queue as it ended; a player whose thread never ran queued nothing. */
    av_frame_free(&player->queue.shown);
    for (int i = 0; player->queue.slots && i < player->queue.capacity; i++)
        av_frame_free(&player->queue.slots[i].frame);
    free(player->queue.slots);
    /* Frames handed out keep the pool until they go. */
    ffmpegkmp_frame_pool_free(player->download_pool);
    pthread_mutex_destroy(&player->queue.refresh_mutex);
    pthread_cond_destroy(&player->queue.cond);
    pthread_mutex_destroy(&player->queue.mutex);
    pthread_cond_destroy(&player->job_cond);
    pthread_mutex_destroy(&player->job_mutex);
    pthread_mutex_destroy(&player->session_mutex);
    pthread_mutex_destroy(&player->mutex);
    av_free((void *)player->configuration.decoder_name);
    av_free((void *)player->configuration.decoder_options);
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
    player->snapshot.catch_ups = 0;
    ffplaykmp_reset_video_metadata(&player->snapshot);
    player->snapshot.active_decoder = FFPLAYKMP_DECODER_UNKNOWN;
    player->snapshot.decoder_fallback = FFPLAYKMP_DECODER_FALLBACK_NONE;
    player->snapshot.last_error = 0;
    player->snapshot.error_message[0] = '\0';
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
    ffplaykmp_flush_for_output(player);
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
    ffplaykmp_flush_for_output(player);
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
    ffplaykmp_begin_command(player);
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
    player->snapshot.catch_ups = 0;
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
        ffplaykmp_wake_queue(player);
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

void ffplaykmp_input_hint_init(ffplaykmp_input_hint *hint) {
    if (!hint)
        return;
    memset(hint, 0, sizeof(*hint));
    hint->size = sizeof(*hint);
    hint->duration_us = -1;
}

int ffplaykmp_player_get_input_hint(const ffplaykmp_player *player, ffplaykmp_input_hint *hint) {
    int result = FFPLAYKMP_ERROR_INVALID_STATE;
    if (!player || !hint || hint->size < sizeof(*hint))
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    pthread_mutex_lock((pthread_mutex_t *)&player->mutex);
    if (player->input_hint_valid) {
        *hint = player->input_hint;
        result = 0;
    }
    pthread_mutex_unlock((pthread_mutex_t *)&player->mutex);
    return result;
}

typedef struct ffplaykmp_web_callbacks {
    ffplaykmp_web_state_callback state;
    ffplaykmp_web_video_frame_callback frame;
    void *opaque;
    ffplaykmp_player *player;
    /*
     * The page gets RGBA bytes, converted here as a frame is presented: by
     * ffplaykmp_web_player_poll's refresh, or by a preview on the session
     * thread. The queue has one taker at a time, so only one touches the
     * converter at once.
     */
    ffmpegkmp_converter *converter;
    /*
     * The frame mailbox, double buffered: the presenting thread converts into
     * converted[!front] and then, under `mutex`, makes it the front one, which
     * the page copies out of the heap in ffplaykmp_web_player_poll, under
     * `mutex` too. So no frame is copied within the heap, and the one the
     * page reads is never written.
     */
    AVFrame *converted[2];
    int front;
    pthread_mutex_t mutex;
    char snapshot_json[4096];
    uint32_t snapshot_json_size;
    int snapshot_pending;
    int64_t presentation_time_us;
    uint32_t queue_serial;
    int frame_pending;
    /* What ffplaykmp_web_player_set_frame_size asked for, guarded by `mutex`; 0 x 0 for the source's size. */
    int32_t frame_width;
    int32_t frame_height;
    /*
     * The frames prepared and not yet presented or released, by token, each a
     * reference; guarded by `mutex`. PRESENT names its frame by token only.
     */
    struct {
        uint64_t token;
        AVFrame *frame;
    } prepared[16];
    /* The worker's buffer, taken over by prepare_bytes. */
    uint8_t *input;
    uint32_t input_size;
    /* IO_OPEN calls on the input, for the page's tests: one a prepare. */
    atomic_uint input_opens;
    /*
     * The session's input is the packet reader's, from open_packets until
     * close_packets; meanwhile no software run decodes from it. Guarded by
     * session_mutex, as the input is.
     */
    int packets_open;
    /* The reader's packet, allocated once. */
    AVPacket *packet;
} ffplaykmp_web_callbacks;

/* Parks the packet reader; a software run after it seeks, since the decoder no longer follows the demuxer. */
static void ffplaykmp_web_close_packet_reader(ffplaykmp_player *player) {
    ffplaykmp_web_callbacks *callbacks;
    if (!player)
        return;
    callbacks = player->opaque;
    if (!callbacks)
        return;
    pthread_mutex_lock(&player->session_mutex);
    if (callbacks->packets_open) {
        callbacks->packets_open = 0;
        player->session.in_sync = 0;
        player->session.chain++;
    }
    pthread_mutex_unlock(&player->session_mutex);
}

/* The session, while the packet reader has its input, with session_mutex held; else NULL. */
static ffplaykmp_session *ffplaykmp_web_packet_session(ffplaykmp_player *player) {
    ffplaykmp_web_callbacks *callbacks = player ? player->opaque : NULL;
    if (!callbacks || !callbacks->packets_open || !player->session.input.format)
        return NULL;
    return &player->session;
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
    if (operation == FFPLAYKMP_IO_OPEN) {
        atomic_fetch_add(&callbacks->input_opens, 1);
        return FFPLAYKMP_IO_CAP_READ | FFPLAYKMP_IO_CAP_SEEK;
    }
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
            "\"maxFrameAverageLightLevel\":%u,\"droppedFrames\":%llu,\"catchUps\":%llu}",
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
            (unsigned long long)snapshot->dropped_frames,
            (unsigned long long)snapshot->catch_ups);
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

/*
 * Whether `width` x `height` shows a `source_width` x `source_height` frame
 * smaller, at its aspect to within rounding: a size the page asked for another
 * source, before it saw this one, is not one.
 */
static int ffplaykmp_web_frame_size_fits(int width, int height, int source_width, int source_height) {
    const int64_t skew = (int64_t)width * source_height - (int64_t)height * source_width;
    return width > 0 && height > 0 && width <= source_width && height <= source_height &&
            (skew < 0 ? -skew : skew) < (source_width > source_height ? source_width : source_height);
}

/* Takes the prepared frame kept under `token`, NULL when none is. */
static AVFrame *ffplaykmp_web_take_prepared(ffplaykmp_web_callbacks *callbacks, uint64_t token) {
    AVFrame *frame = NULL;
    pthread_mutex_lock(&callbacks->mutex);
    for (size_t index = 0; index < sizeof(callbacks->prepared) / sizeof(callbacks->prepared[0]); index++) {
        if (callbacks->prepared[index].frame && callbacks->prepared[index].token == token) {
            frame = callbacks->prepared[index].frame;
            callbacks->prepared[index].frame = NULL;
            break;
        }
    }
    pthread_mutex_unlock(&callbacks->mutex);
    return frame;
}

/* Keeps a reference to `frame` under `token`; 0, the frame not taken, when there is no room. */
static int ffplaykmp_web_keep_prepared(ffplaykmp_web_callbacks *callbacks, const AVFrame *frame, uint64_t token) {
    int kept = 0;
    pthread_mutex_lock(&callbacks->mutex);
    for (size_t index = 0; index < sizeof(callbacks->prepared) / sizeof(callbacks->prepared[0]); index++) {
        if (!callbacks->prepared[index].frame) {
            if ((callbacks->prepared[index].frame = av_frame_clone(frame))) {
                callbacks->prepared[index].token = token;
                kept = 1;
            }
            break;
        }
    }
    pthread_mutex_unlock(&callbacks->mutex);
    return kept;
}

/*
 * The page's frames: the browser's player keeps its own pacing, not the
 * Kotlin output's, so it takes every frame as it is prepared, keeping a
 * reference to it as the queue keeps it, and converts a frame into the
 * mailbox as it is presented, which ffplaykmp_web_player_poll drives.
 */
static int ffplaykmp_web_frame(
        void *opaque,
        const ffplaykmp_video_frame *frame) {
    ffplaykmp_web_callbacks *callbacks = opaque;
    AVFrame *decoded;
    AVFrame *converted;
    int tone_map_hdr;
    int width;
    int height;
    int result;
    if (!callbacks || !callbacks->frame || !callbacks->player || !frame)
        return 0;
    if (frame->event == FFPLAYKMP_VIDEO_FRAME_PREPARE)
        return frame->frame && ffplaykmp_web_keep_prepared(callbacks, frame->frame->frame, frame->token);
    decoded = ffplaykmp_web_take_prepared(callbacks, frame->token);
    if (!decoded || frame->event != FFPLAYKMP_VIDEO_FRAME_PRESENT) {
        av_frame_free(&decoded);
        return 0;
    }
    pthread_mutex_lock(&callbacks->player->mutex);
    tone_map_hdr = (callbacks->player->snapshot.output_flags & FFPLAYKMP_OUTPUT_TONE_MAP_HDR_TO_SDR) != 0;
    pthread_mutex_unlock(&callbacks->player->mutex);
    pthread_mutex_lock(&callbacks->mutex);
    width = callbacks->frame_width;
    height = callbacks->frame_height;
    pthread_mutex_unlock(&callbacks->mutex);
    if (!ffplaykmp_web_frame_size_fits(width, height, decoded->width, decoded->height))
        width = height = 0;
    if (!callbacks->converter)
        callbacks->converter = ffmpegkmp_converter_alloc(0);
    /* Presenting is serialized (the queue has one taker at a time), and only it changes `front`. */
    converted = callbacks->converted[!callbacks->front];
    if (!callbacks->converter || !converted) {
        av_frame_free(&decoded);
        return 0;
    }
    result = ffplaykmp_rgb_output(converted, decoded, AV_PIX_FMT_RGBA, width, height, tone_map_hdr);
    if (result >= 0)
        result = ffmpegkmp_frame_convert(callbacks->converter, converted, decoded);
    /* A packed RGB source converts pixel for pixel only. */
    if (result == AVERROR(ENOSYS) && width > 0) {
        result = ffplaykmp_rgb_output(converted, decoded, AV_PIX_FMT_RGBA, 0, 0, tone_map_hdr);
        if (result >= 0)
            result = ffmpegkmp_frame_convert(callbacks->converter, converted, decoded);
    }
    av_frame_free(&decoded);
    if (result < 0 || (uint64_t)converted->linesize[0] * (uint64_t)converted->height > UINT32_MAX)
        return 0;
    pthread_mutex_lock(&callbacks->mutex);
    callbacks->front = !callbacks->front;
    callbacks->presentation_time_us = frame->presentation_time_us;
    callbacks->queue_serial = frame->queue_serial;
    callbacks->frame_pending = 1;
    pthread_mutex_unlock(&callbacks->mutex);
    return 0;
}

ffplaykmp_player *ffplaykmp_web_player_create(
        int32_t decoder_preference,
        int32_t decoder_threads,
        int64_t codec_release_delay_us,
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
    atomic_init(&callbacks->input_opens, 0);
    callbacks->converted[0] = av_frame_alloc();
    callbacks->converted[1] = av_frame_alloc();
    callbacks->packet = av_packet_alloc();
    if (!callbacks->converted[0] || !callbacks->converted[1] || !callbacks->packet ||
            pthread_mutex_init(&callbacks->mutex, NULL) != 0) {
        av_frame_free(&callbacks->converted[0]);
        av_frame_free(&callbacks->converted[1]);
        av_packet_free(&callbacks->packet);
        free(callbacks);
        return NULL;
    }
    ffplaykmp_configuration_default(&configuration);
    configuration.decoder_preference = decoder_preference;
    /* 0 is FFmpeg's automatic count, of frame and slice threads. */
    configuration.decoder_threads = decoder_threads;
    configuration.codec_release_delay_us = codec_release_delay_us;
    player = ffplaykmp_player_create(
            &configuration,
            ffplaykmp_web_publish,
            callbacks);
    if (!player) {
        pthread_mutex_destroy(&callbacks->mutex);
        av_frame_free(&callbacks->converted[0]);
        av_frame_free(&callbacks->converted[1]);
        av_packet_free(&callbacks->packet);
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
    ffplaykmp_player_destroy(player);
    for (size_t index = 0; index < sizeof(callbacks->prepared) / sizeof(callbacks->prepared[0]); index++)
        av_frame_free(&callbacks->prepared[index].frame);
    pthread_mutex_destroy(&callbacks->mutex);
    ffmpegkmp_converter_free(&callbacks->converter);
    av_frame_free(&callbacks->converted[0]);
    av_frame_free(&callbacks->converted[1]);
    av_packet_free(&callbacks->packet);
    free(callbacks->input);
    free(callbacks);
}

int ffplaykmp_web_player_prepare_bytes(
        ffplaykmp_player *player,
        uint8_t *bytes,
        uint32_t size,
        const char *extension,
        uint32_t source_flags) {
    ffplaykmp_web_callbacks *callbacks = player ? player->opaque : NULL;
    char input[64];
    char safe_extension[17];
    size_t index = 0;
    if (!callbacks || !bytes || size == 0) {
        free(bytes);
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    }
    /* Closes the input, and with it the packet reader's, before the bytes it reads go. */
    ffplaykmp_player_reset_cancel(player);
    ffplaykmp_web_close_packet_reader(player);
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
    callbacks->input = bytes;
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
    /* The software path decodes from the input the packet reader had. */
    ffplaykmp_web_close_packet_reader(player);
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
    /* The worker polls every few milliseconds: often enough to show each frame near its time. */
    ffplaykmp_player_refresh(player, -1, 0);
    pthread_mutex_lock(&callbacks->mutex);
    if (callbacks->snapshot_pending && callbacks->state) {
        callbacks->snapshot_pending = 0;
        callbacks->state(
                callbacks->opaque,
                callbacks->snapshot_json,
                callbacks->snapshot_json_size);
    }
    if (callbacks->frame_pending && callbacks->frame) {
        const AVFrame *front = callbacks->converted[callbacks->front];
        callbacks->frame_pending = 0;
        callbacks->frame(
                callbacks->opaque,
                front->data[0],
                (uint32_t)front->linesize[0] * (uint32_t)front->height,
                front->width,
                front->height,
                front->linesize[0],
                callbacks->presentation_time_us,
                callbacks->queue_serial);
    }
    pthread_mutex_unlock(&callbacks->mutex);
}

void ffplaykmp_web_player_set_frame_size(ffplaykmp_player *player, int32_t width, int32_t height) {
    ffplaykmp_web_callbacks *callbacks = player ? player->opaque : NULL;
    if (!callbacks)
        return;
    pthread_mutex_lock(&callbacks->mutex);
    callbacks->frame_width = width > 0 && height > 0 ? width : 0;
    callbacks->frame_height = width > 0 && height > 0 ? height : 0;
    pthread_mutex_unlock(&callbacks->mutex);
}

uint32_t ffplaykmp_web_input_probes(void) {
    ffplaykmp_statistics statistics;
    ffplaykmp_statistics_init(&statistics);
    ffplaykmp_get_statistics(&statistics);
    return (uint32_t)statistics.input_probes;
}

uint32_t ffplaykmp_web_player_input_opens(ffplaykmp_player *player) {
    ffplaykmp_web_callbacks *callbacks = player ? player->opaque : NULL;
    return callbacks ? atomic_load(&callbacks->input_opens) : 0;
}

/*
 * Moves the session's input to the start of its video stream for the packet
 * reader, with session_mutex held: an input not yet read past its probe is
 * there already. The decoder no longer follows the demuxer, so a software run
 * after it seeks.
 */
static int ffplaykmp_web_rewind_input(ffplaykmp_session *session) {
    AVIOContext *pb = session->input.format->pb;
    int64_t target;
    session->in_sync = 0;
    session->chain++;
    if (session->at_start) {
        session->at_start = 0;
        return 0;
    }
    if (pb) {
        pb->error = 0;
        pb->eof_reached = 0;
    }
    target = av_rescale_q(session->media_start_us, AV_TIME_BASE_Q, session->stream->time_base);
    return avformat_seek_file(
            session->input.format, session->video_stream, INT64_MIN, target, INT64_MAX, AVSEEK_FLAG_BACKWARD);
}

int ffplaykmp_web_player_open_packets(
        ffplaykmp_player *player,
        ffplaykmp_web_decoder_config_callback callback,
        void *opaque) {
    ffplaykmp_web_callbacks *callbacks;
    ffplaykmp_session *session;
    const AVCodecParameters *parameters;
    const char *input;
    char codec[64];
    int result;
    if (!player || !callback)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    callbacks = player->opaque;
    if (!callbacks)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    /* The two outputs are exclusive: a software playback reading the input ends first. */
    ffplaykmp_stop_worker(player);
    pthread_mutex_lock(&player->session_mutex);
    session = &player->session;
    callbacks->packets_open = 0;
    pthread_mutex_lock(&player->mutex);
    input = player->input;
    pthread_mutex_unlock(&player->mutex);
    /* Prepare left the input open; only a software run that failed closed it since. */
    result = input ? ffplaykmp_session_open_input(player, input) : FFPLAYKMP_ERROR_INVALID_STATE;
    if (result < 0) {
        pthread_mutex_unlock(&player->session_mutex);
        return result;
    }
    parameters = session->stream->codecpar;
    result = ffplaykmp_web_codec_string(parameters, codec, sizeof(codec));
    if (result >= 0)
        result = ffplaykmp_web_rewind_input(session);
    if (result >= 0) {
        callbacks->packets_open = 1;
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
        result = 0;
    }
    pthread_mutex_unlock(&player->session_mutex);
    return result;
}

int ffplaykmp_web_player_prepare_packets(
        ffplaykmp_player *player,
        uint8_t *bytes,
        uint32_t size,
        const char *extension,
        ffplaykmp_web_decoder_config_callback callback,
        void *opaque) {
    int result = ffplaykmp_web_player_prepare_bytes(player, bytes, size, extension, 0);
    return result < 0 ? result : ffplaykmp_web_player_open_packets(player, callback, opaque);
}

int ffplaykmp_web_player_read_packet(
        ffplaykmp_player *player,
        ffplaykmp_web_encoded_packet_callback callback,
        void *opaque) {
    ffplaykmp_web_callbacks *callbacks;
    ffplaykmp_session *session;
    AVPacket *packet;
    int64_t timestamp;
    int64_t duration;
    uint32_t queue_serial;
    int result;
    if (!player || !callback)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    callbacks = player->opaque;
    pthread_mutex_lock(&player->session_mutex);
    session = ffplaykmp_web_packet_session(player);
    if (!session) {
        pthread_mutex_unlock(&player->session_mutex);
        return FFPLAYKMP_ERROR_INVALID_STATE;
    }
    packet = callbacks->packet;
    while ((result = av_read_frame(session->input.format, packet)) >= 0) {
        if (packet->stream_index != session->video_stream) {
            av_packet_unref(packet);
            continue;
        }
        timestamp = packet->pts != AV_NOPTS_VALUE ? packet->pts : packet->dts;
        if (timestamp == AV_NOPTS_VALUE)
            timestamp = 0;
        timestamp = ffplaykmp_session_time_us(session, timestamp);
        if (timestamp < 0)
            timestamp = 0;
        duration = packet->duration > 0
                ? av_rescale_q(packet->duration, session->stream->time_base, AV_TIME_BASE_Q)
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
        av_packet_unref(packet);
        pthread_mutex_unlock(&player->session_mutex);
        return 0;
    }
    pthread_mutex_unlock(&player->session_mutex);
    return result == AVERROR_EOF ? 1 : result;
}

void ffplaykmp_web_player_close_packets(ffplaykmp_player *player) {
    ffplaykmp_web_close_packet_reader(player);
}

int ffplaykmp_web_player_packet_timing(ffplaykmp_player *player, int64_t *values) {
    ffplaykmp_session *session;
    int result = FFPLAYKMP_ERROR_INVALID_STATE;
    if (!player || !values)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    pthread_mutex_lock(&player->session_mutex);
    session = ffplaykmp_web_packet_session(player);
    if (session) {
        values[0] = session->stream->time_base.num;
        values[1] = session->stream->time_base.den;
        ffplaykmp_stream_timing(session->input.format, session->stream, &values[2], &values[3], &values[4]);
        result = 0;
    }
    pthread_mutex_unlock(&player->session_mutex);
    return result;
}

int ffplaykmp_web_player_seek_packets(ffplaykmp_player *player, int64_t target) {
    ffplaykmp_session *session;
    AVFormatContext *format;
    int index;
    int result = FFPLAYKMP_ERROR_INVALID_STATE;
    if (!player)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    pthread_mutex_lock(&player->session_mutex);
    session = ffplaykmp_web_packet_session(player);
    if (session) {
        format = session->input.format;
        index = session->video_stream;
        /* The keyframe at or before the target, never after it, as the pull decoder seeks. */
        result = avformat_seek_file(format, index, INT64_MIN, target, target, AVSEEK_FLAG_BACKWARD);
        if (result < 0)
            result = avformat_seek_file(format, index, INT64_MIN, target, INT64_MAX, AVSEEK_FLAG_BACKWARD);
    }
    pthread_mutex_unlock(&player->session_mutex);
    return result;
}

int64_t ffplaykmp_web_player_keyframe_before(ffplaykmp_player *player, int64_t target) {
    ffplaykmp_session *session;
    const AVIndexEntry *entry = NULL;
    int index;
    if (!player)
        return AV_NOPTS_VALUE;
    pthread_mutex_lock(&player->session_mutex);
    session = ffplaykmp_web_packet_session(player);
    if (session) {
        AVStream *stream = session->input.format->streams[session->video_stream];
        index = av_index_search_timestamp(stream, target, AVSEEK_FLAG_BACKWARD);
        entry = index >= 0 ? avformat_index_get_entry(stream, index) : NULL;
    }
    pthread_mutex_unlock(&player->session_mutex);
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
    ffplaykmp_session *session;
    int64_t target;
    int play_when_ready;
    int result;
    if (!player || position_us < 0 || ffplaykmp_require_prepared(player) < 0)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    pthread_mutex_lock(&player->session_mutex);
    session = ffplaykmp_web_packet_session(player);
    if (!session) {
        pthread_mutex_unlock(&player->session_mutex);
        return FFPLAYKMP_ERROR_INVALID_STATE;
    }
    target = av_rescale_q(session->media_start_us + position_us, AV_TIME_BASE_Q, session->stream->time_base);
    pthread_mutex_lock(&player->mutex);
    play_when_ready = player->play_when_ready;
    player->snapshot.state = FFPLAYKMP_STATE_SEEKING;
    player->snapshot.position_us = position_us;
    player->snapshot.queue_serial++;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    result = avformat_seek_file(
            session->input.format,
            session->video_stream,
            INT64_MIN,
            target,
            INT64_MAX,
            AVSEEK_FLAG_BACKWARD);
    pthread_mutex_unlock(&player->session_mutex);
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
