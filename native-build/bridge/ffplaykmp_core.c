// SPDX-License-Identifier: LGPL-2.1-or-later
#include "ffplaykmp_core.h"

#include <errno.h>
#include <limits.h>
#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(__ANDROID__)
#include <libavcodec/jni.h>
#endif
#include <libavutil/cpu.h>
#include <libavutil/csp.h>
#include <libavutil/display.h>
#include <libavutil/hwcontext.h>
#include <libavutil/imgutils.h>
#include <libavutil/mastering_display_metadata.h>
#include <libavutil/pixdesc.h>

static int ffplaykmp_pixel_bit_depth(int pixel_format) {
    const AVPixFmtDescriptor *descriptor = av_pix_fmt_desc_get(pixel_format);
    int depth = 0;
    int component;
    if (!descriptor)
        return 0;
    for (component = 0; component < descriptor->nb_components; component++) {
        if (descriptor->comp[component].depth > depth)
            depth = descriptor->comp[component].depth;
    }
    return depth;
}

static double ffplaykmp_rational_to_double(AVRational value) {
    return value.den == 0 ? 0.0 : av_q2d(value);
}

void ffplaykmp_reset_video_metadata(ffplaykmp_snapshot *snapshot) {
    snapshot->video_width = 0;
    snapshot->video_height = 0;
    snapshot->pixel_format = AV_PIX_FMT_NONE;
    snapshot->bit_depth = 0;
    snapshot->sample_aspect_ratio_num = 0;
    snapshot->sample_aspect_ratio_den = 0;
    snapshot->rotation_degrees = 0.0;
    snapshot->color_primaries = AVCOL_PRI_UNSPECIFIED;
    snapshot->color_transfer = AVCOL_TRC_UNSPECIFIED;
    snapshot->color_space = AVCOL_SPC_UNSPECIFIED;
    snapshot->color_range = AVCOL_RANGE_UNSPECIFIED;
    snapshot->chroma_location = AVCHROMA_LOC_UNSPECIFIED;
    snapshot->hdr_flags = 0;
    snapshot->mastering_has_primaries = 0;
    snapshot->mastering_has_luminance = 0;
    snapshot->mastering_red_x = 0.0;
    snapshot->mastering_red_y = 0.0;
    snapshot->mastering_green_x = 0.0;
    snapshot->mastering_green_y = 0.0;
    snapshot->mastering_blue_x = 0.0;
    snapshot->mastering_blue_y = 0.0;
    snapshot->mastering_white_x = 0.0;
    snapshot->mastering_white_y = 0.0;
    snapshot->mastering_min_luminance = 0.0;
    snapshot->mastering_max_luminance = 0.0;
    snapshot->content_light_present = 0;
    snapshot->max_content_light_level = 0;
    snapshot->max_frame_average_light_level = 0;
}

static void ffplaykmp_copy_mastering_metadata(
        ffplaykmp_snapshot *snapshot,
        const AVMasteringDisplayMetadata *mastering) {
    snapshot->mastering_has_primaries = mastering->has_primaries;
    snapshot->mastering_has_luminance = mastering->has_luminance;
    snapshot->mastering_red_x = ffplaykmp_rational_to_double(mastering->display_primaries[0][0]);
    snapshot->mastering_red_y = ffplaykmp_rational_to_double(mastering->display_primaries[0][1]);
    snapshot->mastering_green_x = ffplaykmp_rational_to_double(mastering->display_primaries[1][0]);
    snapshot->mastering_green_y = ffplaykmp_rational_to_double(mastering->display_primaries[1][1]);
    snapshot->mastering_blue_x = ffplaykmp_rational_to_double(mastering->display_primaries[2][0]);
    snapshot->mastering_blue_y = ffplaykmp_rational_to_double(mastering->display_primaries[2][1]);
    snapshot->mastering_white_x = ffplaykmp_rational_to_double(mastering->white_point[0]);
    snapshot->mastering_white_y = ffplaykmp_rational_to_double(mastering->white_point[1]);
    snapshot->mastering_min_luminance = ffplaykmp_rational_to_double(mastering->min_luminance);
    snapshot->mastering_max_luminance = ffplaykmp_rational_to_double(mastering->max_luminance);
}

void ffplaykmp_read_stream_metadata(
        ffplaykmp_snapshot *snapshot,
        const AVStream *stream) {
    const AVCodecParameters *parameters = stream->codecpar;
    const AVPacketSideData *side_data;
    const AVMasteringDisplayMetadata *mastering;
    const AVContentLightMetadata *content_light;
    const int32_t *display_matrix;

    snapshot->pixel_format = parameters->format;
    snapshot->bit_depth = ffplaykmp_pixel_bit_depth(parameters->format);
    if (snapshot->bit_depth == 0)
        snapshot->bit_depth = parameters->bits_per_raw_sample;
    snapshot->sample_aspect_ratio_num = parameters->sample_aspect_ratio.num;
    snapshot->sample_aspect_ratio_den = parameters->sample_aspect_ratio.den;
    snapshot->color_primaries = parameters->color_primaries;
    snapshot->color_transfer = parameters->color_trc;
    snapshot->color_space = parameters->color_space;
    snapshot->color_range = parameters->color_range;
    snapshot->chroma_location = parameters->chroma_location;

    side_data = av_packet_side_data_get(
            parameters->coded_side_data,
            parameters->nb_coded_side_data,
            AV_PKT_DATA_DISPLAYMATRIX);
    if (side_data && side_data->size >= 9 * sizeof(*display_matrix)) {
        display_matrix = (const int32_t *)side_data->data;
        snapshot->rotation_degrees = av_display_rotation_get(display_matrix);
    }

    side_data = av_packet_side_data_get(
            parameters->coded_side_data,
            parameters->nb_coded_side_data,
            AV_PKT_DATA_MASTERING_DISPLAY_METADATA);
    if (side_data && side_data->size >= sizeof(*mastering)) {
        mastering = (const AVMasteringDisplayMetadata *)side_data->data;
        ffplaykmp_copy_mastering_metadata(snapshot, mastering);
    }

    side_data = av_packet_side_data_get(
            parameters->coded_side_data,
            parameters->nb_coded_side_data,
            AV_PKT_DATA_CONTENT_LIGHT_LEVEL);
    if (side_data && side_data->size >= sizeof(*content_light)) {
        content_light = (const AVContentLightMetadata *)side_data->data;
        snapshot->content_light_present = 1;
        snapshot->max_content_light_level = content_light->MaxCLL;
        snapshot->max_frame_average_light_level = content_light->MaxFALL;
    }

    if (av_packet_side_data_get(
            parameters->coded_side_data,
            parameters->nb_coded_side_data,
            AV_PKT_DATA_DOVI_CONF))
        snapshot->hdr_flags |= FFPLAYKMP_HDR_DOLBY_VISION;
    if (av_packet_side_data_get(
            parameters->coded_side_data,
            parameters->nb_coded_side_data,
            AV_PKT_DATA_DYNAMIC_HDR10_PLUS))
        snapshot->hdr_flags |= FFPLAYKMP_HDR_HDR10_PLUS;
}

