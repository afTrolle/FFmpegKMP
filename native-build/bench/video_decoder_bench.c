// SPDX-License-Identifier: LGPL-2.1-or-later
/*
 * Benchmarks the pull video decoder (ffmpegkmp_decoder.c), its frame handles
 * (ffmpegkmp_frame.c) and the frame conversions it shares with the player
 * (ffplaykmp_core.c), built unchanged.
 * scripts/bench-video-decoder.sh builds and runs it.
 *
 * FFMPEGKMP_BENCH_THREADS is the decoders' decoder_threads, passed to
 * ffmpegkmp_video_decoder_create. Unset, it is 0: FFmpeg's automatic count,
 * capped as the bridge caps it, which is what DecoderThreads.Auto passes.
 */
#include "ffmpegkmp_decoder.h"
#include "ffplaykmp_core.h"

#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavutil/pixdesc.h>
#include <libavutil/time.h>

#if defined(__APPLE__)
#include <mach/mach.h>
#endif

/* ffplaykmp_player.c defines it; the benchmark does not link the player. */
void ffplaykmp_snapshot_init(ffplaykmp_snapshot *snapshot) {
    memset(snapshot, 0, sizeof(*snapshot));
    snapshot->size = sizeof(*snapshot);
    snapshot->duration_us = -1;
    ffplaykmp_reset_video_metadata(snapshot);
}

static double now_ms(void) { return av_gettime_relative() / 1000.0; }

/*
 * What each step hands out: MEMORY output converted into RGBA8 on the
 * decoder's thread, or MEMORY output as decoded, where each frame is a
 * CVPixelBuffer (the PIXEL_BUFFER output before frame handles).
 */
enum { BENCH_RGBA8, BENCH_PIXEL_BUFFER };

static const char *output_name(int output) {
    static const char *names[] = { "RGBA8", "PIXEL_BUFFER" };
    return names[output];
}

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

static const char *preference_name(int preference) {
    static const char *names[] = { "AUTO", "REQUIRE_HW", "SOFTWARE" };
    return names[preference];
}

static const char *file_name(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

/* The decoders' decoder_threads: FFMPEGKMP_BENCH_THREADS, or 0. */
static int bench_threads(void) {
    const char *variable = getenv("FFMPEGKMP_BENCH_THREADS");
    return variable ? atoi(variable) : 0;
}

/*
 * Opens `path`, then calls frame_at on every frame in order (each step
 * decodes one frame ahead and converts the current one), then walks `back`
 * frames backward (each step seeks to the keyframe and decodes up to it).
 */
static void bench_steps(const char *path, int output, int preference, int frames, double fps, int back) {
    int error = 0;
    int result;
    int index;
    int stepped = 0;
    int hardware = 0;
    double start;
    double opened;
    double total = 0;
    double worst = 0;
    const int threads = bench_threads();
    const ffmpegkmp_frame_format rgba8 = rgba8_format();
    ffmpegkmp_decoded_frame frame;
    ffmpegkmp_video_decoder *decoder = ffmpegkmp_video_decoder_create(
            path, FFMPEGKMP_VIDEO_OUTPUT_MEMORY, output == BENCH_RGBA8 ? &rgba8 : NULL, 0, 0, preference, threads, 0,
            NULL, NULL, &error);
    if (!decoder) {
        printf("%s: create failed (%d)\n", file_name(path), error);
        return;
    }
    ffmpegkmp_decoded_frame_init(&frame);
    start = now_ms();
    result = ffmpegkmp_video_decoder_start(decoder);
    if (result >= 0 && (result = ffmpegkmp_video_decoder_frame_at(decoder, 0, &frame)) >= 0)
        ffmpegkmp_frame_unref(frame.frame);
    opened = now_ms();
    if (result < 0) {
        printf("%s %s: start failed (%d)\n", file_name(path), output_name(output), result);
        ffmpegkmp_video_decoder_destroy(decoder);
        return;
    }
    for (index = 1; index < frames; index++) {
        double before = now_ms();
        double took;
        if ((result = ffmpegkmp_video_decoder_frame_at(decoder, llround(index * 1e9 / fps), &frame)) < 0) {
            printf("%s: frame_at %d failed (%d)\n", file_name(path), index, result);
            break;
        }
        took = now_ms() - before;
        ffmpegkmp_frame_unref(frame.frame);
        if (frame.serial != (uint64_t)index + 1)
            printf("%s: step %d returned serial %llu, not a new frame\n",
                    file_name(path), index, (unsigned long long)frame.serial);
        total += took;
        worst = took > worst ? took : worst;
        hardware = frame.hardware;
        stepped++;
    }
    printf("%-20s %-12s %-10s threads=%-4d hw=%d | open+first %6.1f ms | forward %7.2f ms/frame (max %6.1f)",
            file_name(path), output_name(output), preference_name(preference), threads,
            hardware, opened - start, stepped ? total / stepped : 0, worst);
    if (back > 0) {
        total = 0;
        stepped = 0;
        for (index = frames - 2; index >= frames - 1 - back; index--) {
            double before = now_ms();
            if (ffmpegkmp_video_decoder_frame_at(decoder, llround(index * 1e9 / fps), &frame) < 0)
                break;
            total += now_ms() - before;
            ffmpegkmp_frame_unref(frame.frame);
            stepped++;
        }
        printf(" | backward %6.1f ms/frame", stepped ? total / stepped : 0);
    }
    printf("\n");
    ffmpegkmp_video_decoder_destroy(decoder);
}

/* The process's thread count and physical footprint in MB now; 0 where the benchmark cannot tell. */
static void process_usage(int *threads, int *footprint_mb) {
#if defined(__APPLE__)
    thread_act_array_t list;
    mach_msg_type_number_t count = 0;
    task_vm_info_data_t vm;
    mach_msg_type_number_t vm_count = TASK_VM_INFO_COUNT;
#endif
    *threads = 0;
    *footprint_mb = 0;
#if defined(__APPLE__)
    if (task_threads(mach_task_self(), &list, &count) == KERN_SUCCESS) {
        for (mach_msg_type_number_t index = 0; index < count; index++)
            mach_port_deallocate(mach_task_self(), list[index]);
        vm_deallocate(mach_task_self(), (vm_address_t)list, count * sizeof(*list));
        *threads = (int)count;
    }
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&vm, &vm_count) == KERN_SUCCESS)
        *footprint_mb = (int)(vm.phys_footprint >> 20);
