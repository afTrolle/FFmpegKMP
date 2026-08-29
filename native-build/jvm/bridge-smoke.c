// SPDX-License-Identifier: Apache-2.0
#include "ffmpegkmp_bridge.h"

#include <stdio.h>

static void event_callback(
        void *opaque,
        ffmpegkmp_event_kind kind,
        int level,
        const uint8_t *data,
        uint64_t size) {
    size_t *events = opaque;
    (void) kind;
    (void) level;
    (void) data;
    (void) size;
    (*events)++;
}

static void player_state_callback(void *opaque, const ffplaykmp_snapshot *snapshot) {
    size_t *events = opaque;
    if (snapshot && snapshot->size == sizeof(*snapshot))
        (*events)++;
}

static void web_player_state_callback(
        void *opaque,
        const char *snapshot_json,
        uint32_t snapshot_json_size) {
    size_t *events = opaque;
    if (snapshot_json && snapshot_json_size > 0)
        (*events)++;
}

static void web_player_frame_callback(
        void *opaque,
        const uint8_t *rgba,
        uint32_t rgba_size,
        int32_t width,
        int32_t height,
        int32_t stride,
        int64_t presentation_time_us,
        uint32_t queue_serial) {
    (void)opaque;
    (void)rgba;
    (void)rgba_size;
    (void)width;
    (void)height;
    (void)stride;
    (void)presentation_time_us;
    (void)queue_serial;
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
    ffplaykmp_configuration player_configuration = {
        .size = sizeof(player_configuration),
        .decoder_preference = FFPLAYKMP_DECODER_REQUIRE_HARDWARE,
    };
    ffplaykmp_output_capabilities canvas_output = {
        .size = sizeof(canvas_output),
        .flags = FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD,
    };
    ffplaykmp_output_capabilities secure_output = {
        .size = sizeof(secure_output),
        .flags = FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT |
                FFPLAYKMP_OUTPUT_ZERO_COPY |
                FFPLAYKMP_OUTPUT_PROTECTED_CONTENT,
    };
    ffplaykmp_snapshot player_snapshot = { .size = sizeof(player_snapshot) };
    ffplaykmp_player *player;
    ffplaykmp_player *web_player;

    if (!context)
        return 2;
    first = ffmpegkmp_execute(context, FFMPEGKMP_COMMAND_FFMPEG, 2, ffmpeg_version);
    invalid = ffmpegkmp_execute(context, FFMPEGKMP_COMMAND_FFPROBE, 7, ffprobe_invalid);
    repeated = ffmpegkmp_execute(context, FFMPEGKMP_COMMAND_FFPROBE, 2, ffprobe_version);
    ffmpegkmp_context_destroy(context);

    player = ffplaykmp_player_create(&player_configuration, player_state_callback, &events);
    if (!player)
        return 5;
    if (ffplaykmp_player_prepare(
                player, "protected.mpd", FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH) != 0)
        return 6;
    if (ffplaykmp_player_set_output(player, &canvas_output) >= 0)
        return 7;
    if (ffplaykmp_player_set_output(player, &secure_output) != 0)
        return 8;
    if (ffplaykmp_player_play(player) != 0 ||
            ffplaykmp_player_seek(player, 1000000) != 0 ||
            ffplaykmp_player_get_snapshot(player, &player_snapshot) != 0)
        return 9;
    if (player_snapshot.state != FFPLAYKMP_STATE_PLAYING ||
            player_snapshot.position_us != 1000000 ||
            !(player_snapshot.output_flags & FFPLAYKMP_OUTPUT_PROTECTED_CONTENT))
        return 10;
    ffplaykmp_player_destroy(player);

    web_player = ffplaykmp_web_player_create(
            FFPLAYKMP_DECODER_SOFTWARE,
            web_player_state_callback,
            web_player_frame_callback,
            &events);
    if (!web_player || ffplaykmp_web_player_set_output(
                web_player, FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD) != 0)
        return 11;
    ffplaykmp_web_player_poll(web_player);
    ffplaykmp_web_player_destroy(web_player);

    if (first != 0 || invalid == 0 || repeated != 0) {
        fprintf(stderr, "unexpected bridge results: %d, %d, %d\n", first, invalid, repeated);
        return 3;
    }
    return events == 0 ? 4 : 0;
}
