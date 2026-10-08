// SPDX-License-Identifier: LGPL-2.1-or-later
/*
 * Demux, metadata, decoder setup and frame conversion shared by the FFplay
 * player (ffplaykmp_player.c), the pull-based video decoder
 * (ffmpegkmp_decoder.c) and the frame handles (ffmpegkmp_frame.c). Internal to
 * the bridge: not installed, not bound.
 */
#ifndef FFPLAYKMP_CORE_H
#define FFPLAYKMP_CORE_H

#include "ffmpegkmp_frame.h"
#include "ffplaykmp_player.h"

#include <pthread.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#if defined(__ANDROID__)
#include <libavcodec/mediacodec.h>
#endif

/* Serves "ffmpegkmp:<id>" inputs and interrupts blocking I/O. */
typedef struct ffplaykmp_io_host {
    ffplaykmp_io_callback callback;
    void *opaque;
    int (*interrupted)(void *opaque);
    void *interrupt_opaque;
} ffplaykmp_io_host;

typedef struct ffplaykmp_avio {
    ffplaykmp_io_host host;
    int64_t resource_id;
    int64_t position;
} ffplaykmp_avio;

/* Whether the host's interrupt callback asks blocking I/O to stop. */
int ffplaykmp_host_interrupted(const ffplaykmp_io_host *host);
/* AVIOContext callbacks over a host-mounted resource, at io->position. */
int ffplaykmp_avio_read(void *opaque, uint8_t *buffer, int size);
int64_t ffplaykmp_avio_seek(void *opaque, int64_t offset, int whence);

/* A demuxer over a URL or a host-mounted ("ffmpegkmp:<id>") resource. */
typedef struct ffplaykmp_input {
    AVFormatContext *format;
    AVIOContext *avio;
    ffplaykmp_avio *io;
    ffplaykmp_io_host host;
    int64_t resource_id;
    int resource_opened;
} ffplaykmp_input;

/* Opens and probes `url`; on failure the caller still closes `input`. */
int ffplaykmp_open_input(const ffplaykmp_io_host *host, const char *url, ffplaykmp_input *input);
void ffplaykmp_close_input(ffplaykmp_input *input);

/*
 * Media time is measured from the container start, as the audio engine does,
 * so audio and video clocks agree when their streams start at different times.
 */
int64_t ffplaykmp_media_start_us(const AVFormatContext *format);

/*
 * A video stream's timing in its time base, as the pull decoders count frames: `origin` is the
 * stream time of position zero, `default_duration` how long a frame without a duration of its
 * own is held (one frame at the stream's rate), and `stream_end` where the stream ends, or
 * AV_NOPTS_VALUE when the container does not say.
 */
void ffplaykmp_stream_timing(
        const AVFormatContext *format,
        const AVStream *stream,
        int64_t *origin,
        int64_t *default_duration,
        int64_t *stream_end);

void ffplaykmp_reset_video_metadata(ffplaykmp_snapshot *snapshot);
void ffplaykmp_read_stream_metadata(ffplaykmp_snapshot *snapshot, const AVStream *stream);
void ffplaykmp_read_frame_metadata(ffplaykmp_snapshot *snapshot, const AVFrame *frame);
int ffplaykmp_is_hardware_frame(const AVFrame *frame);

/*
 * The best video stream's decoder. With `hardware` set it is the platform
 * decoder: on Android MediaCodec, rendering to `android_surface` (a JNI global
 * reference) or, without one, decoding into memory (ByteBuffer mode); the
 * host's hwaccel device elsewhere. Closing it is always safe,
 * also after a failed open. The struct must not move while open: the hwaccel
 * get_format callback points into it.
 */
typedef struct ffplaykmp_video_codec {
    AVCodecContext *decoder;
    int stream_index;
    int hardware;
#if defined(__ANDROID__)
    AVMediaCodecContext *mediacodec_context;
#else
    AVBufferRef *hardware_device;
    enum AVPixelFormat hardware_format;
#endif
} ffplaykmp_video_codec;

/*
 * Picks the best video stream of `format`. Returns the stream index, or a
 * negative error. `codec` receives its software decoder.
 */
int ffplaykmp_find_video_stream(AVFormatContext *format, const AVCodec **codec);

/* The most threads a software decoder gets when its thread count is automatic (0). */
#define FFPLAYKMP_DECODER_THREADS_AUTO_MAX 8

