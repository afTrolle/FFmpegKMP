// SPDX-License-Identifier: LGPL-2.1-or-later
/*
 * Demux, metadata, decoder setup and frame conversion shared by the FFplay
 * player (ffplaykmp_player.c) and the pull-based video decoder
 * (ffmpegkmp_decoder.c). Internal to the bridge: not installed, not bound.
 */
#ifndef FFPLAYKMP_CORE_H
#define FFPLAYKMP_CORE_H

#include "ffplaykmp_player.h"

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

void ffplaykmp_reset_video_metadata(ffplaykmp_snapshot *snapshot);
void ffplaykmp_read_stream_metadata(ffplaykmp_snapshot *snapshot, const AVStream *stream);
void ffplaykmp_read_frame_metadata(ffplaykmp_snapshot *snapshot, const AVFrame *frame);
int ffplaykmp_is_hardware_frame(const AVFrame *frame);

/*
 * The best video stream's decoder. With `hardware` set it is the platform
 * decoder: MediaCodec rendering to `android_surface` (a JNI global reference)
 * on Android, the host's hwaccel device elsewhere. Closing it is always safe,
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

/*
 * Opens `stream_index`'s decoder. When `hardware` is requested but this host
 * has no hardware decoder for the codec, the software decoder is opened and
 * codec->hardware stays 0; `require_hardware` turns that into
 * FFPLAYKMP_ERROR_UNSUPPORTED instead. `options` (may be NULL) go to
 * avcodec_open2, which leaves the ones the chosen decoder does not know.
 */
int ffplaykmp_video_codec_open(
        ffplaykmp_video_codec *codec,
        AVFormatContext *format,
        int stream_index,
        int hardware,
        int require_hardware,
        void *android_surface,
        AVDictionary **options);
void ffplaykmp_video_codec_close(ffplaykmp_video_codec *codec);

#if defined(__ANDROID__)
/* Retains `object` as a global reference and registers the VM with FFmpeg's JNI support. */
int ffplaykmp_android_retain_global(JNIEnv *env, jobject object, JavaVM **vm, jobject *retained);
/* Deletes a global reference from any thread, attaching it to the VM when needed. */
void ffplaykmp_android_release_global(JavaVM *vm, jobject object);
#endif

/* Frame conversion scratch, reused across frames by one thread. */
typedef struct ffplaykmp_converter {
    struct SwsContext *rgba_scaler;
    struct SwsContext *float_scaler;
    uint8_t *rgba_buffer;
    int rgba_capacity;
    uint8_t *float_buffer;
    int float_capacity;
    uint8_t *half_buffer;
    int half_capacity;
} ffplaykmp_converter;

typedef struct ffplaykmp_converted_frame {
    const uint8_t *pixels;
    int size;
    int width;
    int height;
    int stride;
} ffplaykmp_converted_frame;

/*
 * Software frame to 8-bit sRGB RGBA. With `tone_map_hdr`, PQ and HLG frames
 * are tone mapped to bounded BT.709 first. The pixels live in the converter
 * until its next conversion.
 */
int ffplaykmp_convert_rgba(
        ffplaykmp_converter *converter,
        const AVFrame *frame,
        int tone_map_hdr,
        ffplaykmp_converted_frame *output);

/*
 * Software frame to RGBA half floats in linear extended sRGB (BT.709
 * primaries), 1.0 being SDR reference white: 203 nits for PQ and HLG
 * (BT.2408), the display white of SDR transfers. Values above 1.0 and below 0
 * are kept, never tone mapped or clipped.
 */
int ffplaykmp_convert_linear_f16(
        ffplaykmp_converter *converter,
        const AVFrame *frame,
        ffplaykmp_converted_frame *output);

void ffplaykmp_converter_free(ffplaykmp_converter *converter);

/* Downloads a hardware frame into `software`, keeping the frame properties. */
int ffplaykmp_download_frame(AVFrame *software, const AVFrame *hardware);

#endif
