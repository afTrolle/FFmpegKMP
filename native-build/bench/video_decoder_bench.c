// SPDX-License-Identifier: LGPL-2.1-or-later
/*
 * Benchmarks the pull video decoder (ffmpegkmp_decoder.c) and the frame
 * conversions it shares with the player (ffplaykmp_core.c), built unchanged.
 * scripts/bench-video-decoder.sh builds and runs it.
 *
 * FFMPEGKMP_BENCH_THREADS sets the decoders' thread_count (0 = FFmpeg's auto)
 * by wrapping avcodec_open2: ffplaykmp_core.c is compiled with
 * -Davcodec_open2=bench_avcodec_open2. Unset, decoders open as the bridge
 * opens them.
 */
#include "ffmpegkmp_decoder.h"
#include "ffplaykmp_core.h"

#include <dispatch/dispatch.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libavutil/time.h>
#include <libswscale/swscale.h>

/* ffplaykmp_player.c defines it; the benchmark does not link the player. */
void ffplaykmp_snapshot_init(ffplaykmp_snapshot *snapshot) {
    memset(snapshot, 0, sizeof(*snapshot));
    snapshot->size = sizeof(*snapshot);
    snapshot->duration_us = -1;
    ffplaykmp_reset_video_metadata(snapshot);
}

int bench_avcodec_open2(AVCodecContext *context, const AVCodec *codec, AVDictionary **options) {
    const char *threads = getenv("FFMPEGKMP_BENCH_THREADS");
    if (threads)
        context->thread_count = atoi(threads);
    return avcodec_open2(context, codec, options);
}

static double now_ms(void) { return av_gettime_relative() / 1000.0; }

static const char *output_name(int output) {
    static const char *names[] = { "RGBA8", "LINEAR_F16", "SURFACE", "PIXEL_BUFFER" };
    return names[output];
}

static const char *preference_name(int preference) {
    static const char *names[] = { "AUTO", "REQUIRE_HW", "SOFTWARE" };
    return names[preference];
}

static const char *file_name(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
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
    const char *threads = getenv("FFMPEGKMP_BENCH_THREADS");
    ffmpegkmp_decoded_frame frame;
    ffmpegkmp_video_decoder *decoder =
            ffmpegkmp_video_decoder_create(path, output, preference, 0, NULL, NULL, &error);
    if (!decoder) {
        printf("%s: create failed (%d)\n", file_name(path), error);
        return;
    }
    ffmpegkmp_decoded_frame_init(&frame);
    start = now_ms();
    result = ffmpegkmp_video_decoder_start(decoder);
    if (result >= 0)
        result = ffmpegkmp_video_decoder_frame_at(decoder, 0, &frame);
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
        if (frame.serial != (uint64_t)index + 1)
            printf("%s: step %d returned serial %llu, not a new frame\n",
                    file_name(path), index, (unsigned long long)frame.serial);
        total += took;
        worst = took > worst ? took : worst;
        hardware = frame.hardware;
        stepped++;
    }
    printf("%-20s %-12s %-10s threads=%-4s hw=%d | open+first %6.1f ms | forward %7.2f ms/frame (max %6.1f)",
            file_name(path), output_name(output), preference_name(preference), threads ? threads : "-",
            hardware, opened - start, stepped ? total / stepped : 0, worst);
    if (back > 0) {
        total = 0;
        stepped = 0;
        for (index = frames - 2; index >= frames - 1 - back; index--) {
            double before = now_ms();
            if (ffmpegkmp_video_decoder_frame_at(decoder, llround(index * 1e9 / fps), &frame) < 0)
                break;
            total += now_ms() - before;
            stepped++;
        }
        printf(" | backward %6.1f ms/frame", stepped ? total / stepped : 0);
    }
    printf("\n");
    ffmpegkmp_video_decoder_destroy(decoder);
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

/* The same matrix-only YUV to RGBA conversion through swscale's frame API with slice threads. */
static double threaded_rgba_ms(const AVFrame *source, int runs) {
    SwsContext *sws = sws_alloc_context();
    AVFrame *rgba = av_frame_alloc();
    double start;
    double took;
    int run;
    av_opt_set_int(sws, "threads", 0, 0);
    rgba->format = AV_PIX_FMT_RGBA;
    rgba->width = source->width;
    rgba->height = source->height;
    rgba->color_primaries = source->color_primaries;
    rgba->color_trc = source->color_trc;
    rgba->colorspace = AVCOL_SPC_RGB;
    rgba->color_range = AVCOL_RANGE_JPEG;
    av_frame_get_buffer(rgba, 0);
    sws_scale_frame(sws, rgba, source);
    start = now_ms();
    for (run = 0; run < runs; run++)
        sws_scale_frame(sws, rgba, source);
    took = (now_ms() - start) / runs;
    av_frame_free(&rgba);
    sws_free_context(&sws);
    return took;
}