#endif
}

/* One of bench_parallel's decoders: `frames` frames of `path` forward, into RGBA8 at the size. */
typedef struct bench_decoder_job {
    const char *path;
    int width;
    int height;
    int frames;
    double fps;
    int threads;
    /* Written by the decoder's thread, read by the sampler's. */
    volatile int decoded;
} bench_decoder_job;

static void *bench_decoder_main(void *opaque) {
    bench_decoder_job *job = opaque;
    const ffmpegkmp_frame_format rgba8 = rgba8_format();
    ffmpegkmp_decoded_frame frame;
    int error = 0;
    int index;
    ffmpegkmp_video_decoder *decoder = ffmpegkmp_video_decoder_create(
            job->path, FFMPEGKMP_VIDEO_OUTPUT_MEMORY, &rgba8, job->width, job->height, FFPLAYKMP_DECODER_SOFTWARE,
            job->threads, 0, NULL, NULL, &error);
    if (!decoder)
        return NULL;
    ffmpegkmp_decoded_frame_init(&frame);
    if (ffmpegkmp_video_decoder_start(decoder) >= 0) {
        for (index = 0; index < job->frames; index++) {
            if (ffmpegkmp_video_decoder_frame_at(decoder, llround(index * 1e9 / job->fps), &frame) < 0)
                break;
            ffmpegkmp_frame_unref(frame.frame);
            job->decoded++;
        }
    }
    ffmpegkmp_video_decoder_destroy(decoder);
    return NULL;
}

/*
 * Samples the process's threads and footprint until *stop, keeping the peaks,
 * and the peak threads while every decoder is past its first frames and none
 * has finished: swscale builds its gamut-mapping table on the first frame with
 * a thread per core of its own, whatever the context's thread count.
 */
typedef struct bench_usage_sampler {
    volatile int stop;
    const bench_decoder_job *jobs;
    int count;
    int peak_threads;
    int running_threads;
    int peak_footprint_mb;
} bench_usage_sampler;

static int bench_all_running(const bench_usage_sampler *sampler) {
    for (int index = 0; index < sampler->count; index++) {
        if (sampler->jobs[index].decoded < 2 || sampler->jobs[index].decoded >= sampler->jobs[index].frames)
            return 0;
    }
    return 1;
}

