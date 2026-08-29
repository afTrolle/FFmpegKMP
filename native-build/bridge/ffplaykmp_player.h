// SPDX-License-Identifier: LGPL-2.1-or-later
#ifndef FFPLAYKMP_PLAYER_H
#define FFPLAYKMP_PLAYER_H

#include <stdint.h>

#if defined(__ANDROID__)
#include <jni.h>
#endif

#if defined(_WIN32)
#define FFPLAYKMP_EXPORT __declspec(dllexport)
#else
#define FFPLAYKMP_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ffplaykmp_player ffplaykmp_player;

typedef enum ffplaykmp_state {
    FFPLAYKMP_STATE_IDLE = 0,
    FFPLAYKMP_STATE_PREPARING = 1,
    FFPLAYKMP_STATE_WAITING_FOR_OUTPUT = 2,
    FFPLAYKMP_STATE_READY = 3,
    FFPLAYKMP_STATE_PLAYING = 4,
    FFPLAYKMP_STATE_PAUSED = 5,
    FFPLAYKMP_STATE_SEEKING = 6,
    FFPLAYKMP_STATE_ENDED = 7,
    FFPLAYKMP_STATE_STOPPED = 8,
    FFPLAYKMP_STATE_FAILED = 9,
} ffplaykmp_state;

typedef enum ffplaykmp_decoder_preference {
    FFPLAYKMP_DECODER_AUTO = 0,
    FFPLAYKMP_DECODER_REQUIRE_HARDWARE = 1,
    FFPLAYKMP_DECODER_SOFTWARE = 2,
} ffplaykmp_decoder_preference;

typedef enum ffplaykmp_decoder_kind {
    FFPLAYKMP_DECODER_UNKNOWN = 0,
    FFPLAYKMP_DECODER_HARDWARE = 1,
    FFPLAYKMP_DECODER_SOFTWARE_ACTIVE = 2,
} ffplaykmp_decoder_kind;

typedef enum ffplaykmp_hdr_type {
    FFPLAYKMP_HDR_SDR = 0,
    FFPLAYKMP_HDR_HDR10 = 1,
    FFPLAYKMP_HDR_HLG = 2,
    FFPLAYKMP_HDR_HDR10_PLUS = 3,
    FFPLAYKMP_HDR_DOLBY_VISION = 4,
    FFPLAYKMP_HDR_UNKNOWN = 5,
} ffplaykmp_hdr_type;

typedef enum ffplaykmp_source_flags {
    FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH = 1u << 0,
} ffplaykmp_source_flags;

typedef enum ffplaykmp_output_flags {
    FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT = 1u << 0,
    FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD = 1u << 1,
    FFPLAYKMP_OUTPUT_ZERO_COPY = 1u << 2,
    FFPLAYKMP_OUTPUT_PROTECTED_CONTENT = 1u << 3,
    /** Software-uploaded HDR frames must be converted to bounded BT.709/sRGB. */
    FFPLAYKMP_OUTPUT_TONE_MAP_HDR_TO_SDR = 1u << 4,
} ffplaykmp_output_flags;

typedef struct ffplaykmp_configuration {
    uint32_t size;
    ffplaykmp_decoder_preference decoder_preference;
} ffplaykmp_configuration;

typedef struct ffplaykmp_output_capabilities {
    uint32_t size;
    uint32_t flags;
} ffplaykmp_output_capabilities;

typedef struct ffplaykmp_snapshot {
    uint32_t size;
    ffplaykmp_state state;
    int64_t position_us;
    int64_t duration_us;
    uint32_t queue_serial;
    uint32_t output_flags;
    int32_t last_error;
    int32_t video_width;
    int32_t video_height;
    ffplaykmp_decoder_kind active_decoder;
    int32_t pixel_format;
    int32_t bit_depth;
    int32_t sample_aspect_ratio_num;
    int32_t sample_aspect_ratio_den;
    double rotation_degrees;
    int32_t color_primaries;
    int32_t color_transfer;
    int32_t color_space;
    int32_t color_range;
    int32_t chroma_location;
    ffplaykmp_hdr_type hdr_type;
    int32_t mastering_has_primaries;
    int32_t mastering_has_luminance;
    double mastering_red_x;
    double mastering_red_y;
    double mastering_green_x;
    double mastering_green_y;
    double mastering_blue_x;
    double mastering_blue_y;
    double mastering_white_x;
    double mastering_white_y;
    double mastering_min_luminance;
    double mastering_max_luminance;
    int32_t content_light_present;
    uint32_t max_content_light_level;
    uint32_t max_frame_average_light_level;
    uint64_t dropped_frames;
} ffplaykmp_snapshot;

