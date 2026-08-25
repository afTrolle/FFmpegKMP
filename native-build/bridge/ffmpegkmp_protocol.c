// SPDX-License-Identifier: LGPL-2.1-or-later
#include <errno.h>
#include <inttypes.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>

#include "libavutil/avstring.h"
#include "libavutil/error.h"
#include "libavutil/log.h"
#include "libavutil/mem.h"
#include "libavutil/opt.h"
#include "libavutil/version.h"
#include "url.h"

#if defined(__EMSCRIPTEN__)
#include <emscripten/threading.h>
#endif

enum {
    FFMPEGKMP_IO_OPEN = 0,
    FFMPEGKMP_IO_READ = 1,
    FFMPEGKMP_IO_WRITE = 2,
    FFMPEGKMP_IO_SIZE = 3,
    FFMPEGKMP_IO_CLOSE = 4,
};

enum {
    FFMPEGKMP_IO_CAP_READ = 1,
    FFMPEGKMP_IO_CAP_WRITE = 2,
    FFMPEGKMP_IO_CAP_SEEK = 4,
};

typedef int64_t (*ffmpegkmp_protocol_callback)(
        void *opaque,
        int64_t resource_id,
        int operation,
        int64_t offset,
        uint8_t *data,
        uint64_t size);

void av_ffmpegkmp_protocol_set_callback(
        ffmpegkmp_protocol_callback callback,
        void *opaque);

typedef struct FFmpegKmpProtocolContext {
    const AVClass *class;
    int64_t resource_id;
    int64_t position;
    int capabilities;
} FFmpegKmpProtocolContext;

static ffmpegkmp_protocol_callback io_callback;
static void *io_opaque;

#if defined(__EMSCRIPTEN__)
typedef struct FFmpegKmpIoCall {
    int64_t resource_id;
    int operation;
    int64_t offset;
    uint8_t *data;
    uint64_t size;
    int64_t result;
} FFmpegKmpIoCall;

static void deliver_io_call(void *opaque) {
    FFmpegKmpIoCall *call = opaque;
    call->result = io_callback(
            io_opaque, call->resource_id, call->operation,
            call->offset, call->data, call->size);
}
#endif

static int64_t dispatch_io(
        int64_t resource_id,
        int operation,
        int64_t offset,
        uint8_t *data,
        uint64_t size) {
    if (!io_callback)
        return -1;
#if defined(__EMSCRIPTEN__)
    if (!emscripten_is_main_runtime_thread()) {
        FFmpegKmpIoCall call = {
            .resource_id = resource_id,
            .operation = operation,
            .offset = offset,
            .data = data,
            .size = size,
            .result = -1,
        };
        emscripten_sync_run_in_main_runtime_thread(
                EM_FUNC_SIG_VI, deliver_io_call, &call);
        return call.result;
    }
#endif
    return io_callback(io_opaque, resource_id, operation, offset, data, size);
}

void av_ffmpegkmp_protocol_set_callback(
        ffmpegkmp_protocol_callback callback,
        void *opaque);

void av_ffmpegkmp_protocol_set_callback(
        ffmpegkmp_protocol_callback callback,
        void *opaque) {
    io_callback = callback;
    io_opaque = opaque;
}

static int parse_resource_id(const char *filename, int64_t *resource_id) {
    const char *value;
    const char *cursor;
    int64_t parsed = 0;

    if (!av_strstart(filename, "ffmpegkmp:", &value) || !value || *value < '0' || *value > '9')
        return AVERROR(EINVAL);

    for (cursor = value; *cursor >= '0' && *cursor <= '9'; cursor++) {
        const int digit = *cursor - '0';
        if (parsed > (INT64_MAX - digit) / 10)
            return AVERROR(EINVAL);
        parsed = parsed * 10 + digit;
    }
    if (*cursor && *cursor != '.')
        return AVERROR(EINVAL);

    *resource_id = parsed;
    return 0;
}

