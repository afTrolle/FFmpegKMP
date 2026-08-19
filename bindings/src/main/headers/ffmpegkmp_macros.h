// SPDX-License-Identifier: LGPL-2.1-or-later
#ifndef FFMPEGKMP_MACROS_H
#define FFMPEGKMP_MACROS_H

#include <libavutil/error.h>
#include <libavutil/version.h>

static inline int ffmpegkmp_averror(int error) {
    return AVERROR(error);
}

static inline unsigned ffmpegkmp_av_version_int(int major, int minor, int micro) {
    return AV_VERSION_INT(major, minor, micro);
}

#endif
