// SPDX-License-Identifier: LGPL-2.1-or-later
#include "fftools/opt_common.c"

void ffmpegkmp_opt_common_reset(void);

/* -report opens its log file once per process and never closes it, so a later
 * -report run would write no report. Close it before each ffmpeg or ffprobe run. */
void ffmpegkmp_opt_common_reset(void) {
    if (report_file) {
        fclose(report_file);
        report_file = NULL;
    }
    report_file_level = AV_LOG_DEBUG;
}