/*
 * A comparison point for ffplaykmp_convert_rgba's PQ tone map: the same
 * curve (PQ EOTF, BT.2020 to BT.709, luminance Reinhard, sRGB OETF) with the
 * pow() calls replaced by interpolated 4096-entry tables and rows in
 * parallel. Not bit-exact with the bridge.
 */
#define BENCH_LUT_SIZE 4096
static float bench_pq_lut[BENCH_LUT_SIZE + 1];
static float bench_oetf_lut[BENCH_LUT_SIZE + 1];

static double bench_pq_nits(double value) {
    const double m1 = 2610.0 / 16384.0;
    const double m2 = 2523.0 / 32.0;
    const double c1 = 3424.0 / 4096.0;
    const double c2 = 2413.0 / 128.0;
    const double c3 = 2392.0 / 128.0;
    double signal = pow(value, 1.0 / m2);
    double numerator = signal > c1 ? signal - c1 : 0.0;
    return pow(numerator / (c2 - c3 * signal), 1.0 / m1) * 10000.0;
}

static inline float bench_lookup(const float *table, float value) {
    float scaled = (value <= 0.f ? 0.f : value >= 1.f ? 1.f : value) * BENCH_LUT_SIZE;
    int index = (int)scaled;
    if (index >= BENCH_LUT_SIZE)
        return table[BENCH_LUT_SIZE];
    return table[index] + (table[index + 1] - table[index]) * (scaled - index);
}

static double lut_tone_map_ms(const AVFrame *source, int runs) {
    SwsContext *sws = sws_alloc_context();
    AVFrame *planar = av_frame_alloc();
    uint8_t *rgba = malloc((size_t)source->width * source->height * 4);
    const int width = source->width;
    double start;
    int run;
    int entry;
    for (entry = 0; entry <= BENCH_LUT_SIZE; entry++) {
        double value = (double)entry / BENCH_LUT_SIZE;
        bench_pq_lut[entry] = (float)(bench_pq_nits(value) / 100.0);
        bench_oetf_lut[entry] = (float)(value <= 0.0031308 ? 12.92 * value : 1.055 * pow(value, 1.0 / 2.4) - 0.055);
    }
    av_opt_set_int(sws, "threads", 0, 0);
    planar->format = AV_PIX_FMT_GBRPF32LE;
    planar->width = source->width;
    planar->height = source->height;
    planar->color_primaries = source->color_primaries;
    planar->color_trc = source->color_trc;
    planar->colorspace = AVCOL_SPC_RGB;
    planar->color_range = AVCOL_RANGE_JPEG;
    av_frame_get_buffer(planar, 0);
    start = now_ms();
    for (run = 0; run < runs; run++) {
        sws_scale_frame(sws, planar, source);
        dispatch_apply((size_t)source->height, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^(size_t y) {
            const float *green = (const float *)(planar->data[0] + y * planar->linesize[0]);
            const float *blue = (const float *)(planar->data[1] + y * planar->linesize[1]);
            const float *red = (const float *)(planar->data[2] + y * planar->linesize[2]);
            uint8_t *row = rgba + y * (size_t)width * 4;
            for (int x = 0; x < width; x++) {
                float r = bench_lookup(bench_pq_lut, red[x]);
                float g = bench_lookup(bench_pq_lut, green[x]);
                float b = bench_lookup(bench_pq_lut, blue[x]);
                float r709 = fmaxf(1.660491f * r - 0.587641f * g - 0.072850f * b, 0.f);
                float g709 = fmaxf(-0.124550f * r + 1.132900f * g - 0.008349f * b, 0.f);
                float b709 = fmaxf(-0.018151f * r - 0.100579f * g + 1.118730f * b, 0.f);
                float luminance = 0.2126f * r709 + 0.7152f * g709 + 0.0722f * b709;
                float scale = luminance > 1e-9f ? 1.f / (1.f + luminance) : 0.f;
                row[x * 4 + 0] = (uint8_t)(bench_lookup(bench_oetf_lut, r709 * scale) * 255.f + .5f);
                row[x * 4 + 1] = (uint8_t)(bench_lookup(bench_oetf_lut, g709 * scale) * 255.f + .5f);
                row[x * 4 + 2] = (uint8_t)(bench_lookup(bench_oetf_lut, b709 * scale) * 255.f + .5f);
                row[x * 4 + 3] = 255;
            }
        });
    }
    free(rgba);
    av_frame_free(&planar);
    sws_free_context(&sws);
    return (now_ms() - start) / runs;
}