/*
 * The threads a software decoder gets for `threads`: that many, or for 0
 * FFmpeg's automatic count, one more than the cores, at most
 * FFPLAYKMP_DECODER_THREADS_AUTO_MAX. Never 0.
 */
int ffplaykmp_decoder_thread_count(int threads);

/*
 * Opens `stream_index`'s decoder. When `hardware` is requested but this host
 * has no hardware decoder for the codec, the software decoder is opened and
 * codec->hardware stays 0; `require_hardware` turns that into
 * FFPLAYKMP_ERROR_UNSUPPORTED instead. A software decoder decodes with
 * ffplaykmp_decoder_thread_count(`threads`) threads of `thread_type`:
 * FF_THREAD_FRAME | FF_THREAD_SLICE as ffplay's `-threads` sets them, where
 * FFmpeg takes frame threading wherever the codec has it and keeps about one
 * frame in flight per thread, or FF_THREAD_SLICE alone for one frame in
 * progress. Hardware decoders keep FFmpeg's default of one. `options` (may be
 * NULL) go to avcodec_open2, which leaves the ones the chosen decoder does not
 * know.
 */
int ffplaykmp_video_codec_open(
        ffplaykmp_video_codec *codec,
        AVFormatContext *format,
        int stream_index,
        int hardware,
        int require_hardware,
        int threads,
        int thread_type,
        void *android_surface,
        AVDictionary **options);
void ffplaykmp_video_codec_close(ffplaykmp_video_codec *codec);
/*
 * Whether `codec` decoded `frame` in hardware: a hwaccel frame, or any frame
 * MediaCodec decoded, including the software frames it decodes into memory.
 */
int ffplaykmp_video_codec_frame_is_hardware(const ffplaykmp_video_codec *codec, const AVFrame *frame);

#if defined(__ANDROID__)
/* Retains `object` as a global reference and registers the VM with FFmpeg's JNI support. */
int ffplaykmp_android_retain_global(JNIEnv *env, jobject object, JavaVM **vm, jobject *retained);
/* Deletes a global reference from any thread, attaching it to the VM when needed. */
void ffplaykmp_android_release_global(JavaVM *vm, jobject object);
#endif

/*
 * Converts frames between pixel layouts and colours. It keeps its swscale
 * contexts, a 16-bit intermediate and a small worker pool between
 * conversions; one thread at a time may use it.
 */
typedef struct ffmpegkmp_converter ffmpegkmp_converter;

/*
 * Returns NULL when out of memory. With `threads` above 0 each of its swscale
 * contexts gets that many threads and its worker pool one fewer, the pool
 * still at most one fewer than the cores and its cap of 8; a decoder passes
 * its own thread count, so one video's threads stay within it. 0 keeps the
 * defaults: swscale's automatic count, and the pool at that bound. The
 * Emscripten build keeps its fixed counts whatever `threads` is.
 */
ffmpegkmp_converter *ffmpegkmp_converter_alloc(int threads);
/* Frees *converter, joins its workers and sets it to NULL; NULL is a no-op. */
void ffmpegkmp_converter_free(ffmpegkmp_converter **converter);

/*
 * One threaded, colour-managed pass from `source` into `destination`. Layout
 * and colour come from the two frames: their pixel formats, primaries,
 * transfers, matrices and ranges. `destination`
 * gets buffers when it has none, and gets `source`'s properties and side data
 * (av_frame_copy_props) while keeping its own colour. An unspecified YUV
 * matrix is taken as BT.709, on either side. Frames convert progressively,
 * interlaced or not.
 *
 * A destination of another size is scaled into, bilinearly, in the same
 * swscale pass, and gets the sample aspect ratio that keeps the source's
 * display aspect (ffmpegkmp_scaled_aspect). On the HDR routes the first
 * swscale step scales, so the transfer step and the gamut pass run at the
 * destination's size. From RGBA F16 swscale scales on the way out into YUV;
 * into RGBA F16 or packed RGBA of another size it returns AVERROR(ENOSYS).
 *
 * - Same primaries and transfer: swscale only, for layout, matrix, range and
 *   chroma.
 * - To an HDR-capable target (a PQ, HLG or linear transfer, or RGBA F16): the
 *   transfer step decodes the source curve, converts the primaries and
 *   encodes the target curve, in float and from tables, so values above 1.0
 *   and below 0 survive where the target holds them. Linear light has 1.0 at
 *   203 nits (BT.2408 reference white), HLG is displayed on a 1000-nit
 *   display, and SDR curves decode with the sRGB curve (or their gamma). F16
 *   sources must be linear.
 * - PQ or HLG to an SDR target: BT.2390's EETF onto 203-nit SDR white, from
 *   the clip's peak, on each pixel's brightest component, then swscale's
 *   perceptual gamut mapping into the target's primaries.
 * - Other SDR pairs: swscale with its own colour management.
 *
 * Primaries are BT.709, BT.2020 or Display P3 (SMPTE 432); others are taken
 * as BT.709. Returns AVERROR(ENOSYS) for pairs none of these cover, such as
 * hardware frames.
 */