void ffplaykmp_read_frame_metadata(
        ffplaykmp_snapshot *snapshot,
        const AVFrame *frame) {
    const AVHWFramesContext *hardware_frames = NULL;
    const AVFrameSideData *side_data;
    const AVMasteringDisplayMetadata *mastering;
    const AVContentLightMetadata *content_light;
    int bit_depth;
    snapshot->video_width = frame->width;
    snapshot->video_height = frame->height;
    bit_depth = ffplaykmp_pixel_bit_depth(frame->format);
    if (frame->hw_frames_ctx) {
        hardware_frames = (const AVHWFramesContext *)frame->hw_frames_ctx->data;
        if (hardware_frames && hardware_frames->sw_format != AV_PIX_FMT_NONE) {
            snapshot->pixel_format = hardware_frames->sw_format;
            bit_depth = ffplaykmp_pixel_bit_depth(hardware_frames->sw_format);
        }
    }
    if (bit_depth > 0) {
        if (!hardware_frames)
            snapshot->pixel_format = frame->format;
        snapshot->bit_depth = bit_depth;
    }
    if (frame->color_primaries != AVCOL_PRI_UNSPECIFIED)
        snapshot->color_primaries = frame->color_primaries;
    if (frame->color_trc != AVCOL_TRC_UNSPECIFIED)
        snapshot->color_transfer = frame->color_trc;
    if (frame->colorspace != AVCOL_SPC_UNSPECIFIED)
        snapshot->color_space = frame->colorspace;
    if (frame->color_range != AVCOL_RANGE_UNSPECIFIED)
        snapshot->color_range = frame->color_range;
    if (frame->chroma_location != AVCHROMA_LOC_UNSPECIFIED)
        snapshot->chroma_location = frame->chroma_location;

    side_data = av_frame_get_side_data(frame, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
    if (side_data && side_data->size >= sizeof(*mastering)) {
        mastering = (const AVMasteringDisplayMetadata *)side_data->data;
        ffplaykmp_copy_mastering_metadata(snapshot, mastering);
    }
    side_data = av_frame_get_side_data(frame, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
    if (side_data && side_data->size >= sizeof(*content_light)) {
        content_light = (const AVContentLightMetadata *)side_data->data;
        snapshot->content_light_present = 1;
        snapshot->max_content_light_level = content_light->MaxCLL;
        snapshot->max_frame_average_light_level = content_light->MaxFALL;
    }
    if (av_frame_get_side_data(frame, AV_FRAME_DATA_DOVI_METADATA) ||
            av_frame_get_side_data(frame, AV_FRAME_DATA_DOVI_RPU_BUFFER))
        snapshot->hdr_flags |= FFPLAYKMP_HDR_DOLBY_VISION;
    if (av_frame_get_side_data(frame, AV_FRAME_DATA_DYNAMIC_HDR_PLUS))
        snapshot->hdr_flags |= FFPLAYKMP_HDR_HDR10_PLUS;
}

int ffplaykmp_is_hardware_frame(const AVFrame *frame) {
    const AVPixFmtDescriptor *descriptor;
    if (frame->hw_frames_ctx)
        return 1;
    descriptor = av_pix_fmt_desc_get(frame->format);
    return descriptor && (descriptor->flags & AV_PIX_FMT_FLAG_HWACCEL);
}

int64_t ffplaykmp_media_start_us(const AVFormatContext *format) {
    return format->start_time == AV_NOPTS_VALUE ? 0 : format->start_time;
}

void ffplaykmp_stream_timing(
        const AVFormatContext *format,
        const AVStream *stream,
        int64_t *origin,
        int64_t *default_duration,
        int64_t *stream_end) {
    const int64_t start_us = ffplaykmp_media_start_us(format);
    *origin = av_rescale_q_rnd(
            start_us, AV_TIME_BASE_Q, stream->time_base, AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX);
    /* The container start is this stream's start rounded to microseconds: prefer the exact value. */
    if (stream->start_time != AV_NOPTS_VALUE &&
            llabs(av_rescale_q(stream->start_time, stream->time_base, AV_TIME_BASE_Q) - start_us) <= 1)
        *origin = stream->start_time;
    if (stream->avg_frame_rate.num > 0 && stream->avg_frame_rate.den > 0)
        *default_duration = av_rescale_q(1, av_inv_q(stream->avg_frame_rate), stream->time_base);
    else if (stream->r_frame_rate.num > 0 && stream->r_frame_rate.den > 0)
        *default_duration = av_rescale_q(1, av_inv_q(stream->r_frame_rate), stream->time_base);
    else
        *default_duration = av_rescale_q(40000, AV_TIME_BASE_Q, stream->time_base);
    if (*default_duration <= 0)
        *default_duration = 1;
    *stream_end = stream->start_time != AV_NOPTS_VALUE && stream->duration > 0
            ? stream->start_time + stream->duration
            : AV_NOPTS_VALUE;
}

static int ffplaykmp_parse_resource_id(const char *input, int64_t *resource_id) {
    const char prefix[] = "ffmpegkmp:";
    char *end = NULL;
    long long parsed;
    if (strncmp(input, prefix, sizeof(prefix) - 1) != 0)
        return 0;
    parsed = strtoll(input + sizeof(prefix) - 1, &end, 10);
    if (parsed <= 0 || end == input + sizeof(prefix) - 1)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    *resource_id = (int64_t)parsed;
    return 1;
}

int ffplaykmp_host_interrupted(const ffplaykmp_io_host *host) {
    return host->interrupted && host->interrupted(host->interrupt_opaque);
}

static int ffplaykmp_interrupt(void *opaque) {
    const ffplaykmp_io_host *host = opaque;
    return host ? ffplaykmp_host_interrupted(host) : 1;
}

int ffplaykmp_avio_read(void *opaque, uint8_t *buffer, int size) {
    ffplaykmp_avio *io = opaque;
    int64_t result;
    if (ffplaykmp_host_interrupted(&io->host))
        return AVERROR_EXIT;
    result = io->host.callback(
            io->host.opaque,
            io->resource_id,
            FFPLAYKMP_IO_READ,
            io->position,
            buffer,
            (uint64_t)size);
    if (result <= 0)
        return result == 0 ? AVERROR_EOF : AVERROR(EIO);
    io->position += result;
    return result > INT_MAX ? AVERROR(EIO) : (int)result;
}

int64_t ffplaykmp_avio_seek(void *opaque, int64_t offset, int whence) {
    ffplaykmp_avio *io = opaque;
    int64_t size;
    int64_t position;
    if (whence & AVSEEK_SIZE) {
        return io->host.callback(
                io->host.opaque,
                io->resource_id,
                FFPLAYKMP_IO_SIZE,
                0,
                NULL,
                0);
    }
    whence &= ~AVSEEK_FORCE;
    if (whence == SEEK_SET) {
        position = offset;
    } else if (whence == SEEK_CUR) {
        position = io->position + offset;
    } else if (whence == SEEK_END) {
        size = io->host.callback(
                io->host.opaque,
                io->resource_id,
                FFPLAYKMP_IO_SIZE,
                0,
                NULL,
                0);
        if (size < 0)
            return AVERROR(EIO);
        position = size + offset;
    } else {
        return AVERROR(EINVAL);
    }
    if (position < 0)
        return AVERROR(EINVAL);
    io->position = position;
    return position;
}

#define FFPLAYKMP_IO_BUFFER_SIZE 32768
#define FFPLAYKMP_IO_CAP_SEEK 4

void ffplaykmp_close_input(ffplaykmp_input *input) {
    avformat_close_input(&input->format);
    if (input->avio) {
        av_freep(&input->avio->buffer);
        avio_context_free(&input->avio);
    }
    if (input->resource_opened && input->host.callback)
        input->host.callback(input->host.opaque, input->resource_id, FFPLAYKMP_IO_CLOSE, 0, NULL, 0);
    input->resource_opened = 0;
    av_freep(&input->io);
}

int ffplaykmp_open_input(const ffplaykmp_io_host *host, const char *url, ffplaykmp_input *input) {
    int mounted = ffplaykmp_parse_resource_id(url, &input->resource_id);
    int result;
    if (mounted < 0)
        return mounted;
    input->host = *host;
    input->format = avformat_alloc_context();
    if (!input->format)
        return AVERROR(ENOMEM);
    input->format->interrupt_callback.callback = ffplaykmp_interrupt;
    input->format->interrupt_callback.opaque = &input->host;
    if (mounted) {
        uint8_t *buffer;
        int64_t capabilities;
        if (!input->host.callback)
            return AVERROR(ENOSYS);
        capabilities = input->host.callback(
                input->host.opaque, input->resource_id, FFPLAYKMP_IO_OPEN, 1, NULL, 0);
        if (capabilities < 0)
            return AVERROR(EIO);
        input->resource_opened = 1;
        input->io = av_mallocz(sizeof(*input->io));
        buffer = av_malloc(FFPLAYKMP_IO_BUFFER_SIZE);
        if (input->io && buffer) {
            input->io->host = input->host;
            input->io->resource_id = input->resource_id;
            input->avio = avio_alloc_context(
                    buffer, FFPLAYKMP_IO_BUFFER_SIZE, 0, input->io, ffplaykmp_avio_read, NULL,
                    (capabilities & FFPLAYKMP_IO_CAP_SEEK) ? ffplaykmp_avio_seek : NULL);
        }
        if (!input->avio) {
            av_free(buffer);
            return AVERROR(ENOMEM);
        }
        input->format->pb = input->avio;
        input->format->flags |= AVFMT_FLAG_CUSTOM_IO;
        result = avformat_open_input(&input->format, NULL, NULL, NULL);
    } else {
        result = avformat_open_input(&input->format, url, NULL, NULL);
    }
    return result < 0 ? result : avformat_find_stream_info(input->format, NULL);
}

int ffplaykmp_find_video_stream(AVFormatContext *format, const AVCodec **codec) {
    return av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, codec, 0);
}

#if defined(__ANDROID__)
/* MediaCodec's Surface output is an ad hoc hardware format, which the default get_format never picks. */
static enum AVPixelFormat ffplaykmp_mediacodec_format(
        AVCodecContext *context,
        const enum AVPixelFormat *formats) {
    const enum AVPixelFormat *format;
    for (format = formats; *format != AV_PIX_FMT_NONE; format++) {
        if (*format == AV_PIX_FMT_MEDIACODEC)
            return *format;
    }
    return avcodec_default_get_format(context, formats);
}
#else
/* get_format for hardware decoding; context->opaque points at the wanted format. */
static enum AVPixelFormat ffplaykmp_hardware_format(
        AVCodecContext *context,
        const enum AVPixelFormat *formats) {
    const enum AVPixelFormat *wanted = context->opaque;
    const enum AVPixelFormat *format;
    if (!wanted)
        return AV_PIX_FMT_NONE;
    for (format = formats; *format != AV_PIX_FMT_NONE; format++) {
        if (*format == *wanted)
            return *format;
    }
    return AV_PIX_FMT_NONE;
}

static int ffplaykmp_select_hardware_config(
        const AVCodec *codec,
        enum AVHWDeviceType *device_type,
        enum AVPixelFormat *pixel_format) {
    const AVCodecHWConfig *config;
    enum AVHWDeviceType candidates[3];
    int candidate_count = 0;
    int candidate_index;
    int config_index;
#if defined(__APPLE__)
    candidates[candidate_count++] = AV_HWDEVICE_TYPE_VIDEOTOOLBOX;
#elif defined(_WIN32)
    candidates[candidate_count++] = AV_HWDEVICE_TYPE_D3D11VA;
    candidates[candidate_count++] = AV_HWDEVICE_TYPE_DXVA2;
#elif defined(__linux__)
    candidates[candidate_count++] = AV_HWDEVICE_TYPE_VAAPI;
#else
    (void)codec;
#endif
    for (candidate_index = 0; candidate_index < candidate_count; candidate_index++) {
        for (config_index = 0; ; config_index++) {
            config = avcodec_get_hw_config(codec, config_index);
            if (!config)
                break;
            if (config->device_type == candidates[candidate_index] &&
                    config->pix_fmt != AV_PIX_FMT_NONE &&
                    (config->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX)) {
                *device_type = config->device_type;
                *pixel_format = config->pix_fmt;
                return 0;
            }
        }
    }
    return AVERROR(ENOTSUP);
}
#endif

/*
 * FFmpeg's automatic count is one more than the cores; above the cap the
 * frames in flight cost more memory than the threads gain.
 */
int ffplaykmp_decoder_thread_count(int threads) {
    const int automatic = av_cpu_count() + 1;
    if (threads > 0)
        return threads;
    return automatic > FFPLAYKMP_DECODER_THREADS_AUTO_MAX ? FFPLAYKMP_DECODER_THREADS_AUTO_MAX : automatic;
}

int ffplaykmp_video_codec_open(
        ffplaykmp_video_codec *codec,
        AVFormatContext *format,
        int stream_index,
        int hardware,
        int require_hardware,
        int threads,
        int thread_type,
        void *android_surface,
        AVDictionary **options) {
    const AVStream *stream = format->streams[stream_index];
    const AVCodec *decoder = avcodec_find_decoder(stream->codecpar->codec_id);
    int result;
#if !defined(__ANDROID__)
    enum AVHWDeviceType hardware_device_type = AV_HWDEVICE_TYPE_NONE;
#endif
    memset(codec, 0, sizeof(*codec));
    codec->stream_index = stream_index;
#if !defined(__ANDROID__)
    codec->hardware_format = AV_PIX_FMT_NONE;
    (void)android_surface;
#endif
    if (!decoder)
        return AVERROR_DECODER_NOT_FOUND;
    if (hardware) {
#if defined(__ANDROID__)
        char decoder_name[96];
        const AVCodec *hardware_codec = NULL;
        if (snprintf(decoder_name, sizeof(decoder_name), "%s_mediacodec", decoder->name) > 0)
            hardware_codec = avcodec_find_decoder_by_name(decoder_name);
        if (hardware_codec)
            decoder = hardware_codec;
        else
            hardware = 0;
#else
        hardware = ffplaykmp_select_hardware_config(
                decoder, &hardware_device_type, &codec->hardware_format) >= 0;
#endif
    }
    if (require_hardware && !hardware)
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    codec->hardware = hardware;

    codec->decoder = avcodec_alloc_context3(decoder);
    if (!codec->decoder)
        return AVERROR(ENOMEM);
    if ((result = avcodec_parameters_to_context(codec->decoder, stream->codecpar)) < 0)
        return result;
    codec->decoder->pkt_timebase = stream->time_base;
    if (hardware) {
#if defined(__ANDROID__)
        /* Without a Surface MediaCodec decodes into memory (ByteBuffer mode), which needs no context. */
        if (android_surface) {
            codec->mediacodec_context = av_mediacodec_alloc_context();
            if (!codec->mediacodec_context)
                return AVERROR(ENOMEM);
            if ((result = av_mediacodec_default_init(
                    codec->decoder, codec->mediacodec_context, android_surface)) < 0) {
                /* Not attached to the decoder, so av_mediacodec_default_free won't free it. */
                av_freep(&codec->mediacodec_context);
                return result;
            }
            codec->decoder->get_format = ffplaykmp_mediacodec_format;
        }
#else
        if ((result = av_hwdevice_ctx_create(
                &codec->hardware_device, hardware_device_type, NULL, NULL, 0)) < 0)
            return result;
        codec->decoder->hw_device_ctx = av_buffer_ref(codec->hardware_device);
        if (!codec->decoder->hw_device_ctx)
            return AVERROR(ENOMEM);
        codec->decoder->opaque = &codec->hardware_format;
        codec->decoder->get_format = ffplaykmp_hardware_format;
#endif
    } else {
        codec->decoder->thread_count = ffplaykmp_decoder_thread_count(threads);
        codec->decoder->thread_type = thread_type;
    }
    return avcodec_open2(codec->decoder, decoder, options);
}

int ffplaykmp_video_codec_frame_is_hardware(const ffplaykmp_video_codec *codec, const AVFrame *frame) {
#if defined(__ANDROID__)
    if (codec->hardware)
        return 1;
#else
    (void)codec;
#endif
    return ffplaykmp_is_hardware_frame(frame);
}

void ffplaykmp_video_codec_close(ffplaykmp_video_codec *codec) {
#if defined(__ANDROID__)
    if (codec->decoder && codec->mediacodec_context)
        av_mediacodec_default_free(codec->decoder);
    codec->mediacodec_context = NULL;
#else
    av_buffer_unref(&codec->hardware_device);
#endif
    avcodec_free_context(&codec->decoder);
}

#if defined(__ANDROID__)
int ffplaykmp_android_retain_global(JNIEnv *env, jobject object, JavaVM **vm, jobject *retained) {
    *retained = (*env)->NewGlobalRef(env, object);
    if (!*retained)
        return -ENOMEM;
    if ((*env)->GetJavaVM(env, vm) != JNI_OK || av_jni_set_java_vm(*vm, NULL) < 0) {
        (*env)->DeleteGlobalRef(env, *retained);
        *retained = NULL;
        return FFPLAYKMP_ERROR_IO;
    }
    return 0;
}

void ffplaykmp_android_release_global(JavaVM *vm, jobject object) {
    JNIEnv *env = NULL;
    int attached = 0;
    if (!object || !vm)
        return;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK)
            return;
        attached = 1;
    }
    (*env)->DeleteGlobalRef(env, object);
    if (attached)
        (*vm)->DetachCurrentThread(vm);
}
#endif