static int ffmpegkmp_open(URLContext *h, const char *filename, int flags) {
    FFmpegKmpProtocolContext *context = h->priv_data;
    int64_t capabilities;
    int result = parse_resource_id(filename, &context->resource_id);
    if (result < 0) {
        av_log(h, AV_LOG_ERROR, "Invalid ffmpegkmp URL '%s'.\n", filename);
        return result;
    }
    if (!io_callback)
        return AVERROR(ENOSYS);

    capabilities = dispatch_io(
            context->resource_id, FFMPEGKMP_IO_OPEN, flags, NULL, 0);
    if (capabilities < 0)
        return AVERROR(EIO);
    if ((flags & AVIO_FLAG_READ) && !(capabilities & FFMPEGKMP_IO_CAP_READ))
        return AVERROR(EACCES);
    if ((flags & AVIO_FLAG_WRITE) && !(capabilities & FFMPEGKMP_IO_CAP_WRITE))
        return AVERROR(EACCES);

    context->capabilities = (int)capabilities;
    context->position = 0;
    h->is_streamed = !(capabilities & FFMPEGKMP_IO_CAP_SEEK);
    return 0;
}

static int ffmpegkmp_read(URLContext *h, unsigned char *buffer, int size) {
    FFmpegKmpProtocolContext *context = h->priv_data;
    int64_t result;
    if (!io_callback || !(context->capabilities & FFMPEGKMP_IO_CAP_READ))
        return AVERROR(EBADF);
    result = dispatch_io(
            context->resource_id, FFMPEGKMP_IO_READ,
            context->position, buffer, (uint64_t)size);
    if (result < 0)
        return AVERROR(EIO);
    if (result == 0)
        return AVERROR_EOF;
    if (result > size)
        return AVERROR(EIO);
    context->position += result;
    return (int)result;
}

static int ffmpegkmp_write(URLContext *h, const unsigned char *buffer, int size) {
    FFmpegKmpProtocolContext *context = h->priv_data;
    int64_t result;
    if (!io_callback || !(context->capabilities & FFMPEGKMP_IO_CAP_WRITE))
        return AVERROR(EBADF);
    result = dispatch_io(
            context->resource_id, FFMPEGKMP_IO_WRITE,
            context->position, (uint8_t *)buffer, (uint64_t)size);
    if (result < 0 || result > size)
        return AVERROR(EIO);
    context->position += result;
    return (int)result;
}

static int64_t ffmpegkmp_seek(URLContext *h, int64_t offset, int whence) {
    FFmpegKmpProtocolContext *context = h->priv_data;
    int64_t base;
    int64_t size;
    int normalized_whence = whence & ~AVSEEK_FORCE;

    if (!io_callback || !(context->capabilities & FFMPEGKMP_IO_CAP_SEEK))
        return AVERROR(ESPIPE);

    if (normalized_whence == AVSEEK_SIZE)
        return dispatch_io(
                context->resource_id, FFMPEGKMP_IO_SIZE,
                0, NULL, 0);

    if (normalized_whence == SEEK_SET) {
        base = 0;
    } else if (normalized_whence == SEEK_CUR) {
        base = context->position;
    } else if (normalized_whence == SEEK_END) {
        size = dispatch_io(
                context->resource_id, FFMPEGKMP_IO_SIZE,
                0, NULL, 0);
        if (size < 0)
            return AVERROR(EIO);
        base = size;
    } else {
        return AVERROR(EINVAL);
    }

    if (offset > 0 && base > INT64_MAX - offset)
        return AVERROR(EOVERFLOW);
    if (base + offset < 0)
        return AVERROR(EINVAL);

    context->position = base + offset;
    return context->position;
}

static int ffmpegkmp_close(URLContext *h) {
    FFmpegKmpProtocolContext *context = h->priv_data;
    if (io_callback) {
        const int64_t result = dispatch_io(
                context->resource_id, FFMPEGKMP_IO_CLOSE,
                context->position, NULL, 0);
        if (result < 0)
            return AVERROR(EIO);
    }
    return 0;
}

static const AVClass ffmpegkmp_class = {
    .class_name = "ffmpegkmp",
    .item_name = av_default_item_name,
    .version = LIBAVUTIL_VERSION_INT,
};

const URLProtocol ff_ffmpegkmp_protocol = {
    .name = "ffmpegkmp",
    .url_open = ffmpegkmp_open,
    .url_read = ffmpegkmp_read,
    .url_write = ffmpegkmp_write,
    .url_seek = ffmpegkmp_seek,
    .url_close = ffmpegkmp_close,
    .priv_data_size = sizeof(FFmpegKmpProtocolContext),
    .priv_data_class = &ffmpegkmp_class,
    .default_whitelist = "ffmpegkmp,crypto,data",
};
