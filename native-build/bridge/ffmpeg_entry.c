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

#if defined(__ANDROID__) && CONFIG_MEDIACODEC
/* Replaces compat/android/binder.c. The real helper starts a binder thread pool
 * for the standalone CLI; inside an app process Zygote has already started it
 * and ABinderProcess_setThreadPoolMaxThreadCount aborts the process. MediaCodec
 * works through the framework's existing binder threads, so a no-op is correct. */
void android_binder_threadpool_init_if_required(void) {}
#endif

int ffmpegkmp_ffmpeg_entry(int argc, char **argv) {
    received_sigterm = 0;
    received_nb_signals = 0;
    atomic_store(&transcode_init_done, 0);
    ffmpeg_exited = 0;
    copy_ts_first_pts = AV_NOPTS_VALUE;
    atomic_store(&nb_output_dumped, 0);
    /* ffmpeg_cleanup frees these arrays but leaves the counters behind, which is
     * fine for a dying process. Reused in-process, stale counts make the next
     * run's cleanup walk freshly reallocated arrays past their real size and
     * free garbage pointers (double free / SIGABRT in fg_free). Reset all of
     * the paired array+count globals, and the vstats handle fclose'd but never
     * NULLed, before every run. */
    input_files = NULL;
    nb_input_files = 0;
    output_files = NULL;
    nb_output_files = 0;
    filtergraphs = NULL;
    nb_filtergraphs = 0;
    decoders = NULL;
    nb_decoders = 0;
    vstats_file = NULL;
    progress_avio = NULL;
    return ffmpegkmp_ffmpeg_main_impl(argc, argv);
}

void ffmpegkmp_ffmpeg_cancel(void) {
    received_sigterm = SIGTERM;
}
