// SPDX-License-Identifier: LGPL-2.1-or-later
#include "ffmpegkmp_writer.h"

#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/channel_layout.h>
#include <libavutil/mastering_display_metadata.h>
#include <libavutil/mem.h>
#include <libavutil/pixdesc.h>
#include <libavutil/time.h>

#include "ffplaykmp_core.h"

#define FFMPEGKMP_WRITER_TRACKS 8
#define FFMPEGKMP_WRITER_IO_BUFFER 65536
#define FFMPEGKMP_NANOSECONDS ((AVRational){ 1, 1000000000 })
#define FFMPEGKMP_MICROSECONDS ((AVRational){ 1, 1000000 })
/* Timestamps of a variable frame rate, as MPEG-TS counts them. */
#define FFMPEGKMP_VFR_TIME_BASE ((AVRational){ 1, 90000 })
#define FFMPEGKMP_IO_CAP_WRITE 2
#define FFMPEGKMP_IO_CAP_SEEK 4

typedef struct ffmpegkmp_writer_track {
    enum AVMediaType type;
    AVStream *stream;
    AVCodecContext *encoder;
    AVRational time_base;
    AVPacket *packet;
    int hardware;
    /* A video track of packets another encoder produced: it has no encoder of its own. */
    int packets;
    int started;
    int ended;
    /* av_gettime_relative() bound of the track's running call, 0 for none; a call that reaches it fails the track. */
    int64_t deadline;
    int timed_out;
    int64_t first_pts;
    int64_t end_pts;
    /* Video. */
    ffmpegkmp_video_encoder_config config;
    const AVCodec *codec;
    /* What the encoder is opened with: AV_PIX_FMT_VIDEOTOOLBOX takes pooled CVPixelBuffers of `layout`. */
    enum AVPixelFormat pixel_format;
    enum AVPixelFormat layout;
    ffmpegkmp_frame_format input_format;
    ffmpegkmp_frame_pool *pool;
    ffmpegkmp_converter *converter;
    AVFrame *input;
    int64_t last_pts;
    /* HDR10 without metadata: opened at the first frame, with that frame's. */
    int open_at_first_frame;
    /* Audio. */
    AVAudioFifo *fifo;
    float *planar;
    int planar_frames;
    int64_t samples;
} ffmpegkmp_writer_track;

struct ffmpegkmp_writer {
    char *url;
    AVFormatContext *format;
    int container;
    int fast_start;
    int64_t timeout_us;
    int mounted;
    int64_t resource_id;
    int resource_opened;
    ffplaykmp_io_host host;
    ffplaykmp_avio *io;
    ffmpegkmp_writer_track tracks[FFMPEGKMP_WRITER_TRACKS];
    int track_count;
    /* Guards the muxer, the header and the packets that wait for it. */
    pthread_mutex_t mux;
    int header_written;
    /* Set by the first write: tracks are added before it. */
    atomic_bool writing;
    int finished;
    AVPacket **pending;
    int pending_count;
    int pending_capacity;
    atomic_bool aborted;
};

void ffmpegkmp_hdr_metadata_init(ffmpegkmp_hdr_metadata *metadata) {
    if (!metadata)
        return;
    memset(metadata, 0, sizeof(*metadata));
    metadata->size = sizeof(*metadata);
}

void ffmpegkmp_video_encoder_config_init(ffmpegkmp_video_encoder_config *config) {
    if (!config)
        return;
    memset(config, 0, sizeof(*config));
    config->size = sizeof(*config);
    config->keyframe_interval_us = 2000000;
    ffmpegkmp_hdr_metadata_init(&config->hdr_metadata);
}

void ffmpegkmp_video_track_info_init(ffmpegkmp_video_track_info *info) {
    if (!info)
        return;
    memset(info, 0, sizeof(*info));
    info->size = sizeof(*info);
    ffmpegkmp_frame_format_init(&info->input_format);
}

void ffmpegkmp_audio_encoder_config_init(ffmpegkmp_audio_encoder_config *config) {
    if (!config)
        return;
    memset(config, 0, sizeof(*config));
    config->size = sizeof(*config);
}

void ffmpegkmp_writer_result_init(ffmpegkmp_writer_result *result) {
    if (!result)
        return;
    memset(result, 0, sizeof(*result));
    result->size = sizeof(*result);
    result->bytes = -1;
}

static int ffmpegkmp_writer_interrupted(void *opaque) {
    const ffmpegkmp_writer *writer = opaque;
    return atomic_load(&writer->aborted);
}

/* ---- Video encoders ---- */

static int ffmpegkmp_is_ten_bit(const ffmpegkmp_video_encoder_config *config) {
    return config->bit_depth == 10 || config->dynamic_range != FFMPEGKMP_DYNAMIC_RANGE_SDR;
}

static int ffmpegkmp_validate_video_config(const ffmpegkmp_video_encoder_config *config) {
    if (!config || config->size < sizeof(*config) ||
            config->width <= 0 || config->height <= 0 || (config->width & 1) || (config->height & 1) ||
            config->codec < FFMPEGKMP_VIDEO_CODEC_H264 || config->codec > FFMPEGKMP_VIDEO_CODEC_AV1 ||
            config->dynamic_range < FFMPEGKMP_DYNAMIC_RANGE_SDR || config->dynamic_range > FFMPEGKMP_DYNAMIC_RANGE_HLG ||
            config->preference < FFMPEGKMP_ENCODER_AUTO || config->preference > FFMPEGKMP_ENCODER_SOFTWARE ||
            (config->frame_rate_num > 0) != (config->frame_rate_den > 0) ||
            config->frame_rate_num < 0 || config->bit_rate < 0 || config->keyframe_interval_us < 0 ||
            (config->bit_depth != 0 && config->bit_depth != 8 && config->bit_depth != 10) ||
            (config->bit_depth == 8 && config->dynamic_range != FFMPEGKMP_DYNAMIC_RANGE_SDR))
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    /* H.264 has no 10-bit profile hardware encoders take. */
    if (ffmpegkmp_is_ten_bit(config) && config->codec == FFMPEGKMP_VIDEO_CODEC_H264)
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    return 0;
}

/* The encoders to try for a codec, hardware ones first; the build and the host decide which exist. */
static const char *const *ffmpegkmp_encoder_names(int codec, int hardware) {
    static const char *const h264_hardware[] = {
#if defined(__ANDROID__)
        "h264_mediacodec",
#elif defined(__APPLE__)
        "h264_videotoolbox",
#elif defined(_WIN32)
        "h264_nvenc", "h264_qsv", "h264_amf", "h264_mf",
#endif
        NULL,
    };
    static const char *const hevc_hardware[] = {
#if defined(__ANDROID__)
        "hevc_mediacodec",
#elif defined(__APPLE__)
        "hevc_videotoolbox",
#elif defined(_WIN32)
        "hevc_nvenc", "hevc_qsv", "hevc_amf", "hevc_mf",
#endif
        NULL,
    };
    static const char *const av1_hardware[] = {
#if defined(__ANDROID__)
        "av1_mediacodec",
#elif defined(_WIN32)
        "av1_nvenc", "av1_qsv", "av1_amf", "av1_mf",
#endif
        NULL,
    };
    static const char *const h264_software[] = { "libx264", "libopenh264", NULL };
    static const char *const hevc_software[] = { "libx265", NULL };
    static const char *const av1_software[] = { "libsvtav1", "libaom-av1", "librav1e", NULL };
    switch (codec) {
    case FFMPEGKMP_VIDEO_CODEC_H264:
        return hardware ? h264_hardware : h264_software;
    case FFMPEGKMP_VIDEO_CODEC_HEVC:
        return hardware ? hevc_hardware : hevc_software;
    default:
        return hardware ? av1_hardware : av1_software;
    }
}

