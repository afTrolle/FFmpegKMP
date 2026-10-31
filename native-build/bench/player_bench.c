// SPDX-License-Identifier: LGPL-2.1-or-later
/*
 * Benchmarks the FFplay player engine (ffplaykmp_player.c), built unchanged,
 * through the calls FFplayPlayer makes: prepare, set_output, seeks while
 * paused and while playing, and play after pause. The input is served through
 * bench_io, so every row also says how many times the transition opened the
 * input and how much it read, and from ffplaykmp_get_statistics how many
 * codecs it opened and video packets it demuxed. Frames are converted into one
 * reused RGBA8 bitmap at their own size, as a Canvas output's FrameImage does.
 * scripts/bench-player.sh builds and runs it.
 *
 *   player_bench <clips> transitions <clip> <AUTO|SOFTWARE>
 *   player_bench <clips> parallel <clip> <AUTO|SOFTWARE> <players>
 */
#include "ffplaykmp_player.h"
#include "ffplaykmp_core.h"
#include "ffmpegkmp_frame.h"
#include "bench_io.h"
#include "bench_usage.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavutil/time.h>

static double now_ms(void) { return av_gettime_relative() / 1000.0; }

/* One player and what it has presented. */
typedef struct bench_player {
    ffplaykmp_player *player;
    bench_io io;
    atomic_int frames;
    /* The Canvas output's bitmap: RGBA8 at the frame's size, reused. */
    uint8_t *pixels;
    int width;
    int height;
    double convert_ms;
} bench_player;

/* FrameFormat.Rgba8: sRGB. */
static ffmpegkmp_frame_format rgba8_format(void) {
    ffmpegkmp_frame_format format;
    ffmpegkmp_frame_format_init(&format);
    format.layout = FFMPEGKMP_LAYOUT_RGBA8;
    format.primaries = FFMPEGKMP_PRIMARIES_BT709;
    format.transfer = FFMPEGKMP_TRANSFER_SRGB;
    format.matrix = FFMPEGKMP_MATRIX_RGB;
    format.range = FFMPEGKMP_RANGE_FULL;
    return format;
}

/* On the player's thread: converts the frame into the bitmap, as ComposeCanvasOutput does. */
static void on_frame(void *opaque, const ffplaykmp_video_frame *frame) {
    bench_player *bench = opaque;
    const ffmpegkmp_frame_format rgba8 = rgba8_format();
    ffmpegkmp_frame *bitmap;
    double start = now_ms();
    if (frame->width != bench->width || frame->height != bench->height) {
        free(bench->pixels);
        bench->pixels = malloc((size_t)frame->width * frame->height * 4);
        bench->width = frame->width;
        bench->height = frame->height;
    }
    bitmap = ffmpegkmp_frame_wrap(&rgba8, bench->width, bench->height, bench->pixels, bench->width * 4);
    if (bitmap && ffmpegkmp_frame_convert_into(bitmap, frame->frame) >= 0)
        atomic_fetch_add(&bench->frames, 1);
    ffmpegkmp_frame_unref(bitmap);
    bench->convert_ms += now_ms() - start;
}

static int bench_player_open(bench_player *bench, const char *path, int preference) {
    ffplaykmp_configuration configuration;
    memset(bench, 0, sizeof(*bench));
    atomic_init(&bench->frames, 0);
    if (bench_io_open(&bench->io, path) < 0)
        return -1;
    ffplaykmp_configuration_default(&configuration);
    configuration.decoder_preference = preference;
    bench->player = ffplaykmp_player_create(&configuration, NULL, NULL);
    if (!bench->player)
        return -1;
    ffplaykmp_player_set_io_callback(bench->player, bench_io_callback, &bench->io);
    ffplaykmp_player_set_video_frame_callback(bench->player, on_frame, bench);
    return 0;
}

static void bench_player_close(bench_player *bench) {
    if (bench->player) {
        ffplaykmp_player_stop(bench->player);
        ffplaykmp_player_destroy(bench->player);
    }
    bench_io_close(&bench->io);
    free(bench->pixels);
}

