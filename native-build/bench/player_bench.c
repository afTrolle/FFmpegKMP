// SPDX-License-Identifier: LGPL-2.1-or-later
/*
 * Benchmarks the FFplay player engine (ffplaykmp_player.c), built unchanged,
 * through the calls FFplayPlayer makes: prepare, set_output, seeks while
 * paused and while playing, and play after pause. The input is served through
 * bench_io, so every row also says how many times the transition opened the
 * input and how much it read, and from ffplaykmp_get_statistics how many
 * codecs it opened and video packets it demuxed. The players release an idle
 * codec after the default grace, 2 s. Frames are converted into one
 * reused RGBA8 bitmap at their own size when presented, as a Canvas output's
 * FrameImage does, and each player has a thread that refreshes it as an
 * output's timer does: again after the time each refresh returns.
 * The hardware decoder budget is the platform's default, or
 * FFMPEGKMP_BENCH_HARDWARE_DECODERS (0 for no limit).
 * scripts/bench-player.sh builds and runs it. The audio mode prepares a
 * player and then opens the audio engine (ffmpegkmp_player.c) on the same
 * input, as FFplayPlayer does, with and without the player's input hint. The
 * pacing mode plays a clip in real time into an output that only counts,
 * refreshed the same way, and reports how late frames reached it, for a
 * decoder thread count: the host's smoke test of the player's frame timing.
 *
 *   player_bench <clips> transitions <clip> <AUTO|SOFTWARE>
 *   player_bench <clips> parallel <clip> <AUTO|SOFTWARE> <players>
 *   player_bench <clips> audio <clip> <AUTO|SOFTWARE>
 *   player_bench <clips> pacing <clip> <AUTO|SOFTWARE> <threads> [seconds]
 */
#include "ffmpegkmp_bridge.h"
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
#include <sys/resource.h>

#include <libavutil/time.h>

static double now_ms(void) { return av_gettime_relative() / 1000.0; }

/* A thread that refreshes a player as an output's timer does: again after the time each refresh returns, 10 ms at most. */
typedef struct bench_refresher {
    ffplaykmp_player *player;
    atomic_int running;
    pthread_t thread;
} bench_refresher;

static void *bench_refresher_main(void *opaque) {
    bench_refresher *refresher = opaque;
    while (atomic_load(&refresher->running)) {
        int64_t remaining_us = ffplaykmp_player_refresh(refresher->player, -1, 0);
        if (remaining_us == FFPLAYKMP_REFRESH_IDLE || remaining_us > 10000)
            remaining_us = 10000;
        if (remaining_us > 0)
            av_usleep((unsigned)remaining_us);
    }
    return NULL;
}

static void bench_refresher_start(bench_refresher *refresher, ffplaykmp_player *player) {
    refresher->player = player;
    atomic_init(&refresher->running, 1);
    pthread_create(&refresher->thread, NULL, bench_refresher_main, refresher);
}

/* Before the player is destroyed: a refresh must not overlap it. */
static void bench_refresher_stop(bench_refresher *refresher) {
    atomic_store(&refresher->running, 0);
    pthread_join(refresher->thread, NULL);
}