static int ffmpegkmp_supports_format(const AVCodec *codec, enum AVPixelFormat wanted) {
    const enum AVPixelFormat *formats = NULL;
    int count = 0;
    int index;
    if (avcodec_get_supported_config(NULL, codec, AV_CODEC_CONFIG_PIX_FORMAT, 0,
            (const void **)&formats, &count) < 0 || !formats)
        return 0;
    for (index = 0; index < count; index++) {
        if (formats[index] == wanted)
            return 1;
    }
    return 0;
}

/*
 * The layout the encoder takes: NV12 or P010, else YUV420P or YUV420P10. The
 * Kotlin/Native Apple runtimes hand VideoToolbox pooled CVPixelBuffers.
 */
static int ffmpegkmp_choose_pixel_format(
        const AVCodec *codec,
        int ten_bit,
        enum AVPixelFormat *pixel_format,
        enum AVPixelFormat *layout) {
    const enum AVPixelFormat semi_planar = ten_bit ? AV_PIX_FMT_P010 : AV_PIX_FMT_NV12;
    const enum AVPixelFormat planar = ten_bit ? AV_PIX_FMT_YUV420P10 : AV_PIX_FMT_YUV420P;
#if defined(FFMPEGKMP_PIXEL_BUFFER)
    if (ffmpegkmp_supports_format(codec, AV_PIX_FMT_VIDEOTOOLBOX)) {
        *pixel_format = AV_PIX_FMT_VIDEOTOOLBOX;
        *layout = semi_planar;
        return 0;
    }
#endif
    if (ffmpegkmp_supports_format(codec, semi_planar))
        *layout = semi_planar;
    else if (ffmpegkmp_supports_format(codec, planar))
        *layout = planar;
    else
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    *pixel_format = *layout;
    return 0;
}

static void ffmpegkmp_describe_input(
        const ffmpegkmp_video_encoder_config *config,
        enum AVPixelFormat layout,
        ffmpegkmp_frame_format *format) {
    ffmpegkmp_frame_format_init(format);
    switch (layout) {
    case AV_PIX_FMT_NV12:
        format->layout = FFMPEGKMP_LAYOUT_NV12;
        break;
    case AV_PIX_FMT_P010:
        format->layout = FFMPEGKMP_LAYOUT_P010;
        break;
    case AV_PIX_FMT_YUV420P10:
        format->layout = FFMPEGKMP_LAYOUT_YUV420P10;
        break;
    default:
        format->layout = FFMPEGKMP_LAYOUT_YUV420P;
        break;
    }
    format->range = FFMPEGKMP_RANGE_LIMITED;
    if (config->dynamic_range == FFMPEGKMP_DYNAMIC_RANGE_SDR) {
        format->primaries = FFMPEGKMP_PRIMARIES_BT709;
        format->transfer = FFMPEGKMP_TRANSFER_BT709;
        format->matrix = FFMPEGKMP_MATRIX_BT709;
    } else {
        format->primaries = FFMPEGKMP_PRIMARIES_BT2020;
        format->transfer = config->dynamic_range == FFMPEGKMP_DYNAMIC_RANGE_HLG
                ? FFMPEGKMP_TRANSFER_HLG
                : FFMPEGKMP_TRANSFER_PQ;
        format->matrix = FFMPEGKMP_MATRIX_BT2020_NCL;
    }
}

static AVRational ffmpegkmp_rational(double value, int denominator) {
    return (AVRational){ (int)lround(value * denominator), denominator };
}