int ffmpegkmp_frame_convert(ffmpegkmp_converter *converter, AVFrame *destination, const AVFrame *source);

/*
 * The sample aspect ratio that shows a `width` x `height` frame of `aspect`
 * at its display aspect once scaled to `scaled_width` x `scaled_height`.
 * `aspect` unchanged at the same size; an unknown one counts as square.
 */
AVRational ffmpegkmp_scaled_aspect(AVRational aspect, int width, int height, int scaled_width, int scaled_height);

/*
 * Prepares `output` for one of the bridge's CPU outputs of `source`: packed
 * RGBA (AV_PIX_FMT_RGBA) or RGBA half floats (AV_PIX_FMT_RGBAF16) at the
 * source's size, reusing its buffer when it can. RGBA F16 is linear extended
 * sRGB. RGBA keeps the source's primaries and transfer, so only the matrix
 * changes, except that with `tone_map_hdr` PQ and HLG sources are tone mapped
 * to sRGB.
 */
int ffplaykmp_rgb_output(AVFrame *output, const AVFrame *source, enum AVPixelFormat format, int tone_map_hdr);

/* Downloads a hardware frame into `software`, keeping the frame properties. */
int ffplaykmp_download_frame(AVFrame *software, const AVFrame *hardware);

/*
 * Frame handles (ffmpegkmp_frame.c). A handle is one reference to an AVFrame;
 * the bridge's own code reaches the AVFrame directly.
 */
struct ffmpegkmp_frame {
    AVFrame *frame;
    /* A hardware frame's download, made on its first map or conversion. */
    AVFrame *download;
    /* CVPixelBuffer read locks the handle's maps hold. */
    int locks;
    /* A map for writing is open (ffmpegkmp_frame_map_writable). */
    int write_mapped;
    pthread_mutex_t lock;
};

/* A new handle holding a reference to `frame`; NULL when out of memory. */
ffmpegkmp_frame *ffmpegkmp_frame_from_av(const AVFrame *frame);

/*
 * ffmpegkmp_frame_pool_get, except that a full ring waits for one of its
 * frames to come back, asking `interrupted(opaque)` every 10 ms whether to
 * give up instead, with AVERROR_EXIT. A decoder's hand-out waits so.
 */
int ffmpegkmp_frame_pool_take(
        ffmpegkmp_frame_pool *pool,
        const ffmpegkmp_frame_format *format,
        int32_t width,
        int32_t height,
        int (*interrupted)(void *opaque),
        void *opaque,
        ffmpegkmp_frame **frame);

/*
 * Converts `source` into `destination` with `converter`, reading a
 * CVPixelBuffer in place and downloading other hardware frames. The
 * destination keeps its format, except that with `keep_colour` it takes the
 * source's colour: a layout-only conversion. Otherwise SDR curves convert as
 * they are shown, without a curve change: BT.709-coded video is sRGB.
 */
int ffmpegkmp_frame_convert_with(
        ffmpegkmp_converter *converter,
        ffmpegkmp_frame *destination,
        const AVFrame *source,
        int keep_colour);

/* Whether `format` names a layout and colour of the model; RGB layouts take the RGB matrix. */
int ffmpegkmp_frame_format_is_valid(const ffmpegkmp_frame_format *format);

/* Whether `frame` is a CVPixelBuffer the bridge can lock: VideoToolbox's, or from a frame pool. */
int ffmpegkmp_frame_is_pixel_buffer(const AVFrame *frame);

/* Counts pixel memory the bridge allocated, for ffmpegkmp_frame_get_statistics. */
void ffmpegkmp_frame_count_allocation(size_t bytes);

#endif