/*
 * The converter (ffmpegkmp_frame_convert).
 *
 * swscale does layout, matrix, range and chroma. Its colour management maps
 * colours through a 3D table in 16-bit RGBA64 normalized to the destination's
 * range, treats a linear destination as SDR with a 203-nit peak, and pulls
 * colours into the destination gamut (lut3d.c, cms.c), so nothing above 1.0
 * or below 0 survives it. The converter therefore only lets swscale convert
 * between frames of one colour, except for SDR pairs, and does the HDR curves
 * and primaries itself in a transfer step: float maths from tables, on rows
 * split across the converter's workers.
 */

#define FFMPEGKMP_REFERENCE_WHITE_NITS 203.0
#define FFMPEGKMP_PQ_PEAK_NITS 10000.0
/* BT.2100's reference HLG display, which swscale also assumes. */
#define FFMPEGKMP_HLG_DISPLAY_NITS 1000.0

#if defined(__EMSCRIPTEN__)
/* The worker's pthread pool is fixed (PTHREAD_POOL_SIZE_STRICT), so the converter takes a small share of it. */
#define FFMPEGKMP_CONVERTER_THREADS 4
#define FFMPEGKMP_SCALER_THREADS FFMPEGKMP_CONVERTER_THREADS
#else
/* The transfer step is memory bound; more workers than this gain little. */
#define FFMPEGKMP_CONVERTER_THREADS 8
/* swscale's automatic count. */
#define FFMPEGKMP_SCALER_THREADS 0
#endif

static double ffplaykmp_clamp_unit(double value) {
    if (!isfinite(value) || value <= 0.0)
        return 0.0;
    return value >= 1.0 ? 1.0 : value;
}

static double ffplaykmp_pq_eotf_nits(double value) {
    const double m1 = 2610.0 / 16384.0;
    const double m2 = 2523.0 / 32.0;
    const double c1 = 3424.0 / 4096.0;
    const double c2 = 2413.0 / 128.0;
    const double c3 = 2392.0 / 128.0;
    double signal = pow(ffplaykmp_clamp_unit(value), 1.0 / m2);
    double numerator = signal > c1 ? signal - c1 : 0.0;
    double denominator = c2 - c3 * signal;
    if (denominator <= 0.0)
        return FFMPEGKMP_PQ_PEAK_NITS;
    return pow(numerator / denominator, 1.0 / m1) * FFMPEGKMP_PQ_PEAK_NITS;
}

/* PQ inverse EOTF of `nits` / 10,000. */
static double ffplaykmp_pq_signal(double luminance) {
    const double m1 = 2610.0 / 16384.0;
    const double m2 = 2523.0 / 32.0;
    const double c1 = 3424.0 / 4096.0;
    const double c2 = 2413.0 / 128.0;
    const double c3 = 2392.0 / 128.0;
    double power = pow(ffplaykmp_clamp_unit(luminance), m1);
    return pow((c1 + c2 * power) / (1.0 + c3 * power), m2);
}

#define FFPLAYKMP_HLG_A 0.17883277
#define FFPLAYKMP_HLG_B 0.28466892
#define FFPLAYKMP_HLG_C 0.55991073

/* HLG inverse OETF: scene light in [0, 1]. */
static double ffplaykmp_hlg_scene(double value) {
    value = ffplaykmp_clamp_unit(value);
    return value <= 0.5
            ? value * value / 3.0
            : (exp((value - FFPLAYKMP_HLG_C) / FFPLAYKMP_HLG_A) + FFPLAYKMP_HLG_B) / 12.0;
}

/* HLG OETF of scene light in [0, 1]. */
static double ffplaykmp_hlg_signal(double scene) {
    scene = ffplaykmp_clamp_unit(scene);
    return scene <= 1.0 / 12.0
            ? sqrt(3.0 * scene)
            : FFPLAYKMP_HLG_A * log(12.0 * scene - FFPLAYKMP_HLG_B) + FFPLAYKMP_HLG_C;
}

static double ffplaykmp_srgb_oetf(double value) {
    value = ffplaykmp_clamp_unit(value);
    return value <= 0.0031308
            ? 12.92 * value
            : 1.055 * pow(value, 1.0 / 2.4) - 0.055;
}

static double ffplaykmp_srgb_eotf(double value) {
    return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4);
}

/*
 * The curves the transfer step decodes, each a table indexed by the 16-bit
 * code of the intermediate. Built on first use, shared by every
 * converter, kept for the process.
 */
typedef enum ffmpegkmp_curve {
    /* Every SDR transfer without a curve of its own: the same sRGB curve the RGBA8 output is shown with. */
    FFMPEGKMP_CURVE_SRGB,
    FFMPEGKMP_CURVE_GAMMA22,
    FFMPEGKMP_CURVE_GAMMA28,
    FFMPEGKMP_CURVE_LINEAR,
    /* Luminance / 10,000 nits. */
    FFMPEGKMP_CURVE_PQ,
    /* Scene light in [0, 1]. */
    FFMPEGKMP_CURVE_HLG,
    FFMPEGKMP_CURVE_COUNT,
} ffmpegkmp_curve;

#define FFMPEGKMP_CODES 65536

/*
 * The transfer step's 16-bit intermediate: planar, 6 bytes a pixel. swscale
 * interpolates chroma to every pixel for planar RGB, as the bridge's float
 * conversion always did; its packed RGBA64 output repeats chroma instead, and
 * is wrong from 10-bit YUV with SWS_FULL_CHR_H_INT (FFmpeg 9.0.1).
 */
#define FFMPEGKMP_INTERMEDIATE AV_PIX_FMT_GBRP16

/*
 * Curves the other way, and the HLG OOTF gains, as tables over (0, 1] indexed
 * by a float's exponent and top mantissa bits: 256 linearly interpolated
 * steps per octave over 48 octaves. PQ's dark end is so steep that 2^-32 of
 * its peak still codes 12 of 65535; at 2^-48 it codes a fifth of one.
 */
#define FFMPEGKMP_OCTAVES 48
#define FFMPEGKMP_OCTAVE_BITS 8
#define FFMPEGKMP_OCTAVE_ENTRIES ((FFMPEGKMP_OCTAVES << FFMPEGKMP_OCTAVE_BITS) + 1)
#define FFMPEGKMP_OCTAVE_FRACTION_BITS (23 - FFMPEGKMP_OCTAVE_BITS)
/* The bits of 2^-FFMPEGKMP_OCTAVES, the table's first entry. */
#define FFMPEGKMP_OCTAVE_FLOOR ((uint32_t)(127 - FFMPEGKMP_OCTAVES) << 23)