/* HDR10 metadata from the config, or else from `frame`, into `side_data`: an encoder's, for it and the container. */
static int ffmpegkmp_attach_hdr_metadata(
        AVFrameSideData ***side_data_list,
        int *side_data_count,
        const ffmpegkmp_video_encoder_config *config,
        const AVFrame *frame) {
    const AVFrameSideData *mastering_source = NULL;
    const AVFrameSideData *light_source = NULL;
    AVFrameSideData *side_data;
    if (config->dynamic_range != FFMPEGKMP_DYNAMIC_RANGE_HDR10)
        return 0;
    if (!config->has_hdr_metadata && frame) {
        mastering_source = av_frame_get_side_data(frame, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
        light_source = av_frame_get_side_data(frame, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
    }
    if (config->has_hdr_metadata ? config->hdr_metadata.has_mastering_display : mastering_source != NULL) {
        side_data = av_frame_side_data_new(side_data_list, side_data_count,
                AV_FRAME_DATA_MASTERING_DISPLAY_METADATA, sizeof(AVMasteringDisplayMetadata), 0);
        if (!side_data)
            return AVERROR(ENOMEM);
        if (mastering_source) {
            memcpy(side_data->data, mastering_source->data, sizeof(AVMasteringDisplayMetadata));
        } else {
            const ffmpegkmp_hdr_metadata *hdr = &config->hdr_metadata;
            AVMasteringDisplayMetadata *mastering = (AVMasteringDisplayMetadata *)side_data->data;
            int index;
            memset(mastering, 0, sizeof(*mastering));
            for (index = 0; index < 3; index++) {
                mastering->display_primaries[index][0] = ffmpegkmp_rational(hdr->primaries_x[index], 50000);
                mastering->display_primaries[index][1] = ffmpegkmp_rational(hdr->primaries_y[index], 50000);
            }
            mastering->white_point[0] = ffmpegkmp_rational(hdr->white_point_x, 50000);
            mastering->white_point[1] = ffmpegkmp_rational(hdr->white_point_y, 50000);
            mastering->min_luminance = ffmpegkmp_rational(hdr->min_luminance, 10000);
            mastering->max_luminance = ffmpegkmp_rational(hdr->max_luminance, 10000);
            mastering->has_primaries = 1;
            mastering->has_luminance = 1;
        }
    }
    if (config->has_hdr_metadata ? config->hdr_metadata.has_content_light : light_source != NULL) {
        AVContentLightMetadata *light;
        side_data = av_frame_side_data_new(side_data_list, side_data_count,
                AV_FRAME_DATA_CONTENT_LIGHT_LEVEL, sizeof(AVContentLightMetadata), 0);
        if (!side_data)
            return AVERROR(ENOMEM);
        light = (AVContentLightMetadata *)side_data->data;
        if (light_source) {
            memcpy(light, light_source->data, sizeof(*light));
        } else {
            light->MaxCLL = (unsigned)config->hdr_metadata.max_content_light_level;
            light->MaxFALL = (unsigned)config->hdr_metadata.max_frame_average_light_level;
        }
    }
    return 0;
}

/* A bit rate that suits the size and rate: about 0.1 bit per pixel for H.264, 0.06 for newer codecs. */
static int64_t ffmpegkmp_default_bit_rate(const ffmpegkmp_video_encoder_config *config) {
    const double fps = config->frame_rate_num > 0 ? (double)config->frame_rate_num / config->frame_rate_den : 30.0;
    const double bits_per_pixel = config->codec == FFMPEGKMP_VIDEO_CODEC_H264 ? 0.1 : 0.06;
    const double rate = (double)config->width * config->height * fps * bits_per_pixel *
            (ffmpegkmp_is_ten_bit(config) ? 1.25 : 1.0);
    return rate < 200000.0 ? 200000 : (int64_t)rate;
}

/* The colour a config's dynamic range calls for, in limited range. */
static void ffmpegkmp_video_colour(
        const ffmpegkmp_video_encoder_config *config,
        enum AVColorPrimaries *primaries,
        enum AVColorTransferCharacteristic *transfer,
        enum AVColorSpace *matrix) {
    if (config->dynamic_range == FFMPEGKMP_DYNAMIC_RANGE_SDR) {
        *primaries = AVCOL_PRI_BT709;
        *transfer = AVCOL_TRC_BT709;
        *matrix = AVCOL_SPC_BT709;
    } else {
        *primaries = AVCOL_PRI_BT2020;
        *transfer = config->dynamic_range == FFMPEGKMP_DYNAMIC_RANGE_HLG
                ? AVCOL_TRC_ARIB_STD_B67
                : AVCOL_TRC_SMPTE2084;
        *matrix = AVCOL_SPC_BT2020_NCL;
    }
}

/* Opens `codec` for `config`; the context is freed on failure. */
static int ffmpegkmp_open_video_encoder(
        ffmpegkmp_writer_track *track,
        const AVCodec *codec,
        const ffmpegkmp_video_encoder_config *config,
        int global_header,
        const AVFrame *first_frame) {
    AVCodecContext *encoder;
    const double fps = config->frame_rate_num > 0 ? (double)config->frame_rate_num / config->frame_rate_den : 30.0;
    int64_t gop;
    int result;
    if ((result = ffmpegkmp_choose_pixel_format(
            codec, ffmpegkmp_is_ten_bit(config), &track->pixel_format, &track->layout)) < 0)
        return result;
    if (!(encoder = avcodec_alloc_context3(codec)))
        return AVERROR(ENOMEM);
    encoder->width = config->width;
    encoder->height = config->height;
    encoder->pix_fmt = track->pixel_format;
    if (track->pixel_format == AV_PIX_FMT_VIDEOTOOLBOX)
        encoder->sw_pix_fmt = track->layout;
    if (config->frame_rate_num > 0) {
        encoder->framerate = (AVRational){ config->frame_rate_num, config->frame_rate_den };
        encoder->time_base = av_inv_q(encoder->framerate);
    } else {
        encoder->time_base = FFMPEGKMP_VFR_TIME_BASE;
    }
    encoder->sample_aspect_ratio = (AVRational){ 1, 1 };
    encoder->bit_rate = config->bit_rate > 0 ? config->bit_rate : ffmpegkmp_default_bit_rate(config);
    gop = llround((double)config->keyframe_interval_us / 1e6 * fps);
    encoder->gop_size = gop < 1 ? 1 : gop > INT_MAX ? INT_MAX : (int)gop;
    encoder->color_range = AVCOL_RANGE_MPEG;
    ffmpegkmp_video_colour(config, &encoder->color_primaries, &encoder->color_trc, &encoder->colorspace);
    if (ffmpegkmp_is_ten_bit(config) && codec->id == AV_CODEC_ID_HEVC)
        encoder->profile = AV_PROFILE_HEVC_MAIN_10;
    encoder->chroma_sample_location = AVCHROMA_LOC_LEFT;
    if (global_header)
        encoder->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    if (!(codec->capabilities & AV_CODEC_CAP_HARDWARE))
        encoder->thread_count = 0;
    if ((result = ffmpegkmp_attach_hdr_metadata(
            &encoder->decoded_side_data, &encoder->nb_decoded_side_data, config, first_frame)) >= 0)
        result = avcodec_open2(encoder, codec, NULL);
    if (result < 0) {
        avcodec_free_context(&encoder);
        return result;
    }
    track->encoder = encoder;
    track->time_base = encoder->time_base;
    return 0;
}

/* Tries the encoders `config->preference` allows, hardware first; the first that opens wins. */
static int ffmpegkmp_choose_video_encoder(
        ffmpegkmp_writer_track *track,
        const ffmpegkmp_video_encoder_config *config,
        int global_header) {
    int result = FFPLAYKMP_ERROR_UNSUPPORTED;
    int hardware;
    for (hardware = 1; hardware >= 0; hardware--) {
        const char *const *name;
        if ((hardware && config->preference == FFMPEGKMP_ENCODER_SOFTWARE) ||
                (!hardware && config->preference == FFMPEGKMP_ENCODER_REQUIRE_HARDWARE))
            continue;
        for (name = ffmpegkmp_encoder_names(config->codec, hardware); *name; name++) {
            const AVCodec *codec = avcodec_find_encoder_by_name(*name);
            if (!codec)
                continue;
            if (ffmpegkmp_open_video_encoder(track, codec, config, global_header, NULL) >= 0) {
                track->codec = codec;
                track->hardware = hardware;
                return 0;
            }
        }
    }
    /* No encoder opened, or none is built: the default LGPL builds have no software H.264 or HEVC. */
    return result;
}

static void ffmpegkmp_describe_track(const ffmpegkmp_writer_track *track, ffmpegkmp_video_track_info *info) {
    if (!info || info->size < sizeof(*info))
        return;
    info->input_format = track->input_format;
    info->hardware = track->hardware;
    snprintf(info->encoder, sizeof(info->encoder), "%s", track->codec->name);
}

int ffmpegkmp_writer_can_encode(
        const ffmpegkmp_video_encoder_config *config,
        ffmpegkmp_video_track_info *info) {
    ffmpegkmp_writer_track track;
    int result = ffmpegkmp_validate_video_config(config);
    if (result == FFPLAYKMP_ERROR_UNSUPPORTED)
        return 0;
    if (result < 0)
        return result;
    memset(&track, 0, sizeof(track));
    if (ffmpegkmp_choose_video_encoder(&track, config, 1) < 0)
        return 0;
    ffmpegkmp_describe_input(config, track.layout, &track.input_format);
    ffmpegkmp_describe_track(&track, info);
    avcodec_free_context(&track.encoder);
    return 1;
}

/* ---- The muxer ---- */

static const char *ffmpegkmp_container_name(int container) {
    switch (container) {
    case FFMPEGKMP_CONTAINER_MATROSKA:
        return "matroska";
    case FFMPEGKMP_CONTAINER_MPEGTS:
        return "mpegts";
    default:
        return "mp4";
    }
}

static int ffmpegkmp_writer_avio_write(void *opaque, const uint8_t *buffer, int size) {
    ffplaykmp_avio *io = opaque;
    int64_t result;
    if (ffplaykmp_host_interrupted(&io->host))
        return AVERROR_EXIT;
    result = io->host.callback(
            io->host.opaque, io->resource_id, FFPLAYKMP_IO_WRITE, io->position, (uint8_t *)buffer, (uint64_t)size);
    if (result < 0)
        return AVERROR(EIO);
    io->position += size;
    return size;
}

/* MP4's faststart reads the output back to move its index: a second view of the mounted resource. */
static int ffmpegkmp_writer_io_open(
        AVFormatContext *format,
        AVIOContext **pb,
        const char *url,
        int flags,
        AVDictionary **options) {
    ffmpegkmp_writer *writer = format->opaque;
    ffplaykmp_avio *io;
    uint8_t *buffer;
    (void)options;
    if (!url || strcmp(url, writer->url) != 0 || flags != AVIO_FLAG_READ)
        return AVERROR(EPERM);
    io = av_mallocz(sizeof(*io));
    buffer = av_malloc(FFMPEGKMP_WRITER_IO_BUFFER);
    if (io && buffer) {
        io->host = writer->host;
        io->resource_id = writer->resource_id;
        *pb = avio_alloc_context(buffer, FFMPEGKMP_WRITER_IO_BUFFER, 0, io, ffplaykmp_avio_read, NULL,
                ffplaykmp_avio_seek);
    }
    if (!io || !buffer || !*pb) {
        av_free(io);
        av_free(buffer);
        return AVERROR(ENOMEM);
    }
    return 0;
}

static int ffmpegkmp_writer_io_close(AVFormatContext *format, AVIOContext *pb) {
    (void)format;
    if (!pb)
        return 0;
    av_freep(&pb->opaque);
    av_freep(&pb->buffer);
    avio_context_free(&pb);
    return 0;
}

static int ffmpegkmp_open_output(ffmpegkmp_writer *writer) {
    AVFormatContext *format = writer->format;
    int64_t capabilities;
    uint8_t *buffer;
    int seekable;
    if (!writer->mounted) {
        if (format->oformat->flags & AVFMT_NOFILE)
            return 0;
        return avio_open2(&format->pb, writer->url, AVIO_FLAG_WRITE, &format->interrupt_callback, NULL);
    }
    if (!writer->host.callback)
        return AVERROR(ENOSYS);
    capabilities = writer->host.callback(
            writer->host.opaque, writer->resource_id, FFPLAYKMP_IO_OPEN, AVIO_FLAG_WRITE, NULL, 0);
    if (capabilities < 0 || !(capabilities & FFMPEGKMP_IO_CAP_WRITE))
        return AVERROR(EACCES);
    writer->resource_opened = 1;
    seekable = (capabilities & FFMPEGKMP_IO_CAP_SEEK) != 0;
    /* MP4 goes back to write its index; only a seekable output takes it unfragmented. */
    if (!seekable && writer->container == FFMPEGKMP_CONTAINER_MP4)
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    writer->io = av_mallocz(sizeof(*writer->io));
    buffer = av_malloc(FFMPEGKMP_WRITER_IO_BUFFER);
    if (writer->io && buffer) {
        writer->io->host = writer->host;
        writer->io->resource_id = writer->resource_id;
        format->pb = avio_alloc_context(buffer, FFMPEGKMP_WRITER_IO_BUFFER, 1, writer->io, NULL,
                ffmpegkmp_writer_avio_write, seekable ? ffplaykmp_avio_seek : NULL);
    }
    if (!format->pb) {
        av_free(buffer);
        return AVERROR(ENOMEM);
    }
    if (!seekable)
        format->pb->seekable = 0;
    format->flags |= AVFMT_FLAG_CUSTOM_IO;
    format->opaque = writer;
    format->io_open = ffmpegkmp_writer_io_open;
    format->io_close2 = ffmpegkmp_writer_io_close;
    return 0;
}

ffmpegkmp_writer *ffmpegkmp_writer_create(
        const char *output,
        int32_t container,
        int32_t fast_start,
        int64_t timeout_us,
        ffplaykmp_io_callback io_callback,
        void *io_opaque,
        int32_t *error) {
    ffmpegkmp_writer *writer;
    const char prefix[] = "ffmpegkmp:";
    int result;
    if (error)
        *error = 0;
    if (!output || !*output || container < FFMPEGKMP_CONTAINER_MP4 || container > FFMPEGKMP_CONTAINER_MPEGTS ||
            timeout_us < 0) {
        if (error)
            *error = FFPLAYKMP_ERROR_INVALID_ARGUMENT;
        return NULL;
    }
    writer = calloc(1, sizeof(*writer));
    if (!writer) {
        if (error)
            *error = AVERROR(ENOMEM);
        return NULL;
    }
    pthread_mutex_init(&writer->mux, NULL);
    atomic_init(&writer->aborted, 0);
    atomic_init(&writer->writing, 0);
    writer->container = container;
    writer->fast_start = fast_start && container == FFMPEGKMP_CONTAINER_MP4;
    writer->timeout_us = timeout_us;
    writer->host.callback = io_callback;
    writer->host.opaque = io_opaque;
    writer->host.interrupted = ffmpegkmp_writer_interrupted;
    writer->host.interrupt_opaque = writer;
    if (strncmp(output, prefix, sizeof(prefix) - 1) == 0) {
        char *end = NULL;
        writer->mounted = 1;
        writer->resource_id = strtoll(output + sizeof(prefix) - 1, &end, 10);
        if (writer->resource_id <= 0 || end == output + sizeof(prefix) - 1)
            result = FFPLAYKMP_ERROR_INVALID_ARGUMENT;
        else
            result = 0;
    } else {
        result = 0;
    }
    if (result >= 0 && !(writer->url = av_strdup(output)))
        result = AVERROR(ENOMEM);
    if (result >= 0)
        result = avformat_alloc_output_context2(&writer->format, NULL, ffmpegkmp_container_name(container), output);
    if (result >= 0) {
        writer->format->interrupt_callback.callback = ffmpegkmp_writer_interrupted;
        writer->format->interrupt_callback.opaque = writer;
        result = ffmpegkmp_open_output(writer);
    }
    if (result < 0) {
        ffmpegkmp_writer_destroy(writer);
        if (error)
            *error = result;
        return NULL;
    }
    return writer;
}

static int ffmpegkmp_global_header(const ffmpegkmp_writer *writer) {
    return (writer->format->oformat->flags & AVFMT_GLOBALHEADER) != 0;
}

/* HDR10 metadata for the container: the mastering display and content light entries of `side_data`. */
static int ffmpegkmp_copy_hdr_side_data(
        AVCodecParameters *parameters,
        AVFrameSideData *const *side_data_list,
        int side_data_count) {
    int index;
    for (index = 0; index < side_data_count; index++) {
        const AVFrameSideData *side_data = side_data_list[index];
        enum AVPacketSideDataType type;
        AVPacketSideData *copy;
        if (side_data->type == AV_FRAME_DATA_MASTERING_DISPLAY_METADATA)
            type = AV_PKT_DATA_MASTERING_DISPLAY_METADATA;
        else if (side_data->type == AV_FRAME_DATA_CONTENT_LIGHT_LEVEL)
            type = AV_PKT_DATA_CONTENT_LIGHT_LEVEL;
        else
            continue;
        copy = av_packet_side_data_new(&parameters->coded_side_data, &parameters->nb_coded_side_data,
                type, side_data->size, 0);
        if (!copy)
            return AVERROR(ENOMEM);
        memcpy(copy->data, side_data->data, side_data->size);
    }
    return 0;
}

/* Apple's players take HEVC in MP4 only as hvc1, with the parameter sets in the sample entry. */
static void ffmpegkmp_tag_stream(const ffmpegkmp_writer *writer, AVCodecParameters *parameters) {
    if (parameters->codec_id == AV_CODEC_ID_HEVC && (writer->container == FFMPEGKMP_CONTAINER_MP4 ||
            writer->container == FFMPEGKMP_CONTAINER_FRAGMENTED_MP4))
        parameters->codec_tag = MKTAG('h', 'v', 'c', '1');
}

/* The stream's parameters, with the encoder's HDR10 metadata for the container. */
static int ffmpegkmp_start_track(const ffmpegkmp_writer *writer, ffmpegkmp_writer_track *track) {
    AVCodecParameters *parameters = track->stream->codecpar;
    int result = avcodec_parameters_from_context(parameters, track->encoder);
    if (result < 0)
        return result;
    ffmpegkmp_tag_stream(writer, parameters);
    track->stream->time_base = track->encoder->time_base;
    return ffmpegkmp_copy_hdr_side_data(
            parameters, track->encoder->decoded_side_data, track->encoder->nb_decoded_side_data);
}

static ffmpegkmp_writer_track *ffmpegkmp_track_of_stream(ffmpegkmp_writer *writer, int stream_index) {
    int index;
    for (index = 0; index < writer->track_count; index++) {
        if (writer->tracks[index].stream->index == stream_index)
            return &writer->tracks[index];
    }
    return NULL;
}

static int ffmpegkmp_write_packet_locked(ffmpegkmp_writer *writer, AVPacket *packet, AVRational time_base) {
    av_packet_rescale_ts(packet, time_base, writer->format->streams[packet->stream_index]->time_base);
    return av_interleaved_write_frame(writer->format, packet);
}

/* Writes the header once every track has started, then the packets that waited for it. */
static int ffmpegkmp_write_header_locked(ffmpegkmp_writer *writer, int force) {
    AVDictionary *options = NULL;
    int index;
    int result = 0;
    if (writer->header_written)
        return 0;
    for (index = 0; index < writer->track_count; index++) {
        if (!writer->tracks[index].started && !force)
            return 0;
    }
    if (writer->container == FFMPEGKMP_CONTAINER_MP4 && writer->fast_start)
        result = av_dict_set(&options, "movflags", "+faststart", 0);
    else if (writer->container == FFMPEGKMP_CONTAINER_FRAGMENTED_MP4)
        result = av_dict_set(&options, "movflags", "+frag_keyframe+empty_moov+default_base_moof", 0);
    if (result >= 0)
        result = avformat_write_header(writer->format, &options);
    av_dict_free(&options);
    if (result < 0)
        return result;
    writer->header_written = 1;
    for (index = 0; index < writer->pending_count; index++) {
        AVPacket *packet = writer->pending[index];
        const ffmpegkmp_writer_track *track = ffmpegkmp_track_of_stream(writer, packet->stream_index);
        if (result >= 0)
            result = track ? ffmpegkmp_write_packet_locked(writer, packet, track->time_base) : AVERROR_BUG;
        av_packet_free(&writer->pending[index]);
    }
    writer->pending_count = 0;
    return result;
}

/* Hands an encoded packet, in the encoder's time base, to the muxer; before the header it waits. */
static int ffmpegkmp_mux(ffmpegkmp_writer *writer, ffmpegkmp_writer_track *track, AVPacket *packet) {
    int result = 0;
    packet->stream_index = track->stream->index;
    if (packet->pts != AV_NOPTS_VALUE) {
        if (track->first_pts == AV_NOPTS_VALUE || packet->pts < track->first_pts)
            track->first_pts = packet->pts;
        if (packet->pts + packet->duration > track->end_pts)
            track->end_pts = packet->pts + packet->duration;
    }
    pthread_mutex_lock(&writer->mux);
    if (writer->header_written) {
        result = ffmpegkmp_write_packet_locked(writer, packet, track->time_base);
    } else {
        if (writer->pending_count == writer->pending_capacity) {
            const int capacity = writer->pending_capacity ? writer->pending_capacity * 2 : 16;
            AVPacket **grown = av_realloc_array(writer->pending, capacity, sizeof(*grown));
            if (!grown)
                result = AVERROR(ENOMEM);
            else {
                writer->pending = grown;
                writer->pending_capacity = capacity;
            }
        }
        if (result >= 0) {
            AVPacket *copy = av_packet_alloc();
            if (!copy || (result = av_packet_ref(copy, packet)) < 0) {
                av_packet_free(&copy);
                if (result >= 0)
                    result = AVERROR(ENOMEM);
            } else {
                writer->pending[writer->pending_count++] = copy;
            }
        }
    }
    pthread_mutex_unlock(&writer->mux);
    av_packet_unref(packet);
    return result;
}

/* Whether the track's call must stop: the writer aborted, or the call ran past its deadline. */
static int ffmpegkmp_stopped(const ffmpegkmp_writer *writer, ffmpegkmp_writer_track *track) {
    if (atomic_load(&writer->aborted))
        return AVERROR_EXIT;
    if (track->deadline > 0 && av_gettime_relative() >= track->deadline) {
        track->timed_out = 1;
        return FFPLAYKMP_ERROR_TIMED_OUT;
    }
    return 0;
}

/*
 * Takes the packets the encoder has ready; while flushing, until it has no
 * more. Returns how many it took, or a negative error.
 */
static int ffmpegkmp_drain(ffmpegkmp_writer *writer, ffmpegkmp_writer_track *track, int flushing) {
    int packets = 0;
    for (;;) {
        int result;
        if ((result = ffmpegkmp_stopped(writer, track)) < 0)
            return result;
        result = avcodec_receive_packet(track->encoder, track->packet);
        if (result == AVERROR_EOF || (result == AVERROR(EAGAIN) && !flushing))
            return packets;
        if (result == AVERROR(EAGAIN)) {
            /* A hardware encoder still working on the last frames. */
            av_usleep(1000);
            continue;
        }
        if (result < 0)
            return result;
        if ((result = ffmpegkmp_mux(writer, track, track->packet)) < 0)
            return result;
        packets++;
    }
}

/*
 * Sends a frame, or NULL to flush; a full encoder (MediaCodec's input queue)
 * gives packets first. An encoder that neither takes input nor gives output
 * is waited on, not spun on, until the deadline.
 */
static int ffmpegkmp_encode(ffmpegkmp_writer *writer, ffmpegkmp_writer_track *track, const AVFrame *frame) {
    int result;
    for (;;) {
        if ((result = ffmpegkmp_stopped(writer, track)) < 0)
            return result;
        result = avcodec_send_frame(track->encoder, frame);
        if (result != AVERROR(EAGAIN))
            break;
        if ((result = ffmpegkmp_drain(writer, track, 0)) < 0)
            return result;
        if (result == 0)
            av_usleep(1000);
    }
    if (result < 0 && !(frame == NULL && result == AVERROR_EOF))
        return result;
    result = ffmpegkmp_drain(writer, track, frame == NULL);
    return result < 0 ? result : 0;
}

/* Arms the track's deadline for one call; a track that timed out stays failed. */
static int ffmpegkmp_begin_track_call(const ffmpegkmp_writer *writer, ffmpegkmp_writer_track *track) {
    if (track->timed_out)
        return FFPLAYKMP_ERROR_TIMED_OUT;
    track->deadline = writer->timeout_us > 0 ? av_gettime_relative() + writer->timeout_us : 0;
    return 0;
}

static int ffmpegkmp_end_track_call(ffmpegkmp_writer_track *track, int result) {
    track->deadline = 0;
    return result < 0 && track->timed_out ? FFPLAYKMP_ERROR_TIMED_OUT : result;
}

static void ffmpegkmp_init_track(ffmpegkmp_writer_track *track, enum AVMediaType type) {
    memset(track, 0, sizeof(*track));
    track->type = type;
    track->first_pts = AV_NOPTS_VALUE;
    track->end_pts = INT64_MIN;
    track->last_pts = AV_NOPTS_VALUE;
}

static void ffmpegkmp_free_track(ffmpegkmp_writer_track *track) {
    avcodec_free_context(&track->encoder);
    av_packet_free(&track->packet);
    av_frame_free(&track->input);
    ffmpegkmp_frame_pool_free(track->pool);
    track->pool = NULL;
    ffmpegkmp_converter_free(&track->converter);
    if (track->fifo)
        av_audio_fifo_free(track->fifo);
    track->fifo = NULL;
    av_freep(&track->planar);
}

/*
 * Gives an opened track its slot and stream, under the lock: tracks may be
 * added from their threads at once, but only before the first write. On
 * failure the track is freed.
 */
static int ffmpegkmp_attach_track(ffmpegkmp_writer *writer, ffmpegkmp_writer_track *track, int start) {
    int result = 0;
    int index = -1;
    pthread_mutex_lock(&writer->mux);
    if (atomic_load(&writer->aborted))
        result = AVERROR_EXIT;
    else if (writer->finished || writer->header_written || atomic_load(&writer->writing) ||
            writer->track_count == FFMPEGKMP_WRITER_TRACKS)
        result = FFPLAYKMP_ERROR_INVALID_STATE;
    else if (!(track->stream = avformat_new_stream(writer->format, NULL)))
        result = AVERROR(ENOMEM);
    if (result >= 0 && start && (result = ffmpegkmp_start_track(writer, track)) >= 0)
        track->started = 1;
    if (result >= 0) {
        index = writer->track_count++;
        writer->tracks[index] = *track;
    }
    pthread_mutex_unlock(&writer->mux);
    if (result < 0) {
        ffmpegkmp_free_track(track);
        return result;
    }
    return index;
}

int ffmpegkmp_writer_add_video_track(
        ffmpegkmp_writer *writer,
        const ffmpegkmp_video_encoder_config *config,
        ffmpegkmp_video_track_info *info) {
    ffmpegkmp_writer_track track;
    int result;
    if (!writer)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if ((result = ffmpegkmp_validate_video_config(config)) < 0)
        return result;
    ffmpegkmp_init_track(&track, AVMEDIA_TYPE_VIDEO);
    track.config = *config;
    /* Opened outside the lock: a hardware encoder takes a while. */
    if ((result = ffmpegkmp_choose_video_encoder(&track, config, ffmpegkmp_global_header(writer))) < 0)
        return result;
    ffmpegkmp_describe_input(config, track.layout, &track.input_format);
    track.packet = av_packet_alloc();
    track.input = av_frame_alloc();
    track.pool = ffmpegkmp_frame_pool_alloc(0);
    track.converter = ffmpegkmp_converter_alloc(0);
    if (!track.packet || !track.input || !track.pool || !track.converter) {
        ffmpegkmp_free_track(&track);
        return AVERROR(ENOMEM);
    }
    /* Without metadata of its own, HDR10 takes the first frame's, which the encoder needs at open. */
    if (config->dynamic_range == FFMPEGKMP_DYNAMIC_RANGE_HDR10 && !config->has_hdr_metadata) {
        avcodec_free_context(&track.encoder);
        track.open_at_first_frame = 1;
    }
    ffmpegkmp_describe_track(&track, info);
    return ffmpegkmp_attach_track(writer, &track, 0);
}

int ffmpegkmp_writer_add_audio_track(ffmpegkmp_writer *writer, const ffmpegkmp_audio_encoder_config *config) {
    ffmpegkmp_writer_track storage;
    ffmpegkmp_writer_track *track = &storage;
    const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_AAC);
    AVCodecContext *encoder;
    int result;
    if (!writer || !config || config->size < sizeof(*config) || config->sample_rate <= 0 ||
            config->channels <= 0 || config->channels > 8 || config->bit_rate < 0)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if (!codec)
        return FFPLAYKMP_ERROR_UNSUPPORTED;
    ffmpegkmp_init_track(track, AVMEDIA_TYPE_AUDIO);
    if (!(encoder = avcodec_alloc_context3(codec)))
        return AVERROR(ENOMEM);
    track->encoder = encoder;
    encoder->sample_fmt = AV_SAMPLE_FMT_FLTP;
    encoder->sample_rate = config->sample_rate;
    av_channel_layout_default(&encoder->ch_layout, config->channels);
    encoder->bit_rate = config->bit_rate > 0 ? config->bit_rate : 64000LL * config->channels;
    encoder->time_base = (AVRational){ 1, config->sample_rate };
    if (ffmpegkmp_global_header(writer))
        encoder->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    if ((result = avcodec_open2(encoder, codec, NULL)) < 0) {
        ffmpegkmp_free_track(track);
        return result;
    }
    track->time_base = encoder->time_base;
    track->packet = av_packet_alloc();
    track->fifo = av_audio_fifo_alloc(AV_SAMPLE_FMT_FLTP, config->channels, encoder->frame_size * 2);
    if (!track->packet || !track->fifo) {
        ffmpegkmp_free_track(track);
        return AVERROR(ENOMEM);
    }
    return ffmpegkmp_attach_track(writer, track, 1);
}

static ffmpegkmp_writer_track *ffmpegkmp_track(ffmpegkmp_writer *writer, int32_t index, enum AVMediaType type) {
    if (!writer || index < 0 || index >= writer->track_count || writer->tracks[index].type != type)
        return NULL;
    return &writer->tracks[index];
}

static int ffmpegkmp_writer_can_write(ffmpegkmp_writer *writer, const ffmpegkmp_writer_track *track) {
    if (atomic_load(&writer->aborted))
        return AVERROR_EXIT;
    if (track->ended || writer->finished)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    atomic_store(&writer->writing, 1);
    return 0;
}

/* Opens a track that waited for its first frame (or, with none, at its end) and starts it. */
static int ffmpegkmp_start_video_track(ffmpegkmp_writer *writer, ffmpegkmp_writer_track *track, const AVFrame *first) {
    int result;
    if (track->open_at_first_frame) {
        track->open_at_first_frame = 0;
        if ((result = ffmpegkmp_open_video_encoder(
                track, track->codec, &track->config, ffmpegkmp_global_header(writer), first)) < 0)
            return result;
    }
    pthread_mutex_lock(&writer->mux);
    if ((result = ffmpegkmp_start_track(writer, track)) >= 0) {
        track->started = 1;
        result = ffmpegkmp_write_header_locked(writer, 0);
    }
    pthread_mutex_unlock(&writer->mux);
    return result;
}

/* Whether `frame` is already what the encoder takes: its layout, colour and memory. */
static int ffmpegkmp_takes_as_is(const ffmpegkmp_writer_track *track, const ffmpegkmp_frame *frame) {
    const AVFrame *source = frame->frame;
    ffmpegkmp_frame_info info;
    ffmpegkmp_frame_info_init(&info);
    if (ffmpegkmp_frame_get_info(frame, &info) < 0)
        return 0;
    if (info.format.layout != track->input_format.layout ||
            info.format.primaries != track->input_format.primaries ||
            info.format.transfer != track->input_format.transfer ||
            info.format.matrix != track->input_format.matrix ||
            info.format.range != track->input_format.range)
        return 0;
    if (track->pixel_format == AV_PIX_FMT_VIDEOTOOLBOX)
        return ffmpegkmp_frame_is_pixel_buffer(source);
    return source->format == track->pixel_format && !source->hw_frames_ctx;
}

static int ffmpegkmp_write_video(
        ffmpegkmp_writer *writer,
        ffmpegkmp_writer_track *track,
        const ffmpegkmp_frame *frame,
        int64_t pts_ns) {
    ffmpegkmp_frame *converted = NULL;
    int64_t pts;
    int result;
    if (frame->frame->width != track->config.width || frame->frame->height != track->config.height)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if (!track->started && (result = ffmpegkmp_start_video_track(writer, track, frame->frame)) < 0)
        return result;
    pts = av_rescale_q_rnd(pts_ns, FFMPEGKMP_NANOSECONDS, track->time_base, AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX);
    if (track->last_pts != AV_NOPTS_VALUE && pts <= track->last_pts)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if (ffmpegkmp_takes_as_is(track, frame)) {
        result = av_frame_ref(track->input, frame->frame);
    } else {
        /* Converted here, on the track's thread, so it overlaps with whatever renders the next frame. */
        result = ffmpegkmp_frame_pool_get(track->pool, &track->input_format, track->config.width,
                track->config.height, &converted);
        if (result >= 0)
            result = ffmpegkmp_frame_convert_with(track->converter, converted, frame->frame, 0);
        if (result >= 0)
            result = av_frame_ref(track->input, converted->frame);
        ffmpegkmp_frame_unref(converted);
    }
    if (result < 0)
        return result;
    track->input->pts = pts;
    track->input->duration = 0;
    track->input->pict_type = AV_PICTURE_TYPE_NONE;
    result = ffmpegkmp_encode(writer, track, track->input);
    av_frame_unref(track->input);
    if (result >= 0)
        track->last_pts = pts;
    return result;
}

int ffmpegkmp_writer_write_video(
        ffmpegkmp_writer *writer,
        int32_t index,
        const ffmpegkmp_frame *frame,
        int64_t pts_ns) {
    ffmpegkmp_writer_track *track = ffmpegkmp_track(writer, index, AVMEDIA_TYPE_VIDEO);
    int result;
    if (!track || track->packets || !frame || pts_ns < 0)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if ((result = ffmpegkmp_writer_can_write(writer, track)) < 0 ||
            (result = ffmpegkmp_begin_track_call(writer, track)) < 0)
        return result;
    return ffmpegkmp_end_track_call(track, ffmpegkmp_write_video(writer, track, frame, pts_ns));
}

/* Encodes whole encoder frames from the FIFO; at the end, what is left as a short last frame. */
static int ffmpegkmp_encode_audio(ffmpegkmp_writer *writer, ffmpegkmp_writer_track *track, int final) {
    const int frame_size = track->encoder->frame_size;
    int result = 0;
    while (result >= 0 && av_audio_fifo_size(track->fifo) >= (final ? 1 : frame_size)) {
        AVFrame *frame = av_frame_alloc();
        const int count = FFMIN(frame_size, av_audio_fifo_size(track->fifo));
        if (!frame)
            return AVERROR(ENOMEM);
        frame->nb_samples = count;
        frame->format = AV_SAMPLE_FMT_FLTP;
        frame->sample_rate = track->encoder->sample_rate;
        if ((result = av_channel_layout_copy(&frame->ch_layout, &track->encoder->ch_layout)) >= 0 &&
                (result = av_frame_get_buffer(frame, 0)) >= 0 &&
                (result = av_audio_fifo_read(track->fifo, (void **)frame->data, count)) >= 0) {
            frame->pts = track->samples;
            track->samples += count;
            result = ffmpegkmp_encode(writer, track, frame);
        }
        av_frame_free(&frame);
    }
    return result;
}

int ffmpegkmp_writer_write_audio(
        ffmpegkmp_writer *writer,
        int32_t index,
        const float *samples,
        int32_t frames) {
    ffmpegkmp_writer_track *track = ffmpegkmp_track(writer, index, AVMEDIA_TYPE_AUDIO);
    void *planes[8];
    int channels;
    int channel;
    int frame;
    int result;
    if (!track || !samples || frames < 0)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if ((result = ffmpegkmp_writer_can_write(writer, track)) < 0 ||
            (result = ffmpegkmp_begin_track_call(writer, track)) < 0)
        return result;
    if (frames == 0)
        return ffmpegkmp_end_track_call(track, 0);
    channels = track->encoder->ch_layout.nb_channels;
    if (frames > track->planar_frames) {
        float *grown = av_realloc_array(track->planar, (size_t)frames * channels, sizeof(*grown));
        if (!grown)
            return ffmpegkmp_end_track_call(track, AVERROR(ENOMEM));
        track->planar = grown;
        track->planar_frames = frames;
    }
    for (channel = 0; channel < channels; channel++) {
        float *plane = track->planar + (size_t)channel * frames;
        for (frame = 0; frame < frames; frame++)
            plane[frame] = samples[(size_t)frame * channels + channel];
        planes[channel] = plane;
    }
    if ((result = av_audio_fifo_write(track->fifo, planes, frames)) < 0)
        return ffmpegkmp_end_track_call(track, result);
    return ffmpegkmp_end_track_call(track, ffmpegkmp_encode_audio(writer, track, 0));
}

static enum AVCodecID ffmpegkmp_codec_id(int codec) {
    if (codec == FFMPEGKMP_VIDEO_CODEC_H264)
        return AV_CODEC_ID_H264;
    return codec == FFMPEGKMP_VIDEO_CODEC_HEVC ? AV_CODEC_ID_HEVC : AV_CODEC_ID_AV1;
}

int ffmpegkmp_writer_add_packet_track(ffmpegkmp_writer *writer, const ffmpegkmp_video_encoder_config *config) {
    ffmpegkmp_writer_track track;
    int result;
    if (!writer)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if ((result = ffmpegkmp_validate_video_config(config)) < 0)
        return result;
    ffmpegkmp_init_track(&track, AVMEDIA_TYPE_VIDEO);
    track.config = *config;
    track.packets = 1;
    /* The time base an encoder of the config would have. */
    track.time_base = config->frame_rate_num > 0
            ? (AVRational){ config->frame_rate_den, config->frame_rate_num }
            : FFMPEGKMP_VFR_TIME_BASE;
    if (!(track.packet = av_packet_alloc()))
        return AVERROR(ENOMEM);
    return ffmpegkmp_attach_track(writer, &track, 0);
}

/* The parameter sets a first packet carries in band, as the container's global header. */
static int ffmpegkmp_extract_extradata(AVCodecParameters *parameters, const AVPacket *packet) {
    const AVBitStreamFilter *filter = av_bsf_get_by_name("extract_extradata");
    AVBSFContext *bsf = NULL;
    AVPacket *copy = NULL;
    const uint8_t *data;
    size_t size = 0;
    int result;
    if (!filter)
        return 0;
    if ((result = av_bsf_alloc(filter, &bsf)) < 0)
        return result;
    if ((result = avcodec_parameters_copy(bsf->par_in, parameters)) >= 0 && (result = av_bsf_init(bsf)) >= 0)
        result = (copy = av_packet_clone(packet)) ? av_bsf_send_packet(bsf, copy) : AVERROR(ENOMEM);
    if (result >= 0)
        result = av_bsf_receive_packet(bsf, copy);
    if (result >= 0 && (data = av_packet_get_side_data(copy, AV_PKT_DATA_NEW_EXTRADATA, &size)) && size > 0) {
        if (!(parameters->extradata = av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE))) {
            result = AVERROR(ENOMEM);
        } else {
            memcpy(parameters->extradata, data, size);
            parameters->extradata_size = (int)size;
        }
    }
    av_packet_free(&copy);
    av_bsf_free(&bsf);
    return result < 0 ? result : 0;
}

