// SPDX-License-Identifier: LGPL-2.1-or-later
#ifndef FFMPEGKMP_WRITER_H
#define FFMPEGKMP_WRITER_H

#include <stdint.h>

#include "ffmpegkmp_frame.h"
#include "ffplaykmp_player.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Encoders and a muxer for one output, on FFmpeg's libraries directly, so a
 * writer runs alongside commands, decoders and other writers.
 *
 * Threading: a track's add, write and end calls must come from one thread
 * (MediaCodec binds an encoder to the thread that opened it). Calls for
 * different tracks may run at once; the muxer is shared under a lock. finish
 * runs once every track has ended, and destroy after it or instead of it.
 * abort is safe from any thread.
 *
 * The muxer writes its header once every track has started: an audio track
 * when it is added, a video track at its first frame. Packets encoded before
 * then wait in the writer.
 */
typedef struct ffmpegkmp_writer ffmpegkmp_writer;

/* The values are the Kotlin enums' ordinals. */
typedef enum ffmpegkmp_container {
    FFMPEGKMP_CONTAINER_MP4 = 0,
    /* MP4 as fragments, which a stream output can take: nothing is rewritten. */
    FFMPEGKMP_CONTAINER_FRAGMENTED_MP4 = 1,
    FFMPEGKMP_CONTAINER_MATROSKA = 2,
    FFMPEGKMP_CONTAINER_MPEGTS = 3,
} ffmpegkmp_container;

typedef enum ffmpegkmp_video_codec {
    FFMPEGKMP_VIDEO_CODEC_H264 = 0,
    FFMPEGKMP_VIDEO_CODEC_HEVC = 1,
    FFMPEGKMP_VIDEO_CODEC_AV1 = 2,
} ffmpegkmp_video_codec;

typedef enum ffmpegkmp_dynamic_range {
    /* BT.709, at 8 bits or 10. */
    FFMPEGKMP_DYNAMIC_RANGE_SDR = 0,
    /* 10-bit BT.2020 PQ, with mastering-display and content-light metadata. */
    FFMPEGKMP_DYNAMIC_RANGE_HDR10 = 1,
    /* 10-bit BT.2020 HLG. */
    FFMPEGKMP_DYNAMIC_RANGE_HLG = 2,
} ffmpegkmp_dynamic_range;

/* The same values as the decoders' FFPLAYKMP_DECODER_* preferences. */
typedef enum ffmpegkmp_encoder_preference {
    FFMPEGKMP_ENCODER_AUTO = 0,
    FFMPEGKMP_ENCODER_REQUIRE_HARDWARE = 1,
    FFMPEGKMP_ENCODER_SOFTWARE = 2,
} ffmpegkmp_encoder_preference;

/* HDR10 static metadata. Chromaticities are CIE 1931 x and y; luminances are in cd/m². */
typedef struct ffmpegkmp_hdr_metadata {
    uint32_t size;
    int32_t has_mastering_display;
    /* Red, green and blue, then the white point. */
    double primaries_x[3];
    double primaries_y[3];
    double white_point_x;
    double white_point_y;
    double min_luminance;
    double max_luminance;
    int32_t has_content_light;
    int32_t max_content_light_level;
    int32_t max_frame_average_light_level;
} ffmpegkmp_hdr_metadata;

typedef struct ffmpegkmp_video_encoder_config {
    uint32_t size;
    int32_t width;
    int32_t height;
    /* Frames per second as a fraction; 0/0 for a variable frame rate. */
    int32_t frame_rate_num;
    int32_t frame_rate_den;
    int32_t codec;
    int32_t dynamic_range;
    int32_t preference;
    /* Bits per second; 0 for one that suits the size and rate. */
    int64_t bit_rate;
    int64_t keyframe_interval_us;
    /* HDR10 only. Without it the writer takes the first frame's metadata. */
    int32_t has_hdr_metadata;
    ffmpegkmp_hdr_metadata hdr_metadata;
    /* 8 or 10; 0 for the dynamic range's own, 8 for SDR and 10 for HDR, which takes 10 only. */
    int32_t bit_depth;
    /*
     * An encoder FFmpeg knows by name, as `ffmpeg -c:v` takes it ("libaom-av1", "h264_mediacodec"),
     * which the writer opens alone: no other is tried and `preference` is ignored. It must encode
     * `codec`. NULL to choose an encoder by `preference`.
     */
    const char *encoder_name;
    /*
     * AVOptions to open the named encoder with, generic (b, g, profile, ...) and its own alike:
     * `options_size` bytes holding, for each option, its key and then its value, each a
     * NUL-terminated string. Each is set on the encoder as avcodec_open2 sets its options
     * dictionary's (av_opt_set, private options included), after the fields the writer sets from
     * this config, so an option overrides one (a "b" option `bit_rate`, a "g" option the keyframe
     * interval), except the size, the pixel format, the colour description and the global header
     * the container needs, which the frames and the muxer rely on: an option that changes one
     * fails the open. An option the encoder does not recognise, one that is no video encoding
     * option, or a value it does not take fails it too. NULL and 0 for none.
     */
    const char *options;
    int32_t options_size;
} ffmpegkmp_video_encoder_config;

