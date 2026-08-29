// SPDX-License-Identifier: LGPL-2.1-or-later
#include "ffplaykmp_player.h"

#include <errno.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include <libavcodec/avcodec.h>
#if defined(__ANDROID__)
#include <libavcodec/jni.h>
#include <libavcodec/mediacodec.h>
#endif
#include <libavformat/avformat.h>
#include <libavutil/display.h>
#include <libavutil/hwcontext.h>
#include <libavutil/imgutils.h>
#include <libavutil/mastering_display_metadata.h>
#include <libavutil/pixdesc.h>
#include <libavutil/time.h>
#include <libswscale/swscale.h>

typedef struct ffplaykmp_avio {
    struct ffplaykmp_player *player;
    int64_t resource_id;
    int64_t position;
} ffplaykmp_avio;

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
    pthread_t worker;
    atomic_bool cancelled;
    atomic_bool worker_abort;
    int worker_running;
    int play_when_ready;
    int has_output;
#if defined(__ANDROID__)
    JavaVM *android_vm;
    jobject android_surface;
#endif
};

static void ffplaykmp_publish(ffplaykmp_player *player);

static int ffplaykmp_decode_frames(
        ffplaykmp_player *player,
        const char *input,
        int64_t start_position_us,
        int continuous);

static int ffplaykmp_inspect_source(ffplaykmp_player *player, const char *input);

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