/* A packet track's stream parameters, from its config and first packet, under the lock; then the header once every track has started. */
static int ffmpegkmp_start_packet_track(
        ffmpegkmp_writer *writer,
        ffmpegkmp_writer_track *track,
        const AVPacket *first,
        const uint8_t *extradata,
        int32_t extradata_size) {
    const ffmpegkmp_video_encoder_config *config = &track->config;
    AVCodecParameters *parameters = track->stream->codecpar;
    AVFrameSideData **side_data = NULL;
    int side_data_count = 0;
    int result = 0;
    pthread_mutex_lock(&writer->mux);
    parameters->codec_type = AVMEDIA_TYPE_VIDEO;
    parameters->codec_id = ffmpegkmp_codec_id(config->codec);
    parameters->width = config->width;
    parameters->height = config->height;
    parameters->format = ffmpegkmp_is_ten_bit(config) ? AV_PIX_FMT_YUV420P10 : AV_PIX_FMT_YUV420P;
    parameters->sample_aspect_ratio = (AVRational){ 1, 1 };
    parameters->color_range = AVCOL_RANGE_MPEG;
    ffmpegkmp_video_colour(config, &parameters->color_primaries, &parameters->color_trc, &parameters->color_space);
    parameters->chroma_location = AVCHROMA_LOC_LEFT;
    if (config->frame_rate_num > 0) {
        parameters->framerate = (AVRational){ config->frame_rate_num, config->frame_rate_den };
        track->stream->avg_frame_rate = parameters->framerate;
    }
    ffmpegkmp_tag_stream(writer, parameters);
    track->stream->time_base = track->time_base;
    if (extradata && extradata_size > 0) {
        if ((parameters->extradata = av_mallocz(extradata_size + AV_INPUT_BUFFER_PADDING_SIZE))) {
            memcpy(parameters->extradata, extradata, extradata_size);
            parameters->extradata_size = extradata_size;
        } else {
            result = AVERROR(ENOMEM);
        }
    } else if (first) {
        result = ffmpegkmp_extract_extradata(parameters, first);
    }
    if (result >= 0 && (result = ffmpegkmp_attach_hdr_metadata(&side_data, &side_data_count, config, NULL)) >= 0)
        result = ffmpegkmp_copy_hdr_side_data(parameters, side_data, side_data_count);
    av_frame_side_data_free(&side_data, &side_data_count);
    if (result >= 0) {
        track->started = 1;
        result = ffmpegkmp_write_header_locked(writer, 0);
    }
    pthread_mutex_unlock(&writer->mux);
    return result;
}