/* One player and what it has presented. */
typedef struct bench_player {
    ffplaykmp_player *player;
    bench_refresher refresher;
    bench_io io;
    atomic_int frames;
    /* The frames prepared and not yet presented or released, by token: the queue's and a preview's. */
    pthread_mutex_t prepared_mutex;
    struct {
        uint64_t token;
        ffmpegkmp_frame *frame;
    } prepared[16];
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

/* Takes the prepared frame for `token` out of `bench->prepared`; NULL when there is none. */
static ffmpegkmp_frame *take_prepared(bench_player *bench, uint64_t token) {
    ffmpegkmp_frame *frame = NULL;
    pthread_mutex_lock(&bench->prepared_mutex);
    for (size_t index = 0; index < sizeof(bench->prepared) / sizeof(bench->prepared[0]); index++) {
        if (bench->prepared[index].frame && bench->prepared[index].token == token) {
            frame = bench->prepared[index].frame;
            bench->prepared[index].frame = NULL;
            break;
        }
    }
    pthread_mutex_unlock(&bench->prepared_mutex);
    return frame;
}

/* Keeps a reference to `frame` under `token`; 0 when there is no room. */
static int keep_prepared(bench_player *bench, const ffmpegkmp_frame *frame, uint64_t token) {
    int kept = 0;
    pthread_mutex_lock(&bench->prepared_mutex);
    for (size_t index = 0; index < sizeof(bench->prepared) / sizeof(bench->prepared[0]); index++) {
        if (!bench->prepared[index].frame) {
            if ((bench->prepared[index].frame = ffmpegkmp_frame_ref(frame))) {
                bench->prepared[index].token = token;
                kept = 1;
            }
            break;
        }
    }
    pthread_mutex_unlock(&bench->prepared_mutex);
    return kept;
}

/*
 * Keeps every frame it is given under its token, and converts it into the bitmap when it is
 * presented, on the refreshing (or, for a preview, the session) thread, as ComposeCanvasOutput does.
 */
static int on_frame(void *opaque, const ffplaykmp_video_frame *frame) {
    bench_player *bench = opaque;
    const ffmpegkmp_frame_format rgba8 = rgba8_format();
    ffmpegkmp_frame *bitmap;
    ffmpegkmp_frame *prepared;
    ffmpegkmp_frame_info info;
    int width;
    int height;
    double start;
    if (frame->event == FFPLAYKMP_VIDEO_FRAME_PREPARE)
        return keep_prepared(bench, frame->frame, frame->token);
    if (!(prepared = take_prepared(bench, frame->token)))
        return 0;
    if (frame->event == FFPLAYKMP_VIDEO_FRAME_PRESENT) {
        start = now_ms();
        ffmpegkmp_frame_info_init(&info);
        ffmpegkmp_frame_get_info(prepared, &info);
        width = info.width;
        height = info.height;
        if (width != bench->width || height != bench->height) {
            free(bench->pixels);
            bench->pixels = malloc((size_t)width * height * 4);
            bench->width = width;
            bench->height = height;
        }
        bitmap = ffmpegkmp_frame_wrap(&rgba8, bench->width, bench->height, bench->pixels, bench->width * 4);
        if (bitmap && ffmpegkmp_frame_convert_into(bitmap, prepared) >= 0)
            atomic_fetch_add(&bench->frames, 1);
        ffmpegkmp_frame_unref(bitmap);
        bench->convert_ms += now_ms() - start;
    }
    ffmpegkmp_frame_unref(prepared);
    return 0;
}

static int bench_player_open(bench_player *bench, const char *path, int preference) {
    ffplaykmp_configuration configuration;
    memset(bench, 0, sizeof(*bench));
    atomic_init(&bench->frames, 0);
    pthread_mutex_init(&bench->prepared_mutex, NULL);
    if (bench_io_open(&bench->io, path) < 0)
        return -1;
    ffplaykmp_configuration_default(&configuration);
    configuration.decoder_preference = preference;
    bench->player = ffplaykmp_player_create(&configuration, NULL, NULL);
    if (!bench->player)
        return -1;
    ffplaykmp_player_set_io_callback(bench->player, bench_io_callback, &bench->io);
    ffplaykmp_player_set_video_frame_callback(bench->player, on_frame, bench);
    bench_refresher_start(&bench->refresher, bench->player);
    return 0;
}

static void bench_player_close(bench_player *bench) {
    if (bench->player) {
        bench_refresher_stop(&bench->refresher);
        ffplaykmp_player_stop(bench->player);
        ffplaykmp_player_destroy(bench->player);
    }
    bench_io_close(&bench->io);
    for (size_t index = 0; index < sizeof(bench->prepared) / sizeof(bench->prepared[0]); index++)
        ffmpegkmp_frame_unref(bench->prepared[index].frame);
    pthread_mutex_destroy(&bench->prepared_mutex);
    free(bench->pixels);
}

/* The Canvas output: frames uploaded in software, which desktop hardware decoding downloads into. */
static int bench_set_output_of(ffplaykmp_player *player) {
    ffplaykmp_output_capabilities capabilities;
    ffplaykmp_output_capabilities_init(&capabilities);
    capabilities.flags = FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD;
    return ffplaykmp_player_set_output(player, &capabilities);
}

static int bench_set_output(bench_player *bench) {
    return bench_set_output_of(bench->player);
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
    now.codec_closes -= before.codec_closes;
    now.video_packets -= before.video_packets;
    now.downloads -= before.downloads;
    now.input_opens -= before.input_opens;
    now.input_probes -= before.input_probes;
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

/* Waits up to `timeout_ms` for a codec to close after `before`; returns ms waited, negative on timeout. */
static double wait_codec_close(ffplaykmp_statistics before, double timeout_ms) {
    double start = now_ms();
    while (statistics_since(before).codec_closes == 0) {
        if (now_ms() - start > timeout_ms)
            return -1;
        av_usleep(1000);
    }
    return now_ms() - start;
}

/* Seek targets as fractions of the clip, jumping back and forth as a timeline scrub does. */
static const double seek_targets[] = { 0.5, 0.1, 0.9, 0.3, 0.7, 0.2, 0.8, 0.4, 0.6, 0.15 };
#define SEEK_TARGETS ((int)(sizeof(seek_targets) / sizeof(seek_targets[0])))

/*
 * Every transition FFplayPlayer makes, in the order an editor makes them:
 * prepare before the surface exists, then attach it, scrub while paused, step
 * forward a frame at a time, play and pause, seek while playing, and play
 * once a pause has lasted long enough for the codec to go.
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

    /* The paused player releases its codec after the grace; play then opens one and decodes from the keyframe. */
    counted = statistics_now();
    took = wait_codec_close(counted, 10000);
    printf("  %-26s %8.1f ms after pause\n", "codec released", took);
    /* Counted over the three plays; pausing and the release between them read nothing and open no codec. */
    before = bench_io_now(&bench.io);
    counted = statistics_now();
    first_frame = 0;
    for (index = 0; index < 3; index++) {
        double waited;
        if (index > 0) {
            ffplaykmp_player_pause(bench.player);
            wait_codec_close(statistics_now(), 10000);
        }
        frames = atomic_load(&bench.frames);
        start = now_ms();
        ffplaykmp_player_play(bench.player);
        waited = wait_frames(&bench, frames, 1, 5000);
        first_frame += waited < 0 ? 5000 : now_ms() - start;
        wait_frames(&bench, frames, 5, 5000);
    }
    /* The time is to the first frame; the frames, those presented within it. */
    report("play after the grace", first_frame, 3, bench_io_since(&bench.io, before), statistics_since(counted), 3);
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
    ffplaykmp_decoder_fallback fallback;
} bench_parallel_job;

static void *bench_parallel_main(void *opaque) {
    bench_parallel_job *job = opaque;
    ffplaykmp_snapshot snapshot;
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
    snapshot = bench_snapshot(&job->bench);
    job->decoder = snapshot.active_decoder;
    job->fallback = snapshot.decoder_fallback;
    ffplaykmp_player_pause(job->bench.player);
    return NULL;
}

/*
 * `count` players at once, as an editor's timeline shows them: each prepares,
 * attaches a Canvas output, plays for two seconds and pauses. Reports how
 * long prepare took, frames a second each, how many got a hardware decoder,
 * and the process's threads and footprint while all of them are paused,
 * which is what a timeline of paused players holds, at once and once their
 * codecs' grace has passed.
 */
static void bench_parallel(const char *path, int preference, int count) {
    bench_parallel_job jobs[32];
    pthread_t threads[32];
    double prepare_ms = 0;
    int played = 0;
    int hardware = 0;
    int budget = 0;
    int idle_threads;
    int idle_footprint_mb;
    int paused_threads;
    int paused_footprint_mb;
    int released_threads;
    int released_footprint_mb;
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
        budget += jobs[index].fallback == FFPLAYKMP_DECODER_FALLBACK_BUDGET;
    }
    process_usage(&paused_threads, &paused_footprint_mb);
    /* Past the default 2 s grace, every paused player has released its codec. */
    av_usleep(3000000);
    process_usage(&released_threads, &released_footprint_mb);
    printf("%-22s %-8s players=%-2d | prepare %6.1f ms | %5.1f frames/s each | %2d hw, %2d budget (limit %d) | "
            "paused: %3d threads, %5d MB (%4d MB each) | after the grace: %3d threads, %5d MB (%4d MB each), %d hw held\n",
            file_name(path), preference == FFPLAYKMP_DECODER_AUTO ? "AUTO" : "SOFTWARE", count, prepare_ms / count,
            played / 2.0 / count, hardware, budget, ffplaykmp_decoder_budget_get(), paused_threads, paused_footprint_mb,
            (paused_footprint_mb - idle_footprint_mb) / count, released_threads, released_footprint_mb,
            (released_footprint_mb - idle_footprint_mb) / count, ffplaykmp_decoder_budget_in_use());
    for (index = 0; index < count; index++)
        bench_player_close(&jobs[index].bench);
}