typedef struct ffplaykmp_video_frame {
    uint32_t size;
    const uint8_t *rgba;
    uint64_t rgba_size;
    int32_t width;
    int32_t height;
    int32_t stride;
    int64_t presentation_time_us;
    uint32_t queue_serial;
} ffplaykmp_video_frame;

typedef enum ffplaykmp_platform_frame_kind {
    FFPLAYKMP_PLATFORM_FRAME_UNKNOWN = 0,
    FFPLAYKMP_PLATFORM_FRAME_CV_PIXEL_BUFFER = 1,
} ffplaykmp_platform_frame_kind;

typedef struct ffplaykmp_platform_video_frame {
    uint32_t size;
    ffplaykmp_platform_frame_kind kind;
    /** Borrowed platform handle, valid only for the duration of the callback. */
    void *handle;
    int32_t width;
    int32_t height;
    int64_t presentation_time_us;
    uint32_t queue_serial;
} ffplaykmp_platform_video_frame;

typedef enum ffplaykmp_io_operation {
    FFPLAYKMP_IO_OPEN = 0,
    FFPLAYKMP_IO_READ = 1,
    FFPLAYKMP_IO_WRITE = 2,
    FFPLAYKMP_IO_SIZE = 3,
    FFPLAYKMP_IO_CLOSE = 4,
} ffplaykmp_io_operation;

typedef int64_t (*ffplaykmp_io_callback)(
        void *opaque,
        int64_t resource_id,
        uint32_t operation,
        int64_t offset,
        uint8_t *data,
        uint64_t size);

typedef void (*ffplaykmp_state_callback)(
        void *opaque,
        const ffplaykmp_snapshot *snapshot);

/** The frame memory is valid only for the duration of this callback. */
typedef void (*ffplaykmp_video_frame_callback)(
        void *opaque,
        const ffplaykmp_video_frame *frame);

/** Returns non-zero when the borrowed platform frame was accepted synchronously. */
typedef int (*ffplaykmp_platform_video_frame_callback)(
        void *opaque,
        const ffplaykmp_platform_video_frame *frame);

FFPLAYKMP_EXPORT void ffplaykmp_configuration_default(
        ffplaykmp_configuration *configuration);
FFPLAYKMP_EXPORT void ffplaykmp_output_capabilities_init(
        ffplaykmp_output_capabilities *capabilities);
FFPLAYKMP_EXPORT void ffplaykmp_snapshot_init(ffplaykmp_snapshot *snapshot);
FFPLAYKMP_EXPORT const char *ffplaykmp_pixel_format_name(int32_t pixel_format);

FFPLAYKMP_EXPORT ffplaykmp_player *ffplaykmp_player_create(
        const ffplaykmp_configuration *configuration,
        ffplaykmp_state_callback callback,
        void *opaque);
FFPLAYKMP_EXPORT void ffplaykmp_player_destroy(ffplaykmp_player *player);
FFPLAYKMP_EXPORT void ffplaykmp_player_set_io_callback(
        ffplaykmp_player *player,
        ffplaykmp_io_callback callback,
        void *opaque);
FFPLAYKMP_EXPORT void ffplaykmp_player_set_video_frame_callback(
        ffplaykmp_player *player,
        ffplaykmp_video_frame_callback callback,
        void *opaque);
FFPLAYKMP_EXPORT void ffplaykmp_player_set_platform_video_frame_callback(
        ffplaykmp_player *player,
        ffplaykmp_platform_video_frame_callback callback,
        void *opaque);
#if defined(__ANDROID__)
/**
 * Attaches an Android Surface to the player. The player retains its own global
 * JNI reference until another surface is supplied, it is cleared, or the
 * player is destroyed. Secure playback additionally requires a DRM session;
 * secure != 0 is rejected until that session has been configured.
 */
FFPLAYKMP_EXPORT int ffplaykmp_player_set_android_surface(
        JNIEnv *env,
        jclass owner,
        jobject surface,
        ffplaykmp_player *player,
        int secure);
#endif
FFPLAYKMP_EXPORT void ffplaykmp_player_reset_cancel(ffplaykmp_player *player);
FFPLAYKMP_EXPORT int ffplaykmp_player_prepare(
        ffplaykmp_player *player,
        const char *input,
        uint32_t source_flags);
FFPLAYKMP_EXPORT int ffplaykmp_player_set_output(
        ffplaykmp_player *player,
        const ffplaykmp_output_capabilities *capabilities);