typedef enum ffmpegkmp_octave_table {
    FFMPEGKMP_TABLE_PQ_SIGNAL,
    FFMPEGKMP_TABLE_HLG_SIGNAL,
    /* Ys^0.2: the BT.2100 HLG OOTF gain for a 1000-nit display (gamma 1.2). */
    FFMPEGKMP_TABLE_HLG_GAIN,
    /* Yd^(-1/6): its inverse. */
    FFMPEGKMP_TABLE_HLG_INVERSE_GAIN,
    FFMPEGKMP_TABLE_COUNT,
} ffmpegkmp_octave_table;

static pthread_mutex_t ffmpegkmp_tables_lock = PTHREAD_MUTEX_INITIALIZER;
static float *ffmpegkmp_curves[FFMPEGKMP_CURVE_COUNT];
static float *ffmpegkmp_octave_tables[FFMPEGKMP_TABLE_COUNT];
/* sRGB OETF of [0, 1] in 1/65535 steps, as 16-bit codes: the tone map's output curve. */
static uint16_t *ffmpegkmp_srgb_codes;

static double ffmpegkmp_curve_value(ffmpegkmp_curve curve, double value) {
    switch (curve) {
    case FFMPEGKMP_CURVE_GAMMA22:
        return pow(value, 2.2);
    case FFMPEGKMP_CURVE_GAMMA28:
        return pow(value, 2.8);
    case FFMPEGKMP_CURVE_LINEAR:
        return value;
    case FFMPEGKMP_CURVE_PQ:
        return ffplaykmp_pq_eotf_nits(value) / FFMPEGKMP_PQ_PEAK_NITS;
    case FFMPEGKMP_CURVE_HLG:
        return ffplaykmp_hlg_scene(value);
    default:
        return ffplaykmp_srgb_eotf(value);
    }
}

static double ffmpegkmp_octave_value(ffmpegkmp_octave_table table, double value) {
    switch (table) {
    case FFMPEGKMP_TABLE_PQ_SIGNAL:
        return ffplaykmp_pq_signal(value);
    case FFMPEGKMP_TABLE_HLG_SIGNAL:
        return ffplaykmp_hlg_signal(value);
    case FFMPEGKMP_TABLE_HLG_GAIN:
        return pow(value, 0.2);
    default:
        return pow(value, -1.0 / 6.0);
    }
}

static const float *ffmpegkmp_curve_table(ffmpegkmp_curve curve) {
    float *table;
    int code;
    pthread_mutex_lock(&ffmpegkmp_tables_lock);
    table = ffmpegkmp_curves[curve];
    if (!table && (table = av_malloc(FFMPEGKMP_CODES * sizeof(*table)))) {
        for (code = 0; code < FFMPEGKMP_CODES; code++)
            table[code] = (float)ffmpegkmp_curve_value(curve, code / (double)(FFMPEGKMP_CODES - 1));
        ffmpegkmp_curves[curve] = table;
    }
    pthread_mutex_unlock(&ffmpegkmp_tables_lock);
    return table;
}

static const float *ffmpegkmp_octave_table_get(ffmpegkmp_octave_table which) {
    float *table;
    int entry;
    pthread_mutex_lock(&ffmpegkmp_tables_lock);
    table = ffmpegkmp_octave_tables[which];
    if (!table && (table = av_malloc(FFMPEGKMP_OCTAVE_ENTRIES * sizeof(*table)))) {
        for (entry = 0; entry < FFMPEGKMP_OCTAVE_ENTRIES; entry++) {
            double value = ldexp(1.0 + (entry & ((1 << FFMPEGKMP_OCTAVE_BITS) - 1)) /
                    (double)(1 << FFMPEGKMP_OCTAVE_BITS), (entry >> FFMPEGKMP_OCTAVE_BITS) - FFMPEGKMP_OCTAVES);
            table[entry] = (float)ffmpegkmp_octave_value(which, value);
        }
        ffmpegkmp_octave_tables[which] = table;
    }
    pthread_mutex_unlock(&ffmpegkmp_tables_lock);
    return table;
}

/* The table's value at `value`, clamped to (0, 1]. */
static const uint16_t *ffmpegkmp_srgb_table(void) {
    uint16_t *table;
    int code;
    pthread_mutex_lock(&ffmpegkmp_tables_lock);
    table = ffmpegkmp_srgb_codes;
    if (!table && (table = av_malloc(FFMPEGKMP_CODES * sizeof(*table)))) {
        for (code = 0; code < FFMPEGKMP_CODES; code++)
            table[code] = (uint16_t)lrint(
                    ffplaykmp_srgb_oetf(code / (double)(FFMPEGKMP_CODES - 1)) * (FFMPEGKMP_CODES - 1));
        ffmpegkmp_srgb_codes = table;
    }
    pthread_mutex_unlock(&ffmpegkmp_tables_lock);
    return table;
}

/*
 * The tone map of a clip that peaks at `peak` nits, as an octave table: the
 * entry for x, a fraction of that peak, is the light SDR shows, in units of
 * its white. It is BT.2390's EETF in PQ with SDR white, 203 nits, as the
 * target peak: below the knee the source keeps its own light, so shadows and
 * mid-tones keep their nits, and above it highlights roll off to SDR white at
 * the clip's peak. A clip no brighter than SDR white is not mapped.
 */
static void ffmpegkmp_tone_map_fill(float *table, double peak) {
    const double source_max = ffplaykmp_pq_signal(peak / FFMPEGKMP_PQ_PEAK_NITS);
    const double target = ffplaykmp_pq_signal(FFMPEGKMP_REFERENCE_WHITE_NITS / FFMPEGKMP_PQ_PEAK_NITS) / source_max;
    const double knee = 1.5 * target - 0.5;
    int entry;
    for (entry = 0; entry < FFMPEGKMP_OCTAVE_ENTRIES; entry++) {
        const double x = ldexp(1.0 + (entry & ((1 << FFMPEGKMP_OCTAVE_BITS) - 1)) / (double)(1 << FFMPEGKMP_OCTAVE_BITS),
                (entry >> FFMPEGKMP_OCTAVE_BITS) - FFMPEGKMP_OCTAVES);
        const double e1 = ffplaykmp_pq_signal(x * peak / FFMPEGKMP_PQ_PEAK_NITS) / source_max;
        double e2 = e1;
        if (target < 1.0 && e1 > knee) {
            const double t = (e1 - knee) / (1.0 - knee);
            e2 = (2 * t * t * t - 3 * t * t + 1) * knee + (t * t * t - 2 * t * t + t) * (1.0 - knee) +
                    (-2 * t * t * t + 3 * t * t) * target;
        }
        table[entry] = (float)(ffplaykmp_pq_eotf_nits(e2 * source_max) / FFMPEGKMP_REFERENCE_WHITE_NITS);
    }
}

static inline float ffmpegkmp_octave_lookup(const float *table, float value) {
    union {
        float value;
        uint32_t bits;
    } single = { value };
    uint32_t offset;
    uint32_t index;
    /* Also NaN. */
    if (!(value > 0.0f) || single.bits <= FFMPEGKMP_OCTAVE_FLOOR)
        return table[0];
    if (value >= 1.0f)
        return table[FFMPEGKMP_OCTAVE_ENTRIES - 1];
    offset = single.bits - FFMPEGKMP_OCTAVE_FLOOR;
    index = offset >> FFMPEGKMP_OCTAVE_FRACTION_BITS;
    return table[index] + (table[index + 1] - table[index]) *
            ((offset & ((1u << FFMPEGKMP_OCTAVE_FRACTION_BITS) - 1)) *
             (1.0f / (1u << FFMPEGKMP_OCTAVE_FRACTION_BITS)));
}

static inline uint16_t ffplaykmp_half(float value) {
    union {
        float value;
        uint32_t bits;
    } single = { value };
    uint32_t sign = (single.bits >> 16) & 0x8000u;
    uint32_t magnitude = single.bits & 0x7fffffffu;
    uint32_t half;
    uint32_t remainder;
    if (magnitude >= 0x47800000u)
        return (uint16_t)(sign | (magnitude > 0x7f800000u ? 0x7e00u : 0x7c00u));
    if (magnitude < 0x38800000u)
        return (uint16_t)(sign | (uint32_t)lrintf(fabsf(value) * 16777216.0f));
    half = (magnitude - 0x38000000u) >> 13;
    remainder = magnitude & 0x1fffu;
    if (remainder > 0x1000u || (remainder == 0x1000u && (half & 1u)))
        half++;
    return (uint16_t)(sign | half);
}

static inline float ffplaykmp_float_from_half(uint16_t half) {
    union {
        uint32_t bits;
        float value;
    } single;
    uint32_t magnitude = half & 0x7fffu;
    if (magnitude >= 0x7c00u) {
        single.bits = 0x7f800000u | ((magnitude & 0x3ffu) << 13);
    } else {
        /* Rebias the exponent by multiplying; this also normalizes subnormals. */
        single.bits = magnitude << 13;
        single.value *= 0x1p112f;
    }
    single.bits |= (uint32_t)(half & 0x8000u) << 16;
    return single.value;
}

/* Primaries the converter distinguishes; the rest are taken as BT.709, as the outputs always have. */
static enum AVColorPrimaries ffmpegkmp_known_primaries(enum AVColorPrimaries primaries) {
    return primaries == AVCOL_PRI_BT2020 || primaries == AVCOL_PRI_SMPTE432 ? primaries : AVCOL_PRI_BT709;
}

static void ffmpegkmp_invert(const double matrix[3][3], double inverse[3][3]) {
    const double determinant =
            matrix[0][0] * (matrix[1][1] * matrix[2][2] - matrix[1][2] * matrix[2][1]) -
            matrix[0][1] * (matrix[1][0] * matrix[2][2] - matrix[1][2] * matrix[2][0]) +
            matrix[0][2] * (matrix[1][0] * matrix[2][1] - matrix[1][1] * matrix[2][0]);
    int row;
    int column;
    for (row = 0; row < 3; row++) {
        for (column = 0; column < 3; column++) {
            /* The cofactor of (column, row), cyclically indexed so the sign comes out right. */
            const int r0 = (column + 1) % 3, r1 = (column + 2) % 3;
            const int c0 = (row + 1) % 3, c1 = (row + 2) % 3;
            inverse[row][column] =
                    (matrix[r0][c0] * matrix[r1][c1] - matrix[r0][c1] * matrix[r1][c0]) / determinant;
        }
    }
}