/*
 * Opens the audio engine on the player's input, hinted or not, counting what
 * the open alone read into `opened`, and decodes its first half second.
 */
static double bench_audio_open(bench_player *bench, int hinted, int *frames, bench_io_counts *opened) {
    float pcm[4800 * 2];
    bench_io_counts before = bench_io_now(&bench->io);
    double start = now_ms();
    int error = 0;
    ffmpegkmp_player *audio = hinted
            ? ffmpegkmp_player_open_prepared(bench->player, BENCH_IO_URL, bench_io_callback, &bench->io, 48000, 2, &error)
            : ffmpegkmp_player_open(BENCH_IO_URL, bench_io_callback, &bench->io, 48000, 2, &error);
    double took = now_ms() - start;
    int read = 0;
    int result;
    *opened = bench_io_since(&bench->io, before);
    *frames = 0;
    if (!audio) {
        fprintf(stderr, "audio open failed: %d\n", error);
        return -1;
    }
    while (read < 24000 && (result = ffmpegkmp_player_read(audio, pcm, 4800)) > 0)
        read += result;
    ffmpegkmp_player_close(audio);
    *frames = read;
    return took;
}

/*
 * What a prepare with audio costs: the player's prepare, which probes the
 * input, then the audio engine's open of the same input with a probe of its
 * own (before phase 8) and with the player's hint (FFplayPlayer since).
 */
