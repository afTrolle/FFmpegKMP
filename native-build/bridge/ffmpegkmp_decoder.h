// SPDX-License-Identifier: LGPL-2.1-or-later
#ifndef FFMPEGKMP_DECODER_H
#define FFMPEGKMP_DECODER_H

#include <stdint.h>

#include "ffmpegkmp_frame.h"
#include "ffplaykmp_player.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Pull-based video decoder: returns the frame shown at a media position. No
 * worker thread, clock or queue; every call decodes on the calling thread.
 *
 * The frame at position s is the decoded frame with the largest pts <= s,
 * held until the next frame's pts. Before the first frame the first frame is
 * returned, past the last the last is held. Positions are nanoseconds from the
 * container start time (the origin FFplay and `-ss` use) and are matched to
 * the stream time base to the nearest unit, so a clock tick that rounds onto a
 * frame's pts selects that frame.
 *
 * Threading: every call except interrupt, abort and time_left must come from
 * one thread at a time, and on Android from the thread that called start
 * (MediaCodec sessions are bound to it). interrupt, abort and time_left are
 * safe from any thread.
 *
 * Deadlines: with a timeout, each start, seek and frame_at call is bounded by
 * it. FFmpeg I/O, the decode loop and MediaCodec waits give up at the deadline
 * and the call returns FFPLAYKMP_ERROR_TIMED_OUT, as does every later call.
 * Only a host I/O callback that blocks cannot be interrupted; the host must
 * unblock it (after abort) itself.
 */
typedef struct ffmpegkmp_video_decoder ffmpegkmp_video_decoder;

typedef enum ffmpegkmp_video_output {
    /*
     * Frames in memory. With a memory format, each frame is converted into a
     * pooled frame of that format on the decoding thread. Without one, frames
     * come as decoded, and hardware frames are downloaded; except in the
     * Kotlin/Native Apple runtimes, where every frame is a CVPixelBuffer:
     * VideoToolbox's own, or a software frame copied into a pooled
     * IOSurface-backed NV12 (8-bit), P010 (deeper) or, for RGB, BGRA buffer in
     * its own colour. On Android MediaCodec decodes 8-bit sources into memory
     * itself (ByteBuffer mode), as NV12 or YUV420P; deeper sources decode in
     * software, and FFPLAYKMP_DECODER_REQUIRE_HARDWARE fails on them.
     */
    FFMPEGKMP_VIDEO_OUTPUT_MEMORY = 0,
    /* Android: MediaCodec renders into the attached Surface; frames carry no pixels. */
    FFMPEGKMP_VIDEO_OUTPUT_SURFACE = 1,
} ffmpegkmp_video_output;

typedef struct ffmpegkmp_decoded_frame {
    uint32_t size;
    /* Changes whenever a different decoded frame becomes the current one. */
    uint64_t serial;
    int64_t pts_ns;
    int64_t duration_ns;
    /* The size handed out, the memory format's when the decoder has one, and its sample aspect ratio. */
    int32_t width;
    int32_t height;
    int32_t sample_aspect_ratio_num;
    int32_t sample_aspect_ratio_den;
    double rotation_degrees;
    /* Non-zero when a hardware decoder produced the frame. */
    int32_t hardware;
    /*
     * A new reference to the frame's pixels, which the caller releases with
     * ffmpegkmp_frame_unref, also past the decoder's destruction. The same
     * decoded frame is the same memory each time. NULL when the frame was
     * rendered to the Surface.
     */
    ffmpegkmp_frame *frame;
} ffmpegkmp_decoded_frame;

FFPLAYKMP_EXPORT void ffmpegkmp_decoded_frame_init(ffmpegkmp_decoded_frame *frame);

/*
 * Creates a decoder for `input` (a path, URL, or "ffmpegkmp:<id>" served by
 * `io_callback`) without touching it: start opens it. `memory_format` is the
 * MEMORY output's format, NULL for frames as decoded; a SURFACE output takes
 * NULL. `width` and `height` are the memory format's frame size, 0 and 0 for
 * the source's: frames are scaled to it as they are converted, keeping their
 * display aspect through their sample aspect ratio. A size needs a memory
 * format. `decoder_threads` is
 * the software decoder's thread count, 0 for FFmpeg's automatic count capped
 * at 8; it decodes with slice threads only, so one frame is in progress, and
 * hardware decoders ignore it. Conversions use at most as many threads. `timeout_us` bounds each blocking call, 0
 * for none. Returns NULL and stores the negative error in *error on failure.
 */
FFPLAYKMP_EXPORT ffmpegkmp_video_decoder *ffmpegkmp_video_decoder_create(
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
        int32_t *error);
#if defined(__ANDROID__)
/* Attaches the Surface a SURFACE decoder renders into; call before start. */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_set_android_surface(
        JNIEnv *env,
        jclass owner,
        jobject surface,
        ffmpegkmp_video_decoder *decoder);
#endif
/*
 * Opens the input, selects its best video stream and decodes the first frame.
 * Under FFPLAYKMP_DECODER_AUTO a hardware decoder that fails or times out
 * before producing a frame is replaced once by the software one, which gets a
 * fresh deadline; a SURFACE output then returns frames in memory, as decoded,
 * instead (reported by `hardware == 0` and a non-NULL frame).
 */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_start(ffmpegkmp_video_decoder *decoder);
/* Stream metadata, refined by the first decoded frame after start. */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_get_info(
        const ffmpegkmp_video_decoder *decoder,
        ffplaykmp_snapshot *info);
/* Seeks accurately so that the frame at `position_ns` becomes current. */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_seek(
        ffmpegkmp_video_decoder *decoder,
        int64_t position_ns);
/*
 * Makes the frame at `position_ns` current and describes it, with a new
 * reference to its pixels. Returns without decoding or converting when the
 * current frame still covers the position, decodes forward when it lies ahead
 * and seeks when it lies behind.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_frame_at(
        ffmpegkmp_video_decoder *decoder,
        int64_t position_ns,
        ffmpegkmp_decoded_frame *frame);
/*
 * Fails the running call, as its caller no longer wants it: FFmpeg I/O, the
 * decode loop and MediaCodec waits give up and it returns AVERROR_EXIT. Later
 * calls work, and the next seek or frame_at seeks to its position first, as an
 * interrupted call can stop mid-seek. Between calls it has no effect: every
 * call starts uninterrupted.
 */
FFPLAYKMP_EXPORT void ffmpegkmp_video_decoder_interrupt(ffmpegkmp_video_decoder *decoder);
/* Interrupts blocking input; later calls fail. The decoder must still be destroyed. */
FFPLAYKMP_EXPORT void ffmpegkmp_video_decoder_abort(ffmpegkmp_video_decoder *decoder);
/*
 * Microseconds until the running call's deadline, negative once it has passed;
 * 0 when no call with a deadline is running.
 */
FFPLAYKMP_EXPORT int64_t ffmpegkmp_video_decoder_time_left(const ffmpegkmp_video_decoder *decoder);
FFPLAYKMP_EXPORT void ffmpegkmp_video_decoder_destroy(ffmpegkmp_video_decoder *decoder);

#ifdef __cplusplus
}
#endif

#endif