static void ffmpegkmp_rgb_to_xyz(enum AVColorPrimaries primaries, double matrix[3][3]) {
    const AVColorPrimariesDesc *description = av_csp_primaries_desc_from_id(primaries);
    const AVCIExy corners[3] = { description->prim.r, description->prim.g, description->prim.b };
    double columns[3][3];
    double inverse[3][3];
    double white[3];
    double scale[3];
    int row;
    int column;
    for (column = 0; column < 3; column++) {
        double x = av_q2d(corners[column].x);
        double y = av_q2d(corners[column].y);
        columns[0][column] = x / y;
        columns[1][column] = 1.0;
        columns[2][column] = (1.0 - x - y) / y;
    }
    white[0] = av_q2d(description->wp.x) / av_q2d(description->wp.y);
    white[1] = 1.0;
    white[2] = (1.0 - av_q2d(description->wp.x) - av_q2d(description->wp.y)) / av_q2d(description->wp.y);
    /* Scale the primaries' columns so that RGB white lands on the white point. */
    ffmpegkmp_invert(columns, inverse);
    for (row = 0; row < 3; row++)
        scale[row] = inverse[row][0] * white[0] + inverse[row][1] * white[1] + inverse[row][2] * white[2];
    for (row = 0; row < 3; row++) {
        for (column = 0; column < 3; column++)
            matrix[row][column] = columns[row][column] * scale[column];
    }
}

/* The linear-light matrix from `from` to `to` primaries, times `gain`. Both share D65 white. */
static void ffmpegkmp_primaries_matrix(
        enum AVColorPrimaries from,
        enum AVColorPrimaries to,
        double gain,
        float matrix[9]) {
    double source[3][3];
    double target[3][3];
    double inverse[3][3];
    int row;
    int column;
    if (from == to) {
        for (row = 0; row < 9; row++)
            matrix[row] = row % 4 == 0 ? (float)gain : 0.0f;
        return;
    }
    ffmpegkmp_rgb_to_xyz(from, source);
    ffmpegkmp_rgb_to_xyz(to, target);
    ffmpegkmp_invert(target, inverse);
    for (row = 0; row < 3; row++) {
        for (column = 0; column < 3; column++) {
            matrix[row * 3 + column] = (float)(gain * (inverse[row][0] * source[0][column] +
                    inverse[row][1] * source[1][column] + inverse[row][2] * source[2][column]));
        }
    }
}

/*
 * A bounded pool of workers that split a job's rows with the calling thread.
 * Workers start on the first job that has rows for them; one that fails to
 * start just leaves more rows to the others.
 */
typedef void (*ffmpegkmp_rows_job)(void *opaque, int first_row, int end_row);

typedef struct ffmpegkmp_worker_pool ffmpegkmp_worker_pool;

typedef struct ffmpegkmp_worker {
    ffmpegkmp_worker_pool *pool;
    pthread_t thread;
    /* The worker's part of each job; the calling thread does part 0. */
    int part;
} ffmpegkmp_worker;

struct ffmpegkmp_worker_pool {
    pthread_mutex_t mutex;
    pthread_cond_t started;
    pthread_cond_t finished;
    /* The most threads a job may use, the calling thread's included; 0 for no bound. */
    int threads;
    int initialized;
    int stopping;
    ffmpegkmp_worker workers[FFMPEGKMP_CONVERTER_THREADS - 1];
    int worker_count;
    /* The current job, numbered so each worker takes it once. */
    uint64_t job;
    int running;
    ffmpegkmp_rows_job function;
    void *opaque;
    int rows;
    int parts;
};

static void ffmpegkmp_run_part(ffmpegkmp_rows_job function, void *opaque, int rows, int parts, int part) {
    const int first = (int)((int64_t)rows * part / parts);
    const int end = (int)((int64_t)rows * (part + 1) / parts);
    if (first < end)
        function(opaque, first, end);
}

static void *ffmpegkmp_worker_main(void *opaque) {
    ffmpegkmp_worker *worker = opaque;
    ffmpegkmp_worker_pool *pool = worker->pool;
    uint64_t done = 0;
    pthread_mutex_lock(&pool->mutex);
    for (;;) {
        ffmpegkmp_rows_job function;
        void *job_opaque;
        int rows;
        int parts;
        while (!pool->stopping && pool->job == done)
            pthread_cond_wait(&pool->started, &pool->mutex);
        if (pool->stopping)
            break;
        done = pool->job;
        function = pool->function;
        job_opaque = pool->opaque;
        rows = pool->rows;
        parts = pool->parts;
        pthread_mutex_unlock(&pool->mutex);
        ffmpegkmp_run_part(function, job_opaque, rows, parts, worker->part);
        pthread_mutex_lock(&pool->mutex);
        if (--pool->running == 0)
            pthread_cond_signal(&pool->finished);
    }
    pthread_mutex_unlock(&pool->mutex);
    return NULL;
}

static void ffmpegkmp_pool_start(ffmpegkmp_worker_pool *pool) {
    int cores = av_cpu_count();
    int wanted = (cores < FFMPEGKMP_CONVERTER_THREADS ? cores : FFMPEGKMP_CONVERTER_THREADS) - 1;
    if (pool->threads > 0 && pool->threads - 1 < wanted)
        wanted = pool->threads - 1;
    if (pool->initialized)
        return;
    if (pthread_mutex_init(&pool->mutex, NULL) != 0)
        return;
    if (pthread_cond_init(&pool->started, NULL) != 0) {
        pthread_mutex_destroy(&pool->mutex);
        return;
    }
    if (pthread_cond_init(&pool->finished, NULL) != 0) {
        pthread_cond_destroy(&pool->started);
        pthread_mutex_destroy(&pool->mutex);
        return;
    }
    pool->initialized = 1;
    while (pool->worker_count < wanted) {
        ffmpegkmp_worker *worker = &pool->workers[pool->worker_count];
        worker->pool = pool;
        worker->part = pool->worker_count + 1;
        if (pthread_create(&worker->thread, NULL, ffmpegkmp_worker_main, worker) != 0)
            break;
        pool->worker_count++;
    }
}

/* Runs `function` over `rows` rows, split across the workers and the calling thread. */
static void ffmpegkmp_pool_run(ffmpegkmp_worker_pool *pool, ffmpegkmp_rows_job function, void *opaque, int rows) {
    ffmpegkmp_pool_start(pool);
    if (!pool->initialized || pool->worker_count == 0 || rows < 2) {
        function(opaque, 0, rows);
        return;
    }
    pthread_mutex_lock(&pool->mutex);
    pool->function = function;
    pool->opaque = opaque;
    pool->rows = rows;
    pool->parts = pool->worker_count + 1;
    pool->running = pool->worker_count;
    pool->job++;
    pthread_cond_broadcast(&pool->started);
    pthread_mutex_unlock(&pool->mutex);
    ffmpegkmp_run_part(function, opaque, rows, pool->worker_count + 1, 0);
    pthread_mutex_lock(&pool->mutex);
    while (pool->running > 0)
        pthread_cond_wait(&pool->finished, &pool->mutex);
    pthread_mutex_unlock(&pool->mutex);
}

static void ffmpegkmp_pool_stop(ffmpegkmp_worker_pool *pool) {
    int index;
    if (!pool->initialized)
        return;
    pthread_mutex_lock(&pool->mutex);
    pool->stopping = 1;
    pthread_cond_broadcast(&pool->started);
    pthread_mutex_unlock(&pool->mutex);
    for (index = 0; index < pool->worker_count; index++)
        pthread_join(pool->workers[index].thread, NULL);
    pthread_cond_destroy(&pool->finished);
    pthread_cond_destroy(&pool->started);
    pthread_mutex_destroy(&pool->mutex);
    pool->initialized = 0;
    pool->worker_count = 0;
}

struct ffmpegkmp_converter {
    /* One context per leg, so each keeps its cached swscale graph. */
    SwsContext *scaler;
    SwsContext *input_scaler;
    SwsContext *output_scaler;
    /* FFMPEGKMP_INTERMEDIATE in the colour of the frame it is converted from or to. */
    AVFrame *intermediate;
    /* The tone map for clips that peak at tone_map_peak nits, made when the peak changes. */
    float *tone_map;
    double tone_map_peak;
    ffmpegkmp_worker_pool pool;
};

typedef enum ffmpegkmp_route {
    /* Same primaries and transfer: swscale maps no colour. */
    FFMPEGKMP_ROUTE_SCALE,
    /* The transfer step, with swscale for layout on either side. */
    FFMPEGKMP_ROUTE_TRANSFER,
    /* HDR to SDR: the transfer step tone maps, and swscale then maps the gamut. */
    FFMPEGKMP_ROUTE_TONE_MAP,
    /* SDR to SDR through swscale's colour management. */
    FFMPEGKMP_ROUTE_SCALE_MAPPED,
} ffmpegkmp_route;

typedef enum ffmpegkmp_encoding {
    /* Linear light, 1.0 at 203 nits. */
    FFMPEGKMP_ENCODING_LINEAR,
    FFMPEGKMP_ENCODING_PQ,
    FFMPEGKMP_ENCODING_HLG,
    /* SDR in the sRGB curve, BT.2390-mapped from linear light in SDR white's units. */
    FFMPEGKMP_ENCODING_TONE_MAP,
} ffmpegkmp_encoding;

/* One transfer step pass: the rows' input, the maths, and where the result goes. */
typedef struct ffmpegkmp_transfer {
    /* Red, green and blue 16-bit samples: the intermediate's planes, or packed RGBA half floats. */
    uint8_t *input[3];
    int input_stride;
    /* Samples from one pixel to the next: 1 in planes, 4 packed. */
    int input_step;
    /* The source curve by 16-bit code; NULL for half-float input, which is linear. */
    const float *decode;
    /* swscale's range scale undone on input codes, and applied to intermediate output (ffmpegkmp_range_factor). */
    float input_scale;
    float output_scale;
    /* HLG decoded to display light: the OOTF gain by scene luminance. */
    const float *decode_gain;
    /* Primaries, and the scale from the decoded unit to the encoded one. */
    float matrix[9];
    ffmpegkmp_encoding encoding;
    const float *encode;
    /* HLG encoded from display light: the inverse OOTF gain by display luminance. */
    const float *encode_gain;
    /* The tone map's octave table, and the scale from SDR white's units to a fraction of the clip's peak. */
    const float *tone_map;
    float tone_map_input;
    const uint16_t *srgb;
    /* AV_PIX_FMT_RGBA, BGRA, RGBAF16, or the intermediate's planes, which may be the input's. */
    enum AVPixelFormat output_format;
    uint8_t *output[3];
    int output_stride;
    int width;
} ffmpegkmp_transfer;