static void ffplaykmp_reset_video_metadata(ffplaykmp_snapshot *snapshot) {
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

static void ffplaykmp_read_stream_metadata(
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

static void ffplaykmp_read_frame_metadata(
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

static int ffplaykmp_is_aborted(const ffplaykmp_player *player) {
    return atomic_load(&player->cancelled) || atomic_load(&player->worker_abort);
}

static int ffplaykmp_interrupt(void *opaque) {
    const ffplaykmp_player *player = opaque;
    return player ? ffplaykmp_is_aborted(player) : 1;
}

#if defined(__ANDROID__)
static JNIEnv *ffplaykmp_android_env(ffplaykmp_player *player, int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    if (!player->android_vm)
        return NULL;
    if ((*player->android_vm)->GetEnv(
            player->android_vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK)
        return env;
    if ((*player->android_vm)->AttachCurrentThread(
            player->android_vm, &env, NULL) != JNI_OK)
        return NULL;
    *attached = 1;
    return env;
}

static void ffplaykmp_release_android_surface(ffplaykmp_player *player) {
    int attached;
    JNIEnv *env;
    jobject surface;
    pthread_mutex_lock(&player->mutex);
    surface = player->android_surface;
    player->android_surface = NULL;
    pthread_mutex_unlock(&player->mutex);
    if (!surface)
        return;
    env = ffplaykmp_android_env(player, &attached);
    if (env)
        (*env)->DeleteGlobalRef(env, surface);
    if (attached)
        (*player->android_vm)->DetachCurrentThread(player->android_vm);
}
#endif

static int ffplaykmp_parse_resource_id(const char *input, int64_t *resource_id) {
    const char prefix[] = "ffmpegkmp:";
    char *end = NULL;
    long long parsed;
    if (strncmp(input, prefix, sizeof(prefix) - 1) != 0)
        return 0;
    parsed = strtoll(input + sizeof(prefix) - 1, &end, 10);
    if (parsed <= 0 || end == input + sizeof(prefix) - 1)
        return -EINVAL;
    *resource_id = (int64_t)parsed;
    return 1;
}

static int ffplaykmp_avio_read(void *opaque, uint8_t *buffer, int size) {
    ffplaykmp_avio *io = opaque;
    if (ffplaykmp_is_aborted(io->player))
        return AVERROR_EXIT;
    int64_t result = io->player->io_callback(
            io->player->io_opaque,
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
        return io->player->io_callback(
                io->player->io_opaque,
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
        size = io->player->io_callback(
                io->player->io_opaque,
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

static float ffplaykmp_clamp_unit(double value) {
    if (!isfinite(value) || value <= 0.0)
        return 0.0f;
    if (value >= 1.0)
        return 1.0f;
    return (float)value;
}

static double ffplaykmp_hdr_eotf(double value, enum AVColorTransferCharacteristic transfer) {
    value = ffplaykmp_clamp_unit(value);
    if (transfer == AVCOL_TRC_SMPTE2084) {
        const double m1 = 2610.0 / 16384.0;
        const double m2 = 2523.0 / 32.0;
        const double c1 = 3424.0 / 4096.0;
        const double c2 = 2413.0 / 128.0;
        const double c3 = 2392.0 / 128.0;
        double signal = pow(value, 1.0 / m2);
        double numerator = signal > c1 ? signal - c1 : 0.0;
        double denominator = c2 - c3 * signal;
        if (denominator <= 0.0)
            return 100.0;
        /* ST 2084 is normalized to 10,000 nits. Express it relative to a
         * 100-nit SDR reference white before applying the shoulder. */
        return pow(numerator / denominator, 1.0 / m1) * 100.0;
    }
    if (transfer == AVCOL_TRC_ARIB_STD_B67) {
        const double a = 0.17883277;
        const double b = 0.28466892;
        const double c = 0.55991073;
        double scene = value <= 0.5
                ? value * value / 3.0
                : (exp((value - c) / a) + b) / 12.0;
        /* Nominal HLG peak is twelve times diffuse scene white. */
        return scene * 12.0;
    }
    return value;
}

static double ffplaykmp_srgb_oetf(double value) {
    value = ffplaykmp_clamp_unit(value);
    return value <= 0.0031308
            ? 12.92 * value
            : 1.055 * pow(value, 1.0 / 2.4) - 0.055;
}

static void ffplaykmp_bt2020_to_bt709(double *red, double *green, double *blue) {
    double source_red = *red;
    double source_green = *green;
    double source_blue = *blue;
    *red = 1.660491 * source_red - 0.587641 * source_green - 0.072850 * source_blue;
    *green = -0.124550 * source_red + 1.132900 * source_green - 0.008349 * source_blue;
    *blue = -0.018151 * source_red - 0.100579 * source_green + 1.118730 * source_blue;
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

static int ffplaykmp_tone_map_hdr_frame(
        const AVFrame *decoded,
        uint8_t *rgba,
        int rgba_stride) {
    struct SwsContext *scaler = NULL;
    uint8_t *float_pixels = NULL;
    uint8_t *planes[4] = { NULL };
    int strides[4] = { 0 };
    int float_size;
    int x;
    int y;
    int result = AVERROR(EINVAL);
    enum AVColorTransferCharacteristic transfer = decoded->color_trc;
    if (transfer != AVCOL_TRC_SMPTE2084 && transfer != AVCOL_TRC_ARIB_STD_B67)
        return AVERROR(ENOSYS);
    float_size = av_image_get_buffer_size(
            AV_PIX_FMT_GBRPF32LE, decoded->width, decoded->height, 1);
    if (float_size <= 0)
        return AVERROR(EINVAL);
    float_pixels = av_malloc((size_t)float_size);
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
        goto cleanup;
    scaler = sws_getContext(
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
    if (!scaler)
        goto cleanup;
    ffplaykmp_configure_scaler_colors(scaler, decoded);
    if (sws_scale(
            scaler,
            (const uint8_t *const *)decoded->data,
            decoded->linesize,
            0,
            decoded->height,
            planes,
            strides) != decoded->height)
        goto cleanup;
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
    result = 0;
cleanup:
    sws_freeContext(scaler);
    av_free(float_pixels);
    return result;
}

static void ffplaykmp_emit_video_frame(
        ffplaykmp_player *player,
        const AVFrame *decoded,
        AVRational time_base) {
    struct SwsContext *scaler = NULL;
    ffplaykmp_video_frame frame;
    uint8_t *rgba = NULL;
    uint8_t *planes[4] = { NULL };
    int strides[4] = { 0 };
    int size;
    ffplaykmp_video_frame_callback callback;
    void *callback_opaque;
    uint32_t queue_serial;
    int can_upload;
    int tone_map_hdr;
    pthread_mutex_lock(&player->mutex);
    callback = player->video_frame_callback;
    callback_opaque = player->video_frame_opaque;
    queue_serial = player->snapshot.queue_serial;
    can_upload = player->has_output &&
            (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD) &&
            !(player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH);
    tone_map_hdr =
            (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_TONE_MAP_HDR_TO_SDR) != 0;
    pthread_mutex_unlock(&player->mutex);
    if (!callback || !can_upload || ffplaykmp_is_aborted(player))
        return;
    size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, decoded->width, decoded->height, 1);
    if (size <= 0)
        return;
    rgba = av_malloc((size_t)size);
    if (!rgba)
        return;
    if (av_image_fill_arrays(
            planes, strides, rgba, AV_PIX_FMT_RGBA, decoded->width, decoded->height, 1) < 0)
        goto cleanup;
    if (tone_map_hdr && ffplaykmp_tone_map_hdr_frame(decoded, rgba, strides[0]) == 0)
        goto emit;
    scaler = sws_getContext(
            decoded->width,
            decoded->height,
            decoded->format,
            decoded->width,
            decoded->height,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            NULL,
            NULL,
            NULL);
    if (!scaler)
        goto cleanup;
    ffplaykmp_configure_scaler_colors(scaler, decoded);
    if (sws_scale(
            scaler,
            (const uint8_t *const *)decoded->data,
            decoded->linesize,
            0,
            decoded->height,
            planes,
            strides) != decoded->height)
        goto cleanup;
emit:
    memset(&frame, 0, sizeof(frame));
    frame.size = sizeof(frame);
    frame.rgba = rgba;
    frame.rgba_size = (uint64_t)size;
    frame.width = decoded->width;
    frame.height = decoded->height;
    frame.stride = strides[0];
    frame.presentation_time_us = decoded->best_effort_timestamp == AV_NOPTS_VALUE
            ? 0
            : av_rescale_q(decoded->best_effort_timestamp, time_base, AV_TIME_BASE_Q);
    frame.queue_serial = queue_serial;
    callback(callback_opaque, &frame);
cleanup:
    sws_freeContext(scaler);
    av_free(rgba);
}

static int ffplaykmp_is_hardware_frame(const AVFrame *frame) {
    const AVPixFmtDescriptor *descriptor;
    if (frame->hw_frames_ctx)
        return 1;
    descriptor = av_pix_fmt_desc_get(frame->format);
    return descriptor && (descriptor->flags & AV_PIX_FMT_FLAG_HWACCEL);
}

#if !defined(__ANDROID__)
typedef struct ffplaykmp_hardware_selection {
    enum AVPixelFormat pixel_format;
} ffplaykmp_hardware_selection;

static enum AVPixelFormat ffplaykmp_hardware_format(
        AVCodecContext *context,
        const enum AVPixelFormat *formats) {
    const ffplaykmp_hardware_selection *selection = context->opaque;
    const enum AVPixelFormat *format;
    if (!selection)
        return AV_PIX_FMT_NONE;
    for (format = formats; *format != AV_PIX_FMT_NONE; format++) {
        if (*format == selection->pixel_format)
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

static int ffplaykmp_emit_downloaded_video_frame(
        ffplaykmp_player *player,
        const AVFrame *hardware_frame,
        AVRational time_base) {
    AVFrame *software_frame;
    int can_download;
    int result;
    pthread_mutex_lock(&player->mutex);
    can_download = player->has_output &&
            (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD) &&
            !(player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH);
    pthread_mutex_unlock(&player->mutex);
    if (!can_download)
        return AVERROR(ENOSYS);
    software_frame = av_frame_alloc();
    if (!software_frame)
        return AVERROR(ENOMEM);
    result = av_hwframe_transfer_data(software_frame, hardware_frame, 0);
    if (result >= 0)
        result = av_frame_copy_props(software_frame, hardware_frame);
    if (result >= 0)
        ffplaykmp_emit_video_frame(player, software_frame, time_base);
    av_frame_free(&software_frame);
    return result;
}
#endif

static int ffplaykmp_wait_until(
        ffplaykmp_player *player,
        int64_t presentation_time_us,
        int64_t drop_threshold_us,
        int64_t *clock_origin_us) {
    int64_t now;
    int64_t remaining;
    if (*clock_origin_us == AV_NOPTS_VALUE)
        *clock_origin_us = av_gettime_relative() - presentation_time_us;
    while (!ffplaykmp_is_aborted(player)) {
        now = av_gettime_relative();
        remaining = *clock_origin_us + presentation_time_us - now;
        if (remaining <= 0)
            return -remaining > drop_threshold_us ? 1 : 0;
        av_usleep((unsigned int)(remaining > 10000 ? 10000 : remaining));
    }
    return AVERROR_EXIT;
}

static int ffplaykmp_present_decoded_frame(
        ffplaykmp_player *player,
        const AVFrame *frame,
        AVRational time_base,
        int64_t stream_start_time_us,
        int64_t start_position_us,
        int continuous,
        int64_t *clock_origin_us) {
    int64_t presentation_time_us = frame->best_effort_timestamp == AV_NOPTS_VALUE
            ? start_position_us
            : av_rescale_q(frame->best_effort_timestamp, time_base, AV_TIME_BASE_Q) -
                    stream_start_time_us;
    int64_t duration_us = frame->duration > 0
            ? av_rescale_q(frame->duration, time_base, AV_TIME_BASE_Q)
            : 40000;
    int schedule_result;
    if (presentation_time_us < start_position_us)
        return 0;
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
        schedule_result = ffplaykmp_emit_platform_video_frame(
                player, frame, presentation_time_us);
        if (schedule_result == AVERROR(ENOSYS))
            schedule_result = ffplaykmp_emit_downloaded_video_frame(player, frame, time_base);
        if (schedule_result < 0 && schedule_result != AVERROR(EAGAIN))
            return schedule_result;
    } else
#else
    if (ffplaykmp_is_hardware_frame(frame)) {
        schedule_result = ffplaykmp_emit_downloaded_video_frame(player, frame, time_base);
        if (schedule_result < 0)
            return schedule_result;
    } else
#endif
    {
        ffplaykmp_emit_video_frame(player, frame, time_base);
    }
    if (!continuous)
        return 1;
    pthread_mutex_lock(&player->mutex);
    player->snapshot.position_us = presentation_time_us;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    return 0;
}

static int ffplaykmp_decode_frames(
        ffplaykmp_player *player,
        const char *input,
        int64_t start_position_us,
        int continuous) {
    AVFormatContext *format = NULL;
    AVCodecContext *decoder = NULL;
    const AVCodec *codec;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    AVIOContext *avio = NULL;
    ffplaykmp_avio *io = NULL;
    uint8_t *avio_buffer = NULL;
    int64_t resource_id = 0;
    int resource_opened = 0;
    int video_stream;
    int result;
    int presented;
    int decoded_frames = 0;
    int retry_software = 0;
    int64_t clock_origin_us = AV_NOPTS_VALUE;
    int64_t stream_start_time_us = 0;
    int hardware_requested = 0;
    int hardware_active = 0;
#if defined(__ANDROID__)
    const AVCodec *software_codec = NULL;
    AVMediaCodecContext *mediacodec_context = NULL;
    jobject android_surface = NULL;
#else
    const AVCodec *software_codec = NULL;
    AVBufferRef *hardware_device = NULL;
    enum AVHWDeviceType hardware_device_type = AV_HWDEVICE_TYPE_NONE;
    ffplaykmp_hardware_selection hardware_selection = { AV_PIX_FMT_NONE };
#endif
    int mounted = ffplaykmp_parse_resource_id(input, &resource_id);
    if (mounted < 0)
        return mounted;
    format = avformat_alloc_context();
    if (!format)
        return AVERROR(ENOMEM);
    format->interrupt_callback.callback = ffplaykmp_interrupt;
    format->interrupt_callback.opaque = player;
    if (mounted) {
        int64_t capabilities;
        if (!player->io_callback) {
            result = AVERROR(ENOSYS);
            goto cleanup;
        }
        capabilities = player->io_callback(
                player->io_opaque, resource_id, FFPLAYKMP_IO_OPEN, 1, NULL, 0);
        if (capabilities < 0) {
            result = AVERROR(EIO);
            goto cleanup;
        }
        resource_opened = 1;
        io = av_mallocz(sizeof(*io));
        avio_buffer = av_malloc(32768);
        if (!io || !avio_buffer) {
            result = AVERROR(ENOMEM);
            goto cleanup;
        }
        io->player = player;
        io->resource_id = resource_id;
        avio = avio_alloc_context(
                avio_buffer, 32768, 0, io, ffplaykmp_avio_read, NULL,
                (capabilities & 4) ? ffplaykmp_avio_seek : NULL);
        if (!avio) {
            result = AVERROR(ENOMEM);
            goto cleanup;
        }
        avio_buffer = NULL;
        format->pb = avio;
        format->flags |= AVFMT_FLAG_CUSTOM_IO;
        result = avformat_open_input(&format, NULL, NULL, NULL);
    } else {
        result = avformat_open_input(&format, input, NULL, NULL);
    }
    if (result < 0)
        goto cleanup;
    result = avformat_find_stream_info(format, NULL);
    if (result < 0)
        goto cleanup;
    video_stream = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0);
    if (video_stream < 0) {
        result = video_stream;
        goto cleanup;
    }
    if (format->streams[video_stream]->start_time != AV_NOPTS_VALUE) {
        stream_start_time_us = av_rescale_q(
                format->streams[video_stream]->start_time,
                format->streams[video_stream]->time_base,
                AV_TIME_BASE_Q);
    }
#if defined(__ANDROID__)
    software_codec = codec;
    pthread_mutex_lock(&player->mutex);
    hardware_requested = player->configuration.decoder_preference != FFPLAYKMP_DECODER_SOFTWARE &&
            player->has_output &&
            (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT) &&
            player->android_surface;
    android_surface = player->android_surface;
    pthread_mutex_unlock(&player->mutex);
    if (hardware_requested) {
        char decoder_name[96];
        const char *software_name = codec->name;
        if (snprintf(decoder_name, sizeof(decoder_name), "%s_mediacodec", software_name) > 0) {
            const AVCodec *hardware_codec = avcodec_find_decoder_by_name(decoder_name);
            if (hardware_codec)
                codec = hardware_codec;
        }
        hardware_active = strstr(codec->name, "_mediacodec") != NULL;
        if (!hardware_active &&
                player->configuration.decoder_preference == FFPLAYKMP_DECODER_REQUIRE_HARDWARE) {
            result = AVERROR(ENOTSUP);
            goto cleanup;
        }
    }
#else
    software_codec = codec;
    pthread_mutex_lock(&player->mutex);
    hardware_requested = player->configuration.decoder_preference != FFPLAYKMP_DECODER_SOFTWARE &&
            player->has_output &&
            (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD
#if defined(__APPLE__)
                    || (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT)
#endif
            );
    pthread_mutex_unlock(&player->mutex);
    if (hardware_requested) {
        hardware_active = ffplaykmp_select_hardware_config(
                codec, &hardware_device_type, &hardware_selection.pixel_format) >= 0;
        if (!hardware_active &&
                player->configuration.decoder_preference == FFPLAYKMP_DECODER_REQUIRE_HARDWARE) {
            result = AVERROR(ENOTSUP);
            goto cleanup;
        }
    }
#endif
    decoder = avcodec_alloc_context3(codec);
    if (!decoder) {
        result = AVERROR(ENOMEM);
        goto cleanup;
    }
    result = avcodec_parameters_to_context(decoder, format->streams[video_stream]->codecpar);
    if (result < 0)
        goto cleanup;
#if defined(__ANDROID__)
    if (hardware_active) {
        mediacodec_context = av_mediacodec_alloc_context();
        if (!mediacodec_context) {
            result = AVERROR(ENOMEM);
            goto cleanup;
        }
        result = av_mediacodec_default_init(decoder, mediacodec_context, android_surface);
        if (result < 0 &&
                player->configuration.decoder_preference == FFPLAYKMP_DECODER_AUTO) {
            av_freep(&mediacodec_context);
            avcodec_free_context(&decoder);
            codec = software_codec;
            hardware_active = 0;
            decoder = avcodec_alloc_context3(codec);
            if (!decoder) {
                result = AVERROR(ENOMEM);
                goto cleanup;
            }
            result = avcodec_parameters_to_context(
                    decoder, format->streams[video_stream]->codecpar);
        }
        if (result < 0)
            goto cleanup;
    }
#else
    if (hardware_active) {
        result = av_hwdevice_ctx_create(
                &hardware_device, hardware_device_type, NULL, NULL, 0);
        if (result >= 0) {
            decoder->hw_device_ctx = av_buffer_ref(hardware_device);
            if (!decoder->hw_device_ctx)
                result = AVERROR(ENOMEM);
            decoder->opaque = &hardware_selection;
            decoder->get_format = ffplaykmp_hardware_format;
        }
        if (result < 0 &&
                player->configuration.decoder_preference == FFPLAYKMP_DECODER_AUTO) {
            av_buffer_unref(&hardware_device);
            avcodec_free_context(&decoder);
            codec = software_codec;
            hardware_active = 0;
            decoder = avcodec_alloc_context3(codec);
            if (!decoder) {
                result = AVERROR(ENOMEM);
                goto cleanup;
            }
            result = avcodec_parameters_to_context(
                    decoder, format->streams[video_stream]->codecpar);
        }
        if (result < 0)
            goto cleanup;
    }
#endif
    result = avcodec_open2(decoder, codec, NULL);
#if defined(__ANDROID__)
    if (result < 0 && hardware_active &&
            player->configuration.decoder_preference == FFPLAYKMP_DECODER_AUTO) {
        av_mediacodec_default_free(decoder);
        mediacodec_context = NULL;
        avcodec_free_context(&decoder);
        codec = software_codec;
        hardware_active = 0;
        decoder = avcodec_alloc_context3(codec);
        if (!decoder) {
            result = AVERROR(ENOMEM);
            goto cleanup;
        }
        result = avcodec_parameters_to_context(
                decoder, format->streams[video_stream]->codecpar);
        if (result >= 0)
            result = avcodec_open2(decoder, codec, NULL);
    }
#else
    if (result < 0 && hardware_active &&
            player->configuration.decoder_preference == FFPLAYKMP_DECODER_AUTO) {
        av_buffer_unref(&hardware_device);
        avcodec_free_context(&decoder);
        codec = software_codec;
        hardware_active = 0;
        decoder = avcodec_alloc_context3(codec);
        if (!decoder) {
            result = AVERROR(ENOMEM);
            goto cleanup;
        }
        result = avcodec_parameters_to_context(
                decoder, format->streams[video_stream]->codecpar);
        if (result >= 0)
            result = avcodec_open2(decoder, codec, NULL);
    }
#endif
    if (result < 0)
        goto cleanup;
    pthread_mutex_lock(&player->mutex);
    // A configured device is not proof that a platform decoder produced a hardware frame.
    // Publish the active decoder only after the first decoded frame crosses this boundary.
    player->snapshot.active_decoder = FFPLAYKMP_DECODER_UNKNOWN;
    pthread_mutex_unlock(&player->mutex);
    if (!continuous) {
        pthread_mutex_lock(&player->mutex);
        player->snapshot.video_width = decoder->width;
        player->snapshot.video_height = decoder->height;
        player->snapshot.duration_us = format->duration == AV_NOPTS_VALUE
                ? -1
                : format->duration;
        pthread_mutex_unlock(&player->mutex);
    }
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    if (!packet || !frame) {
        result = AVERROR(ENOMEM);
        goto cleanup;
    }
    if (start_position_us > 0) {
        int64_t target = av_rescale_q(
                start_position_us,
                AV_TIME_BASE_Q,
                format->streams[video_stream]->time_base);
        if (format->streams[video_stream]->start_time != AV_NOPTS_VALUE)
            target += format->streams[video_stream]->start_time;
        result = avformat_seek_file(
                format, video_stream, INT64_MIN, target, INT64_MAX, AVSEEK_FLAG_BACKWARD);
        if (result < 0)
            goto cleanup;
        avcodec_flush_buffers(decoder);
    }
    while (!ffplaykmp_is_aborted(player) && (result = av_read_frame(format, packet)) >= 0) {
        if (packet->stream_index == video_stream) {
            result = avcodec_send_packet(decoder, packet);
            if (result < 0 && result != AVERROR(EAGAIN)) {
                // Match ffplay's resilience: a damaged packet is dropped without
                // terminating the whole player when later packets may recover.
                av_packet_unref(packet);
                continue;
            }
            while ((result = avcodec_receive_frame(decoder, frame)) >= 0) {
                int frame_is_hardware = ffplaykmp_is_hardware_frame(frame);
                decoded_frames++;
                if (hardware_active && !frame_is_hardware &&
                        player->configuration.decoder_preference ==
                                FFPLAYKMP_DECODER_REQUIRE_HARDWARE) {
                    result = AVERROR(ENOTSUP);
                    av_frame_unref(frame);
                    goto cleanup;
                }
                pthread_mutex_lock(&player->mutex);
                player->snapshot.active_decoder = frame_is_hardware
                        ? FFPLAYKMP_DECODER_HARDWARE
                        : FFPLAYKMP_DECODER_SOFTWARE_ACTIVE;
                pthread_mutex_unlock(&player->mutex);
                presented = ffplaykmp_present_decoded_frame(
                        player,
                        frame,
                        format->streams[video_stream]->time_base,
                        stream_start_time_us,
                        start_position_us,
                        continuous,
                        &clock_origin_us);
                av_frame_unref(frame);
                if (presented < 0)
                    goto cleanup;
                if (presented > 0) {
                    result = 0;
                    av_packet_unref(packet);
                    goto cleanup;
                }
            }
            if (result != AVERROR(EAGAIN) && result != AVERROR_EOF)
                avcodec_flush_buffers(decoder);
        }
        av_packet_unref(packet);
    }
    if (ffplaykmp_is_aborted(player)) {
        result = AVERROR_EXIT;
        goto cleanup;
    }
    if (result == AVERROR_EOF) {
        result = avcodec_send_packet(decoder, NULL);
        if (result < 0 && result != AVERROR_EOF)
            goto cleanup;
        while ((result = avcodec_receive_frame(decoder, frame)) >= 0) {
            int frame_is_hardware = ffplaykmp_is_hardware_frame(frame);
            decoded_frames++;
            if (hardware_active && !frame_is_hardware &&
                    player->configuration.decoder_preference ==
                            FFPLAYKMP_DECODER_REQUIRE_HARDWARE) {
                result = AVERROR(ENOTSUP);
                av_frame_unref(frame);
                goto cleanup;
            }
            pthread_mutex_lock(&player->mutex);
            player->snapshot.active_decoder = frame_is_hardware
                    ? FFPLAYKMP_DECODER_HARDWARE
                    : FFPLAYKMP_DECODER_SOFTWARE_ACTIVE;
            pthread_mutex_unlock(&player->mutex);
            presented = ffplaykmp_present_decoded_frame(
                    player,
                    frame,
                    format->streams[video_stream]->time_base,
                    stream_start_time_us,
                    start_position_us,
                    continuous,
                    &clock_origin_us);
            av_frame_unref(frame);
            if (presented < 0)
                goto cleanup;
            if (presented > 0) {
                result = 0;
                goto cleanup;
            }
        }
        if (result == AVERROR_EOF || result == AVERROR(EAGAIN))
            result = 0;
    }
    if (result >= 0 && decoded_frames == 0)
        result = AVERROR_INVALIDDATA;
cleanup:
    retry_software = result < 0 && hardware_active && decoded_frames == 0 &&
            !ffplaykmp_is_aborted(player) &&
            player->configuration.decoder_preference == FFPLAYKMP_DECODER_AUTO;
    av_packet_free(&packet);
    av_frame_free(&frame);
#if defined(__ANDROID__)
    if (decoder && mediacodec_context)
        av_mediacodec_default_free(decoder);
#else
    av_buffer_unref(&hardware_device);
#endif
    avcodec_free_context(&decoder);
    avformat_close_input(&format);
    if (avio) {
        av_freep(&avio->buffer);
        avio_context_free(&avio);
    }
    av_free(avio_buffer);
    if (resource_opened && player->io_callback)
        player->io_callback(player->io_opaque, resource_id, FFPLAYKMP_IO_CLOSE, 0, NULL, 0);
    av_free(io);
    if (retry_software) {
        int original_preference = player->configuration.decoder_preference;
        player->configuration.decoder_preference = FFPLAYKMP_DECODER_SOFTWARE;
        result = ffplaykmp_decode_frames(player, input, start_position_us, continuous);
        player->configuration.decoder_preference = original_preference;
    }
    return result;
}

/*
 * Opens only the container and stream headers. This keeps prepare independent
 * from an output target and, importantly, never crosses the decoded-pixel
 * boundary for protected sources.
 */
static int ffplaykmp_inspect_source(ffplaykmp_player *player, const char *input) {
    AVFormatContext *format = NULL;
    AVIOContext *avio = NULL;
    ffplaykmp_avio *io = NULL;
    uint8_t *avio_buffer = NULL;
    int64_t resource_id = 0;
    int resource_opened = 0;
    int mounted = ffplaykmp_parse_resource_id(input, &resource_id);
    int video_stream;
    int result;
    if (mounted < 0)
        return mounted;
    format = avformat_alloc_context();
    if (!format)
        return AVERROR(ENOMEM);
    format->interrupt_callback.callback = ffplaykmp_interrupt;
    format->interrupt_callback.opaque = player;
    if (mounted) {
        int64_t capabilities;
        if (!player->io_callback) {
            result = AVERROR(ENOSYS);
            goto cleanup;
        }
        capabilities = player->io_callback(
                player->io_opaque, resource_id, FFPLAYKMP_IO_OPEN, 1, NULL, 0);
        if (capabilities < 0) {
            result = AVERROR(EIO);
            goto cleanup;
        }
        resource_opened = 1;
        io = av_mallocz(sizeof(*io));
        avio_buffer = av_malloc(32768);
        if (!io || !avio_buffer) {
            result = AVERROR(ENOMEM);
            goto cleanup;
        }
        io->player = player;
        io->resource_id = resource_id;
        avio = avio_alloc_context(
                avio_buffer, 32768, 0, io, ffplaykmp_avio_read, NULL,
                (capabilities & 4) ? ffplaykmp_avio_seek : NULL);
        if (!avio) {
            result = AVERROR(ENOMEM);
            goto cleanup;
        }
        avio_buffer = NULL;
        format->pb = avio;
        format->flags |= AVFMT_FLAG_CUSTOM_IO;
        result = avformat_open_input(&format, NULL, NULL, NULL);
    } else {
        result = avformat_open_input(&format, input, NULL, NULL);
    }
    if (result < 0)
        goto cleanup;
    result = avformat_find_stream_info(format, NULL);
    if (result < 0)
        goto cleanup;
    video_stream = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (video_stream < 0) {
        result = video_stream;
        goto cleanup;
    }
    pthread_mutex_lock(&player->mutex);
    player->snapshot.video_width = format->streams[video_stream]->codecpar->width;
    player->snapshot.video_height = format->streams[video_stream]->codecpar->height;
    ffplaykmp_read_stream_metadata(&player->snapshot, format->streams[video_stream]);
    player->snapshot.duration_us = format->duration == AV_NOPTS_VALUE
            ? -1
            : format->duration;
    pthread_mutex_unlock(&player->mutex);
    result = 0;
cleanup:
    avformat_close_input(&format);
    if (avio) {
        av_freep(&avio->buffer);
        avio_context_free(&avio);
    }
    av_free(avio_buffer);
    if (resource_opened && player->io_callback)
        player->io_callback(player->io_opaque, resource_id, FFPLAYKMP_IO_CLOSE, 0, NULL, 0);
    av_free(io);
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

static void *ffplaykmp_playback_worker(void *opaque) {
    ffplaykmp_player *player = opaque;
    const char *input;
    int64_t start_position_us;
    int result;
    pthread_mutex_lock(&player->mutex);
    input = player->input;
    start_position_us = player->snapshot.position_us;
    pthread_mutex_unlock(&player->mutex);
    result = ffplaykmp_decode_frames(player, input, start_position_us, 1);
    if (ffplaykmp_is_aborted(player))
        return NULL;
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
    return NULL;
}

static void ffplaykmp_stop_worker(ffplaykmp_player *player) {
    pthread_t worker;
    int should_join = 0;
    pthread_mutex_lock(&player->mutex);
    if (player->worker_running) {
        atomic_store(&player->worker_abort, 1);
        worker = player->worker;
        player->worker_running = 0;
        should_join = 1;
    }
    pthread_mutex_unlock(&player->mutex);
    if (should_join) {
        pthread_join(worker, NULL);
        atomic_store(&player->worker_abort, 0);
    }
}

static int ffplaykmp_start_worker(ffplaykmp_player *player) {
    int result;
    ffplaykmp_stop_worker(player);
    atomic_store(&player->worker_abort, 0);
    pthread_mutex_lock(&player->mutex);
    result = pthread_create(&player->worker, NULL, ffplaykmp_playback_worker, player);
    if (result == 0)
        player->worker_running = 1;
    pthread_mutex_unlock(&player->mutex);
    return result == 0 ? 0 : -result;
}

static int ffplaykmp_require_prepared(ffplaykmp_player *player) {
    if (!player)
        return -EINVAL;
    if (!player->input)
        return -EPERM;
    return 0;
}

static int ffplaykmp_validate_output(
        const ffplaykmp_player *player,
        uint32_t output_flags) {
    if ((player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) &&
            !(output_flags & FFPLAYKMP_OUTPUT_PROTECTED_CONTENT))
        return -EACCES;
    if (player->configuration.decoder_preference == FFPLAYKMP_DECODER_REQUIRE_HARDWARE &&
#if defined(__APPLE__)
            !(output_flags & (FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT |
                    FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD)))
#else
            !(output_flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT))
#endif
        return -ENOTSUP;
    if (!(output_flags & (FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT |
            FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD)))
        return -ENOTSUP;
    return 0;
}

ffplaykmp_player *ffplaykmp_player_create(
        const ffplaykmp_configuration *configuration,
        ffplaykmp_state_callback callback,
        void *opaque) {
    ffplaykmp_player *player = calloc(1, sizeof(*player));
    if (!player)
        return NULL;
    if (pthread_mutex_init(&player->mutex, NULL) != 0) {
        free(player);
        return NULL;
    }
    ffplaykmp_configuration_default(&player->configuration);
    if (configuration) {
        if (configuration->size < sizeof(*configuration)) {
            pthread_mutex_destroy(&player->mutex);
            free(player);
            return NULL;
        }
        player->configuration = *configuration;
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
        return -EINVAL;
    (void)owner;
    /* A secure Surface alone is not a DRM session. Never claim a protected
     * path until MediaCrypto/secure-input integration is supplied. */
    if (secure)
        return -ENOTSUP;
    if (surface) {
        retained = (*env)->NewGlobalRef(env, surface);
        if (!retained)
            return -ENOMEM;
        if ((*env)->GetJavaVM(env, &vm) != JNI_OK) {
            (*env)->DeleteGlobalRef(env, retained);
            return -EIO;
        }
        if (av_jni_set_java_vm(vm, NULL) < 0) {
            (*env)->DeleteGlobalRef(env, retained);
            return -EIO;
        }
    }
    ffplaykmp_stop_worker(player);
    pthread_mutex_lock(&player->mutex);
    previous = player->android_surface;
    player->android_surface = retained;
    if (vm)
        player->android_vm = vm;
    pthread_mutex_unlock(&player->mutex);
    if (previous)
        (*env)->DeleteGlobalRef(env, previous);
    return 0;
}
#endif

void ffplaykmp_player_destroy(ffplaykmp_player *player) {
    if (!player)
        return;
    atomic_store(&player->cancelled, 1);
    ffplaykmp_stop_worker(player);
#if defined(__ANDROID__)
    ffplaykmp_release_android_surface(player);
#endif
    free(player->input);
    pthread_mutex_destroy(&player->mutex);
    free(player);
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
        return -EINVAL;
    owned_input = malloc(strlen(input) + 1);
    if (!owned_input)
        return -ENOMEM;
    strcpy(owned_input, input);
    ffplaykmp_stop_worker(player);
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
    player->snapshot.last_error = 0;
    player->snapshot.queue_serial++;
    player->snapshot.state = FFPLAYKMP_STATE_PREPARING;
    has_output = player->has_output;
    output_flags = player->snapshot.output_flags;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    result = ffplaykmp_inspect_source(player, input);
    if (result < 0) {
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
    /* Publish inspected stream metadata before output negotiation decodes its preview frame. */
    ffplaykmp_publish(player);
    if (has_output) {
        result = ffplaykmp_validate_output(player, output_flags);
        if (result < 0) {
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
    }
    if (!(source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) && has_output &&
            (((output_flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD) &&
              player->configuration.decoder_preference != FFPLAYKMP_DECODER_REQUIRE_HARDWARE)
#if defined(__ANDROID__)
             || ((output_flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT) &&
                 player->configuration.decoder_preference != FFPLAYKMP_DECODER_SOFTWARE &&
                 player->android_surface)
#elif defined(__APPLE__)
             || ((output_flags & (FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT |
                                  FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD)) &&
                 player->configuration.decoder_preference != FFPLAYKMP_DECODER_SOFTWARE)
#endif
            )) {
        result = ffplaykmp_decode_frames(player, input, 0, 0);
        if (result < 0) {
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
    uint32_t source_flags;
    int64_t position_us;
    int play_when_ready;
    int software_playback;
    int native_playback;
    if (!player || !capabilities || capabilities->size < sizeof(*capabilities))
        return -EINVAL;
    ffplaykmp_stop_worker(player);
    result = ffplaykmp_validate_output(player, capabilities->flags);
    if (result < 0) {
        pthread_mutex_lock(&player->mutex);
        player->snapshot.last_error = result;
        player->snapshot.state = FFPLAYKMP_STATE_FAILED;
        pthread_mutex_unlock(&player->mutex);
        ffplaykmp_publish(player);
        return result;
    }
    pthread_mutex_lock(&player->mutex);
    player->has_output = 1;
    player->snapshot.output_flags = capabilities->flags;
    player->snapshot.active_decoder = FFPLAYKMP_DECODER_UNKNOWN;
    input = player->input;
    source_flags = player->source_flags;
    position_us = player->snapshot.position_us;
    play_when_ready = player->play_when_ready;
    software_playback = input &&
            !(source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) &&
            (capabilities->flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD) &&
            player->configuration.decoder_preference != FFPLAYKMP_DECODER_REQUIRE_HARDWARE;
    native_playback = software_playback;
#if defined(__ANDROID__)
    if (input && !(source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) &&
            (capabilities->flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT) &&
            player->configuration.decoder_preference != FFPLAYKMP_DECODER_SOFTWARE &&
            player->android_surface)
        native_playback = 1;
#elif defined(__APPLE__)
    if (input && !(source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) &&
            (capabilities->flags & (FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT |
                                    FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD)) &&
            player->configuration.decoder_preference != FFPLAYKMP_DECODER_SOFTWARE)
        native_playback = 1;
#endif
    pthread_mutex_unlock(&player->mutex);
    if (native_playback) {
        result = ffplaykmp_decode_frames(player, input, position_us, 0);
        if (result < 0) {
            pthread_mutex_lock(&player->mutex);
            player->snapshot.last_error = result;
            player->snapshot.state = FFPLAYKMP_STATE_FAILED;
            pthread_mutex_unlock(&player->mutex);
            ffplaykmp_publish(player);
            return result;
        }
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
        if (result < 0) {
            pthread_mutex_lock(&player->mutex);
            player->snapshot.last_error = result;
            player->snapshot.state = FFPLAYKMP_STATE_FAILED;
            pthread_mutex_unlock(&player->mutex);
            ffplaykmp_publish(player);
            return result;
        }
    }
    return 0;
}

void ffplaykmp_player_clear_output(ffplaykmp_player *player) {
    if (!player)
        return;
    ffplaykmp_stop_worker(player);
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
    int software_playback;
    int native_playback;
    if (result < 0)
        return result;
    ffplaykmp_stop_worker(player);
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
    software_playback = player->has_output &&
            !(player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) &&
            (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD) &&
            player->configuration.decoder_preference != FFPLAYKMP_DECODER_REQUIRE_HARDWARE;
    native_playback = software_playback;
#if defined(__ANDROID__)
    if (player->has_output &&
            !(player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) &&
            (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT) &&
            player->configuration.decoder_preference != FFPLAYKMP_DECODER_SOFTWARE &&
            player->android_surface)
        native_playback = 1;
#elif defined(__APPLE__)
    if (player->has_output &&
            !(player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) &&
            (player->snapshot.output_flags & (FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT |
                                              FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD)) &&
            player->configuration.decoder_preference != FFPLAYKMP_DECODER_SOFTWARE)
        native_playback = 1;
#endif
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    if (native_playback) {
        result = ffplaykmp_start_worker(player);
        if (result < 0) {
            pthread_mutex_lock(&player->mutex);
            player->snapshot.last_error = result;
            player->snapshot.state = FFPLAYKMP_STATE_FAILED;
            pthread_mutex_unlock(&player->mutex);
            ffplaykmp_publish(player);
            return result;
        }
    }
    return 0;
}

int ffplaykmp_player_pause(ffplaykmp_player *player) {
    int result = ffplaykmp_require_prepared(player);
    if (result < 0)
        return result;
    ffplaykmp_stop_worker(player);
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
    int software_playback;
    int native_playback;
    const char *input;
    if (result < 0)
        return result;
    if (position_us < 0)
        return -EINVAL;
    ffplaykmp_stop_worker(player);
    pthread_mutex_lock(&player->mutex);
    player->snapshot.state = FFPLAYKMP_STATE_SEEKING;
    player->snapshot.position_us = position_us;
    player->snapshot.queue_serial++;
    play_when_ready = player->play_when_ready;
    input = player->input;
    software_playback = player->has_output &&
            !(player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) &&
            (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD) &&
            player->configuration.decoder_preference != FFPLAYKMP_DECODER_REQUIRE_HARDWARE;
    native_playback = software_playback;
#if defined(__ANDROID__)
    if (player->has_output &&
            !(player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) &&
            (player->snapshot.output_flags & FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT) &&
            player->configuration.decoder_preference != FFPLAYKMP_DECODER_SOFTWARE &&
            player->android_surface)
        native_playback = 1;
#elif defined(__APPLE__)
    if (player->has_output &&
            !(player->source_flags & FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) &&
            (player->snapshot.output_flags & (FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT |
                                              FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD)) &&
            player->configuration.decoder_preference != FFPLAYKMP_DECODER_SOFTWARE)
        native_playback = 1;
#endif
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    if (native_playback) {
        result = ffplaykmp_decode_frames(player, input, position_us, 0);
        if (result < 0) {
            pthread_mutex_lock(&player->mutex);
            player->snapshot.last_error = result;
            player->snapshot.state = FFPLAYKMP_STATE_FAILED;
            pthread_mutex_unlock(&player->mutex);
            ffplaykmp_publish(player);
            return result;
        }
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
        return -EINVAL;
    ffplaykmp_stop_worker(player);
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

void ffplaykmp_player_reset_cancel(ffplaykmp_player *player) {
    if (player) {
        ffplaykmp_stop_worker(player);
        atomic_store(&player->cancelled, 0);
    }
}

int ffplaykmp_player_get_snapshot(
        const ffplaykmp_player *player,
        ffplaykmp_snapshot *snapshot) {
    if (!player || !snapshot || snapshot->size < sizeof(*snapshot))
        return -EINVAL;
    pthread_mutex_lock((pthread_mutex_t *)&player->mutex);
    *snapshot = player->snapshot;
    pthread_mutex_unlock((pthread_mutex_t *)&player->mutex);
    return 0;
}

typedef struct ffplaykmp_web_packet_reader {
    AVFormatContext *format;
    AVIOContext *avio;
    ffplaykmp_avio *io;
    uint8_t *avio_buffer;
    int resource_opened;
    int video_stream;
} ffplaykmp_web_packet_reader;

typedef struct ffplaykmp_web_callbacks {
    ffplaykmp_web_state_callback state;
    ffplaykmp_web_video_frame_callback frame;
    void *opaque;
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
    avformat_close_input(&reader->format);
    if (reader->avio) {
        av_freep(&reader->avio->buffer);
        avio_context_free(&reader->avio);
    }
    av_free(reader->avio_buffer);
    if (reader->resource_opened && player->io_callback)
        player->io_callback(player->io_opaque, 1, FFPLAYKMP_IO_CLOSE, 0, NULL, 0);
    av_free(reader->io);
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
        return AVERROR(ENOTSUP);
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
        return 1 | 4; /* read + seek */
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
            "\"colorRange\":%d,\"chromaLocation\":%d,\"hdrType\":%d,"
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
            snapshot->hdr_type,
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
    if (!callbacks || !callbacks->frame || !frame ||
            frame->rgba_size > UINT32_MAX)
        return;
    pthread_mutex_lock(&callbacks->mutex);
    if (callbacks->rgba_capacity < frame->rgba_size) {
        uint8_t *grown = realloc(callbacks->rgba, (size_t)frame->rgba_size);
        if (!grown) {
            pthread_mutex_unlock(&callbacks->mutex);
            return;
        }
        callbacks->rgba = grown;
        callbacks->rgba_capacity = (uint32_t)frame->rgba_size;
    }
    memcpy(callbacks->rgba, frame->rgba, (size_t)frame->rgba_size);
    callbacks->rgba_size = (uint32_t)frame->rgba_size;
    callbacks->width = frame->width;
    callbacks->height = frame->height;
    callbacks->stride = frame->stride;
    callbacks->presentation_time_us = frame->presentation_time_us;
    callbacks->queue_serial = frame->queue_serial;
    callbacks->frame_pending = 1;
    pthread_mutex_unlock(&callbacks->mutex);
}

ffplaykmp_player *ffplaykmp_web_player_create(
        int32_t decoder_preference,
        ffplaykmp_web_state_callback state_callback,
        ffplaykmp_web_video_frame_callback frame_callback,
        void *opaque) {
    ffplaykmp_configuration configuration;
    ffplaykmp_web_callbacks *callbacks;
    ffplaykmp_player *player;
    if (decoder_preference < FFPLAYKMP_DECODER_AUTO ||
            decoder_preference > FFPLAYKMP_DECODER_SOFTWARE)
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
    player = ffplaykmp_player_create(
            &configuration,
            ffplaykmp_web_publish,
            callbacks);
    if (!player) {
        pthread_mutex_destroy(&callbacks->mutex);
        free(callbacks);
        return NULL;
    }
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
        return -EINVAL;
    callbacks = player->opaque;
    if (!callbacks)
        return -EINVAL;
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
    int64_t capabilities;
    int result;
    if (!player || !callback)
        return -EINVAL;
    callbacks = player->opaque;
    if (!callbacks || !callbacks->input || callbacks->input_size == 0)
        return -EPERM;
    ffplaykmp_web_close_packet_reader(player);
    reader = calloc(1, sizeof(*reader));
    if (!reader)
        return AVERROR(ENOMEM);
    reader->format = avformat_alloc_context();
    reader->io = av_mallocz(sizeof(*reader->io));
    reader->avio_buffer = av_malloc(32768);
    if (!reader->format || !reader->io || !reader->avio_buffer) {
        result = AVERROR(ENOMEM);
        goto fail;
    }
    capabilities = player->io_callback(
            player->io_opaque, 1, FFPLAYKMP_IO_OPEN, 1, NULL, 0);
    if (capabilities < 0) {
        result = AVERROR(EIO);
        goto fail;
    }
    reader->resource_opened = 1;
    reader->io->player = player;
    reader->io->resource_id = 1;
    reader->avio = avio_alloc_context(
            reader->avio_buffer,
            32768,
            0,
            reader->io,
            ffplaykmp_avio_read,
            NULL,
            (capabilities & 4) ? ffplaykmp_avio_seek : NULL);
    if (!reader->avio) {
        result = AVERROR(ENOMEM);
        goto fail;
    }
    reader->avio_buffer = NULL;
    reader->format->pb = reader->avio;
    reader->format->flags |= AVFMT_FLAG_CUSTOM_IO;
    result = avformat_open_input(&reader->format, NULL, NULL, NULL);
    if (result < 0)
        goto fail;
    result = avformat_find_stream_info(reader->format, NULL);
    if (result < 0)
        goto fail;
    reader->video_stream = av_find_best_stream(
            reader->format, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (reader->video_stream < 0) {
        result = reader->video_stream;
        goto fail;
    }
    parameters = reader->format->streams[reader->video_stream]->codecpar;
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
        return -EINVAL;
    callbacks = player->opaque;
    reader = callbacks ? callbacks->packet_reader : NULL;
    if (!reader || !reader->format)
        return -EPERM;
    packet = av_packet_alloc();
    if (!packet)
        return AVERROR(ENOMEM);
    while ((result = av_read_frame(reader->format, packet)) >= 0) {
        if (packet->stream_index != reader->video_stream) {
            av_packet_unref(packet);
            continue;
        }
        stream = reader->format->streams[reader->video_stream];
        timestamp = packet->pts != AV_NOPTS_VALUE ? packet->pts : packet->dts;
        if (timestamp == AV_NOPTS_VALUE)
            timestamp = 0;
        if (stream->start_time != AV_NOPTS_VALUE)
            timestamp -= stream->start_time;
        timestamp = av_rescale_q(timestamp, stream->time_base, AV_TIME_BASE_Q);
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
                queue_serial);
        av_packet_free(&packet);
        return 0;
    }
    av_packet_free(&packet);
    return result == AVERROR_EOF ? 1 : result;
}

void ffplaykmp_web_player_close_packets(ffplaykmp_player *player) {
    ffplaykmp_web_close_packet_reader(player);
}

int ffplaykmp_web_player_set_webcodecs_output(
        ffplaykmp_player *player,
        uint32_t output_flags) {
    int result;
    if (!player)
        return -EINVAL;
    result = ffplaykmp_validate_output(player, output_flags);
    if (result < 0)
        return result;
    pthread_mutex_lock(&player->mutex);
    player->has_output = 1;
    player->snapshot.output_flags = output_flags;
    /* WebCodecs exposes an acceleration preference, not the selected decoder
     * implementation. Keep this truthful instead of labelling a preference as hardware. */
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
        return -EPERM;
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
        return -EPERM;
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
        return -EINVAL;
    callbacks = player->opaque;
    reader = callbacks ? callbacks->packet_reader : NULL;
    if (!reader || !reader->format)
        return -EPERM;
    stream = reader->format->streams[reader->video_stream];
    target = av_rescale_q(position_us, AV_TIME_BASE_Q, stream->time_base);
    if (stream->start_time != AV_NOPTS_VALUE)
        target += stream->start_time;
    pthread_mutex_lock(&player->mutex);
    play_when_ready = player->play_when_ready;
    player->snapshot.state = FFPLAYKMP_STATE_SEEKING;
    player->snapshot.position_us = position_us;
    player->snapshot.queue_serial++;
    pthread_mutex_unlock(&player->mutex);
    ffplaykmp_publish(player);
    result = avformat_seek_file(
            reader->format,
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
        return -EINVAL;
    pthread_mutex_lock(&player->mutex);
    if (queue_serial != player->snapshot.queue_serial) {
        pthread_mutex_unlock(&player->mutex);
        return -ESTALE;
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