/* What a video track's encoder takes. */
typedef struct ffmpegkmp_video_track_info {
    uint32_t size;
    /* Frames in this format reach the encoder without a conversion. */
    ffmpegkmp_frame_format input_format;
    int32_t hardware;
    /*
     * Bits per second the encoder opened with: the config's, else one that suits the size and rate,
     * or a named encoder's option's; 0 when an option leaves the rate to the encoder (CRF, CQ).
     */
    int64_t bit_rate;
    char encoder[64];
    /* Why no encoder opened, when the call that filled this failed or answered 0; empty otherwise. */
    char error[256];
} ffmpegkmp_video_track_info;

typedef struct ffmpegkmp_audio_encoder_config {
    uint32_t size;
    int32_t sample_rate;
    int32_t channels;
    /* Bits per second; 0 for 64 kbit/s per channel. */
    int64_t bit_rate;
} ffmpegkmp_audio_encoder_config;

typedef struct ffmpegkmp_writer_result {
    uint32_t size;
    /* The output's size in bytes, -1 where it cannot be told. */
    int64_t bytes;
    /* From the first packet to the end of the last, across every track. */
    int64_t duration_us;
} ffmpegkmp_writer_result;

FFPLAYKMP_EXPORT void ffmpegkmp_video_encoder_config_init(ffmpegkmp_video_encoder_config *config);
FFPLAYKMP_EXPORT void ffmpegkmp_video_track_info_init(ffmpegkmp_video_track_info *info);
FFPLAYKMP_EXPORT void ffmpegkmp_audio_encoder_config_init(ffmpegkmp_audio_encoder_config *config);
FFPLAYKMP_EXPORT void ffmpegkmp_writer_result_init(ffmpegkmp_writer_result *result);

/*
 * Whether this build and platform have an encoder that opens with `config`,
 * which it opens and closes to find out: 1 when one does, describing it in
 * `info` (may be NULL), 0 when none does, saying why in `info->error`, or a
 * negative error for an invalid config. With `config->encoder_name` only that
 * encoder, with its options, is asked.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_writer_can_encode(
        const ffmpegkmp_video_encoder_config *config,
        ffmpegkmp_video_track_info *info);

/*
 * Creates a writer for `output`, a path or an "ffmpegkmp:<id>" resource served
 * by `io_callback`, which it opens for writing. `fast_start` moves an MP4's
 * index to the front, which needs an output that can be read back. An output
 * that cannot seek takes only a container that never rewrites: fragmented MP4,
 * Matroska or MPEG-TS. `timeout_us` bounds each write and end of a track, 0 for
 * none: an encoder that takes frames without ever giving packets, as some
 * emulators' MediaCodec encoders do, fails the call and its track with
 * FFPLAYKMP_ERROR_TIMED_OUT instead of blocking. Returns NULL and stores the
 * negative error in *error on failure.
 */
FFPLAYKMP_EXPORT ffmpegkmp_writer *ffmpegkmp_writer_create(
        const char *output,
        int32_t container,
        int32_t fast_start,
        int64_t timeout_us,
        ffplaykmp_io_callback io_callback,
        void *io_opaque,
        int32_t *error);
/*
 * Opens the encoder for a video track, trying the hardware encoders and then
 * the software ones as `config->preference` allows, and describes it in
 * `info`. Returns the track's index, or FFPLAYKMP_ERROR_UNSUPPORTED when no
 * encoder in this build opens with the config. Tracks are added before the
 * first write.
 *
 * With `config->encoder_name` it opens that encoder with its options, or fails
 * saying why in `info->error`: FFPLAYKMP_ERROR_UNSUPPORTED when this build has
 * no encoder of that name, FFPLAYKMP_ERROR_INVALID_ARGUMENT when it encodes
 * another codec or an option changes what the writer relies on,
 * AVERROR_OPTION_NOT_FOUND when the encoder does not recognise an option, and
 * FFPLAYKMP_ERROR_INVALID_ARGUMENT as well for an option that is no video
 * encoding option, and the open's own error otherwise, or the option's for a
 * value the encoder does not take. `info->hardware` is then 1 for the platform
 * codec wrappers that `preference` tries as hardware (MediaCodec,
 * VideoToolbox, NVENC, Quick Sync, AMF, Media Foundation), whichever component
 * the platform runs behind one, which FFmpeg does not tell.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_writer_add_video_track(
        ffmpegkmp_writer *writer,
        const ffmpegkmp_video_encoder_config *config,
        ffmpegkmp_video_track_info *info);
/* Adds an AAC track taking interleaved float samples. Returns the track's index. */
FFPLAYKMP_EXPORT int ffmpegkmp_writer_add_audio_track(
        ffmpegkmp_writer *writer,
        const ffmpegkmp_audio_encoder_config *config);
