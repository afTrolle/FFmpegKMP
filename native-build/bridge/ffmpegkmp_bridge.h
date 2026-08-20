// SPDX-License-Identifier: LGPL-2.1-or-later
#ifndef FFMPEGKMP_BRIDGE_H
#define FFMPEGKMP_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

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

#ifdef __cplusplus
}
#endif

#endif
