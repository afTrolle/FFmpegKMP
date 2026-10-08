// SPDX-License-Identifier: LGPL-2.1-or-later
#include "ffmpegkmp_bridge.h"

#define main ffmpegkmp_ffmpeg_main_impl
#define program_name ffmpegkmp_ffmpeg_program_name
#define program_birth_year ffmpegkmp_ffmpeg_program_birth_year
#define exit ffmpegkmp_exit
/* The upstream term_init installs SIGINT, SIGTERM, SIGQUIT and SIGXCPU handlers,
 * ignores SIGPIPE and puts the terminal into raw mode, all for the whole host
 * process. It stays compiled under another name; fftools calls the one below. */
#define term_init ffmpegkmp_upstream_term_init
int ffmpegkmp_ffmpeg_main_impl(int argc, char **argv);
#include "fftools/ffmpeg.c"
#undef term_init
#undef exit
#undef program_birth_year
#undef program_name
#undef main

int ffmpegkmp_ffmpeg_entry(int argc, char **argv);
void ffmpegkmp_ffmpeg_cancel(void);
void term_init(void);
/* Supplied by the ffmpeg_opt.c and opt_common.c wrappers. */
extern void ffmpegkmp_ffmpeg_opt_reset(void);
extern void ffmpegkmp_opt_common_reset(void);

/* fftools calls this after it parses the global options and before it opens any
 * file. Embedded commands never read keystrokes or overwrite prompts from the
 * host's standard input, so an explicit -stdin is ignored here. */
void term_init(void) {
    if (stdin_interaction)
        av_log(NULL, AV_LOG_WARNING,
               "-stdin is ignored: embedded commands never read standard input\n");
    stdin_interaction = 0;
}

int ffmpegkmp_ffmpeg_entry(int argc, char **argv) {
    /* The CLI frees these arrays but assumes process exit and leaves their counts unchanged. */
    input_files = NULL;
    nb_input_files = 0;
    output_files = NULL;
    nb_output_files = 0;
    filtergraphs = NULL;
    nb_filtergraphs = 0;
    decoders = NULL;
    nb_decoders = 0;
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
    ffmpegkmp_ffmpeg_opt_reset();
    ffmpegkmp_opt_common_reset();
    hide_banner = 0;
    /* An exit() mid-run skips ffmpeg_cleanup, which frees the cmdutils option dicts. */
    uninit_opts();
    /* A cancel requested before this point is not in the flags reset above: either it
     * was wiped with them, or it came while the context was not yet the active one. */
    if (ffmpegkmp_cancel_requested())
        ffmpegkmp_ffmpeg_cancel();
    return ffmpegkmp_ffmpeg_main_impl(argc, argv);
}

/* What sigterm_handler did for the first SIGTERM, minus the handler: the main
 * loop stops at its next stats_period tick, stops the scheduler and writes the
 * trailers, and I/O opened before transcoding starts is interrupted. */
void ffmpegkmp_ffmpeg_cancel(void) {
    /* The transcode loop and the I/O interrupt callback watch the signal count, the way
     * the CLI's own handler leaves it after one SIGTERM. received_sigterm alone only
     * changes the exit message, so a cancelled run carried on to its natural end.
     * Set rather than counted: a repeated cancel must not escalate into the second-signal
     * interrupt that abandons the output mid-write. */
    received_sigterm = SIGTERM;
    received_nb_signals = 1;
}