static inline void ffmpegkmp_store(uint8_t *const row[3], enum AVPixelFormat format, int x, const float value[3]) {
    int channel;
    switch (format) {
    case AV_PIX_FMT_RGBAF16: {
        uint16_t *pixel = (uint16_t *)row[0] + x * 4;
        for (channel = 0; channel < 3; channel++)
            pixel[channel] = ffplaykmp_half(value[channel]);
        pixel[3] = 0x3c00u;
        break;
    }
    case FFMPEGKMP_INTERMEDIATE:
        for (channel = 0; channel < 3; channel++)
            ((uint16_t *)row[channel])[x] = (uint16_t)(fminf(fmaxf(value[channel], 0.0f), 1.0f) * 65535.0f + 0.5f);
        break;
    default: {
        uint8_t *pixel = row[0] + x * 4;
        const int red = format == AV_PIX_FMT_BGRA ? 2 : 0;
        pixel[red] = (uint8_t)(fminf(fmaxf(value[0], 0.0f), 1.0f) * 255.0f + 0.5f);
        pixel[1] = (uint8_t)(fminf(fmaxf(value[1], 0.0f), 1.0f) * 255.0f + 0.5f);
        pixel[2 - red] = (uint8_t)(fminf(fmaxf(value[2], 0.0f), 1.0f) * 255.0f + 0.5f);
        pixel[3] = 255;
        break;
    }
    }
}

/*
 * `table` at `code` times `scale`, interpolated rather than rounded to a code:
 * a second rounding would add to swscale's own, and saturated colours at the
 * gamut's edge, whose components cancel, would amplify it.
 */
static inline float ffmpegkmp_scaled_lookup(const float *table, uint16_t code, float scale) {
    const float position = code * scale;
    const int index = (int)position;
    if (index >= 65535)
        return table[65535];
    return table[index] + (position - index) * (table[index + 1] - table[index]);
}

static void ffmpegkmp_transfer_rows(void *opaque, int first_row, int end_row) {
    const ffmpegkmp_transfer *transfer = opaque;
    const float *matrix = transfer->matrix;
    const int step = transfer->input_step;
    int y;
    int x;
    for (y = first_row; y < end_row; y++) {
        const size_t input_offset = (size_t)y * transfer->input_stride;
        const size_t output_offset = (size_t)y * transfer->output_stride;
        const uint16_t *red = (const uint16_t *)(transfer->input[0] + input_offset);
        const uint16_t *green = (const uint16_t *)(transfer->input[1] + input_offset);
        const uint16_t *blue = (const uint16_t *)(transfer->input[2] + input_offset);
        uint8_t *const output[3] = {
            transfer->output[0] + output_offset,
            transfer->output[1] ? transfer->output[1] + output_offset : NULL,
            transfer->output[2] ? transfer->output[2] + output_offset : NULL,
        };
        for (x = 0; x < transfer->width; x++) {
            float source[3];
            float value[3];
            int channel;
            if (transfer->decode && transfer->input_scale != 1.0f) {
                source[0] = ffmpegkmp_scaled_lookup(transfer->decode, red[x * step], transfer->input_scale);
                source[1] = ffmpegkmp_scaled_lookup(transfer->decode, green[x * step], transfer->input_scale);
                source[2] = ffmpegkmp_scaled_lookup(transfer->decode, blue[x * step], transfer->input_scale);
            } else if (transfer->decode) {
                source[0] = transfer->decode[red[x * step]];
                source[1] = transfer->decode[green[x * step]];
                source[2] = transfer->decode[blue[x * step]];
            } else {
                source[0] = ffplaykmp_float_from_half(red[x * step]);
                source[1] = ffplaykmp_float_from_half(green[x * step]);
                source[2] = ffplaykmp_float_from_half(blue[x * step]);
            }
            if (transfer->decode_gain) {
                /* BT.2100 HLG OOTF: display light = Ys^0.2 * scene light, BT.2020 luminance. */
                float luminance = 0.2627f * source[0] + 0.6780f * source[1] + 0.0593f * source[2];
                float gain = luminance > 0.0f ? ffmpegkmp_octave_lookup(transfer->decode_gain, luminance) : 0.0f;
                source[0] *= gain;
                source[1] *= gain;
                source[2] *= gain;
            }
            for (channel = 0; channel < 3; channel++) {
                value[channel] = matrix[channel * 3] * source[0] + matrix[channel * 3 + 1] * source[1] +
                        matrix[channel * 3 + 2] * source[2];
            }
            switch (transfer->encoding) {
            case FFMPEGKMP_ENCODING_PQ:
                for (channel = 0; channel < 3; channel++)
                    value[channel] = ffmpegkmp_octave_lookup(transfer->encode, value[channel]);
                break;
            case FFMPEGKMP_ENCODING_HLG: {
                /* The inverse OOTF, from display light relative to 1000 nits, then the OETF. */
                float luminance = 0.2627f * value[0] + 0.6780f * value[1] + 0.0593f * value[2];
                float gain = luminance > 0.0f ? ffmpegkmp_octave_lookup(transfer->encode_gain, luminance) : 0.0f;
                for (channel = 0; channel < 3; channel++)
                    value[channel] = ffmpegkmp_octave_lookup(transfer->encode, value[channel] * gain);
                break;
            }
            case FFMPEGKMP_ENCODING_TONE_MAP: {
                /* On the brightest component, so each colour keeps its hue; the gamut is mapped after. */
                const float brightest = fmaxf(fmaxf(value[0], value[1]), value[2]);
                const float scale = brightest > 0.0f
                        ? ffmpegkmp_octave_lookup(transfer->tone_map, brightest * transfer->tone_map_input) / brightest
                        : 0.0f;
                for (channel = 0; channel < 3; channel++) {
                    const float mapped = fminf(fmaxf(value[channel] * scale, 0.0f), 1.0f);
                    value[channel] = transfer->srgb[(int)(mapped * 65535.0f + 0.5f)] * (1.0f / 65535.0f);
                }
                break;
            }
            default:
                break;
            }
            if (transfer->output_scale != 1.0f) {
                for (channel = 0; channel < 3; channel++)
                    value[channel] *= transfer->output_scale;
            }
            ffmpegkmp_store(output, transfer->output_format, x, value);
        }
    }
}

static int ffmpegkmp_is_hdr_transfer(enum AVColorTransferCharacteristic transfer) {
    return transfer == AVCOL_TRC_SMPTE2084 || transfer == AVCOL_TRC_ARIB_STD_B67;
}

static int ffmpegkmp_is_rgb(const AVFrame *frame) {
    const AVPixFmtDescriptor *descriptor = av_pix_fmt_desc_get(frame->format);
    return descriptor && (descriptor->flags & AV_PIX_FMT_FLAG_RGB);
}

static ffmpegkmp_route ffmpegkmp_choose_route(const AVFrame *destination, const AVFrame *source) {
    const int same = destination->color_primaries == source->color_primaries &&
            destination->color_trc == source->color_trc;
    /* swscale reads RGBA F16 but cannot write it. */
    if (destination->format == AV_PIX_FMT_RGBAF16 || (!same && source->format == AV_PIX_FMT_RGBAF16))
        return FFMPEGKMP_ROUTE_TRANSFER;
    if (same)
        return FFMPEGKMP_ROUTE_SCALE;
    if (ffmpegkmp_is_hdr_transfer(destination->color_trc) || destination->color_trc == AVCOL_TRC_LINEAR)
        return FFMPEGKMP_ROUTE_TRANSFER;
    if (ffmpegkmp_is_hdr_transfer(source->color_trc))
        return FFMPEGKMP_ROUTE_TONE_MAP;
    return FFMPEGKMP_ROUTE_SCALE_MAPPED;
}

static ffmpegkmp_curve ffmpegkmp_source_curve(enum AVColorTransferCharacteristic transfer) {
    switch (transfer) {
    case AVCOL_TRC_SMPTE2084:
        return FFMPEGKMP_CURVE_PQ;
    case AVCOL_TRC_ARIB_STD_B67:
        return FFMPEGKMP_CURVE_HLG;
    case AVCOL_TRC_LINEAR:
        return FFMPEGKMP_CURVE_LINEAR;
    case AVCOL_TRC_GAMMA22:
        return FFMPEGKMP_CURVE_GAMMA22;
    case AVCOL_TRC_GAMMA28:
        return FFMPEGKMP_CURVE_GAMMA28;
    default:
        return FFMPEGKMP_CURVE_SRGB;
    }
}

