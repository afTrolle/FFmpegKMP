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

/*
 * Why a player whose output wanted a hardware decoder decodes in software:
 * this host has no hardware decoder for the stream, opening or decoding with
 * it failed, after which AUTO keeps to software for the source, or the
 * process's hardware decoder budget (ffplaykmp_decoder_budget_set) was full
 * when the decoder opened.
 */
typedef enum ffplaykmp_decoder_fallback {
    FFPLAYKMP_DECODER_FALLBACK_NONE = 0,
    FFPLAYKMP_DECODER_FALLBACK_UNSUPPORTED = 1,
    FFPLAYKMP_DECODER_FALLBACK_FAILED = 2,
    FFPLAYKMP_DECODER_FALLBACK_BUDGET = 3,
} ffplaykmp_decoder_fallback;

/*
 * Dynamic-metadata signalling a stream carries, as bits of ffplaykmp_snapshot.hdr_flags. Whether the
 * picture is HDR follows from color_transfer, never from these.
 */
enum {
    FFPLAYKMP_HDR_DOLBY_VISION = 1 << 0,
    FFPLAYKMP_HDR_HDR10_PLUS = 1 << 1,
};

/*
 * Errors the player reports itself. errno numbering differs between hosts
 * (Apple's ENOTSUP is 45, Emscripten follows WASI), so these fixed values sit
 * outside the errno range. Any other negative result is an FFmpeg AVERROR.
 */
typedef enum ffplaykmp_error {
    FFPLAYKMP_ERROR_INVALID_ARGUMENT = -1001,
    FFPLAYKMP_ERROR_INVALID_STATE = -1002,
    FFPLAYKMP_ERROR_ACCESS_DENIED = -1003,
    FFPLAYKMP_ERROR_UNSUPPORTED = -1004,
    FFPLAYKMP_ERROR_IO = -1005,
    FFPLAYKMP_ERROR_STALE = -1006,
    FFPLAYKMP_ERROR_TIMED_OUT = -1007,
    /* Every frame of a decoder's ring is held by the caller; the call fails and the decoder stays usable. */
    FFPLAYKMP_ERROR_RING_FULL = -1008,
    /* Every hardware decoder the process's budget allows is open; REQUIRE_HARDWARE fails with it. */
    FFPLAYKMP_ERROR_BUSY = -1009,
} ffplaykmp_error;

typedef enum ffplaykmp_source_flags {
    FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH = 1u << 0,
} ffplaykmp_source_flags;

typedef enum ffplaykmp_output_flags {
    FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT = 1u << 0,
    FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD = 1u << 1,
    FFPLAYKMP_OUTPUT_ZERO_COPY = 1u << 2,
    FFPLAYKMP_OUTPUT_PROTECTED_CONTENT = 1u << 3,
    /** The browser worker's RGBA frames of HDR sources are tone mapped to BT.709/sRGB. */
    FFPLAYKMP_OUTPUT_TONE_MAP_HDR_TO_SDR = 1u << 4,
} ffplaykmp_output_flags;

typedef struct ffplaykmp_configuration {
    uint32_t size;
    ffplaykmp_decoder_preference decoder_preference;
    /*
     * The software video decoder's frame and slice threads, 0 (the default)
     * for FFmpeg's automatic count capped at 8. Hardware decoders ignore it.
     */
    int32_t decoder_threads;
    /*
     * How long the player keeps its video decoder once it has nothing to
     * decode (paused, ended, waiting for an output, or after a preview), so
     * that a play or seek soon after needs no new one: 0 releases it as soon as
     * the player is idle, a negative value keeps it until the source, the
     * output's decoder or the Surface changes. ffplaykmp_configuration_default
     * sets 2 s. The decoder that is released frees its frames and threads; the
     * next play or seek opens one and decodes from the keyframe.
     */
    int64_t codec_release_delay_us;
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
    int32_t hdr_flags;
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
    /* Why the open decoder is not the hardware one the output wanted; NONE while it is, or none was wanted. */
    ffplaykmp_decoder_fallback decoder_fallback;
} ffplaykmp_snapshot;

/* One reference to a decoded frame (ffmpegkmp_frame.h). */
struct ffmpegkmp_frame;