static void bench_audio(const char *path, int preference) {
    enum { RUNS = 5 };
    const char *rows[] = { "prepare (video probe)", "audio open, own probe", "audio open, hinted" };
    double took[3] = { 0 };
    bench_io_counts io[3] = { { 0 } };
    long long probes[3] = { 0 };
    int frames[3] = { 0 };
    for (int run = 0; run < RUNS; run++) {
        bench_player bench;
        if (bench_player_open(&bench, path, preference) < 0) {
            fprintf(stderr, "cannot open %s\n", path);
            return;
        }
        for (int row = 0; row < 3; row++) {
            bench_io_counts before = bench_io_now(&bench.io);
            ffplaykmp_statistics counted = statistics_now();
            bench_io_counts delta;
            double start = now_ms();
            int decoded = 0;
            if (row == 0) {
                if (ffplaykmp_player_prepare(bench.player, BENCH_IO_URL, 0) < 0) {
                    fprintf(stderr, "prepare failed\n");
                    bench_player_close(&bench);
                    return;
                }
                took[row] += now_ms() - start;
                delta = bench_io_since(&bench.io, before);
            } else {
                took[row] += bench_audio_open(&bench, row == 2, &decoded, &delta);
            }
            io[row].opens += delta.opens;
            io[row].reads += delta.reads;
            io[row].bytes += delta.bytes;
            probes[row] += (long long)statistics_since(counted).input_probes;
            frames[row] += decoded;
        }
        bench_player_close(&bench);
    }
    printf("%s %s\n", file_name(path), preference == FFPLAYKMP_DECODER_AUTO ? "AUTO" : "SOFTWARE");
    for (int row = 0; row < 3; row++) {
        /* The audio rows count the open alone; each then decodes its first 0.5 s, to show it plays. */
        printf("  %-24s %8.1f ms | %4.1f opens %4.1f probes %6.1f reads %7.2f MB%s\n",
                rows[row], took[row] / RUNS, (double)io[row].opens / RUNS, (double)probes[row] / RUNS,
                (double)io[row].reads / RUNS, io[row].bytes / 1048576.0 / RUNS,
                row > 0 && frames[row] < 24000 * RUNS ? " (short read)" : "");
    }
}

