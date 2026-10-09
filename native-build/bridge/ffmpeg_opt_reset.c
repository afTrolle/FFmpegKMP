// SPDX-License-Identifier: LGPL-2.1-or-later
#include "config.h"

#include <stddef.h>

#if HAVE_SYS_RESOURCE_H
#include <sys/time.h>
#include <sys/resource.h>
#endif

#include "libavutil/log.h"

#if HAVE_SETRLIMIT
/* -timelimit sets RLIMIT_CPU for the whole host process, and without fftools'
 * SIGXCPU handler reaching that limit kills it. Accept the option, change nothing. */
static int ffmpegkmp_setrlimit(int resource, const struct rlimit *limit) {
    (void) resource;
    (void) limit;
    av_log(NULL, AV_LOG_WARNING, "-timelimit is ignored: it would limit the host process\n");
    return 0;
}
#define setrlimit ffmpegkmp_setrlimit
#endif
#include "fftools/ffmpeg_opt.c"
#undef setrlimit

void ffmpegkmp_ffmpeg_opt_reset(void);

/* ffmpeg_opt.c initializes its option globals once per process, so -y, -copyts,
 * -nostats and the rest would carry into every later command. Restore the
 * upstream initial values before each run, including the statics only this
 * file can reach. stdin_interaction is the exception: embedded commands never
 * read the host's standard input (see term_init in ffmpeg_entry.c). */
void ffmpegkmp_ffmpeg_opt_reset(void) {
    /* Points into the device list ffmpeg_cleanup frees without clearing it. */
    filter_hw_device = NULL;
    av_freep(&vstats_filename);
    dts_delta_threshold = 10;
    dts_error_threshold = 3600 * 30;
    frame_drop_threshold = 0;
    do_benchmark = 0;
    do_benchmark_all = 0;
    do_hex_dump = 0;
    do_pkt_dump = 0;
    copy_ts = 0;
    start_at_zero = 0;
    copy_tb = -1;
    debug_ts = 0;
    exit_on_error = 0;
    abort_on_flags = 0;
    print_stats = -1;
    stdin_interaction = 0;
    max_error_rate = 2.0 / 3;
    av_freep(&filter_nbthreads);
    filter_complex_nbthreads = 0;
    filter_buffered_frames = 0;
    vstats_version = 2;
    print_graphs = 0;
    av_freep(&print_graphs_file);
    av_freep(&print_graphs_format);
    auto_conversion_filters = 1;
    stats_period = 500000;
    file_overwrite = 0;
    no_file_overwrite = 0;
    ignore_unknown_streams = 0;
    copy_unknown_streams = 0;
    recast_media = 0;
}