int ffmpegkmp_writer_write_packet(
        ffmpegkmp_writer *writer,
        int32_t index,
        const uint8_t *data,
        int32_t size,
        int64_t pts_ns,
        int64_t duration_ns,
        int32_t key_frame,
        const uint8_t *extradata,
        int32_t extradata_size) {
    ffmpegkmp_writer_track *track = ffmpegkmp_track(writer, index, AVMEDIA_TYPE_VIDEO);
    AVPacket *packet;
    int64_t pts;
    int result;
    if (!track || !track->packets || !data || size <= 0 || pts_ns < 0 || duration_ns < 0 || extradata_size < 0)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if ((result = ffmpegkmp_writer_can_write(writer, track)) < 0)
        return result;
    pts = av_rescale_q_rnd(pts_ns, FFMPEGKMP_NANOSECONDS, track->time_base, AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX);
    if (track->last_pts != AV_NOPTS_VALUE && pts <= track->last_pts)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    packet = track->packet;
    if ((result = av_new_packet(packet, size)) < 0)
        return result;
    memcpy(packet->data, data, size);
    /* The encoder does not reorder frames, so packets decode in the order they are shown. */
    packet->pts = pts;
    packet->dts = pts;
    packet->duration = av_rescale_q(duration_ns, FFMPEGKMP_NANOSECONDS, track->time_base);
    if (key_frame)
        packet->flags |= AV_PKT_FLAG_KEY;
    if (!track->started && (result = ffmpegkmp_start_packet_track(writer, track, packet, extradata, extradata_size)) < 0) {
        av_packet_unref(packet);
        return result;
    }
    track->last_pts = pts;
    return ffmpegkmp_mux(writer, track, packet);
}