/* What a pacing run's output saw: when each frame reached it, and its media time. */
typedef struct pacing_output {
    pthread_mutex_t mutex;
    int64_t *at_us;
    int64_t *pts_us;
    int count;
    int capacity;
} pacing_output;

/* A null output that takes every frame and, when one is presented, only notes when and which it was. */
static int on_paced_frame(void *opaque, const ffplaykmp_video_frame *frame) {
    pacing_output *output = opaque;
    int64_t now = av_gettime_relative();
    if (frame->event != FFPLAYKMP_VIDEO_FRAME_PRESENT)
        return frame->event == FFPLAYKMP_VIDEO_FRAME_PREPARE;
    pthread_mutex_lock(&output->mutex);
    if (output->count < output->capacity) {
        output->at_us[output->count] = now;
        output->pts_us[output->count] = frame->presentation_time_us;
        output->count++;
    }
    pthread_mutex_unlock(&output->mutex);
    return 0;
}

static int compare_int64(const void *a, const void *b) {
    int64_t x = *(const int64_t *)a;
    int64_t y = *(const int64_t *)b;
    return x < y ? -1 : x > y;
}

/* The median, 95th percentile and maximum of `count` values, in ms; sorts them. */
static void distribution(int64_t *values, int count, double *median, double *p95, double *max) {
    *median = *p95 = *max = 0;
    if (count <= 0)
        return;
    qsort(values, count, sizeof(*values), compare_int64);
    *median = values[count / 2] / 1000.0;
    *p95 = values[(count * 95) / 100 < count ? (count * 95) / 100 : count - 1] / 1000.0;
    *max = values[count - 1] / 1000.0;
}

/* The process's peak resident memory in MB; getrusage reports bytes on Apple platforms and KB elsewhere. */
static double peak_rss_mb(void) {
    struct rusage usage;
    getrusage(RUSAGE_SELF, &usage);
#if defined(__APPLE__)
    return usage.ru_maxrss / 1048576.0;
#else
    return usage.ru_maxrss / 1024.0;
#endif
}

/*
 * Plays the clip in real time, from the start, for `seconds` or to its end, through a null output
 * that only notes when each frame reached it: as close as the host comes to the player's own
 * pacing, with nothing converted or drawn. Audio plays no part, so the player follows the wall
 * clock from the first frame of the run: frame i is due at the time frame 0 reached the output
 * plus (pts i - pts 0). One line: frames presented and dropped, frames a second, how late
 * each frame reached the output against that schedule, how far apart consecutive frames came
 * against the stream's frame duration, and the process's peak RSS (one run per process, so it is
 * this run's). `threads` is FFplayConfiguration.threads: 0 for the automatic count.
 */