/* The Canvas output: frames uploaded in software, which desktop hardware decoding downloads into. */
static int bench_set_output(bench_player *bench) {
    ffplaykmp_output_capabilities capabilities;
    ffplaykmp_output_capabilities_init(&capabilities);
    capabilities.flags = FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD;
    return ffplaykmp_player_set_output(bench->player, &capabilities);
}

static ffplaykmp_snapshot bench_snapshot(const bench_player *bench) {
    ffplaykmp_snapshot snapshot;
    ffplaykmp_snapshot_init(&snapshot);
    ffplaykmp_player_get_snapshot(bench->player, &snapshot);
    return snapshot;
}

/* Waits until `count` more frames than `since` are presented; returns ms waited, negative on timeout. */
static double wait_frames(bench_player *bench, int since, int count, double timeout_ms) {
    double start = now_ms();
    while (atomic_load(&bench->frames) < since + count) {
        if (now_ms() - start > timeout_ms)
            return -1;
        av_usleep(200);
    }
    return now_ms() - start;
}

static const char *decoder_name(ffplaykmp_decoder_kind kind) {
    return kind == FFPLAYKMP_DECODER_HARDWARE ? "hw" : kind == FFPLAYKMP_DECODER_SOFTWARE_ACTIVE ? "sw" : "?";
}