/* Sets up the maths of a transfer step pass; the caller fills in the rows. */
static int ffmpegkmp_transfer_setup(
        ffmpegkmp_transfer *transfer,
        const AVFrame *destination,
        const AVFrame *source,
        ffmpegkmp_route route) {
    const enum AVColorTransferCharacteristic from = source->color_trc;
    const enum AVColorTransferCharacteristic to = destination->color_trc;
    /* The unit decoded values are in, in nits, and the unit the encoding takes. */
    double decoded_unit = FFMPEGKMP_REFERENCE_WHITE_NITS;
    double encoded_unit = FFMPEGKMP_REFERENCE_WHITE_NITS;
    memset(transfer, 0, sizeof(*transfer));
    if (source->format == AV_PIX_FMT_RGBAF16) {
        if (from != AVCOL_TRC_LINEAR)
            return AVERROR(ENOSYS);
    } else if (!(transfer->decode = ffmpegkmp_curve_table(ffmpegkmp_source_curve(from)))) {
        return AVERROR(ENOMEM);
    }
    if (route == FFMPEGKMP_ROUTE_TONE_MAP) {
        transfer->encoding = FFMPEGKMP_ENCODING_TONE_MAP;
        if (!(transfer->srgb = ffmpegkmp_srgb_table()))
            return AVERROR(ENOMEM);
    } else if (to == AVCOL_TRC_SMPTE2084) {
        transfer->encoding = FFMPEGKMP_ENCODING_PQ;
        encoded_unit = FFMPEGKMP_PQ_PEAK_NITS;
        if (!(transfer->encode = ffmpegkmp_octave_table_get(FFMPEGKMP_TABLE_PQ_SIGNAL)))
            return AVERROR(ENOMEM);
    } else if (to == AVCOL_TRC_ARIB_STD_B67) {
        transfer->encoding = FFMPEGKMP_ENCODING_HLG;
        encoded_unit = FFMPEGKMP_HLG_DISPLAY_NITS;
        if (!(transfer->encode = ffmpegkmp_octave_table_get(FFMPEGKMP_TABLE_HLG_SIGNAL)) ||
                !(transfer->encode_gain = ffmpegkmp_octave_table_get(FFMPEGKMP_TABLE_HLG_INVERSE_GAIN)))
            return AVERROR(ENOMEM);
    } else if (to == AVCOL_TRC_LINEAR && destination->format == AV_PIX_FMT_RGBAF16) {
        transfer->encoding = FFMPEGKMP_ENCODING_LINEAR;
    } else {
        return AVERROR(ENOSYS);
    }
    if (from == AVCOL_TRC_SMPTE2084) {
        decoded_unit = FFMPEGKMP_PQ_PEAK_NITS;
    } else if (from == AVCOL_TRC_ARIB_STD_B67) {
        decoded_unit = FFMPEGKMP_HLG_DISPLAY_NITS;
        if (!(transfer->decode_gain = ffmpegkmp_octave_table_get(FFMPEGKMP_TABLE_HLG_GAIN)))
            return AVERROR(ENOMEM);
    }
    /* A tone map keeps the source's primaries: swscale maps its gamut perceptually afterwards. */
    ffmpegkmp_primaries_matrix(
            ffmpegkmp_known_primaries(source->color_primaries),
            ffmpegkmp_known_primaries(route == FFMPEGKMP_ROUTE_TONE_MAP ? source->color_primaries : destination->color_primaries),
            decoded_unit / encoded_unit,
            transfer->matrix);
    return 0;
}

/* An unspecified YUV matrix is BT.709, as the bridge has always converted it, not swscale's BT.601. */
static void ffmpegkmp_default_matrix(AVFrame *frame) {
    if (frame->colorspace == AVCOL_SPC_UNSPECIFIED && !ffmpegkmp_is_rgb(frame))
        frame->colorspace = AVCOL_SPC_BT709;
}

/*
 * Gives `frame` the properties and side data of `source` but keeps its own
 * colour. swscale compares the two frames' mastering displays too
 * (ff_sws_color_map_noop), so frames of one colour must carry the same.
 */
static int ffmpegkmp_copy_props(AVFrame *frame, const AVFrame *source) {
    const enum AVColorPrimaries primaries = frame->color_primaries;
    const enum AVColorTransferCharacteristic transfer = frame->color_trc;
    const enum AVColorSpace matrix = frame->colorspace;
    const enum AVColorRange range = frame->color_range;
    const enum AVChromaLocation chroma = frame->chroma_location;
    int result;
    av_frame_side_data_free(&frame->side_data, &frame->nb_side_data);
    av_dict_free(&frame->metadata);
    result = av_frame_copy_props(frame, source);
    frame->color_primaries = primaries;
    frame->color_trc = transfer;
    frame->colorspace = matrix;
    frame->color_range = range;
    frame->chroma_location = chroma;
    frame->flags &= ~(AV_FRAME_FLAG_INTERLACED | AV_FRAME_FLAG_TOP_FIELD_FIRST);
    return result;
}