static void *bench_sampler_main(void *opaque) {
    bench_usage_sampler *sampler = opaque;
    while (!sampler->stop) {
        int threads;
        int footprint_mb;
        process_usage(&threads, &footprint_mb);
        sampler->peak_threads = threads > sampler->peak_threads ? threads : sampler->peak_threads;
        if (bench_all_running(sampler) && threads > sampler->running_threads)
            sampler->running_threads = threads;
        sampler->peak_footprint_mb =
                footprint_mb > sampler->peak_footprint_mb ? footprint_mb : sampler->peak_footprint_mb;
        av_usleep(2000);
    }
    return NULL;
}

/*
 * `count` decoders at once, each on a thread of its own, opening `path` and
 * decoding and converting its first `frames` frames forward. Reports the
 * frames all of them handed out a second, open included, the most threads
 * the process had meanwhile (overall, and once they all run) and its largest
 * footprint, with the footprint's growth over the start shared out per
 * decoder. Memory freed stays in the process's footprint, so each case runs
 * in a process of its own.
 */
static void bench_parallel(const char *path, int width, int height, int frames, double fps, int count) {
    bench_decoder_job jobs[8];
    pthread_t threads[8];
    pthread_t sampler_thread;
    bench_usage_sampler sampler = { 0, jobs, count };
    int idle_threads;
    int idle_footprint_mb;
    int decoded = 0;
    int index;
    double start;
    double took;
    process_usage(&idle_threads, &idle_footprint_mb);
    sampler.peak_threads = idle_threads;
    sampler.peak_footprint_mb = idle_footprint_mb;
    for (index = 0; index < count; index++)
        jobs[index] = (bench_decoder_job) { path, width, height, frames, fps, bench_threads(), 0 };
    pthread_create(&sampler_thread, NULL, bench_sampler_main, &sampler);
    start = now_ms();
    for (index = 0; index < count; index++)
        pthread_create(&threads[index], NULL, bench_decoder_main, &jobs[index]);
    for (index = 0; index < count; index++) {
        pthread_join(threads[index], NULL);
        decoded += jobs[index].decoded;
    }
    took = now_ms() - start;
    sampler.stop = 1;
    pthread_join(sampler_thread, NULL);
    printf("%-22s RGBA8 %-7s decoders=%d threads=%-2d | %6.1f frames/s (%5.1f each) | peak %3d threads "
            "(%3d running), %5d MB (%4d MB each)",
            file_name(path), width ? "960x540" : "full", count, jobs[0].threads,
            decoded * 1000.0 / took, decoded * 1000.0 / took / count, sampler.peak_threads,
            sampler.running_threads, sampler.peak_footprint_mb,
            (sampler.peak_footprint_mb - idle_footprint_mb) / count);
    if (decoded != count * frames)
        printf(" | %d of %d frames", decoded, count * frames);
    printf("\n");
}

/* The first frame of `path`, decoded in software. */
static AVFrame *first_frame(const char *path) {
    AVFormatContext *format = NULL;
    AVCodecContext *context = NULL;
    const AVCodec *codec = NULL;
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    int decoded = 0;
    int stream;
    if (avformat_open_input(&format, path, NULL, NULL) < 0 || avformat_find_stream_info(format, NULL) < 0 ||
            (stream = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0)) < 0 ||
            !(context = avcodec_alloc_context3(codec)) ||
            avcodec_parameters_to_context(context, format->streams[stream]->codecpar) < 0 ||
            avcodec_open2(context, codec, NULL) < 0)
        goto done;
    while (!decoded && av_read_frame(format, packet) >= 0) {
        if (packet->stream_index == stream && avcodec_send_packet(context, packet) >= 0)
            decoded = avcodec_receive_frame(context, frame) >= 0;
        av_packet_unref(packet);
    }
done:
    avcodec_free_context(&context);
    avformat_close_input(&format);
    av_packet_free(&packet);
    if (!decoded)
        av_frame_free(&frame);
    return frame;
}

/*
 * Converts `source` into a new `width` x `height` frame of `format` and colour
 * `runs` times; returns ms per conversion.
 */
static double convert_ms(
        ffmpegkmp_converter *converter,
        const AVFrame *source,
        enum AVPixelFormat format,
        enum AVColorPrimaries primaries,
        enum AVColorTransferCharacteristic transfer,
        int width,
        int height,
        int runs) {
    AVFrame *target = av_frame_alloc();
    double start;
    double took = -1;
    int run;
    target->format = format;
    target->width = width;
    target->height = height;
    target->color_primaries = primaries;
    target->color_trc = transfer;
    target->colorspace = format == AV_PIX_FMT_P010 ? AVCOL_SPC_BT2020_NCL : AVCOL_SPC_RGB;
    target->color_range = format == AV_PIX_FMT_P010 ? AVCOL_RANGE_MPEG : AVCOL_RANGE_JPEG;
    if (ffmpegkmp_frame_convert(converter, target, source) >= 0) {
        start = now_ms();
        for (run = 0; run < runs; run++)
            ffmpegkmp_frame_convert(converter, target, source);
        took = (now_ms() - start) / runs;
    }
    av_frame_free(&target);
    return took;
}

