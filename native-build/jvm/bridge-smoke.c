// SPDX-License-Identifier: Apache-2.0
#include "ffmpegkmp_bridge.h"

#include <stdio.h>

static void event_callback(
        void *opaque,
        ffmpegkmp_event_kind kind,
        int level,
        const uint8_t *data,
        size_t size) {
    size_t *events = opaque;
    (void) kind;
    (void) level;
    (void) data;
    (void) size;
    (*events)++;
}

int main(void) {
    static const char *ffmpeg_version[] = { "ffmpeg", "-version" };
    static const char *ffprobe_version[] = { "ffprobe", "-version" };
    static const char *ffprobe_invalid[] = {
        "ffprobe", "-v", "error", "-of", "json", "-show_format", "does-not-exist.ffmpegkmp"
    };
    size_t events = 0;
    ffmpegkmp_context *context = ffmpegkmp_context_create(event_callback, &events);
    int first;
    int invalid;
    int repeated;

    if (!context)
        return 2;
    first = ffmpegkmp_execute(context, FFMPEGKMP_COMMAND_FFMPEG, 2, ffmpeg_version);
    invalid = ffmpegkmp_execute(context, FFMPEGKMP_COMMAND_FFPROBE, 7, ffprobe_invalid);
    repeated = ffmpegkmp_execute(context, FFMPEGKMP_COMMAND_FFPROBE, 2, ffprobe_version);
    ffmpegkmp_context_destroy(context);

    if (first != 0 || invalid == 0 || repeated != 0) {
        fprintf(stderr, "unexpected bridge results: %d, %d, %d\n", first, invalid, repeated);
        return 3;
    }
    return events == 0 ? 4 : 0;
}
