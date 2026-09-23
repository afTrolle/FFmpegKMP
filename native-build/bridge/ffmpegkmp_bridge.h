// SPDX-License-Identifier: LGPL-2.1-or-later
#ifndef FFMPEGKMP_BRIDGE_H
#define FFMPEGKMP_BRIDGE_H

#include <stddef.h>
#include <stdint.h>
#include "ffplaykmp_player.h"

#if defined(_WIN32)
#define FFMPEGKMP_EXPORT __declspec(dllexport)
#else
#define FFMPEGKMP_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ffmpegkmp_context ffmpegkmp_context;

typedef enum ffmpegkmp_command_kind {
    FFMPEGKMP_COMMAND_FFMPEG = 0,
    FFMPEGKMP_COMMAND_FFPROBE = 1,
} ffmpegkmp_command_kind;

typedef enum ffmpegkmp_event_kind {
    FFMPEGKMP_EVENT_LOG = 0,
    FFMPEGKMP_EVENT_STDOUT = 1,
    FFMPEGKMP_EVENT_STDERR = 2,
} ffmpegkmp_event_kind;

typedef void (*ffmpegkmp_event_callback)(
        void *opaque,
        ffmpegkmp_event_kind kind,
        int level,
        const uint8_t *data,
        uint64_t size);

typedef enum ffmpegkmp_io_operation {
    FFMPEGKMP_IO_OPEN = 0,
    FFMPEGKMP_IO_READ = 1,
    FFMPEGKMP_IO_WRITE = 2,
    FFMPEGKMP_IO_SIZE = 3,
    FFMPEGKMP_IO_CLOSE = 4,
} ffmpegkmp_io_operation;

typedef enum ffmpegkmp_io_capability {
    FFMPEGKMP_IO_CAP_READ = 1,
    FFMPEGKMP_IO_CAP_WRITE = 2,
    FFMPEGKMP_IO_CAP_SEEK = 4,
} ffmpegkmp_io_capability;

/**
 * Host callback used by the ffmpegkmp: URL protocol. OPEN receives FFmpeg's
 * AVIO access flags in `offset` and returns a capability mask; READ/WRITE return
 * a byte count; SIZE returns the current size; CLOSE returns zero. Any negative
 * value is reported to FFmpeg as an I/O failure.
 */
typedef int64_t (*ffmpegkmp_io_callback)(
        void *opaque,
        int64_t resource_id,
        int operation,
        int64_t offset,
        uint8_t *data,
        uint64_t size);

FFMPEGKMP_EXPORT ffmpegkmp_context *ffmpegkmp_context_create(
        ffmpegkmp_event_callback callback,
        void *opaque);
FFMPEGKMP_EXPORT void ffmpegkmp_context_destroy(ffmpegkmp_context *context);
FFMPEGKMP_EXPORT void ffmpegkmp_context_set_io_callback(
        ffmpegkmp_context *context,
        ffmpegkmp_io_callback callback);
FFMPEGKMP_EXPORT int ffmpegkmp_execute(
        ffmpegkmp_context *context,
        ffmpegkmp_command_kind kind,
        int argc,
        const char *const *argv);
FFMPEGKMP_EXPORT void ffmpegkmp_cancel(ffmpegkmp_context *context);

/* Called by the reviewed fftools overlay at safe processing and I/O points. */
FFMPEGKMP_EXPORT int ffmpegkmp_cancel_requested(void);
FFMPEGKMP_EXPORT void ffmpegkmp_emit(
        ffmpegkmp_event_kind kind,
        int level,
        const uint8_t *data,
        size_t size);

/* Replaces fftools exit() calls so a command can never terminate its host. */
FFMPEGKMP_EXPORT void ffmpegkmp_exit(int status);

/* Directory for the bridge's scratch files (ffprobe stdout redirect). Hosts whose
 * process has no usable cwd or TMPDIR (Android apps) must call this once before
 * running ffprobe. Copies the string; pass NULL to reset to the defaults. */
FFMPEGKMP_EXPORT void ffmpegkmp_set_temp_directory(const char *path);

