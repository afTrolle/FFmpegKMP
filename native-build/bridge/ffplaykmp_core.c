// SPDX-License-Identifier: LGPL-2.1-or-later
#include "ffplaykmp_core.h"

#include <errno.h>
#include <limits.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(__ANDROID__)
#include <libavcodec/jni.h>
#endif
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
    snapshot->hdr_type = FFPLAYKMP_HDR_SDR;
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
            AV_PKT_DATA_DOVI_CONF)) {
        snapshot->hdr_type = FFPLAYKMP_HDR_DOLBY_VISION;
    } else if (av_packet_side_data_get(
            parameters->coded_side_data,
            parameters->nb_coded_side_data,
            AV_PKT_DATA_DYNAMIC_HDR10_PLUS)) {
        snapshot->hdr_type = FFPLAYKMP_HDR_HDR10_PLUS;
    } else if (parameters->color_trc == AVCOL_TRC_ARIB_STD_B67) {
        snapshot->hdr_type = FFPLAYKMP_HDR_HLG;
    } else if (parameters->color_trc == AVCOL_TRC_SMPTE2084) {
        snapshot->hdr_type = FFPLAYKMP_HDR_HDR10;
    } else if (parameters->color_trc == AVCOL_TRC_UNSPECIFIED ||
            parameters->color_trc == AVCOL_TRC_BT709 ||
            parameters->color_trc == AVCOL_TRC_GAMMA22 ||
            parameters->color_trc == AVCOL_TRC_GAMMA28 ||
            parameters->color_trc == AVCOL_TRC_IEC61966_2_1) {
        snapshot->hdr_type = FFPLAYKMP_HDR_SDR;
    } else {
        snapshot->hdr_type = FFPLAYKMP_HDR_UNKNOWN;
    }
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
            av_frame_get_side_data(frame, AV_FRAME_DATA_DOVI_RPU_BUFFER)) {
        snapshot->hdr_type = FFPLAYKMP_HDR_DOLBY_VISION;
    } else if (av_frame_get_side_data(frame, AV_FRAME_DATA_DYNAMIC_HDR_PLUS)) {
        snapshot->hdr_type = FFPLAYKMP_HDR_HDR10_PLUS;
    } else if (frame->color_trc == AVCOL_TRC_ARIB_STD_B67) {
        snapshot->hdr_type = FFPLAYKMP_HDR_HLG;
    } else if (frame->color_trc == AVCOL_TRC_SMPTE2084) {
        snapshot->hdr_type = FFPLAYKMP_HDR_HDR10;
    }
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

static int ffplaykmp_host_interrupted(const ffplaykmp_io_host *host) {
    return host->interrupted && host->interrupted(host->interrupt_opaque);
}

static int ffplaykmp_interrupt(void *opaque) {
    const ffplaykmp_io_host *host = opaque;
    return host ? ffplaykmp_host_interrupted(host) : 1;
}

