// SPDX-License-Identifier: LGPL-2.1-or-later
#ifndef FFMPEGKMP_DECODER_H
#define FFMPEGKMP_DECODER_H

#include <stdint.h>

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
 * Threading: every call except abort and time_left must come from one thread
 * at a time, and on Android from the thread that called start (MediaCodec
 * sessions are bound to it). abort and time_left are safe from any thread.
 *
 * Deadlines: with a timeout, each start, seek and frame_at call is bounded by
 * it. FFmpeg I/O, the decode loop and MediaCodec waits give up at the deadline
 * and the call returns FFPLAYKMP_ERROR_TIMED_OUT, as does every later call.
 * Only a host I/O callback that blocks cannot be interrupted; the host must
 * unblock it (after abort) itself.
 */
typedef struct ffmpegkmp_video_decoder ffmpegkmp_video_decoder;

typedef enum ffmpegkmp_video_output {
    /* 8-bit sRGB RGBA; PQ and HLG are tone mapped to BT.709. */
    FFMPEGKMP_VIDEO_OUTPUT_RGBA8 = 0,
    /* RGBA half floats, linear extended sRGB, 1.0 = SDR white (203 nits for HDR). */
    FFMPEGKMP_VIDEO_OUTPUT_LINEAR_F16 = 1,
    /* Android: MediaCodec renders into the attached Surface; frames carry no pixels. */
    FFMPEGKMP_VIDEO_OUTPUT_SURFACE = 2,
    /*
     * Apple: frames carry the CVPixelBuffer VideoToolbox decoded into. Software
     * frames are copied into pooled IOSurface-backed NV12 (8-bit) or P010
     * (deeper) buffers, so every frame is a CVPixelBuffer. Other hosts, and
     * Apple runtimes built without VideoToolbox, reject it.
     */
    FFMPEGKMP_VIDEO_OUTPUT_PIXEL_BUFFER = 3,
} ffmpegkmp_video_output;

typedef struct ffmpegkmp_decoded_frame {
    uint32_t size;
    /* Changes whenever a different decoded frame becomes the current one. */
    uint64_t serial;
    int64_t pts_ns;
    int64_t duration_ns;
    /* RGBA8 or RGBA F16 rows, valid until the next call on the decoder. NULL
     * when the frame was rendered to the Surface. */
    const uint8_t *pixels;
    uint64_t pixels_size;
    int32_t width;
    int32_t height;
    int32_t stride;
    int32_t sample_aspect_ratio_num;
    int32_t sample_aspect_ratio_den;
    double rotation_degrees;
    /* Non-zero when a hardware decoder produced the frame. */
    int32_t hardware;
    /*
     * PIXEL_BUFFER output: the frame's CVPixelBufferRef, owned by the decoder
     * and valid until the next call on it; CFRetain it to keep it longer. Its
     * colour attachments match the fields below. NULL for other outputs.
     */
    void *pixel_buffer;
    /* The buffer's CoreVideo pixel format, an OSType such as '420v' or 'x420'. */
    uint32_t pixel_format;
    /* Non-zero when an IOSurface backs the buffer, as CVMetalTextureCache needs. */
    int32_t io_surface;
    /* The frame's AVColorPrimaries, AVColorTransferCharacteristic, AVColorSpace and AVColorRange. */
    int32_t color_primaries;
    int32_t color_transfer;
    int32_t color_space;
    int32_t color_range;
} ffmpegkmp_decoded_frame;

FFPLAYKMP_EXPORT void ffmpegkmp_decoded_frame_init(ffmpegkmp_decoded_frame *frame);

/*
 * Creates a decoder for `input` (a path, URL, or "ffmpegkmp:<id>" served by
 * `io_callback`) without touching it: start opens it. `timeout_us` bounds each
 * blocking call, 0 for none. Returns NULL and stores the negative error in
 * *error on failure.
 */
FFPLAYKMP_EXPORT ffmpegkmp_video_decoder *ffmpegkmp_video_decoder_create(
        const char *input,
        int32_t output,
        int32_t decoder_preference,
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
 * fresh deadline; a SURFACE output then returns CPU RGBA frames instead
 * (reported by `hardware == 0` and non-NULL pixels), and a PIXEL_BUFFER output
 * copies the software frames into CVPixelBuffers.
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
 * Makes the frame at `position_ns` current and describes it. Returns without
 * decoding when the current frame still covers the position, decodes forward
 * when it lies ahead and seeks when it lies behind.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_video_decoder_frame_at(
        ffmpegkmp_video_decoder *decoder,
        int64_t position_ns,
        ffmpegkmp_decoded_frame *frame);
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
