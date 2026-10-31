// SPDX-License-Identifier: LGPL-2.1-or-later
/*
 * A counting ffplaykmp_io_callback for the benchmarks: serves one file as
 * "ffmpegkmp:1", as a mounted FileHandle reaches the bridge, and counts every
 * open, read, byte and close. FFMPEGKMP_BENCH_IO_LATENCY_US adds that many
 * microseconds to each read, standing in for a slow or remote source.
 */
#ifndef FFMPEGKMP_BENCH_IO_H
#define FFMPEGKMP_BENCH_IO_H

#include "ffplaykmp_player.h"

#include <fcntl.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>

#include <libavutil/time.h>

#define BENCH_IO_URL "ffmpegkmp:1"

typedef struct bench_io {
    int fd;
    int64_t latency_us;
    atomic_llong opens;
    atomic_llong closes;
    atomic_llong reads;
    atomic_llong bytes;
} bench_io;

/* What a bench_io has counted so far; subtract two to count one operation. */
typedef struct bench_io_counts {
    long long opens;
    long long closes;
    long long reads;
    long long bytes;
} bench_io_counts;

static int bench_io_open(bench_io *io, const char *path) {
    const char *latency = getenv("FFMPEGKMP_BENCH_IO_LATENCY_US");
    io->fd = open(path, O_RDONLY);
    io->latency_us = latency ? atoll(latency) : 0;
    atomic_init(&io->opens, 0);
    atomic_init(&io->closes, 0);
    atomic_init(&io->reads, 0);
    atomic_init(&io->bytes, 0);
    return io->fd < 0 ? -1 : 0;
}

static void bench_io_close(bench_io *io) {
    if (io->fd >= 0)
        close(io->fd);
    io->fd = -1;
}

static bench_io_counts bench_io_now(bench_io *io) {
    bench_io_counts counts = {
        atomic_load(&io->opens), atomic_load(&io->closes), atomic_load(&io->reads), atomic_load(&io->bytes),
    };
    return counts;
}

static bench_io_counts bench_io_since(bench_io *io, bench_io_counts before) {
    bench_io_counts now = bench_io_now(io);
    bench_io_counts delta = {
        now.opens - before.opens, now.closes - before.closes, now.reads - before.reads, now.bytes - before.bytes,
    };
    return delta;
}

static int64_t bench_io_callback(
        void *opaque,
        int64_t resource_id,
        uint32_t operation,
        int64_t offset,
        uint8_t *data,
        uint64_t size) {
    bench_io *io = opaque;
    struct stat status;
    ssize_t count;
    if (resource_id != 1)
        return -1;
    switch (operation) {
    case FFPLAYKMP_IO_OPEN:
        atomic_fetch_add(&io->opens, 1);
        return FFPLAYKMP_IO_CAP_READ | FFPLAYKMP_IO_CAP_SEEK;
    case FFPLAYKMP_IO_READ:
        if (io->latency_us > 0)
            av_usleep((unsigned)io->latency_us);
        count = pread(io->fd, data, size, offset);
        if (count < 0)
            return -1;
        atomic_fetch_add(&io->reads, 1);
        atomic_fetch_add(&io->bytes, count);
        return count;
    case FFPLAYKMP_IO_SIZE:
        return fstat(io->fd, &status) == 0 ? (int64_t)status.st_size : -1;
    case FFPLAYKMP_IO_CLOSE:
        atomic_fetch_add(&io->closes, 1);
        return 0;
    default:
        return -1;
    }
}

#endif