static void bench_pacing(const char *path, int preference, int threads, double seconds) {
    pacing_output output;
    bench_refresher refresher;
    ffplaykmp_configuration configuration;
    ffplaykmp_snapshot snapshot;
    bench_io io;
    ffplaykmp_player *player;
    int64_t *late;
    int64_t *gap;
    int64_t duration_us;
    double start;
    double late_median, late_p95, late_max, gap_median, gap_p95, gap_max;
    int count;
    int index;
    memset(&output, 0, sizeof(output));
    pthread_mutex_init(&output.mutex, NULL);
    output.capacity = (int)(seconds * 240) + 16;
    output.at_us = calloc(output.capacity, sizeof(int64_t));
    output.pts_us = calloc(output.capacity, sizeof(int64_t));
    if (bench_io_open(&io, path) < 0) {
        printf("%s: cannot open\n", file_name(path));
        return;
    }
    ffplaykmp_configuration_default(&configuration);
    configuration.decoder_preference = preference;
    configuration.decoder_threads = threads;
    player = ffplaykmp_player_create(&configuration, NULL, NULL);
    ffplaykmp_player_set_io_callback(player, bench_io_callback, &io);
    ffplaykmp_player_set_video_frame_callback(player, on_paced_frame, &output);
    if (ffplaykmp_player_prepare(player, BENCH_IO_URL, 0) < 0 || bench_set_output_of(player) < 0) {
        printf("%s: prepare failed\n", file_name(path));
        ffplaykmp_player_destroy(player);
        bench_io_close(&io);
        return;
    }
    /* The preview the output got on attaching is not part of the run. */
    av_usleep(200000);
    pthread_mutex_lock(&output.mutex);
    output.count = 0;
    pthread_mutex_unlock(&output.mutex);
    bench_refresher_start(&refresher, player);
    ffplaykmp_player_play(player);
    start = now_ms();
    do {
        av_usleep(20000);
        ffplaykmp_snapshot_init(&snapshot);
        ffplaykmp_player_get_snapshot(player, &snapshot);
    } while (snapshot.state == FFPLAYKMP_STATE_PLAYING && now_ms() - start < seconds * 1000);
    ffplaykmp_player_pause(player);
    bench_refresher_stop(&refresher);

    pthread_mutex_lock(&output.mutex);
    count = output.count;
    pthread_mutex_unlock(&output.mutex);
    late = calloc(count + 1, sizeof(int64_t));
    gap = calloc(count + 1, sizeof(int64_t));
    /* The stream's frame duration: the median distance between consecutive pts. */
    for (index = 1; index < count; index++)
        gap[index - 1] = output.pts_us[index] - output.pts_us[index - 1];
    qsort(gap, count > 1 ? count - 1 : 0, sizeof(int64_t), compare_int64);
    duration_us = count > 1 ? gap[(count - 1) / 2] : 0;
    for (index = 0; index < count; index++) {
        late[index] = output.at_us[index] - (output.at_us[0] + output.pts_us[index] - output.pts_us[0]);
        if (index > 0) {
            int64_t off = output.at_us[index] - output.at_us[index - 1] - duration_us;
            gap[index - 1] = off < 0 ? -off : off;
        }
    }
    distribution(late, count, &late_median, &late_p95, &late_max);
    distribution(gap, count > 1 ? count - 1 : 0, &gap_median, &gap_p95, &gap_max);
    printf("%-24s %-8s threads=%d | %4d presented, %4lld dropped, %5.1f fps over %.1f s | late ms median %6.2f p95 %6.2f "
            "max %7.2f | |gap - %.1f ms| median %6.2f p95 %6.2f max %7.2f | decoder %s | peak RSS %6.0f MB\n",
            file_name(path), preference == FFPLAYKMP_DECODER_AUTO ? "AUTO" : "SOFTWARE", threads, count,
            (long long)snapshot.dropped_frames,
            count > 1 ? (count - 1) * 1e6 / (output.at_us[count - 1] - output.at_us[0]) : 0,
            (now_ms() - start) / 1000.0, late_median, late_p95, late_max, duration_us / 1000.0, gap_median, gap_p95,
            gap_max, decoder_name(snapshot.active_decoder), peak_rss_mb());
    ffplaykmp_player_stop(player);
    ffplaykmp_player_destroy(player);
    bench_io_close(&io);
    free(late);
    free(gap);
    free(output.at_us);
    free(output.pts_us);
    pthread_mutex_destroy(&output.mutex);
}

int main(int argc, char **argv) {
    char path[1024];
    int preference;
    if (argc < 5) {
        fprintf(stderr, "usage: %s <clips> transitions|parallel|audio|pacing <clip> AUTO|SOFTWARE [players|threads] "
                "[seconds]\n", argv[0]);
        return 2;
    }
    if (getenv("FFMPEGKMP_BENCH_HARDWARE_DECODERS"))
        ffplaykmp_decoder_budget_set(atoi(getenv("FFMPEGKMP_BENCH_HARDWARE_DECODERS")));
    snprintf(path, sizeof(path), "%s/%s", argv[1], argv[3]);
    preference = !strcmp(argv[4], "AUTO") ? FFPLAYKMP_DECODER_AUTO : FFPLAYKMP_DECODER_SOFTWARE;
    if (!strcmp(argv[2], "transitions"))
        bench_transitions(path, preference);
    else if (!strcmp(argv[2], "audio"))
        bench_audio(path, preference);
    else if (!strcmp(argv[2], "parallel") && argc > 5 && atoi(argv[5]) >= 1 && atoi(argv[5]) <= 32)
        bench_parallel(path, preference, atoi(argv[5]));
    else if (!strcmp(argv[2], "pacing") && argc > 5 && atoi(argv[5]) >= 0)
        bench_pacing(path, preference, atoi(argv[5]), argc > 6 ? atof(argv[6]) : 10);
    return 0;
}