FFPLAYKMP_EXPORT void ffplaykmp_player_clear_output(ffplaykmp_player *player);
FFPLAYKMP_EXPORT int ffplaykmp_player_play(ffplaykmp_player *player);
FFPLAYKMP_EXPORT int ffplaykmp_player_pause(ffplaykmp_player *player);
FFPLAYKMP_EXPORT int ffplaykmp_player_seek(ffplaykmp_player *player, int64_t position_us);
FFPLAYKMP_EXPORT int ffplaykmp_player_stop(ffplaykmp_player *player);
FFPLAYKMP_EXPORT void ffplaykmp_player_cancel(ffplaykmp_player *player);
FFPLAYKMP_EXPORT int ffplaykmp_player_get_snapshot(
        const ffplaykmp_player *player,
        ffplaykmp_snapshot *snapshot);

/*
 * Flat callback facade used by the browser worker. It keeps C structure layout out of
 * JavaScript/Wasm interop while still running the exact same per-player engine.
 */
typedef void (*ffplaykmp_web_state_callback)(
        void *opaque,
        const char *snapshot_json,
        uint32_t snapshot_json_size);
typedef void (*ffplaykmp_web_video_frame_callback)(
        void *opaque,
        const uint8_t *rgba,
        uint32_t rgba_size,
        int32_t width,
        int32_t height,
        int32_t stride,
        int64_t presentation_time_us,
        uint32_t queue_serial);
typedef void (*ffplaykmp_web_decoder_config_callback)(
        void *opaque,
        const char *codec,
        const uint8_t *description,
        uint32_t description_size,
        int32_t coded_width,
        int32_t coded_height,
        int32_t color_primaries,
        int32_t color_transfer,
        int32_t color_space);
typedef void (*ffplaykmp_web_encoded_packet_callback)(
        void *opaque,
        const uint8_t *data,
        uint32_t data_size,
        int64_t timestamp_us,
        int64_t duration_us,
        int32_t key_frame,
        uint32_t queue_serial);

FFPLAYKMP_EXPORT ffplaykmp_player *ffplaykmp_web_player_create(
        int32_t decoder_preference,
        ffplaykmp_web_state_callback state_callback,
        ffplaykmp_web_video_frame_callback frame_callback,
        void *opaque);
FFPLAYKMP_EXPORT void ffplaykmp_web_player_destroy(ffplaykmp_player *player);
FFPLAYKMP_EXPORT int ffplaykmp_web_player_prepare_bytes(
        ffplaykmp_player *player,
        const uint8_t *bytes,
        uint32_t size,
        const char *extension,
        uint32_t source_flags);
FFPLAYKMP_EXPORT int ffplaykmp_web_player_set_output(
        ffplaykmp_player *player,
        uint32_t output_flags);
FFPLAYKMP_EXPORT void ffplaykmp_web_player_poll(ffplaykmp_player *player);
/**
 * Opens the prepared source as a packet stream for WebCodecs. The callbacks run
 * synchronously on the calling browser worker and borrowed bytes are valid only
 * for the duration of each callback.
 */
FFPLAYKMP_EXPORT int ffplaykmp_web_player_open_packets(
        ffplaykmp_player *player,
        ffplaykmp_web_decoder_config_callback callback,
        void *opaque);
FFPLAYKMP_EXPORT int ffplaykmp_web_player_read_packet(
        ffplaykmp_player *player,
        ffplaykmp_web_encoded_packet_callback callback,
        void *opaque);
FFPLAYKMP_EXPORT void ffplaykmp_web_player_close_packets(ffplaykmp_player *player);
FFPLAYKMP_EXPORT int ffplaykmp_web_player_set_webcodecs_output(
        ffplaykmp_player *player,
        uint32_t output_flags);
FFPLAYKMP_EXPORT int ffplaykmp_web_player_webcodecs_play(ffplaykmp_player *player);
FFPLAYKMP_EXPORT int ffplaykmp_web_player_webcodecs_pause(ffplaykmp_player *player);
FFPLAYKMP_EXPORT int ffplaykmp_web_player_webcodecs_seek(
        ffplaykmp_player *player,
        int64_t position_us);
FFPLAYKMP_EXPORT int ffplaykmp_web_player_webcodecs_presented(
        ffplaykmp_player *player,
        int64_t position_us,
        uint32_t queue_serial,
        int32_t dropped);
FFPLAYKMP_EXPORT void ffplaykmp_web_player_webcodecs_end(ffplaykmp_player *player);

#ifdef __cplusplus
}
#endif

#endif