/*
 * Encodes `frame`, shown from `pts_ns`, which must increase from frame to
 * frame. A frame of the track's input format goes to the encoder as it is;
 * another is converted once into it, on this thread. The frame must have the
 * track's size. The writer keeps its own reference while it needs one.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_writer_write_video(
        ffmpegkmp_writer *writer,
        int32_t track,
        const ffmpegkmp_frame *frame,
        int64_t pts_ns);
/* Encodes `frames` frames of interleaved float samples, each `channels` samples wide. */
FFPLAYKMP_EXPORT int ffmpegkmp_writer_write_audio(
        ffmpegkmp_writer *writer,
        int32_t track,
        const float *samples,
        int32_t frames);
/*
 * Adds a video track for packets another encoder produced, such as WebCodecs in the browser: its
 * stream takes `config`'s codec, size, rate and dynamic range, and starts at its first packet.
 * Returns the track's index; ffmpegkmp_writer_write_packet feeds it.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_writer_add_packet_track(
        ffmpegkmp_writer *writer,
        const ffmpegkmp_video_encoder_config *config);
/*
 * Muxes one packet of a packet track, shown from `pts_ns` for `duration_ns` (0 when not known).
 * The encoder must not reorder frames: packets come in the order they are shown, so each pts
 * is later than the one before. `extradata` holds the codec's parameter sets (avcC, hvcC or
 * av1C) with the first packet; without them the writer takes them from that packet.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_writer_write_packet(
        ffmpegkmp_writer *writer,
        int32_t track,
        const uint8_t *data,
        int32_t size,
        int64_t pts_ns,
        int64_t duration_ns,
        int32_t key_frame,
        const uint8_t *extradata,
        int32_t extradata_size);
/*
 * Turns a video track that has taken no frame into a packet track, as if it had been added with
 * ffmpegkmp_writer_add_packet_track: frees its encoder, on the thread that opened it, and
 * ffmpegkmp_writer_write_video fails for it from then on. For a platform encoder that takes its
 * input some other way, such as an Android MediaCodec's input Surface, whose packets
 * ffmpegkmp_writer_write_packet takes. Fails with FFPLAYKMP_ERROR_INVALID_STATE once the track
 * has taken a frame or is a packet track already, leaving it as it was.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_writer_use_packets(ffmpegkmp_writer *writer, int32_t track);
/* Drains the track's encoder and frees it, on its thread; the track takes no more input. */
FFPLAYKMP_EXPORT int ffmpegkmp_writer_end_track(ffmpegkmp_writer *writer, int32_t track);
/*
 * The packets the track has handed the muxer so far: its encoder's, or those
 * ffmpegkmp_writer_write_packet took. Once the track has ended, a track that took frames and
 * shows 0 had an encoder that gave nothing. FFPLAYKMP_ERROR_INVALID_ARGUMENT for no such track.
 */
FFPLAYKMP_EXPORT int64_t ffmpegkmp_writer_track_packets(const ffmpegkmp_writer *writer, int32_t track);
/*
 * For tests only: the track drops every packet instead of writing it, as an encoder that takes
 * frames and gives nothing would. Before the first write; FFPLAYKMP_ERROR_INVALID_STATE after.
 */
FFPLAYKMP_EXPORT int ffmpegkmp_writer_discard_packets(ffmpegkmp_writer *writer, int32_t track);
/* Frees the track's encoder without draining it, on its thread: for an output that is abandoned. */
FFPLAYKMP_EXPORT void ffmpegkmp_writer_release_track(ffmpegkmp_writer *writer, int32_t track);
/* Writes the index and closes the output, once every track has ended. */
FFPLAYKMP_EXPORT int ffmpegkmp_writer_finish(ffmpegkmp_writer *writer, ffmpegkmp_writer_result *result);
/* Makes blocked output and encoder calls return; the writer must still be destroyed. */
FFPLAYKMP_EXPORT void ffmpegkmp_writer_abort(ffmpegkmp_writer *writer);
/* Frees the writer. An unfinished output is left incomplete. */
FFPLAYKMP_EXPORT void ffmpegkmp_writer_destroy(ffmpegkmp_writer *writer);

#ifdef __cplusplus
}
#endif

#endif