static int ffplaykmp_avio_read(void *opaque, uint8_t *buffer, int size) {
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

static int64_t ffplaykmp_avio_seek(void *opaque, int64_t offset, int whence) {
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

int ffplaykmp_video_codec_open(
        ffplaykmp_video_codec *codec,
        AVFormatContext *format,
        int stream_index,
        int hardware,
        int require_hardware,
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
        codec->mediacodec_context = av_mediacodec_alloc_context();
        if (!codec->mediacodec_context)
            return AVERROR(ENOMEM);
        if ((result = av_mediacodec_default_init(
                codec->decoder, codec->mediacodec_context, android_surface)) < 0) {
            /* Not attached to the decoder, so av_mediacodec_default_free won't free it. */
            av_freep(&codec->mediacodec_context);
            return result;
        }
        if (android_surface)
            codec->decoder->get_format = ffplaykmp_mediacodec_format;
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
    }
    return avcodec_open2(codec->decoder, decoder, options);
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

static float ffplaykmp_clamp_unit(double value) {
    if (!isfinite(value) || value <= 0.0)
        return 0.0f;
    if (value >= 1.0)
        return 1.0f;
    return (float)value;
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
        return 10000.0;
    return pow(numerator / denominator, 1.0 / m1) * 10000.0;
}

/* HLG inverse OETF: scene light in [0, 1]. */
static double ffplaykmp_hlg_scene(double value) {
    const double a = 0.17883277;
    const double b = 0.28466892;
    const double c = 0.55991073;
    value = ffplaykmp_clamp_unit(value);
    return value <= 0.5 ? value * value / 3.0 : (exp((value - c) / a) + b) / 12.0;
}

static double ffplaykmp_hdr_eotf(double value, enum AVColorTransferCharacteristic transfer) {
    if (transfer == AVCOL_TRC_SMPTE2084) {
        /* ST 2084 is normalized to 10,000 nits. Express it relative to a
         * 100-nit SDR reference white before applying the shoulder. */
        return ffplaykmp_pq_eotf_nits(value) / 100.0;
    }
    if (transfer == AVCOL_TRC_ARIB_STD_B67) {
        /* Nominal HLG peak is twelve times diffuse scene white. */
        return ffplaykmp_hlg_scene(value) * 12.0;
    }
    return ffplaykmp_clamp_unit(value);
}

static double ffplaykmp_srgb_oetf(double value) {
    value = ffplaykmp_clamp_unit(value);
    return value <= 0.0031308
            ? 12.92 * value
            : 1.055 * pow(value, 1.0 / 2.4) - 0.055;
}

static double ffplaykmp_srgb_eotf(double value) {
    double magnitude = fabs(value);
    double linear = magnitude <= 0.04045
            ? magnitude / 12.92
            : pow((magnitude + 0.055) / 1.055, 2.4);
    return value < 0.0 ? -linear : linear;
}

static void ffplaykmp_bt2020_to_bt709(double *red, double *green, double *blue) {
    double source_red = *red;
    double source_green = *green;
    double source_blue = *blue;
    *red = 1.660491 * source_red - 0.587641 * source_green - 0.072850 * source_blue;
    *green = -0.124550 * source_red + 1.132900 * source_green - 0.008349 * source_blue;
    *blue = -0.018151 * source_red - 0.100579 * source_green + 1.118730 * source_blue;
}

static void ffplaykmp_display_p3_to_bt709(double *red, double *green, double *blue) {
    double source_red = *red;
    double source_green = *green;
    double source_blue = *blue;
    *red = 1.2249401 * source_red - 0.2249404 * source_green;
    *green = -0.0420569 * source_red + 1.0420571 * source_green;
    *blue = -0.0196376 * source_red - 0.0786361 * source_green + 1.0982735 * source_blue;
}

static void ffplaykmp_configure_scaler_colors(
        struct SwsContext *scaler,
        const AVFrame *decoded) {
    const int *source_coefficients;
    const int *destination_coefficients = sws_getCoefficients(SWS_CS_ITU709);
    int source_space = decoded->colorspace == AVCOL_SPC_UNSPECIFIED
            ? SWS_CS_ITU709
            : decoded->colorspace;
    source_coefficients = sws_getCoefficients(source_space);
    if (!source_coefficients)
        source_coefficients = destination_coefficients;
    if (source_coefficients && destination_coefficients) {
        sws_setColorspaceDetails(
                scaler,
                source_coefficients,
                decoded->color_range == AVCOL_RANGE_JPEG,
                destination_coefficients,
                1,
                0,
                1 << 16,
                1 << 16);
    }
}

/* Grows *buffer to at least size bytes, keeping it for later frames. */
static uint8_t *ffplaykmp_scratch(uint8_t **buffer, int *capacity, int size) {
    if (size > *capacity) {
        av_freep(buffer);
        *buffer = av_malloc((size_t)size);
        *capacity = *buffer ? size : 0;
    }
    return *buffer;
}

/* Converts to non-linear planar float RGB (G, B, R planes) with the frame's matrix and range. */
static int ffplaykmp_scale_to_float(
        ffplaykmp_converter *converter,
        const AVFrame *decoded,
        uint8_t *planes[4],
        int strides[4]) {
    uint8_t *float_pixels;
    int float_size = av_image_get_buffer_size(
            AV_PIX_FMT_GBRPF32LE, decoded->width, decoded->height, 1);
    if (float_size <= 0)
        return AVERROR(EINVAL);
    float_pixels = ffplaykmp_scratch(&converter->float_buffer, &converter->float_capacity, float_size);
    if (!float_pixels)
        return AVERROR(ENOMEM);
    if (av_image_fill_arrays(
            planes,
            strides,
            float_pixels,
            AV_PIX_FMT_GBRPF32LE,
            decoded->width,
            decoded->height,
            1) < 0)
        return AVERROR(EINVAL);
    converter->float_scaler = sws_getCachedContext(
            converter->float_scaler,
            decoded->width,
            decoded->height,
            decoded->format,
            decoded->width,
            decoded->height,
            AV_PIX_FMT_GBRPF32LE,
            SWS_BILINEAR,
            NULL,
            NULL,
            NULL);
    if (!converter->float_scaler)
        return AVERROR(EINVAL);
    ffplaykmp_configure_scaler_colors(converter->float_scaler, decoded);
    if (sws_scale(
            converter->float_scaler,
            (const uint8_t *const *)decoded->data,
            decoded->linesize,
            0,
            decoded->height,
            planes,
            strides) != decoded->height)
        return AVERROR(EINVAL);
    return 0;
}

static int ffplaykmp_tone_map_hdr_frame(
        ffplaykmp_converter *converter,
        const AVFrame *decoded,
        uint8_t *rgba,
        int rgba_stride) {
    uint8_t *planes[4] = { NULL };
    int strides[4] = { 0 };
    int x;
    int y;
    int result;
    enum AVColorTransferCharacteristic transfer = decoded->color_trc;
    if (transfer != AVCOL_TRC_SMPTE2084 && transfer != AVCOL_TRC_ARIB_STD_B67)
        return AVERROR(ENOSYS);
    if ((result = ffplaykmp_scale_to_float(converter, decoded, planes, strides)) < 0)
        return result;
    for (y = 0; y < decoded->height; y++) {
        const float *green_row = (const float *)(planes[0] + y * strides[0]);
        const float *blue_row = (const float *)(planes[1] + y * strides[1]);
        const float *red_row = (const float *)(planes[2] + y * strides[2]);
        uint8_t *output = rgba + y * rgba_stride;
        for (x = 0; x < decoded->width; x++) {
            double red = ffplaykmp_hdr_eotf(red_row[x], transfer);
            double green = ffplaykmp_hdr_eotf(green_row[x], transfer);
            double blue = ffplaykmp_hdr_eotf(blue_row[x], transfer);
            double luminance;
            double mapped_luminance;
            double scale;
            if (decoded->color_primaries == AVCOL_PRI_BT2020)
                ffplaykmp_bt2020_to_bt709(&red, &green, &blue);
            red = red > 0.0 ? red : 0.0;
            green = green > 0.0 ? green : 0.0;
            blue = blue > 0.0 ? blue : 0.0;
            luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
            mapped_luminance = luminance / (1.0 + luminance);
            scale = luminance > 1e-9 ? mapped_luminance / luminance : 0.0;
            output[x * 4 + 0] = (uint8_t)lrint(
                    ffplaykmp_clamp_unit(ffplaykmp_srgb_oetf(red * scale)) * 255.0);
            output[x * 4 + 1] = (uint8_t)lrint(
                    ffplaykmp_clamp_unit(ffplaykmp_srgb_oetf(green * scale)) * 255.0);
            output[x * 4 + 2] = (uint8_t)lrint(
                    ffplaykmp_clamp_unit(ffplaykmp_srgb_oetf(blue * scale)) * 255.0);
            output[x * 4 + 3] = 255;
        }
    }
    return 0;
}

int ffplaykmp_convert_rgba(
        ffplaykmp_converter *converter,
        const AVFrame *decoded,
        int tone_map_hdr,
        ffplaykmp_converted_frame *output) {
    uint8_t *rgba;
    uint8_t *planes[4] = { NULL };
    int strides[4] = { 0 };
    int size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, decoded->width, decoded->height, 1);
    if (size <= 0)
        return AVERROR(EINVAL);
    rgba = ffplaykmp_scratch(&converter->rgba_buffer, &converter->rgba_capacity, size);
    if (!rgba)
        return AVERROR(ENOMEM);
    if (av_image_fill_arrays(
            planes, strides, rgba, AV_PIX_FMT_RGBA, decoded->width, decoded->height, 1) < 0)
        return AVERROR(EINVAL);
    if (!tone_map_hdr || ffplaykmp_tone_map_hdr_frame(converter, decoded, rgba, strides[0]) != 0) {
        converter->rgba_scaler = sws_getCachedContext(
                converter->rgba_scaler,
                decoded->width, decoded->height, decoded->format,
                decoded->width, decoded->height, AV_PIX_FMT_RGBA,
                SWS_BILINEAR, NULL, NULL, NULL);
        if (!converter->rgba_scaler)
            return AVERROR(EINVAL);
        ffplaykmp_configure_scaler_colors(converter->rgba_scaler, decoded);
        if (sws_scale(
                converter->rgba_scaler,
                (const uint8_t *const *)decoded->data,
                decoded->linesize,
                0,
                decoded->height,
                planes,
                strides) != decoded->height)
            return AVERROR(EINVAL);
    }
    output->pixels = rgba;
    output->size = size;
    output->width = decoded->width;
    output->height = decoded->height;
    output->stride = strides[0];
    return 0;
}

static uint16_t ffplaykmp_half(float value) {
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

#define FFPLAYKMP_REFERENCE_WHITE_NITS 203.0

static double ffplaykmp_linear_light(double value, enum AVColorTransferCharacteristic transfer) {
    switch (transfer) {
    case AVCOL_TRC_SMPTE2084:
        return ffplaykmp_pq_eotf_nits(value) / FFPLAYKMP_REFERENCE_WHITE_NITS;
    case AVCOL_TRC_LINEAR:
        return value;
    case AVCOL_TRC_GAMMA22:
        return value < 0.0 ? -pow(-value, 2.2) : pow(value, 2.2);
    case AVCOL_TRC_GAMMA28:
        return value < 0.0 ? -pow(-value, 2.8) : pow(value, 2.8);
    default:
        /* The same sRGB curve the RGBA8 output is displayed with, so both outputs agree. */
        return ffplaykmp_srgb_eotf(value);
    }
}

int ffplaykmp_convert_linear_f16(
        ffplaykmp_converter *converter,
        const AVFrame *decoded,
        ffplaykmp_converted_frame *output) {
    uint8_t *planes[4] = { NULL };
    int strides[4] = { 0 };
    uint8_t *half_pixels;
    const int stride = decoded->width * 8;
    const enum AVColorTransferCharacteristic transfer = decoded->color_trc;
    int size;
    int result;
    int x;
    int y;
    if (decoded->width <= 0 || decoded->height <= 0 || decoded->width > INT_MAX / 8 / decoded->height)
        return AVERROR(EINVAL);
    size = stride * decoded->height;
    half_pixels = ffplaykmp_scratch(&converter->half_buffer, &converter->half_capacity, size);
    if (!half_pixels)
        return AVERROR(ENOMEM);
    if ((result = ffplaykmp_scale_to_float(converter, decoded, planes, strides)) < 0)
        return result;
    for (y = 0; y < decoded->height; y++) {
        const float *green_row = (const float *)(planes[0] + y * strides[0]);
        const float *blue_row = (const float *)(planes[1] + y * strides[1]);
        const float *red_row = (const float *)(planes[2] + y * strides[2]);
        uint16_t *row = (uint16_t *)(half_pixels + y * stride);
        for (x = 0; x < decoded->width; x++) {
            double red;
            double green;
            double blue;
            if (transfer == AVCOL_TRC_ARIB_STD_B67) {
                /* BT.2100 HLG OOTF for a 1000-nit display, where 75% signal is 203 nits. */
                double scene_red = ffplaykmp_hlg_scene(red_row[x]);
                double scene_green = ffplaykmp_hlg_scene(green_row[x]);
                double scene_blue = ffplaykmp_hlg_scene(blue_row[x]);
                double luminance = 0.2627 * scene_red + 0.6780 * scene_green + 0.0593 * scene_blue;
                double gain = luminance > 0.0
                        ? 1000.0 * pow(luminance, 0.2) / FFPLAYKMP_REFERENCE_WHITE_NITS
                        : 0.0;
                red = scene_red * gain;
                green = scene_green * gain;
                blue = scene_blue * gain;
            } else {
                red = ffplaykmp_linear_light(red_row[x], transfer);
                green = ffplaykmp_linear_light(green_row[x], transfer);
                blue = ffplaykmp_linear_light(blue_row[x], transfer);
            }
            if (decoded->color_primaries == AVCOL_PRI_BT2020)
                ffplaykmp_bt2020_to_bt709(&red, &green, &blue);
            else if (decoded->color_primaries == AVCOL_PRI_SMPTE432)
                ffplaykmp_display_p3_to_bt709(&red, &green, &blue);
            row[x * 4 + 0] = ffplaykmp_half((float)red);
            row[x * 4 + 1] = ffplaykmp_half((float)green);
            row[x * 4 + 2] = ffplaykmp_half((float)blue);
            row[x * 4 + 3] = 0x3c00u;
        }
    }
    output->pixels = half_pixels;
    output->size = size;
    output->width = decoded->width;
    output->height = decoded->height;
    output->stride = stride;
    return 0;
}

void ffplaykmp_converter_free(ffplaykmp_converter *converter) {
    sws_freeContext(converter->rgba_scaler);
    sws_freeContext(converter->float_scaler);
    av_freep(&converter->rgba_buffer);
    av_freep(&converter->float_buffer);
    av_freep(&converter->half_buffer);
    memset(converter, 0, sizeof(*converter));
}

int ffplaykmp_download_frame(AVFrame *software, const AVFrame *hardware) {
    int result = av_hwframe_transfer_data(software, hardware, 0);
    return result < 0 ? result : av_frame_copy_props(software, hardware);
}
