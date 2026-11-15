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
 * format of the frames handed out in memory, NULL for frames as decoded: a
 * MEMORY output's, and a SURFACE output's software frames; MediaCodec's frames
 * in the Surface keep theirs. `width` and `height` are the memory format's
 * frame size, 0 and 0 for the source's: frames are scaled to it as they are
 * converted, keeping their display aspect through their sample aspect ratio.
 * A size needs a memory format. `decoder_threads` is
 * the software decoder's thread count, 0 for FFmpeg's automatic count capped
 * at 8; it decodes with slice threads only, so one frame is in progress,
 * unless set_thread_type says otherwise, and hardware decoders ignore it.
 * Conversions use at most as many threads. `timeout_us` bounds each blocking
 * call, 0 for none. Returns NULL and stores the negative error in *error on failure.
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
 * The software decoder's thread_type, before start: FF_THREAD_FRAME,
 * FF_THREAD_SLICE or both, as FFmpeg's codec context takes them. Frame
 * threading decodes several frames at once and holds about one decoded picture
 * per thread; FFmpeg uses it wherever the codec has it, and slice threads
 * elsewhere. FF_THREAD_SLICE alone keeps one frame in progress. Unset, the
 * codec context keeps FFmpeg's default, both.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_set_thread_type(ffmpegkmp_video_decoder *decoder, int32_t thread_type);
/*
 * How many MEMORY frames the caller may hold at once, before start: the pool
 * hands out this many per layout and size, and the next waits for one to
 * close. 3 unless set, at least 2: the caller's and the one decoded ahead.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_set_frame_ring(ffmpegkmp_video_decoder *decoder, int32_t frames);
/*
 * Before start: open the decoder FFmpeg knows as `name` ("h264", "libaom-av1",
 * "av1_mediacodec"), as `ffmpeg -c:v name` before `-i` takes it, alone,
 * instead of choosing by `decoder_preference`, which no longer applies: no
 * other decoder is tried and nothing falls back. It must decode the stream's
 * codec. `options` are its AVOptions, `options_size` bytes holding, for each,
 * its key and then its value, each a NUL-terminated string (NULL and 0 for
 * none), set on the decoder as avcodec_open2 sets an options dictionary's. An
 * unknown name, one for another codec, an option the decoder does not
 * recognise or that is no video decoding option, and a value it does not take
 * fail start, and ffmpegkmp_video_decoder_error says which. A platform wrapper
 * such as MediaCodec's opens as the hardware decoder and takes a place in the
 * budget (FFPLAYKMP_ERROR_BUSY with none free); others decode in software
 * with the decoder's threads, unless their options set them. Both strings
 * are copied.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_set_named_decoder(
        ffmpegkmp_video_decoder *decoder,
        const char *name,
        const char *options,
        int32_t options_size);
/*
 * Why a named decoder did not open, or its options did not check, in words;
 * empty otherwise. Valid until the next call on the decoder.
 */
FFPLAYKMP_EXPORT const char *ffmpegkmp_video_decoder_error(const ffmpegkmp_video_decoder *decoder);
/*
 * Opens the input, selects its best video stream and decodes the first frame.
 * Under FFPLAYKMP_DECODER_AUTO a hardware decoder that fails or times out
 * before producing a frame is replaced once by the software one, which gets a
 * fresh deadline; a SURFACE output then returns frames in memory, in the
 * memory format or as decoded, instead (reported by `hardware == 0` and a non-NULL frame). A hardware
 * decoder holds a place in the process's budget (ffplaykmp_decoder_budget_set)
 * until the decoder is destroyed; with the budget full AUTO opens the software
 * one at once and REQUIRE_HARDWARE fails with FFPLAYKMP_ERROR_BUSY. The info's
 * decoder_fallback says why AUTO decodes in software.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_start(ffmpegkmp_video_decoder *decoder);
/*
 * Closes the input and makes the decoder ready for `input`, which the next
 * start opens and decodes from, as a decoder created for it with this memory
 * format, size, timeout and I/O would; the output, decoder preference or name,
 * threads and ring stay. Between a successful start and this call nothing
 * else may run on the decoder. The next start keeps the open codec, flushed,
 * when the new input's video stream is coded the same way (codec, tag, size,
 * pixel layout, profile, level, bit depth and setup bytes alike): a hardware
 * decoder then keeps its budget place and the reason it was chosen. It closes
 * the codec otherwise, and when it is a software one AUTO opened because the
 * hardware one failed or the budget was full, before opening one for the new
 * stream. The ring of the old memory format and size goes, as a resize lets it
 * go; frames handed out keep their memory. Only MEMORY output reopens; others
 * fail with FFPLAYKMP_ERROR_INVALID_ARGUMENT. Fails with
 * FFPLAYKMP_ERROR_INVALID_STATE once aborted.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_reopen(
        ffmpegkmp_video_decoder *decoder,
        const char *input,
        const ffmpegkmp_frame_format *memory_format,
        int32_t width,
        int32_t height,
        int64_t timeout_us,
        ffplaykmp_io_callback io_callback,
        void *io_opaque);
/* Stream metadata, refined by the first decoded frame after start. */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_get_info(
        const ffmpegkmp_video_decoder *decoder,
        ffplaykmp_snapshot *info);
/* Seeks accurately so that the frame at `position_ns` becomes current. */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_seek(
        ffmpegkmp_video_decoder *decoder,
        int64_t position_ns);
/*
 * Changes the memory format's frame size, as create's `width` and `height`
 * take it: 0 and 0 for the source's, and a size only with a memory format.
 * Frames are converted as they are handed out, so the next frame_at converts
 * the current frame again at the new size, without decoding or seeking, into
 * the frame a decoder created at that size returns. Frames handed out before
 * keep their size and pixels. The decoder frees its pooled memory of the old
 * size, apart from frames the caller still holds, which is freed as they are
 * released; a resize back allocates again.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_set_size(
        ffmpegkmp_video_decoder *decoder,
        int32_t width,
        int32_t height);
/*
 * Changes the format and size of the frames handed out in memory from now on,
 * to those create takes: NULL for frames as decoded, 0 and 0 for the source's
 * size, and a size only with a memory format. As set_size, of which this is
 * the general form, it decodes nothing and does not seek: the next frame_at
 * converts the current frame again, into the frame a decoder created with
 * this format and size returns. Frames handed out before keep their format,
 * size and pixels, and the pooled memory of the old ones is freed.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_set_memory_format(
        ffmpegkmp_video_decoder *decoder,
        const ffmpegkmp_frame_format *memory_format,
        int32_t width,
        int32_t height);
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