int ffmpegkmp_writer_end_track(ffmpegkmp_writer *writer, int32_t index) {
    ffmpegkmp_writer_track *track = writer && index >= 0 && index < writer->track_count
            ? &writer->tracks[index]
            : NULL;
    int result;
    if (!track)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if ((result = ffmpegkmp_writer_can_write(writer, track)) < 0 ||
            (result = ffmpegkmp_begin_track_call(writer, track)) < 0)
        return result;
    /* A video track that never had a frame still needs its encoder's parameters for the header. */
    if (!track->started) {
        result = track->packets
                ? ffmpegkmp_start_packet_track(writer, track, NULL, NULL, 0)
                : ffmpegkmp_start_video_track(writer, track, NULL);
    }
    if (result >= 0 && track->type == AVMEDIA_TYPE_AUDIO)
        result = ffmpegkmp_encode_audio(writer, track, 1);
    if (result >= 0 && !track->packets)
        result = ffmpegkmp_encode(writer, track, NULL);
    track->ended = 1;
    /* Freed on the thread that opened it, as MediaCodec needs. */
    avcodec_free_context(&track->encoder);
    return ffmpegkmp_end_track_call(track, result);
}

void ffmpegkmp_writer_release_track(ffmpegkmp_writer *writer, int32_t index) {
    if (writer && index >= 0 && index < writer->track_count)
        avcodec_free_context(&writer->tracks[index].encoder);
}