static void bench_conversions(const char *path, int hdr) {
    AVFrame *source = first_frame(path);
    ffplaykmp_converter converter = { 0 };
    ffplaykmp_converted_frame converted;
    size_t rgba_size;
    uint8_t *reused;
    double start;
    int runs = 20;
    int run;
    if (!source) {
        printf("%s: could not decode a frame\n", file_name(path));
        return;
    }
    rgba_size = (size_t)source->width * source->height * 4;
    reused = malloc(rgba_size);
    printf("\n%s (%dx%d %s)\n", file_name(path), source->width, source->height,
            av_get_pix_fmt_name(source->format));
    ffplaykmp_convert_rgba(&converter, source, 0, &converted);
    start = now_ms();
    for (run = 0; run < runs; run++)
        ffplaykmp_convert_rgba(&converter, source, 0, &converted);
    printf("  YUV->RGBA, bridge (sws_scale, one thread)       %8.2f ms\n", (now_ms() - start) / runs);
    printf("  YUV->RGBA, sws_scale_frame threads=auto         %8.2f ms\n", threaded_rgba_ms(source, runs));
    start = now_ms();
    for (run = 0; run < runs; run++) {
        memcpy(reused, converted.pixels, rgba_size);
        __asm__ volatile("" : : "r"(reused) : "memory");
    }
    printf("  RGBA frame copy (one pass, as each Kotlin copy) %8.2f ms\n", (now_ms() - start) / runs);
    if (hdr) {
        runs = 2;
        start = now_ms();
        for (run = 0; run < runs; run++)
            ffplaykmp_convert_rgba(&converter, source, 1, &converted);
        printf("  PQ tone map to RGBA8, bridge                    %8.1f ms\n", (now_ms() - start) / runs);
        start = now_ms();
        for (run = 0; run < runs; run++)
            ffplaykmp_convert_linear_f16(&converter, source, &converted);
        printf("  PQ to linear F16, bridge                        %8.1f ms\n", (now_ms() - start) / runs);
        printf("  PQ tone map to RGBA8, tables + parallel rows    %8.1f ms (comparison, not bit-exact)\n",
                lut_tone_map_ms(source, 5));
        printf("  converter scratch held: float %d MB, half %d MB, rgba %d MB\n",
                converter.float_capacity >> 20, converter.half_capacity >> 20, converter.rgba_capacity >> 20);
    }
    ffplaykmp_converter_free(&converter);
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
    snprintf(h264_1080, sizeof(h264_1080), "%s/h264-1080p.mp4", directory);
    snprintf(h264_1080_gop30, sizeof(h264_1080_gop30), "%s/h264-1080p-g30.mp4", directory);
    snprintf(h264_2160, sizeof(h264_2160), "%s/h264-2160p.mp4", directory);
    snprintf(hevc_2160_pq, sizeof(hevc_2160_pq), "%s/hevc-2160p-pq.mp4", directory);
    if (!strcmp(mode, "steps") || !strcmp(mode, "all")) {
        bench_steps(h264_1080, FFMPEGKMP_VIDEO_OUTPUT_RGBA8, FFPLAYKMP_DECODER_SOFTWARE, 120, 30, 10);
        bench_steps(h264_1080_gop30, FFMPEGKMP_VIDEO_OUTPUT_RGBA8, FFPLAYKMP_DECODER_SOFTWARE, 120, 30, 10);
        bench_steps(h264_2160, FFMPEGKMP_VIDEO_OUTPUT_RGBA8, FFPLAYKMP_DECODER_SOFTWARE, 72, 24, 0);
        bench_steps(hevc_2160_pq, FFMPEGKMP_VIDEO_OUTPUT_RGBA8, FFPLAYKMP_DECODER_SOFTWARE, 6, 24, 0);
        bench_steps(hevc_2160_pq, FFMPEGKMP_VIDEO_OUTPUT_PIXEL_BUFFER, FFPLAYKMP_DECODER_SOFTWARE, 48, 24, 0);
    }
    if (!strcmp(mode, "hardware") || !strcmp(mode, "all")) {
        bench_steps(h264_2160, FFMPEGKMP_VIDEO_OUTPUT_RGBA8, FFPLAYKMP_DECODER_AUTO, 72, 24, 0);
        bench_steps(h264_2160, FFMPEGKMP_VIDEO_OUTPUT_PIXEL_BUFFER, FFPLAYKMP_DECODER_AUTO, 72, 24, 0);
        bench_steps(hevc_2160_pq, FFMPEGKMP_VIDEO_OUTPUT_PIXEL_BUFFER, FFPLAYKMP_DECODER_AUTO, 48, 24, 0);
    }
    if (!strcmp(mode, "convert") || !strcmp(mode, "all")) {
        bench_conversions(h264_1080, 0);
        bench_conversions(h264_2160, 0);
        bench_conversions(hevc_2160_pq, 1);
    }
    return 0;
}