static const char *file_name(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

static ffplaykmp_statistics statistics_now(void) {
    ffplaykmp_statistics statistics;
    ffplaykmp_statistics_init(&statistics);
    ffplaykmp_get_statistics(&statistics);
    return statistics;
}

/* What the players did since `before`; the bench runs one player at a time in this mode. */
static ffplaykmp_statistics statistics_since(ffplaykmp_statistics before) {
    ffplaykmp_statistics now = statistics_now();
    now.codec_opens -= before.codec_opens;
    now.video_packets -= before.video_packets;
    now.downloads -= before.downloads;
    return now;
}

/* One row: what `calls` calls of a transition cost on average. */
static void report(const char *what, double took_ms, int calls, bench_io_counts io, ffplaykmp_statistics codec,
        int frames) {
    if (calls <= 0)
        calls = 1;
    printf("  %-26s %8.1f ms | %4.1f opens %6.1f reads %7.2f MB | %4.1f codec opens %6.1f packets | %4.1f frames\n",
            what, took_ms / calls, (double)io.opens / calls, (double)io.reads / calls, io.bytes / 1048576.0 / calls,
            (double)codec.codec_opens / calls, (double)codec.video_packets / calls, (double)frames / calls);
}

/* Seek targets as fractions of the clip, jumping back and forth as a timeline scrub does. */
static const double seek_targets[] = { 0.5, 0.1, 0.9, 0.3, 0.7, 0.2, 0.8, 0.4, 0.6, 0.15 };
#define SEEK_TARGETS ((int)(sizeof(seek_targets) / sizeof(seek_targets[0])))

/*
 * Every transition FFplayPlayer makes, in the order an editor makes them:
 * prepare before the surface exists, then attach it, scrub while paused, step
 * forward a frame at a time, play and pause, and seek while playing.
 */
static void bench_transitions(const char *path, int preference) {
    bench_player bench;
    ffplaykmp_snapshot snapshot;
    bench_io_counts before;
    ffplaykmp_statistics counted;
    ffplaykmp_statistics started;
    ffmpegkmp_frame_statistics memory;
    ffmpegkmp_frame_statistics memory_after;
    double start;
    double took;
    double first_frame;
    int frames;
    int index;
    int threads;
    int footprint_mb;
    int64_t duration_us;
    if (bench_player_open(&bench, path, preference) < 0) {
        printf("%s: cannot open\n", file_name(path));
        return;
    }
    printf("%s %s\n", file_name(path), preference == FFPLAYKMP_DECODER_AUTO ? "AUTO" : "SOFTWARE");
    started = statistics_now();
    ffmpegkmp_frame_statistics_init(&memory);
    ffmpegkmp_frame_get_statistics(&memory);

    before = bench_io_now(&bench.io);
    counted = statistics_now();
    start = now_ms();
    if (ffplaykmp_player_prepare(bench.player, BENCH_IO_URL, 0) < 0) {
        printf("  prepare failed (%d)\n", bench_snapshot(&bench).last_error);
        bench_player_close(&bench);
        return;
    }
    report("prepare, no output", now_ms() - start, 1, bench_io_since(&bench.io, before), statistics_since(counted), 0);
    duration_us = bench_snapshot(&bench).duration_us;

    before = bench_io_now(&bench.io);
    counted = statistics_now();
    frames = atomic_load(&bench.frames);
    start = now_ms();
    bench_set_output(&bench);
    report("set_output (preview)", now_ms() - start, 1, bench_io_since(&bench.io, before), statistics_since(counted),
            atomic_load(&bench.frames) - frames);

    before = bench_io_now(&bench.io);
    counted = statistics_now();
    frames = atomic_load(&bench.frames);
    start = now_ms();
    for (index = 0; index < SEEK_TARGETS; index++)
        ffplaykmp_player_seek(bench.player, (int64_t)(seek_targets[index] * duration_us));
    report("seek while paused", now_ms() - start, SEEK_TARGETS, bench_io_since(&bench.io, before),
            statistics_since(counted), atomic_load(&bench.frames) - frames);

    /* Frame steps forward, as arrow-key stepping or a slow scrub makes: 1/30 s apart. */
    before = bench_io_now(&bench.io);
    counted = statistics_now();
    frames = atomic_load(&bench.frames);
    start = now_ms();
    for (index = 0; index < 10; index++)
        ffplaykmp_player_seek(bench.player, duration_us / 4 + index * 33333);
    report("step forward while paused", now_ms() - start, 10, bench_io_since(&bench.io, before),
            statistics_since(counted), atomic_load(&bench.frames) - frames);

    before = bench_io_now(&bench.io);
    counted = statistics_now();
    first_frame = 0;
    took = 0;
    for (index = 0; index < 5; index++) {
        double waited;
        ffplaykmp_player_seek(bench.player, (int64_t)(seek_targets[index] * duration_us));
        frames = atomic_load(&bench.frames);
        start = now_ms();
        ffplaykmp_player_play(bench.player);
        took += now_ms() - start;
        waited = wait_frames(&bench, frames, 1, 5000);
        first_frame += waited < 0 ? 5000 : now_ms() - start;
        wait_frames(&bench, frames, 5, 5000);
        start = now_ms();
        ffplaykmp_player_pause(bench.player);
        took += now_ms() - start;
    }
    /* The seeks before each play are counted too; subtract "seek while paused" for play alone. */
    report("seek, play, pause (calls)", took, 5, bench_io_since(&bench.io, before), statistics_since(counted), 0);
    printf("  %-26s %8.1f ms\n", "play to first frame", first_frame / 5);

    ffplaykmp_player_seek(bench.player, 0);
    ffplaykmp_player_play(bench.player);
    wait_frames(&bench, atomic_load(&bench.frames), 3, 5000);
    before = bench_io_now(&bench.io);
    counted = statistics_now();
    frames = atomic_load(&bench.frames);
    start = now_ms();
    for (index = 0; index < 5; index++)
        ffplaykmp_player_seek(bench.player, (int64_t)(seek_targets[index] * duration_us));
    took = now_ms() - start;
    report("seek while playing", took, 5, bench_io_since(&bench.io, before), statistics_since(counted),
            atomic_load(&bench.frames) - frames);
    ffplaykmp_player_pause(bench.player);

    snapshot = bench_snapshot(&bench);
    process_usage(&threads, &footprint_mb);
    printf("  decoder %s, %d frames converted at %.2f ms each, %d threads, %d MB footprint\n",
            decoder_name(snapshot.active_decoder), atomic_load(&bench.frames),
            atomic_load(&bench.frames) ? bench.convert_ms / atomic_load(&bench.frames) : 0, threads, footprint_mb);
    counted = statistics_since(started);
    ffmpegkmp_frame_statistics_init(&memory_after);
    ffmpegkmp_frame_get_statistics(&memory_after);
    printf("  %llu codec opens in all; %.2f downloads and %.2f frame buffers allocated per frame converted\n",
            (unsigned long long)counted.codec_opens,
            atomic_load(&bench.frames) ? (double)counted.downloads / atomic_load(&bench.frames) : 0,
            atomic_load(&bench.frames)
                    ? (double)(memory_after.buffers - memory.buffers) / atomic_load(&bench.frames)
                    : 0);

    before = bench_io_now(&bench.io);
    counted = statistics_now();
    ffplaykmp_player_stop(bench.player);
    report("stop", 0, 1, bench_io_since(&bench.io, before), statistics_since(counted), 0);
    bench_player_close(&bench);
}

/* bench_parallel's players and what they report. */
typedef struct bench_parallel_job {
    const char *path;
    int preference;
    bench_player bench;
    double prepare_ms;
    int played;
    ffplaykmp_decoder_kind decoder;
} bench_parallel_job;

static void *bench_parallel_main(void *opaque) {
    bench_parallel_job *job = opaque;
    double start;
    int frames;
    if (bench_player_open(&job->bench, job->path, job->preference) < 0)
        return NULL;
    start = now_ms();
    if (ffplaykmp_player_prepare(job->bench.player, BENCH_IO_URL, 0) < 0 || bench_set_output(&job->bench) < 0)
        return NULL;
    job->prepare_ms = now_ms() - start;
    frames = atomic_load(&job->bench.frames);
    ffplaykmp_player_play(job->bench.player);
    av_usleep(2000000);
    job->played = atomic_load(&job->bench.frames) - frames;
    job->decoder = bench_snapshot(&job->bench).active_decoder;
    ffplaykmp_player_pause(job->bench.player);
    return NULL;
}

/*
 * `count` players at once, as an editor's timeline shows them: each prepares,
 * attaches a Canvas output, plays for two seconds and pauses. Reports how
 * long prepare took, frames a second each, how many got a hardware decoder,
 * and the process's threads and footprint while all of them are paused,
 * which is what a timeline of paused players holds.
 */
static void bench_parallel(const char *path, int preference, int count) {
    bench_parallel_job jobs[32];
    pthread_t threads[32];
    double prepare_ms = 0;
    int played = 0;
    int hardware = 0;
    int idle_threads;
    int idle_footprint_mb;
    int paused_threads;
    int paused_footprint_mb;
    int index;
    process_usage(&idle_threads, &idle_footprint_mb);
    for (index = 0; index < count; index++) {
        memset(&jobs[index], 0, sizeof(jobs[index]));
        jobs[index].path = path;
        jobs[index].preference = preference;
        pthread_create(&threads[index], NULL, bench_parallel_main, &jobs[index]);
    }
    for (index = 0; index < count; index++) {
        pthread_join(threads[index], NULL);
        prepare_ms += jobs[index].prepare_ms;
        played += jobs[index].played;
        hardware += jobs[index].decoder == FFPLAYKMP_DECODER_HARDWARE;
    }
    process_usage(&paused_threads, &paused_footprint_mb);
    printf("%-22s %-8s players=%-2d | prepare %6.1f ms | %5.1f frames/s each | %2d hw | paused: %3d threads, "
            "%5d MB (%4d MB each)\n",
            file_name(path), preference == FFPLAYKMP_DECODER_AUTO ? "AUTO" : "SOFTWARE", count, prepare_ms / count,
            played / 2.0 / count, hardware, paused_threads, paused_footprint_mb,
            (paused_footprint_mb - idle_footprint_mb) / count);
    for (index = 0; index < count; index++)
        bench_player_close(&jobs[index].bench);
}

int main(int argc, char **argv) {
    char path[1024];
    int preference;
    if (argc < 5) {
        fprintf(stderr, "usage: %s <clips> transitions|parallel <clip> AUTO|SOFTWARE [players]\n", argv[0]);
        return 2;
    }
    snprintf(path, sizeof(path), "%s/%s", argv[1], argv[3]);
    preference = !strcmp(argv[4], "AUTO") ? FFPLAYKMP_DECODER_AUTO : FFPLAYKMP_DECODER_SOFTWARE;
    if (!strcmp(argv[2], "transitions"))
        bench_transitions(path, preference);
    else if (!strcmp(argv[2], "parallel") && argc > 5 && atoi(argv[5]) >= 1 && atoi(argv[5]) <= 32)
        bench_parallel(path, preference, atoi(argv[5]));
    return 0;
}