int ffmpegkmp_writer_finish(ffmpegkmp_writer *writer, ffmpegkmp_writer_result *result_info) {
    int64_t first = INT64_MAX;
    int64_t end = INT64_MIN;
    int index;
    int result;
    if (!writer)
        return FFPLAYKMP_ERROR_INVALID_ARGUMENT;
    if (atomic_load(&writer->aborted))
        return AVERROR_EXIT;
    if (writer->finished)
        return FFPLAYKMP_ERROR_INVALID_STATE;
    for (index = 0; index < writer->track_count; index++) {
        const ffmpegkmp_writer_track *track = &writer->tracks[index];
        if (!track->ended)
            return FFPLAYKMP_ERROR_INVALID_STATE;
        if (track->first_pts != AV_NOPTS_VALUE) {
            first = FFMIN(first, av_rescale_q(track->first_pts, track->time_base, FFMPEGKMP_MICROSECONDS));
            end = FFMAX(end, av_rescale_q(track->end_pts, track->time_base, FFMPEGKMP_MICROSECONDS));
        }
    }
    writer->finished = 1;
    pthread_mutex_lock(&writer->mux);
    result = ffmpegkmp_write_header_locked(writer, 1);
    if (result >= 0)
        result = av_write_trailer(writer->format);
    pthread_mutex_unlock(&writer->mux);
    if (result_info && result_info->size >= sizeof(*result_info)) {
        result_info->bytes = writer->format->pb ? avio_size(writer->format->pb) : -1;
        if (result_info->bytes < 0 && writer->io)
            result_info->bytes = writer->io->position;
        result_info->duration_us = first == INT64_MAX ? 0 : end - first;
    }
    if (writer->format->pb) {
        if (writer->mounted) {
            avio_flush(writer->format->pb);
            if (writer->format->pb->error < 0 && result >= 0)
                result = writer->format->pb->error;
        } else {
            int closed = avio_closep(&writer->format->pb);
            if (closed < 0 && result >= 0)
                result = closed;
        }
    }
    if (writer->resource_opened) {
        writer->host.callback(writer->host.opaque, writer->resource_id, FFPLAYKMP_IO_CLOSE, 0, NULL, 0);
        writer->resource_opened = 0;
    }
    return result;
}

void ffmpegkmp_writer_abort(ffmpegkmp_writer *writer) {
    if (writer)
        atomic_store(&writer->aborted, 1);
}

void ffmpegkmp_writer_destroy(ffmpegkmp_writer *writer) {
    int index;
    if (!writer)
        return;
    for (index = 0; index < writer->track_count; index++)
        ffmpegkmp_free_track(&writer->tracks[index]);
    for (index = 0; index < writer->pending_count; index++)
        av_packet_free(&writer->pending[index]);
    av_freep(&writer->pending);
    if (writer->format) {
        if (writer->mounted && writer->format->pb) {
            av_freep(&writer->format->pb->buffer);
            avio_context_free(&writer->format->pb);
        } else if (writer->format->pb) {
            avio_closep(&writer->format->pb);
        }
        avformat_free_context(writer->format);
    }
    if (writer->resource_opened && writer->host.callback)
        writer->host.callback(writer->host.opaque, writer->resource_id, FFPLAYKMP_IO_CLOSE, 0, NULL, 0);
    av_freep(&writer->io);
    av_freep(&writer->url);
    pthread_mutex_destroy(&writer->mux);
    free(writer);
}