typedef struct ffplaykmp_video_frame {
    uint32_t size;
    /*
     * The frame as decoded. A desktop hardware frame is downloaded on its
     * first map or conversion, once, into memory the player reuses. Borrowed
     * for the duration of the callback: ffmpegkmp_frame_ref it to keep it.
     */
    const struct ffmpegkmp_frame *frame;
    int32_t width;
    int32_t height;
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

typedef enum ffplaykmp_io_capability {
    FFPLAYKMP_IO_CAP_READ = 1,
    FFPLAYKMP_IO_CAP_WRITE = 2,
    FFPLAYKMP_IO_CAP_SEEK = 4,
} ffplaykmp_io_capability;

/**
 * Serves `ffmpegkmp:<id>` resources to every engine in the bridge: the command
 * tools' URL protocol, the players, the decoders and the writer. OPEN receives
 * FFmpeg's AVIO access flags in `offset` and returns a capability mask;
 * READ/WRITE return a byte count; SIZE returns the current size; CLOSE returns
 * zero. Any negative value is an I/O failure.
 */
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

/** The frame is borrowed only for the duration of this callback. */
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
/*
 * Makes host-reported media time the master clock for video scheduling,
 * normally the position the listener is hearing now. Report it regularly while
 * playing; it is extrapolated between reports, and play, pause, seek, stop and
 * reaching the end clear it. A negative time clears it, returning scheduling
 * to the wall clock.
 */
FFPLAYKMP_EXPORT void ffplaykmp_player_set_master_clock(
        ffplaykmp_player *player,
        int64_t media_time_us);
FFPLAYKMP_EXPORT int ffplaykmp_player_get_snapshot(
        const ffplaykmp_player *player,
        ffplaykmp_snapshot *snapshot);

/* What the players and decoders of the process have done since it started, for tests and benchmarks. */
typedef struct ffplaykmp_statistics {
    uint32_t size;
    /* Video decoders opened, hardware and software, by players and decoders alike. */
    uint64_t codec_opens;
    /* Of those, the ones closed again. */
    uint64_t codec_closes;
    /* Video packets the players read from their inputs. */
    uint64_t video_packets;
    /* Hardware frames downloaded into memory. */
    uint64_t downloads;
} ffplaykmp_statistics;

FFPLAYKMP_EXPORT void ffplaykmp_statistics_init(ffplaykmp_statistics *statistics);
FFPLAYKMP_EXPORT void ffplaykmp_get_statistics(ffplaykmp_statistics *statistics);

/*
 * The process's budget of hardware video decoders, shared by every player and
 * pull decoder (ffmpegkmp_decoder.h): at most `hardware_decoders` are open at
 * once, 0 for no limit. A decoder takes its place when it opens and gives it
 * back when it closes, which a player does once it has been idle for its
 * codec_release_delay_us. A decoder that finds the budget full opens in
 * software under FFPLAYKMP_DECODER_AUTO, reporting
 * FFPLAYKMP_DECODER_FALLBACK_BUDGET, and fails with FFPLAYKMP_ERROR_BUSY under
 * REQUIRE_HARDWARE; nothing waits for a place. Lowering the limit closes no
 * open decoder: the next ones open in software until fewer are open. Returns
 * FFPLAYKMP_ERROR_INVALID_ARGUMENT for a negative limit. The default is 8 on
 * macOS, where VideoToolbox gives sixteen 4K decoders a session each and then
 * decodes a few frames a second on every one, 4 on Android, where a phone opens
 * eight 4K MediaCodec decoders and fails the next, and on iOS, 8 on other
 * desktops, and 0 in the browser, whose player decodes in software only.
 */
FFPLAYKMP_EXPORT int ffplaykmp_decoder_budget_set(int32_t hardware_decoders);
/* The budget's limit, 0 for none. */
FFPLAYKMP_EXPORT int32_t ffplaykmp_decoder_budget_get(void);
/* The hardware decoders open now, by players and pull decoders together. */
FFPLAYKMP_EXPORT int32_t ffplaykmp_decoder_budget_in_use(void);

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
/* `pts` and `duration` are the packet's own, in the stream's time base; AV_NOPTS_VALUE and 0 when it has none. */
typedef void (*ffplaykmp_web_encoded_packet_callback)(
        void *opaque,
        const uint8_t *data,
        uint32_t data_size,
        int64_t timestamp_us,
        int64_t duration_us,
        int32_t key_frame,
        uint32_t queue_serial,
        int64_t pts,
        int64_t duration);

/* `decoder_threads` and `codec_release_delay_us` are ffplaykmp_configuration's. */
FFPLAYKMP_EXPORT ffplaykmp_player *ffplaykmp_web_player_create(
        int32_t decoder_preference,
        int32_t decoder_threads,
        int64_t codec_release_delay_us,
        ffplaykmp_web_state_callback state_callback,
        ffplaykmp_web_video_frame_callback frame_callback,
        void *opaque);
FFPLAYKMP_EXPORT void ffplaykmp_web_player_destroy(ffplaykmp_player *player);
/*
 * Prepares the `size` bytes at `bytes` as the player's input, opened and
 * probed once and kept until the next prepare, stop or destroy. The player
 * takes `bytes` over, whatever it returns: they must come from malloc (the
 * worker's _malloc), the caller neither frees nor touches them again, and the
 * player frees them with the next prepare or destroy. So the input lies in
 * the heap once.
 */
FFPLAYKMP_EXPORT int ffplaykmp_web_player_prepare_bytes(
        ffplaykmp_player *player,
        uint8_t *bytes,
        uint32_t size,
        const char *extension,
        uint32_t source_flags);
/*
 * The software output's frames: converted to RGBA once each, into one of two
 * buffers the page reads in turn, so a frame is never copied within the heap.
 */
FFPLAYKMP_EXPORT int ffplaykmp_web_player_set_output(
        ffplaykmp_player *player,
        uint32_t output_flags);
FFPLAYKMP_EXPORT void ffplaykmp_web_player_poll(ffplaykmp_player *player);
/*
 * Converts the software output's frames to `width` x `height`, the size the
 * page draws them at, from the next frame on; 0 x 0, the default, for each
 * frame's own size. A size larger than the frame, or not at its aspect to
 * within rounding (one asked for another source), gives the frame's own. The
 * frames keep their display aspect (ffmpegkmp_scaled_aspect).
 */
FFPLAYKMP_EXPORT void ffplaykmp_web_player_set_frame_size(ffplaykmp_player *player, int32_t width, int32_t height);
/* How often the player opened its input: once a prepare, the packet reader's included. */
FFPLAYKMP_EXPORT uint32_t ffplaykmp_web_player_input_opens(ffplaykmp_player *player);
/**
 * Reads the prepared input as a packet stream for WebCodecs: the input
 * prepare opened, from the start of its video stream, with no second open
 * (unless a failed software run closed it). It ends any software playback
 * first; the two outputs are exclusive, and a
 * software output (ffplaykmp_web_player_set_output) closes the packets. The
 * callbacks run synchronously on the calling browser worker and borrowed bytes
 * are valid only for the duration of each callback.
 */
FFPLAYKMP_EXPORT int ffplaykmp_web_player_open_packets(
        ffplaykmp_player *player,
        ffplaykmp_web_decoder_config_callback callback,
        void *opaque);
/*
 * ffplaykmp_web_player_prepare_bytes, without an output, then
 * ffplaykmp_web_player_open_packets: a demuxer for a decoder, which opens its
 * input once. Takes `bytes` over as prepare_bytes does.
 */
FFPLAYKMP_EXPORT int ffplaykmp_web_player_prepare_packets(
        ffplaykmp_player *player,
        uint8_t *bytes,
        uint32_t size,
        const char *extension,
        ffplaykmp_web_decoder_config_callback callback,
        void *opaque);
FFPLAYKMP_EXPORT int ffplaykmp_web_player_read_packet(
        ffplaykmp_player *player,
        ffplaykmp_web_encoded_packet_callback callback,
        void *opaque);
/* Leaves the input where the packets left it; a software run after it seeks. */
FFPLAYKMP_EXPORT void ffplaykmp_web_player_close_packets(ffplaykmp_player *player);
/*
 * The packet reader's stream timing, for a caller that decodes frame by frame: `values` takes
 * the time base's numerator and denominator, then ffplaykmp_stream_timing's origin, default
 * duration and stream end, in that time base.
 */
FFPLAYKMP_EXPORT int ffplaykmp_web_player_packet_timing(ffplaykmp_player *player, int64_t *values);
/* Moves the packet reader to the keyframe at or before `target`, in the stream's time base. */
FFPLAYKMP_EXPORT int ffplaykmp_web_player_seek_packets(ffplaykmp_player *player, int64_t target);
/* The index's last keyframe at or before `target`, in the stream's time base; AV_NOPTS_VALUE when it has none. */
FFPLAYKMP_EXPORT int64_t ffplaykmp_web_player_keyframe_before(ffplaykmp_player *player, int64_t target);
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