static const AVMasteringDisplayMetadata *ffmpegkmp_mastering(const AVFrame *frame) {
    const AVFrameSideData *side_data = av_frame_get_side_data(frame, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
    return side_data && side_data->size >= sizeof(AVMasteringDisplayMetadata)
            ? (const AVMasteringDisplayMetadata *)side_data->data
            : NULL;
}

/* Whether swscale sees one colour volume in both frames (what ff_sws_color_map_noop compares). */
static int ffmpegkmp_same_color(const AVFrame *first, const AVFrame *second) {
    const AVMasteringDisplayMetadata *a = ffmpegkmp_mastering(first);
    const AVMasteringDisplayMetadata *b = ffmpegkmp_mastering(second);
    int corner;
    if (first->color_primaries != second->color_primaries || first->color_trc != second->color_trc)
        return 0;
    if (!a || !b)
        return !a == !b;
    if (a->has_luminance != b->has_luminance || a->has_primaries != b->has_primaries)
        return 0;
    if (a->has_luminance && (av_cmp_q(a->min_luminance, b->min_luminance) ||
            av_cmp_q(a->max_luminance, b->max_luminance)))
        return 0;
    for (corner = 0; a->has_primaries && corner < 3; corner++) {
        if (av_cmp_q(a->display_primaries[corner][0], b->display_primaries[corner][0]) ||
                av_cmp_q(a->display_primaries[corner][1], b->display_primaries[corner][1]))
            return 0;
    }
    return 1;
}

/* swscale between frames of one colour: it must convert layout only and map no colour. */
static int ffmpegkmp_scale(SwsContext *scaler, AVFrame *destination, const AVFrame *source) {
    if (!ffmpegkmp_same_color(destination, source))
        return AVERROR_BUG;
    return sws_scale_frame(scaler, destination, source);
}

/*
 * Readies the intermediate at the size of `size`, in the colour of `colour`,
 * with `source`'s properties.
 */
static int ffmpegkmp_intermediate(
        ffmpegkmp_converter *converter,
        const AVFrame *colour,
        const AVFrame *source,
        const AVFrame *size) {
    AVFrame *intermediate = converter->intermediate;
    int result;
    if (!intermediate->buf[0] || intermediate->width != size->width || intermediate->height != size->height ||
            !av_frame_is_writable(intermediate)) {
        av_frame_unref(intermediate);
        intermediate->format = FFMPEGKMP_INTERMEDIATE;
        intermediate->width = size->width;
        intermediate->height = size->height;
        if ((result = av_frame_get_buffer(intermediate, 0)) < 0)
            return result;
        ffmpegkmp_frame_count_allocation(
                (size_t)av_image_get_buffer_size(FFMPEGKMP_INTERMEDIATE, size->width, size->height, 1));
    }
    intermediate->color_primaries = colour->color_primaries;
    intermediate->color_trc = colour->color_trc;
    intermediate->colorspace = AVCOL_SPC_RGB;
    intermediate->color_range = AVCOL_RANGE_JPEG;
    intermediate->chroma_location = AVCHROMA_LOC_UNSPECIFIED;
    return ffmpegkmp_copy_props(intermediate, source);
}

/* The red, green and blue rows of an intermediate or a packed RGBA half-float frame. */
static void ffmpegkmp_rgb_planes(const AVFrame *frame, uint8_t *planes[3], int *stride, int *step) {
    if (frame->format == FFMPEGKMP_INTERMEDIATE) {
        /* GBR plane order. */
        planes[0] = frame->data[2];
        planes[1] = frame->data[0];
        planes[2] = frame->data[1];
        if (step)
            *step = 1;
    } else {
        planes[0] = frame->data[0];
        planes[1] = frame->data[0] + 2;
        planes[2] = frame->data[0] + 4;
        if (step)
            *step = 4;
    }
    /* av_frame_get_buffer gives every plane of a planar RGB frame the same stride. */
    *stride = frame->linesize[0];
}

/*
 * swscale takes YUV deeper than 8 bits through 16 bits, where limited-range
 * luma spans 219 * 257 codes above black and BT.2100 shifts 8-bit's 219 up
 * instead, 219 * 256: 10-bit white lands on 943 rather than 940, and 940
 * reads as 99.6%. Chroma's span has the same ratio, and both are linear in
 * RGB, so scaling the RGB side by 256/257 before swscale, and by its inverse
 * after it, gives BT.2100's codes. 1 for RGB, full range and 8 bits.
 */
static float ffmpegkmp_range_factor(const AVFrame *frame) {
    const AVPixFmtDescriptor *descriptor = av_pix_fmt_desc_get(frame->format);
    if (!descriptor || (descriptor->flags & AV_PIX_FMT_FLAG_RGB) || descriptor->nb_components < 3 ||
            frame->color_range == AVCOL_RANGE_JPEG || descriptor->comp[0].depth <= 8)
        return 1.0f;
    return 256.0f / 257.0f;
}

/* Through swscale's colour management, with `intent` for colours outside the destination's gamut. */
static int ffmpegkmp_scale_mapped(SwsContext *scaler, AVFrame *destination, const AVFrame *source, int intent) {
    scaler->intent = intent;
    return sws_scale_frame(scaler, destination, source);
}

/*
 * The brightest light a clip says it holds, in nits: the lower of MaxCLL and
 * its mastering display's peak, 1,000 when it gives neither, and no more than
 * HLG's 1,000-nit reference display shows.
 */
static double ffmpegkmp_clip_peak(const AVFrame *frame) {
    const AVFrameSideData *mastering = av_frame_get_side_data(frame, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
    const AVFrameSideData *light = av_frame_get_side_data(frame, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
    double peak = 0.0;
    if (mastering && mastering->size >= sizeof(AVMasteringDisplayMetadata)) {
        const AVMasteringDisplayMetadata *display = (const AVMasteringDisplayMetadata *)mastering->data;
        if (display->has_luminance && display->max_luminance.den > 0)
            peak = av_q2d(display->max_luminance);
    }
    if (light && light->size >= sizeof(AVContentLightMetadata)) {
        const unsigned max_light = ((const AVContentLightMetadata *)light->data)->MaxCLL;
        if (max_light > 0 && (peak <= 0.0 || max_light < peak))
            peak = max_light;
    }
    if (peak <= 0.0 || frame->color_trc == AVCOL_TRC_ARIB_STD_B67)
        peak = peak > 0.0 && peak < FFMPEGKMP_HLG_DISPLAY_NITS ? peak : FFMPEGKMP_HLG_DISPLAY_NITS;
    return av_clipd(peak, FFMPEGKMP_REFERENCE_WHITE_NITS, FFMPEGKMP_PQ_PEAK_NITS);
}

/* The tone map for `source`'s clip, remade when its peak differs from the last one's. */
static const float *ffmpegkmp_tone_map_for(ffmpegkmp_converter *converter, const AVFrame *source, double *peak) {
    *peak = ffmpegkmp_clip_peak(source);
    if (!converter->tone_map || converter->tone_map_peak != *peak) {
        if (!converter->tone_map && !(converter->tone_map = av_malloc(FFMPEGKMP_OCTAVE_ENTRIES * sizeof(float))))
            return NULL;
        ffmpegkmp_tone_map_fill(converter->tone_map, *peak);
        converter->tone_map_peak = *peak;
    }
    return converter->tone_map;
}

static int ffmpegkmp_convert_transfer(
        ffmpegkmp_converter *converter,
        AVFrame *destination,
        const AVFrame *source,
        ffmpegkmp_route route) {
    ffmpegkmp_transfer transfer;
    const AVFrame *input = source;
    /* A tone map goes through the intermediate, which swscale maps into the destination's gamut. */
    const int direct = route != FFMPEGKMP_ROUTE_TONE_MAP && (destination->format == AV_PIX_FMT_RGBA ||
            destination->format == AV_PIX_FMT_BGRA || destination->format == AV_PIX_FMT_RGBAF16);
    int result;
    if ((result = ffmpegkmp_transfer_setup(&transfer, destination, source, route)) < 0)
        return result;
    if (route == FFMPEGKMP_ROUTE_TONE_MAP) {
        double peak;
        if (!(transfer.tone_map = ffmpegkmp_tone_map_for(converter, source, &peak)))
            return AVERROR(ENOMEM);
        transfer.tone_map_input = (float)(FFMPEGKMP_REFERENCE_WHITE_NITS / peak);
    }
    transfer.input_scale = source->format == AV_PIX_FMT_RGBAF16 ? 1.0f : 1.0f / ffmpegkmp_range_factor(source);
    transfer.output_scale = direct ? 1.0f : ffmpegkmp_range_factor(destination);
    if (source->format != AV_PIX_FMT_RGBAF16) {
        /*
         * Into the intermediate in the source's own colour, at the destination's
         * size: layout and scale only, nothing clipped, so the transfer step
         * and the gamut pass work on the scaled frame.
         */
        if ((result = ffmpegkmp_intermediate(converter, source, source, destination)) < 0 ||
                (result = ffmpegkmp_scale(converter->input_scaler, converter->intermediate, source)) < 0)
            return result;
        input = converter->intermediate;
    } else if (direct) {
        /* From a canvas the transfer step works pixel for pixel, and swscale scales on the way out. */
        if (source->width != destination->width || source->height != destination->height)
            return AVERROR(ENOSYS);
    } else if ((result = ffmpegkmp_intermediate(converter, destination, source, source)) < 0) {
        return result;
    }
    transfer.width = input->width;
    ffmpegkmp_rgb_planes(input, transfer.input, &transfer.input_stride, &transfer.input_step);
    if (direct) {
        transfer.output_format = destination->format;
        transfer.output[0] = destination->data[0];
        transfer.output_stride = destination->linesize[0];
    } else {
        /* In place when the input is the intermediate: each pixel is read before it is written. */
        transfer.output_format = FFMPEGKMP_INTERMEDIATE;
        ffmpegkmp_rgb_planes(converter->intermediate, transfer.output, &transfer.output_stride, NULL);
    }
    ffmpegkmp_pool_run(&converter->pool, ffmpegkmp_transfer_rows, &transfer, input->height);
    if (direct)
        return 0;
    /* SDR codes now, shown as coded whichever SDR curve the destination names. */
    converter->intermediate->color_trc = destination->color_trc;
    if (route == FFMPEGKMP_ROUTE_TONE_MAP) {
        /* An SDR frame has no mastering display, which swscale would otherwise tone map for. */
        av_frame_remove_side_data(converter->intermediate, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
        av_frame_remove_side_data(converter->intermediate, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
        av_frame_remove_side_data(converter->intermediate, AV_FRAME_DATA_DYNAMIC_HDR_PLUS);
        if (converter->intermediate->color_primaries != destination->color_primaries) {
            /* Colours outside the destination's gamut are compressed, keeping their hue, not clipped. */
            return ffmpegkmp_scale_mapped(
                    converter->output_scaler, destination, converter->intermediate, SWS_INTENT_PERCEPTUAL);
        }
    }
    /* Out of the intermediate in the destination's colour: layout only again. */
    converter->intermediate->color_primaries = destination->color_primaries;
    return ffmpegkmp_scale(converter->output_scaler, destination, converter->intermediate);
}

static SwsContext *ffmpegkmp_scaler_alloc(int threads) {
    SwsContext *scaler = sws_alloc_context();
    if (scaler) {
        scaler->threads = threads > 0 ? threads : FFMPEGKMP_SCALER_THREADS;
        /* The chroma filter the bridge has always converted with; swscale widens it to downscale. */
        scaler->flags = SWS_BILINEAR;
    }
    return scaler;
}

ffmpegkmp_converter *ffmpegkmp_converter_alloc(int threads) {
    ffmpegkmp_converter *converter = av_mallocz(sizeof(*converter));
#if defined(__EMSCRIPTEN__)
    /* The worker's share of its fixed pthread pool stays as it is. */
    threads = 0;
#endif
    if (!converter)
        return NULL;
    converter->scaler = ffmpegkmp_scaler_alloc(threads);
    converter->input_scaler = ffmpegkmp_scaler_alloc(threads);
    converter->output_scaler = ffmpegkmp_scaler_alloc(threads);
    converter->pool.threads = threads;
    converter->intermediate = av_frame_alloc();
    if (!converter->scaler || !converter->input_scaler || !converter->output_scaler || !converter->intermediate)
        ffmpegkmp_converter_free(&converter);
    return converter;
}

void ffmpegkmp_converter_free(ffmpegkmp_converter **converter) {
    if (!converter || !*converter)
        return;
    ffmpegkmp_pool_stop(&(*converter)->pool);
    sws_free_context(&(*converter)->scaler);
    sws_free_context(&(*converter)->input_scaler);
    sws_free_context(&(*converter)->output_scaler);
    av_frame_free(&(*converter)->intermediate);
    av_freep(&(*converter)->tone_map);
    av_freep(converter);
}

int ffmpegkmp_frame_convert(ffmpegkmp_converter *converter, AVFrame *destination, const AVFrame *source) {
    /* A view of the source with its defaults filled in; it owns nothing and ends with the call. */
    AVFrame input;
    ffmpegkmp_route route;
    int result;
    if (!converter || !destination || !source || source->width <= 0 || source->height <= 0 ||
            destination->width <= 0 || destination->height <= 0)
        return AVERROR(EINVAL);
    if (ffplaykmp_is_hardware_frame(source) || destination->hw_frames_ctx)
        return AVERROR(ENOSYS);
    input = *source;
    ffmpegkmp_default_matrix(&input);
    input.flags &= ~(AV_FRAME_FLAG_INTERLACED | AV_FRAME_FLAG_TOP_FIELD_FIRST);
    /*
     * Into RGB, chroma is sited at the centre whatever the frame says, as the
     * bridge has always converted: following chroma_location instead would
     * move colour edges by half a pixel. Between YUV frames it stays as it is.
     */
    if (ffmpegkmp_is_rgb(destination) || destination->format == AV_PIX_FMT_RGBAF16)
        input.chroma_location = AVCHROMA_LOC_CENTER;
    ffmpegkmp_default_matrix(destination);
    if (!destination->data[0] && (result = av_frame_get_buffer(destination, 0)) < 0)
        return result;
    if ((result = ffmpegkmp_copy_props(destination, &input)) < 0)
        return result;
    destination->sample_aspect_ratio = ffmpegkmp_scaled_aspect(
            input.sample_aspect_ratio, input.width, input.height, destination->width, destination->height);
    /* An SDR frame has no mastering display: swscale would take one for the display it maps to. */
    if (!ffmpegkmp_is_hdr_transfer(destination->color_trc) && destination->color_trc != AVCOL_TRC_LINEAR) {
        av_frame_remove_side_data(destination, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
        av_frame_remove_side_data(destination, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
        av_frame_remove_side_data(destination, AV_FRAME_DATA_DYNAMIC_HDR_PLUS);
    }
    route = ffmpegkmp_choose_route(destination, &input);
    switch (route) {
    case FFMPEGKMP_ROUTE_SCALE:
        return ffmpegkmp_scale(converter->scaler, destination, &input);
    case FFMPEGKMP_ROUTE_SCALE_MAPPED:
        return ffmpegkmp_scale_mapped(converter->scaler, destination, &input, SWS_INTENT_RELATIVE_COLORIMETRIC);
    default:
        return ffmpegkmp_convert_transfer(converter, destination, &input, route);
    }
}

AVRational ffmpegkmp_scaled_aspect(AVRational aspect, int width, int height, int scaled_width, int scaled_height) {
    AVRational scaled;
    if (width == scaled_width && height == scaled_height)
        return aspect;
    if (aspect.num <= 0 || aspect.den <= 0)
        aspect = (AVRational){ 1, 1 };
    av_reduce(&scaled.num, &scaled.den, (int64_t)aspect.num * width * scaled_height,
            (int64_t)aspect.den * height * scaled_width, INT_MAX);
    return scaled;
}

int ffplaykmp_rgb_output(AVFrame *output, const AVFrame *source, enum AVPixelFormat format, int tone_map_hdr) {
    int result;
    if (!output->buf[0] || output->format != format || output->width != source->width ||
            output->height != source->height || !av_frame_is_writable(output)) {
        av_frame_unref(output);
        output->format = format;
        output->width = source->width;
        output->height = source->height;
        /* Packed rows: the outputs hand width * pixel size strides to Kotlin. */
        if ((result = av_frame_get_buffer(output, 1)) < 0)
            return result;
    }
    output->colorspace = AVCOL_SPC_RGB;
    output->color_range = AVCOL_RANGE_JPEG;
    output->chroma_location = AVCHROMA_LOC_UNSPECIFIED;
    if (format == AV_PIX_FMT_RGBAF16) {
        output->color_primaries = AVCOL_PRI_BT709;
        output->color_trc = AVCOL_TRC_LINEAR;
    } else if (tone_map_hdr && ffmpegkmp_is_hdr_transfer(source->color_trc)) {
        output->color_primaries = AVCOL_PRI_BT709;
        output->color_trc = AVCOL_TRC_IEC61966_2_1;
    } else {
        output->color_primaries = source->color_primaries;
        output->color_trc = source->color_trc;
    }
    return 0;
}

int ffplaykmp_download_frame(AVFrame *software, const AVFrame *hardware) {
    int result = av_hwframe_transfer_data(software, hardware, 0);
    return result < 0 ? result : av_frame_copy_props(software, hardware);
}