/*
 * Audio playback engine (ffmpegkmp_player.c). Decodes any subset of one
 * input's audio tracks and mixes them, with live per-track and master gains,
 * into interleaved float PCM at a caller-chosen rate and channel count. It
 * uses libav* directly, so it runs alongside ffmpeg/ffprobe commands.
 *
 * Threading: open/read/seek/close must not run concurrently with each other
 * for one player. The set_*_gain, set_track_enabled and abort functions are
 * safe from any thread; track changes apply on the next read.
 * Tracks are numbered 0..track_count-1 in audio stream order (the `a:N`
 * index), independent of absolute stream indices.
 */
typedef struct ffmpegkmp_player ffmpegkmp_player;

/* Returns NULL on failure and stores the negative AVERROR in *error. The
 * track FFmpeg would pick by default starts enabled; all others start off. */
FFMPEGKMP_EXPORT ffmpegkmp_player *ffmpegkmp_player_open(
        const char *url,
        int output_sample_rate,
        int output_channels,
        int *error);
/* Opens input read through `callback` (READ and SIZE operations, with
 * `resource_id` passed through), e.g. an in-memory or content-URI file. */
FFMPEGKMP_EXPORT ffmpegkmp_player *ffmpegkmp_player_open_io(
        ffmpegkmp_io_callback callback,
        void *opaque,
        int64_t resource_id,
        int output_sample_rate,
        int output_channels,
        int *error);
/* Makes a blocked open/read return promptly; the player must still be closed. */
FFMPEGKMP_EXPORT void ffmpegkmp_player_abort(ffmpegkmp_player *player);
FFMPEGKMP_EXPORT void ffmpegkmp_player_close(ffmpegkmp_player *player);

FFMPEGKMP_EXPORT int ffmpegkmp_player_track_count(const ffmpegkmp_player *player);
FFMPEGKMP_EXPORT int ffmpegkmp_player_track_stream_index(const ffmpegkmp_player *player, int track);
FFMPEGKMP_EXPORT int ffmpegkmp_player_track_channels(const ffmpegkmp_player *player, int track);
FFMPEGKMP_EXPORT int ffmpegkmp_player_track_sample_rate(const ffmpegkmp_player *player, int track);
/* Strings stay valid until the player is closed; absent tags are "". */
FFMPEGKMP_EXPORT const char *ffmpegkmp_player_track_codec(const ffmpegkmp_player *player, int track);
FFMPEGKMP_EXPORT const char *ffmpegkmp_player_track_language(const ffmpegkmp_player *player, int track);
FFMPEGKMP_EXPORT const char *ffmpegkmp_player_track_title(const ffmpegkmp_player *player, int track);
FFMPEGKMP_EXPORT int ffmpegkmp_player_track_is_default(const ffmpegkmp_player *player, int track);
/* Zero when this build has no decoder for the track's codec. */
FFMPEGKMP_EXPORT int ffmpegkmp_player_track_is_decodable(const ffmpegkmp_player *player, int track);
FFMPEGKMP_EXPORT int ffmpegkmp_player_track_enabled(const ffmpegkmp_player *player, int track);
FFMPEGKMP_EXPORT int ffmpegkmp_player_set_track_enabled(ffmpegkmp_player *player, int track, int enabled);
/* Linear gains; values above 1 amplify and the mix clips at full scale. */
FFMPEGKMP_EXPORT int ffmpegkmp_player_set_track_gain(ffmpegkmp_player *player, int track, float gain);
FFMPEGKMP_EXPORT int ffmpegkmp_player_set_master_gain(ffmpegkmp_player *player, float gain);

/* Microseconds from the input's start; duration is negative when unknown. */
FFMPEGKMP_EXPORT int64_t ffmpegkmp_player_duration_us(const ffmpegkmp_player *player);
FFMPEGKMP_EXPORT int64_t ffmpegkmp_player_position_us(const ffmpegkmp_player *player);
FFMPEGKMP_EXPORT int ffmpegkmp_player_seek(ffmpegkmp_player *player, int64_t position_us);
/* Fills up to `frames` interleaved frames; returns the count written, 0 at
 * the end of the input, or a negative AVERROR. */
FFMPEGKMP_EXPORT int ffmpegkmp_player_read(ffmpegkmp_player *player, float *pcm, int frames);

#ifdef __cplusplus
}
#endif

#endif