static void bench_conversions(const char *path, int hdr) {
    AVFrame *source = first_frame(path);
    ffmpegkmp_converter *converter = ffmpegkmp_converter_alloc(0);
    AVFrame *rgba = av_frame_alloc();
    AVFrame *linear = av_frame_alloc();
    size_t rgba_size;
    uint8_t *reused;
    double start;
    int runs = 20;
    int run;
    if (!source || !converter || !rgba || !linear) {
        printf("%s: could not decode a frame\n", file_name(path));
        return;
    }
    rgba_size = (size_t)source->width * source->height * 4;
    reused = malloc(rgba_size);
    printf("\n%s (%dx%d %s)\n", file_name(path), source->width, source->height,
            av_get_pix_fmt_name(source->format));
    /* The decoder's RGBA8 output of an SDR source, and the player's without the tone map: swscale only. */
    ffplaykmp_rgb_output(rgba, source, AV_PIX_FMT_RGBA, 0);
    ffmpegkmp_frame_convert(converter, rgba, source);
    start = now_ms();
    for (run = 0; run < runs; run++)
        ffmpegkmp_frame_convert(converter, rgba, source);
    printf("  YUV->RGBA, converter (swscale only)             %8.2f ms\n", (now_ms() - start) / runs);
    start = now_ms();
    for (run = 0; run < runs; run++) {
        memcpy(reused, rgba->data[0], rgba_size);
        __asm__ volatile("" : : "r"(reused) : "memory");
    }
    printf("  RGBA frame copy (one pass, as each Kotlin copy) %8.2f ms\n", (now_ms() - start) / runs);
    if (!hdr) {
        /* VideoOutput.Memory(Rgba8, FrameSize(960, 540)): scaled in the same swscale pass. */
        printf("  YUV->RGBA8 at 960x540, converter                %8.2f ms\n", convert_ms(
                converter, source, AV_PIX_FMT_RGBA, AVCOL_PRI_BT709, AVCOL_TRC_IEC61966_2_1, 960, 540, runs));
    }
    {
        /* VideoFrame.toImageBitmap's path: the decoded frame's handle straight into a bitmap's memory. */
        const ffmpegkmp_frame_format rgba8 = rgba8_format();
        ffmpegkmp_frame *handle = ffmpegkmp_frame_from_av(source);
        ffmpegkmp_frame *bitmap = ffmpegkmp_frame_wrap(&rgba8, source->width, source->height, reused, source->width * 4);
        if (handle && bitmap && ffmpegkmp_frame_convert_into(bitmap, handle) >= 0) {
            start = now_ms();
            for (run = 0; run < runs; run++)
                ffmpegkmp_frame_convert_into(bitmap, handle);
            printf("  YUV->RGBA8 into a bitmap, frame handles         %8.2f ms\n", (now_ms() - start) / runs);
        }
        ffmpegkmp_frame_unref(bitmap);
        ffmpegkmp_frame_unref(handle);
    }
    if (hdr) {
        ffplaykmp_rgb_output(rgba, source, AV_PIX_FMT_RGBA, 1);
        ffmpegkmp_frame_convert(converter, rgba, source);
        start = now_ms();
        for (run = 0; run < runs; run++)
            ffmpegkmp_frame_convert(converter, rgba, source);
        printf("  PQ tone map to RGBA8, converter                 %8.2f ms\n", (now_ms() - start) / runs);
        printf("  PQ tone map to RGBA8 at 960x540, converter      %8.2f ms\n", convert_ms(
                converter, source, AV_PIX_FMT_RGBA, AVCOL_PRI_BT709, AVCOL_TRC_IEC61966_2_1, 960, 540, runs));
        ffplaykmp_rgb_output(linear, source, AV_PIX_FMT_RGBAF16, 0);
        ffmpegkmp_frame_convert(converter, linear, source);
        start = now_ms();
        for (run = 0; run < runs; run++)
            ffmpegkmp_frame_convert(converter, linear, source);
        printf("  PQ to linear F16, converter                     %8.2f ms\n", (now_ms() - start) / runs);
        printf("  PQ to linear F16 at 960x540, converter          %8.2f ms\n", convert_ms(
                converter, source, AV_PIX_FMT_RGBAF16, AVCOL_PRI_BT709, AVCOL_TRC_LINEAR, 960, 540, runs));
        printf("  linear F16 to P010 PQ, converter                %8.2f ms\n", convert_ms(
                converter, linear, AV_PIX_FMT_P010, AVCOL_PRI_BT2020, AVCOL_TRC_SMPTE2084,
                linear->width, linear->height, runs));
        printf("  linear F16 to P010 HLG, converter               %8.2f ms\n", convert_ms(
                converter, linear, AV_PIX_FMT_P010, AVCOL_PRI_BT2020, AVCOL_TRC_ARIB_STD_B67,
                linear->width, linear->height, runs));
        printf("  converter intermediate: 16-bit planar RGB, %d MB\n",
                (int)((size_t)source->width * source->height * 6 >> 20));
    }
    ffmpegkmp_converter_free(&converter);
    av_frame_free(&rgba);
    av_frame_free(&linear);
    free(reused);
    av_frame_free(&source);
}

