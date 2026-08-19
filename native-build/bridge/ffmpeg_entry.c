// SPDX-License-Identifier: LGPL-2.1-or-later
#include "ffmpegkmp_bridge.h"

#define main ffmpegkmp_ffmpeg_main_impl
#define program_name ffmpegkmp_ffmpeg_program_name
#define program_birth_year ffmpegkmp_ffmpeg_program_birth_year
#define exit ffmpegkmp_exit
int ffmpegkmp_ffmpeg_main_impl(int argc, char **argv);
#include "fftools/ffmpeg.c"
#undef exit
#undef program_birth_year
#undef program_name
#undef main

int ffmpegkmp_ffmpeg_entry(int argc, char **argv);
void ffmpegkmp_ffmpeg_cancel(void);

int ffmpegkmp_ffmpeg_entry(int argc, char **argv) {
    received_sigterm = 0;
    received_nb_signals = 0;
    atomic_store(&transcode_init_done, 0);
    ffmpeg_exited = 0;
    copy_ts_first_pts = AV_NOPTS_VALUE;
    atomic_store(&nb_output_dumped, 0);
    return ffmpegkmp_ffmpeg_main_impl(argc, argv);
}

void ffmpegkmp_ffmpeg_cancel(void) {
    received_sigterm = SIGTERM;
}