int main(int argc, char **argv) {
    const char *directory = argc > 1 ? argv[1] : ".";
    const char *mode = argc > 2 ? argv[2] : "all";
    char h264_1080[1024];
    char h264_1080_gop30[1024];
    char h264_2160[1024];
    char hevc_2160_pq[1024];
    char h264_2160_long[1024];
    char hevc_2160_pq_long[1024];
    snprintf(h264_1080, sizeof(h264_1080), "%s/h264-1080p.mp4", directory);
    snprintf(h264_1080_gop30, sizeof(h264_1080_gop30), "%s/h264-1080p-g30.mp4", directory);
    snprintf(h264_2160, sizeof(h264_2160), "%s/h264-2160p.mp4", directory);
    snprintf(hevc_2160_pq, sizeof(hevc_2160_pq), "%s/hevc-2160p-pq.mp4", directory);
    snprintf(h264_2160_long, sizeof(h264_2160_long), "%s/h264-2160p-10s.mp4", directory);
    snprintf(hevc_2160_pq_long, sizeof(hevc_2160_pq_long), "%s/hevc-2160p-pq-10s.mp4", directory);
    if (!strcmp(mode, "steps") || !strcmp(mode, "all")) {
        bench_steps(h264_1080, BENCH_RGBA8, FFPLAYKMP_DECODER_SOFTWARE, 120, 30, 10);
        bench_steps(h264_1080_gop30, BENCH_RGBA8, FFPLAYKMP_DECODER_SOFTWARE, 120, 30, 10);
        bench_steps(h264_2160, BENCH_RGBA8, FFPLAYKMP_DECODER_SOFTWARE, 72, 24, 0);
        bench_steps(hevc_2160_pq, BENCH_RGBA8, FFPLAYKMP_DECODER_SOFTWARE, 6, 24, 0);
        bench_steps(hevc_2160_pq, BENCH_PIXEL_BUFFER, FFPLAYKMP_DECODER_SOFTWARE, 48, 24, 0);
    }
    if (!strcmp(mode, "hardware") || !strcmp(mode, "all")) {
        bench_steps(h264_2160, BENCH_RGBA8, FFPLAYKMP_DECODER_AUTO, 72, 24, 0);
        bench_steps(h264_2160, BENCH_PIXEL_BUFFER, FFPLAYKMP_DECODER_AUTO, 72, 24, 0);
        bench_steps(hevc_2160_pq, BENCH_PIXEL_BUFFER, FFPLAYKMP_DECODER_AUTO, 48, 24, 0);
    }
    if (!strcmp(mode, "convert") || !strcmp(mode, "all")) {
        bench_conversions(h264_1080, 0);
        bench_conversions(h264_2160, 0);
        bench_conversions(hevc_2160_pq, 1);
    }
    /* parallel hevc|h264 full|small <decoders>: one case, small being 960x540. */
    if (!strcmp(mode, "parallel") && argc > 5) {
        const int small = !strcmp(argv[4], "small");
        const int count = atoi(argv[5]);
        if (count >= 1 && count <= 8) {
            bench_parallel(!strcmp(argv[3], "hevc") ? hevc_2160_pq_long : h264_2160_long,
                    small ? 960 : 0, small ? 540 : 0, 240, 24, count);
        }
    }
    return 0;
}
