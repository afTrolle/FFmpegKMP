// SPDX-License-Identifier: LGPL-2.1-or-later
/*
 * Golden-reference checks of the video decoder's conversions (its MEMORY
 * output into RGBA8 and linear RGBA F16, and the CVPixelBuffer copies of
 * frames as decoded), built unchanged from the bridge sources.
 * scripts/test-converter.sh builds and runs it.
 *
 *   converter_test <fixture dir> <golden dir> record   writes the references
 *   converter_test <fixture dir> <golden dir> check    compares against them
 *
 * A reference holds a few frames of one clip and output: the full frame's
 * FNV-1a hash, its smallest and largest components, and every second pixel of
 * every second row. codec's VideoDecoderGoldenTest and ffplay's VideoFrameImageTest
 * read the RGBA8 and RGBA F16 ones.
 *
 * check also runs the converter's own checks: the matrix-only RGBA8 of HDR
 * sources against the legacy swscale setup, and the encode-direction round
 * trip, linear F16 to P010 PQ or HLG and back.
 */
#include "ffmpegkmp_decoder.h"
#include "ffplaykmp_core.h"

#include <CoreVideo/CoreVideo.h>
#include <math.h>
#include <libavutil/imgutils.h>
#include <libavutil/mastering_display_metadata.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ffplaykmp_player.c defines it; the test does not link the player. */
void ffplaykmp_snapshot_init(ffplaykmp_snapshot *snapshot) {
    memset(snapshot, 0, sizeof(*snapshot));
    snapshot->size = sizeof(*snapshot);
    snapshot->duration_us = -1;
    ffplaykmp_reset_video_metadata(snapshot);
}

enum { SAMPLE_RGBA8 = 0, SAMPLE_RGBA_F16 = 1, SAMPLE_NV12 = 2, SAMPLE_P010 = 3 };

/*
 * The outputs the references are named after: MEMORY output in FrameFormat.Rgba8
 * and FrameFormat.RgbaF16, and MEMORY output as decoded, where every frame is a
 * CVPixelBuffer (VideoOutput.PixelBuffer before it).
 */
enum { OUTPUT_RGBA8 = 0, OUTPUT_LINEAR_F16 = 1, OUTPUT_PIXEL_BUFFER = 3 };

#define GOLDEN_STEP 2
#define GOLDEN_MAX_FRAMES 4
#define GOLDEN_MAX_SAMPLES (1 << 16)

typedef struct golden_case {
    const char *clip;
    int output;
    int frames;
    int64_t positions_ns[GOLDEN_MAX_FRAMES];
} golden_case;

static const golden_case CASES[] = {
    { "cfr-30.mp4", OUTPUT_RGBA8, 2, { 0, 1500000000 } },
    { "cfr-30-h264.mp4", OUTPUT_RGBA8, 2, { 0, 1500000000 } },
    { "cfr-30.mp4", OUTPUT_LINEAR_F16, 2, { 0, 1500000000 } },
    { "hdr10-pq.mp4", OUTPUT_RGBA8, 1, { 500000000 } },
    { "hdr10-pq.mp4", OUTPUT_LINEAR_F16, 1, { 500000000 } },
    { "hlg.mp4", OUTPUT_RGBA8, 1, { 500000000 } },
    { "hlg.mp4", OUTPUT_LINEAR_F16, 1, { 500000000 } },
    { "cfr-30.mp4", OUTPUT_PIXEL_BUFFER, 2, { 0, 1500000000 } },
    { "hdr10-pq.mp4", OUTPUT_PIXEL_BUFFER, 1, { 500000000 } },
    { "hlg.mp4", OUTPUT_PIXEL_BUFFER, 1, { 500000000 } },
};

/* One frame: its hash, component range and samples, as stored in a reference. */
typedef struct golden_frame {
    int64_t pts_ns;
    uint64_t hash;
    float minimum;
    float maximum;
    int count;
    /* Samples widened to float: 8-bit codes, half floats, or 10-bit codes. */
    float samples[GOLDEN_MAX_SAMPLES];
} golden_frame;

static uint64_t fnv1a(uint64_t hash, const uint8_t *bytes, size_t size) {
    size_t index;
    for (index = 0; index < size; index++)
        hash = (hash ^ bytes[index]) * 0x100000001b3ull;
    return hash;
}

static float half_to_float(uint16_t half) {
    const int exponent = half >> 10 & 0x1f;
    const int mantissa = half & 0x3ff;
    float magnitude = exponent == 0 ? ldexpf((float)mantissa, -24)
            : exponent == 0x1f ? INFINITY
            : ldexpf((float)(mantissa | 0x400), exponent - 25);
    return half & 0x8000 ? -magnitude : magnitude;
}

/* RGBA F16 may differ from its reference by this many half-float steps. */
#define GOLDEN_HALF_ULPS 2

static const char *file_name(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

static int half_order(float value);

/* Codes apart, or for half floats, ULPs apart. */
static double sample_error(float actual, float expected, int half) {
    return half ? abs(half_order(actual) - half_order(expected)) : fabs((double)actual - expected);
}

static const char *output_name(int output) {
    static const char *names[] = { "rgba8", "linear-f16", "surface", "pixel-buffer" };
    return names[output];
}

static void add_sample(golden_frame *frame, float value) {
    if (frame->count < GOLDEN_MAX_SAMPLES)
        frame->samples[frame->count++] = value;
}

static void capture_rgb(golden_frame *frame, const ffmpegkmp_frame_planes *planes, int width, int height, int half) {
    int x;
    int y;
    int channel;
    frame->minimum = INFINITY;
    frame->maximum = -INFINITY;
    for (y = 0; y < height; y++) {
        const uint8_t *row = planes->data[0] + (size_t)y * planes->row_bytes[0];
        frame->hash = fnv1a(frame->hash, row, (size_t)width * (half ? 8 : 4));
        for (x = 0; x < width; x++) {
            for (channel = 0; channel < 3; channel++) {
                float value = half ? half_to_float(((const uint16_t *)row)[x * 4 + channel])
                        : row[x * 4 + channel];
                frame->minimum = fminf(frame->minimum, value);
                frame->maximum = fmaxf(frame->maximum, value);
                if (y % GOLDEN_STEP == 0 && x % GOLDEN_STEP == 0)
                    add_sample(frame, value);
            }
        }
    }
}

/* NV12 and P010 planes: luma, then interleaved chroma, as codes. */
static int capture_pixel_buffer(golden_frame *frame, CVPixelBufferRef buffer) {
    const int deep = CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange ||
            CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_420YpCbCr10BiPlanarFullRange;
    size_t plane;
    if (CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess)
        return -1;
    frame->minimum = INFINITY;
    frame->maximum = -INFINITY;
    for (plane = 0; plane < 2; plane++) {
        const uint8_t *base = CVPixelBufferGetBaseAddressOfPlane(buffer, plane);
        const size_t stride = CVPixelBufferGetBytesPerRowOfPlane(buffer, plane);
        const size_t width = CVPixelBufferGetWidthOfPlane(buffer, plane) * (plane ? 2 : 1);
        const size_t height = CVPixelBufferGetHeightOfPlane(buffer, plane);
        size_t x;
        size_t y;
        for (y = 0; y < height; y++) {
            const uint8_t *row = base + y * stride;
            frame->hash = fnv1a(frame->hash, row, width * (deep ? 2 : 1));
            for (x = 0; x < width; x++) {
                float value = deep ? (float)(((const uint16_t *)row)[x] >> 6) : row[x];
                frame->minimum = fminf(frame->minimum, value);
                frame->maximum = fmaxf(frame->maximum, value);
                /* Chroma is interleaved: keep both components of every second pair. */
                if (y % GOLDEN_STEP == 0 && (plane ? (x / 2) % GOLDEN_STEP == 0 : x % GOLDEN_STEP == 0))
                    add_sample(frame, value);
            }
        }
    }
    CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly);
    return deep ? SAMPLE_P010 : SAMPLE_NV12;
}

/* Decodes the case's frames. Returns the sample format, or a negative error. */
static int decode_case(const char *directory, const golden_case *test, golden_frame *frames, int *width, int *height) {
    char path[1024];
    int error = 0;
    int result;
    int index;
    int format = test->output == OUTPUT_LINEAR_F16 ? SAMPLE_RGBA_F16 : SAMPLE_RGBA8;
    ffmpegkmp_frame_format memory;
    ffmpegkmp_decoded_frame decoded;
    ffmpegkmp_video_decoder *decoder;
    snprintf(path, sizeof(path), "%s/%s", directory, test->clip);
    ffmpegkmp_frame_format_init(&memory);
    memory.layout = format == SAMPLE_RGBA_F16 ? FFMPEGKMP_LAYOUT_RGBA_F16 : FFMPEGKMP_LAYOUT_RGBA8;
    memory.primaries = FFMPEGKMP_PRIMARIES_BT709;
    memory.transfer = format == SAMPLE_RGBA_F16 ? FFMPEGKMP_TRANSFER_LINEAR : FFMPEGKMP_TRANSFER_SRGB;
    memory.matrix = FFMPEGKMP_MATRIX_RGB;
    memory.range = FFMPEGKMP_RANGE_FULL;
    decoder = ffmpegkmp_video_decoder_create(
            path, FFMPEGKMP_VIDEO_OUTPUT_MEMORY, test->output == OUTPUT_PIXEL_BUFFER ? NULL : &memory, 0, 0,
            FFPLAYKMP_DECODER_SOFTWARE, 0, 0, NULL, NULL, &error);
    if (!decoder)
        return error;
    ffmpegkmp_decoded_frame_init(&decoded);
    result = ffmpegkmp_video_decoder_start(decoder);
    for (index = 0; result >= 0 && index < test->frames; index++) {
        golden_frame *frame = &frames[index];
        if ((result = ffmpegkmp_video_decoder_frame_at(decoder, test->positions_ns[index], &decoded)) < 0)
            break;
        memset(frame, 0, sizeof(*frame));
        frame->pts_ns = decoded.pts_ns;
        frame->hash = 0xcbf29ce484222325ull;
        *width = decoded.width;
        *height = decoded.height;
        if (test->output == OUTPUT_PIXEL_BUFFER) {
            ffmpegkmp_frame_info info;
            ffmpegkmp_frame_info_init(&info);
            result = ffmpegkmp_frame_get_info(decoded.frame, &info);
            if (result >= 0)
                result = info.pixel_buffer ? capture_pixel_buffer(frame, info.pixel_buffer) : -1;
            if (result >= 0)
                format = result;
        } else {
            ffmpegkmp_frame_planes planes;
            ffmpegkmp_frame_planes_init(&planes);
            if ((result = ffmpegkmp_frame_map(decoded.frame, &planes)) >= 0) {
                capture_rgb(frame, &planes, decoded.width, decoded.height, format == SAMPLE_RGBA_F16);
                ffmpegkmp_frame_unmap(decoded.frame);
            }
        }
        ffmpegkmp_frame_unref(decoded.frame);
    }
    ffmpegkmp_video_decoder_destroy(decoder);
    return result < 0 ? result : format;
}

/*
 * The reference file: "FKG1", then little-endian u32 sample format, width,
 * height, step and frame count; per frame i64 pts_ns, u64 hash, f32 minimum
 * and maximum, u32 sample count, then the samples as u8 (RGBA8 and NV12), u16
 * (P010 codes) or IEEE half floats (RGBA F16).
 */
static void golden_path(char *path, size_t size, const char *golden, const golden_case *test) {
    char clip[256];
    char *dot;
    snprintf(clip, sizeof(clip), "%s", test->clip);
    if ((dot = strrchr(clip, '.')))
        *dot = 0;
    snprintf(path, size, "%s/%s-%s.bin", golden, clip, output_name(test->output));
}

static uint16_t float_to_half(float value) {
    union {
        float value;
        uint32_t bits;
    } single = { value };
    uint32_t sign = (single.bits >> 16) & 0x8000u;
    uint32_t magnitude = single.bits & 0x7fffffffu;
    uint32_t half;
    uint32_t remainder;
    if (magnitude >= 0x47800000u)
        return (uint16_t)(sign | 0x7c00u);
    if (magnitude < 0x38800000u)
        return (uint16_t)(sign | (uint32_t)lrintf(fabsf(value) * 16777216.0f));
    half = (magnitude - 0x38000000u) >> 13;
    remainder = magnitude & 0x1fffu;
    if (remainder > 0x1000u || (remainder == 0x1000u && (half & 1u)))
        half++;
    return (uint16_t)(sign | half);
}

/* A half float's position on the number line, so that neighbours differ by one. */
static int half_order(float value) {
    const uint16_t half = float_to_half(value);
    return half & 0x8000 ? -(half & 0x7fff) : half;
}

static int write_golden(const char *path, int format, int width, int height, const golden_frame *frames, int count) {
    FILE *file = fopen(path, "wb");
    uint32_t header[5] = { (uint32_t)format, (uint32_t)width, (uint32_t)height, GOLDEN_STEP, (uint32_t)count };
    int index;
    int sample;
    if (!file)
        return -1;
    fwrite("FKG1", 1, 4, file);
    fwrite(header, sizeof(header), 1, file);
    for (index = 0; index < count; index++) {
        const golden_frame *frame = &frames[index];
        uint32_t samples = (uint32_t)frame->count;
        fwrite(&frame->pts_ns, 8, 1, file);
        fwrite(&frame->hash, 8, 1, file);
        fwrite(&frame->minimum, 4, 1, file);
        fwrite(&frame->maximum, 4, 1, file);
        fwrite(&samples, 4, 1, file);
        for (sample = 0; sample < frame->count; sample++) {
            float value = frame->samples[sample];
            if (format == SAMPLE_RGBA_F16 || format == SAMPLE_P010) {
                uint16_t stored = format == SAMPLE_P010 ? (uint16_t)value : float_to_half(value);
                fwrite(&stored, 2, 1, file);
            } else {
                uint8_t stored = (uint8_t)value;
                fwrite(&stored, 1, 1, file);
            }
        }
    }
    return fclose(file);
}

static int read_golden(const char *path, int *format, int *width, int *height, golden_frame *frames, int *count) {
    FILE *file = fopen(path, "rb");
    char magic[4];
    uint32_t header[5];
    uint32_t index;
    uint32_t sample;
    if (!file)
        return -1;
    if (fread(magic, 1, 4, file) != 4 || memcmp(magic, "FKG1", 4) || fread(header, sizeof(header), 1, file) != 1 ||
            header[4] > GOLDEN_MAX_FRAMES) {
        fclose(file);
        return -1;
    }
    *format = (int)header[0];
    *width = (int)header[1];
    *height = (int)header[2];
    *count = (int)header[4];
    for (index = 0; index < header[4]; index++) {
        golden_frame *frame = &frames[index];
        uint32_t samples = 0;
        if (fread(&frame->pts_ns, 8, 1, file) != 1 || fread(&frame->hash, 8, 1, file) != 1 ||
                fread(&frame->minimum, 4, 1, file) != 1 || fread(&frame->maximum, 4, 1, file) != 1 ||
                fread(&samples, 4, 1, file) != 1 || samples > GOLDEN_MAX_SAMPLES) {
            fclose(file);
            return -1;
        }
        frame->count = (int)samples;
        for (sample = 0; sample < samples; sample++) {
            if (*format == SAMPLE_RGBA_F16 || *format == SAMPLE_P010) {
                uint16_t stored = 0;
                fread(&stored, 2, 1, file);
                frame->samples[sample] = *format == SAMPLE_P010 ? stored : half_to_float(stored);
            } else {
                uint8_t stored = 0;
                fread(&stored, 1, 1, file);
                frame->samples[sample] = stored;
            }
        }
    }
    fclose(file);
    return 0;
}

static golden_frame decoded_frames[GOLDEN_MAX_FRAMES];
static golden_frame reference_frames[GOLDEN_MAX_FRAMES];

/* Compares one case; returns the number of failures. */
static int check_case(const char *fixtures, const char *golden, const golden_case *test, int record) {
    char path[1024];
    int width = 0;
    int height = 0;
    int reference_format = 0;
    int reference_width = 0;
    int reference_height = 0;
    int reference_count = 0;
    int format = decode_case(fixtures, test, decoded_frames, &width, &height);
    int failures = 0;
    int index;
    golden_path(path, sizeof(path), golden, test);
    if (format < 0) {
        printf("FAIL %s %s: decode failed (%d)\n", test->clip, output_name(test->output), format);
        return 1;
    }
    if (record) {
        if (write_golden(path, format, width, height, decoded_frames, test->frames) != 0) {
            printf("FAIL %s: could not write\n", path);
            return 1;
        }
        for (index = 0; index < test->frames; index++)
            printf("recorded %s frame %d: hash %016llx, components %g..%g\n", path, index,
                    (unsigned long long)decoded_frames[index].hash, decoded_frames[index].minimum,
                    decoded_frames[index].maximum);
        return 0;
    }
    if (read_golden(path, &reference_format, &reference_width, &reference_height, reference_frames,
            &reference_count) != 0) {
        printf("FAIL %s: missing or unreadable\n", path);
        return 1;
    }
    if (reference_format != format || reference_width != width || reference_height != height ||
            reference_count != test->frames) {
        printf("FAIL %s: format %d %dx%d x%d, reference %d %dx%d x%d\n", path, format, width, height,
                test->frames, reference_format, reference_width, reference_height, reference_count);
        return 1;
    }
    for (index = 0; index < test->frames; index++) {
        const golden_frame *actual = &decoded_frames[index];
        const golden_frame *expected = &reference_frames[index];
        /* RGBA F16 in half-float steps (ULPs), codes otherwise. */
        const int half = format == SAMPLE_RGBA_F16;
        const double tolerance = half ? GOLDEN_HALF_ULPS : 1.0;
        double worst = 0;
        double range_error;
        int worst_sample = 0;
        int sample;
        if (actual->count != expected->count) {
            printf("FAIL %s frame %d: %d samples, reference %d\n", path, index, actual->count, expected->count);
            failures++;
            continue;
        }
        for (sample = 0; sample < actual->count; sample++) {
            double error = sample_error(actual->samples[sample], expected->samples[sample], half);
            if (error > worst) {
                worst = error;
                worst_sample = sample;
            }
        }
        /* The whole frame's extremes too: highlights above 1.0 and negative components must stay. */
        range_error = fmax(sample_error(actual->minimum, expected->minimum, half),
                sample_error(actual->maximum, expected->maximum, half));
        if (worst > tolerance || range_error > tolerance)
            failures++;
        printf("%s %s frame %d: max error %g%s (%g, reference %g), %s; components %g..%g, reference %g..%g\n",
                worst > tolerance || range_error > tolerance ? "FAIL" : "ok  ", file_name(path), index, worst,
                half ? " ULPs" : " codes", actual->samples[worst_sample], expected->samples[worst_sample],
                actual->hash == expected->hash ? "bit-exact" : "not bit-exact",
                actual->minimum, actual->maximum, expected->minimum, expected->maximum);
    }
    return failures;
}

/* A frame of `format` and colour with its own buffers. */
static AVFrame *new_frame(
        enum AVPixelFormat format,
        int width,
        int height,
        enum AVColorPrimaries primaries,
        enum AVColorTransferCharacteristic transfer,
        enum AVColorSpace matrix,
        enum AVColorRange range) {
    AVFrame *frame = av_frame_alloc();
    if (!frame)
        return NULL;
    frame->format = format;
    frame->width = width;
    frame->height = height;
    frame->color_primaries = primaries;
    frame->color_trc = transfer;
    frame->colorspace = matrix;
    frame->color_range = range;
    if (av_frame_get_buffer(frame, 0) < 0)
        av_frame_free(&frame);
    return frame;
}

static double pq_signal(double nits) {
    const double m1 = 2610.0 / 16384, m2 = 2523.0 / 32, c1 = 3424.0 / 4096, c2 = 2413.0 / 128, c3 = 2392.0 / 128;
    const double power = pow(fmin(fmax(nits / 10000, 0), 1), m1);
    return pow((c1 + c2 * power) / (1 + c3 * power), m2);
}

static double pq_nits(double signal) {
    const double m1 = 2610.0 / 16384, m2 = 2523.0 / 32, c1 = 3424.0 / 4096, c2 = 2413.0 / 128, c3 = 2392.0 / 128;
    const double power = pow(fmin(fmax(signal, 0), 1), 1 / m2);
    return 10000 * pow(fmax(power - c1, 0) / (c2 - c3 * power), 1 / m1);
}

static double hlg_signal(double scene) {
    scene = fmin(fmax(scene, 0), 1);
    return scene <= 1.0 / 12 ? sqrt(3 * scene) : 0.17883277 * log(12 * scene - 0.28466892) + 0.55991073;
}

static double hlg_scene(double signal) {
    signal = fmin(fmax(signal, 0), 1);
    return signal <= 0.5 ? signal * signal / 3 : (exp((signal - 0.55991073) / 0.17883277) + 0.28466892) / 12;
}

/* BT.2020 to BT.709 and back, linear light (BT.2087). */
static const double BT2020_TO_BT709[9] = {
    1.660491, -0.587641, -0.072850, -0.124550, 1.132900, -0.008349, -0.018151, -0.100579, 1.118730,
};
static const double BT709_TO_BT2020[9] = {
    0.627404, 0.329283, 0.043313, 0.069097, 0.919540, 0.011362, 0.016391, 0.088013, 0.895595,
};

static void multiply(const double matrix[9], const double in[3], double out[3]) {
    int row;
    for (row = 0; row < 3; row++)
        out[row] = matrix[row * 3] * in[0] + matrix[row * 3 + 1] * in[1] + matrix[row * 3 + 2] * in[2];
}

/*
 * BT.2020 signal of a linear extended sRGB pixel (1.0 = 203 nits) for PQ, or
 * for HLG on a 1000-nit display (BT.2100 inverse OOTF, gamma 1.2).
 */
static void signal_of(const double linear[3], int hlg, double signal[3]) {
    double bt2020[3];
    int channel;
    multiply(BT709_TO_BT2020, linear, bt2020);
    if (!hlg) {
        for (channel = 0; channel < 3; channel++)
            signal[channel] = pq_signal(bt2020[channel] * 203);
        return;
    }
    {
        const double luminance = fmax(0.2627 * bt2020[0] + 0.6780 * bt2020[1] + 0.0593 * bt2020[2], 0) * 0.203;
        for (channel = 0; channel < 3; channel++)
            signal[channel] = luminance > 0 ? hlg_signal(fmax(bt2020[channel], 0) * 0.203 * pow(luminance, -1.0 / 6)) : 0;
    }
}

/* The linear extended sRGB pixel of a BT.2020 PQ or HLG signal: the inverse of signal_of. */
static void linear_of(const double signal[3], int hlg, double linear[3]) {
    double bt2020[3];
    int channel;
    if (!hlg) {
        for (channel = 0; channel < 3; channel++)
            bt2020[channel] = pq_nits(signal[channel]) / 203;
    } else {
        double scene[3];
        double luminance;
        for (channel = 0; channel < 3; channel++)
            scene[channel] = hlg_scene(signal[channel]);
        luminance = 0.2627 * scene[0] + 0.6780 * scene[1] + 0.0593 * scene[2];
        for (channel = 0; channel < 3; channel++)
            bt2020[channel] = luminance > 0 ? scene[channel] * pow(luminance, 0.2) * 1000 / 203 : 0;
    }
    multiply(BT2020_TO_BT709, bt2020, linear);
}

/* Pseudo-random numbers in [0, 1), reproducible across runs. */
static double next_random(uint32_t *state) {
    *state = *state * 1664525u + 1013904223u;
    return (*state >> 8) / 16777216.0;
}

#define ROUNDTRIP_SIZE 256
/*
 * Colours come in 16x16 blocks and are checked on each block's inner 6x6
 * pixels, whose 4:2:0 chroma, filtered down and back up, comes from their own
 * block only.
 */
#define ROUNDTRIP_BLOCK 16
#define ROUNDTRIP_BLOCKS (ROUNDTRIP_SIZE / ROUNDTRIP_BLOCK)

static int roundtrip_inner(int x, int y) {
    return x % ROUNDTRIP_BLOCK >= 5 && x % ROUNDTRIP_BLOCK < 11 && y % ROUNDTRIP_BLOCK >= 5 && y % ROUNDTRIP_BLOCK < 11;
}

/*
 * A linear F16 canvas whose colours are BT.2020 signals spread over the whole
 * code range: a row of greys, then colours that reach the edges of the
 * BT.2020 gamut, whose linear sRGB components go below 0 and above 1. It
 * carries HDR10 mastering-display metadata, which swscale would compare with
 * the P010 frame's.
 */
static AVFrame *roundtrip_canvas(int hlg) {
    AVFrame *canvas = new_frame(AV_PIX_FMT_RGBAF16, ROUNDTRIP_SIZE, ROUNDTRIP_SIZE, AVCOL_PRI_BT709,
            AVCOL_TRC_LINEAR, AVCOL_SPC_RGB, AVCOL_RANGE_JPEG);
    AVMasteringDisplayMetadata *mastering;
    uint32_t state = 7;
    int block;
    if (!canvas || !(mastering = av_mastering_display_metadata_create_side_data(canvas))) {
        av_frame_free(&canvas);
        return NULL;
    }
    mastering->display_primaries[0][0] = av_make_q(35400, 50000);
    mastering->display_primaries[0][1] = av_make_q(14600, 50000);
    mastering->display_primaries[1][0] = av_make_q(8500, 50000);
    mastering->display_primaries[1][1] = av_make_q(39850, 50000);
    mastering->display_primaries[2][0] = av_make_q(6550, 50000);
    mastering->display_primaries[2][1] = av_make_q(2300, 50000);
    mastering->white_point[0] = av_make_q(15635, 50000);
    mastering->white_point[1] = av_make_q(16450, 50000);
    mastering->min_luminance = av_make_q(1, 10000);
    mastering->max_luminance = av_make_q(1000, 1);
    mastering->has_primaries = 1;
    mastering->has_luminance = 1;
    for (block = 0; block < ROUNDTRIP_BLOCKS * ROUNDTRIP_BLOCKS; block++) {
        double signal[3];
        double linear[3];
        int x;
        int y;
        int channel;
        if (block < ROUNDTRIP_BLOCKS) {
            signal[0] = signal[1] = signal[2] = block / (double)(ROUNDTRIP_BLOCKS - 1);
        } else {
            for (channel = 0; channel < 3; channel++)
                signal[channel] = next_random(&state);
            /* Every fourth colour on a primary or secondary: the far edges of the BT.2020 gamut. */
            if (block % 4 == 0)
                signal[block / 4 % 3] = 0;
        }
        linear_of(signal, hlg, linear);
        for (y = 0; y < ROUNDTRIP_BLOCK; y++) {
            for (x = 0; x < ROUNDTRIP_BLOCK; x++) {
                uint16_t *pixel = (uint16_t *)(canvas->data[0] +
                        (size_t)(block / ROUNDTRIP_BLOCKS * ROUNDTRIP_BLOCK + y) * canvas->linesize[0]) +
                        (block % ROUNDTRIP_BLOCKS * ROUNDTRIP_BLOCK + x) * 4;
                for (channel = 0; channel < 3; channel++)
                    pixel[channel] = float_to_half((float)linear[channel]);
                pixel[3] = 0x3c00;
            }
        }
    }
    return canvas;
}

static void linear_pixel(const AVFrame *frame, int x, int y, double linear[3]) {
    const uint16_t *pixel = (const uint16_t *)(frame->data[0] + (size_t)y * frame->linesize[0]) + x * 4;
    int channel;
    for (channel = 0; channel < 3; channel++)
        linear[channel] = half_to_float(pixel[channel]);
}

/*
 * Whether a canvas pixel's BT.2020 signal is well conditioned: no BT.2020
 * component below a thousandth of its largest. A component at the edge of the
 * gamut next to a bright one is the difference of large sRGB values, so float
 * rounding moves it by many codes of PQ's or HLG's steep dark end; it is
 * checked by its components' range instead.
 */
static int well_conditioned(const AVFrame *canvas, int x, int y) {
    double linear[3];
    double bt2020[3];
    double largest;
    linear_pixel(canvas, x, y, linear);
    multiply(BT709_TO_BT2020, linear, bt2020);
    largest = fmax(bt2020[0], fmax(bt2020[1], bt2020[2]));
    return largest <= 0 || fmin(bt2020[0], fmin(bt2020[1], bt2020[2])) >= 1e-3 * largest;
}

/* The smallest and largest components of the inner pixels; block edges ring where chroma is filtered. */
static void component_range(const AVFrame *frame, double *minimum, double *maximum) {
    int x;
    int y;
    int channel;
    *minimum = INFINITY;
    *maximum = -INFINITY;
    for (y = 0; y < frame->height; y++) {
        for (x = 0; x < frame->width; x++) {
            double linear[3];
            if (!roundtrip_inner(x, y))
                continue;
            linear_pixel(frame, x, y, linear);
            for (channel = 0; channel < 3; channel++) {
                *minimum = fmin(*minimum, linear[channel]);
                *maximum = fmax(*maximum, linear[channel]);
            }
        }
    }
}

/* A P010 sample as a 10-bit code: luma (plane 0), or Cb or Cr (plane 1, component 0 or 1) at luma x and y. */
static int p010_code(const AVFrame *frame, int plane, int component, int x, int y) {
    const uint16_t *row = (const uint16_t *)(frame->data[plane] + (size_t)(plane ? y / 2 : y) * frame->linesize[plane]);
    return (plane ? row[x / 2 * 2 + component] : row[x]) >> 6;
}

/* The largest difference in 10-bit codes between two P010 frames' inner, well-conditioned pixels. */
static int p010_error(const AVFrame *first, const AVFrame *second, const AVFrame *canvas) {
    int worst = 0;
    int x;
    int y;
    for (y = 0; y < first->height; y++) {
        for (x = 0; x < first->width; x++) {
            int component;
            if (!roundtrip_inner(x, y) || !well_conditioned(canvas, x, y))
                continue;
            for (component = 0; component < 3; component++) {
                const int plane = component > 0;
                const int error = abs(p010_code(first, plane, component - plane, x, y) -
                        p010_code(second, plane, component - plane, x, y));
                worst = error > worst ? error : worst;
            }
        }
    }
    return worst;
}

/*
 * BT.2020 non-constant luminance in limited range, with BT.2100's 10-bit
 * ranges: 876 and 896 codes, white at 940. swscale spans 219 and 224 times
 * 257 in 16 bits instead, white at 943; the converter undoes that ratio
 * around it (ffmpegkmp_range_factor).
 */
#define BT2100_LUMA_RANGE 876.0
#define BT2100_CHROMA_RANGE 896.0
/* BT.2100's range over swscale's: RGB scaled by it before swscale comes out in BT.2100's codes. */
#define SWSCALE_RANGE_FACTOR (256.0 / 257)

static void p010_of(const double signal[3], double code[3]) {
    const double luma = 0.2627 * signal[0] + 0.6780 * signal[1] + 0.0593 * signal[2];
    code[0] = 64 + BT2100_LUMA_RANGE * luma;
    code[1] = 512 + BT2100_CHROMA_RANGE * (signal[2] - luma) / 1.8814;
    code[2] = 512 + BT2100_CHROMA_RANGE * (signal[0] - luma) / 1.4746;
}

static void signal_of_p010(const double code[3], double signal[3]) {
    const double luma = (code[0] - 64) / BT2100_LUMA_RANGE;
    const double blue = (code[1] - 512) / BT2100_CHROMA_RANGE;
    const double red = (code[2] - 512) / BT2100_CHROMA_RANGE;
    signal[0] = luma + 1.4746 * red;
    signal[2] = luma + 1.8814 * blue;
    signal[1] = (luma - 0.2627 * signal[0] - 0.0593 * signal[2]) / 0.6780;
}

static void p010_codes(const AVFrame *frame, int x, int y, double code[3]) {
    int component;
    for (component = 0; component < 3; component++)
        code[component] = p010_code(frame, component > 0, component - (component > 0), x, y);
}

/* The largest difference, in 16-bit codes, between a planar RGB frame and the exact signal of `canvas`'s well-conditioned pixels. */
static double planar_error(const AVFrame *planar, const AVFrame *canvas, int hlg) {
    double worst = 0;
    int x;
    int y;
    for (y = 0; y < canvas->height; y++) {
        for (x = 0; x < canvas->width; x++) {
            double linear[3];
            double signal[3];
            int channel;
            if (!well_conditioned(canvas, x, y))
                continue;
            linear_pixel(canvas, x, y, linear);
            signal_of(linear, hlg, signal);
            for (channel = 0; channel < 3; channel++) {
                /* GBR plane order. */
                const int plane = (channel + 2) % 3;
                const int code = ((const uint16_t *)(planar->data[plane] + (size_t)y * planar->linesize[plane]))[x];
                worst = fmax(worst, fabs(code - signal[channel] * 65535));
            }
        }
    }
    return worst;
}

/*
 * How far re-encoding `back`, decoded from `p010`, strays from `p010`, beyond
 * what the half floats it is held in allow: for each inner pixel, the exact
 * decode of its P010 codes rounded to half floats and exactly re-encoded
 * shows how far even an exact converter would stray. Returns the worst excess
 * in 10-bit codes, and the worst of that allowance in *allowed.
 */
static double reencode_excess(
        const AVFrame *again,
        const AVFrame *p010,
        const AVFrame *canvas,
        int hlg,
        double *allowed) {
    double worst = 0;
    int x;
    int y;
    *allowed = 0;
    for (y = 0; y < p010->height; y++) {
        for (x = 0; x < p010->width; x++) {
            double code[3];
            double reencoded[3];
            double actual[3];
            double signal[3];
            double linear[3];
            int component;
            if (!roundtrip_inner(x, y) || !well_conditioned(canvas, x, y))
                continue;
            p010_codes(p010, x, y, code);
            p010_codes(again, x, y, actual);
            signal_of_p010(code, signal);
            linear_of(signal, hlg, linear);
            for (component = 0; component < 3; component++)
                linear[component] = half_to_float(float_to_half((float)linear[component]));
            signal_of(linear, hlg, signal);
            p010_of(signal, reencoded);
            for (component = 0; component < 3; component++) {
                const double exact_error = fabs(lrint(fmin(fmax(reencoded[component], 0), 1023)) - code[component]);
                *allowed = fmax(*allowed, exact_error);
                worst = fmax(worst, fabs(actual[component] - code[component]) - exact_error);
            }
        }
    }
    return worst;
}

/* The canvas's exact signal as 16-bit planar RGB, times `scale`, without the canvas's metadata. */
static AVFrame *exact_planar(const AVFrame *canvas, int hlg, double scale) {
    AVFrame *coded = new_frame(AV_PIX_FMT_GBRP16, canvas->width, canvas->height, AVCOL_PRI_BT2020,
            hlg ? AVCOL_TRC_ARIB_STD_B67 : AVCOL_TRC_SMPTE2084, AVCOL_SPC_RGB, AVCOL_RANGE_JPEG);
    int x;
    int y;
    int channel;
    for (y = 0; coded && y < canvas->height; y++) {
        for (x = 0; x < canvas->width; x++) {
            double linear[3];
            double signal[3];
            linear_pixel(canvas, x, y, linear);
            signal_of(linear, hlg, signal);
            for (channel = 0; channel < 3; channel++) {
                const int plane = (channel + 2) % 3;
                ((uint16_t *)(coded->data[plane] + (size_t)y * coded->linesize[plane]))[x] =
                        (uint16_t)lrint(fmin(fmax(signal[channel], 0), 1) * scale * 65535);
            }
        }
    }
    return coded;
}

/* The largest difference, in 10-bit codes of the BT.2020 signal, between two linear frames' inner grey pixels. */
static double grey_signal_error(const AVFrame *first, const AVFrame *second, int hlg) {
    double worst = 0;
    int x;
    int y;
    for (y = 0; y < ROUNDTRIP_BLOCK; y++) {
        for (x = 0; x < first->width; x++) {
            double a[3];
            double b[3];
            double signal_a[3];
            double signal_b[3];
            int channel;
            if (!roundtrip_inner(x, y))
                continue;
            linear_pixel(first, x, y, a);
            linear_pixel(second, x, y, b);
            signal_of(a, hlg, signal_a);
            signal_of(b, hlg, signal_b);
            for (channel = 0; channel < 3; channel++)
                worst = fmax(worst, fabs(signal_a[channel] - signal_b[channel]) * 1023);
        }
    }
    return worst;
}

/*
 * Linear F16 to P010 in PQ or HLG and back, the encode direction no Kotlin
 * caller has yet. On the well-conditioned pixels:
 * - the transfer step's 16-bit signal is within a few 16-bit codes of exact;
 * - the P010 frame is within one code of what swscale makes of the exact
 *   signal, scaled into BT.2100's codes;
 * - the F16 frame that comes back re-encodes to the same P010 within one
 *   code, beyond what holding it in half floats allows (a saturated colour's
 *   signal is only as exact as the half floats that hold it), and its greys
 *   are within one code of the canvas's;
 * And on every pixel, highlights above 1.0 and negative components come back.
 * Last, the PQ conversion straight through swscale into a P010 frame that
 * carries the HDR10 metadata the source lacks shows these checks catch
 * swscale mapping colours.
 */
static int check_roundtrip(int hlg) {
    const char *name = hlg ? "HLG" : "PQ";
    const enum AVColorTransferCharacteristic transfer = hlg ? AVCOL_TRC_ARIB_STD_B67 : AVCOL_TRC_SMPTE2084;
    ffmpegkmp_converter *converter = ffmpegkmp_converter_alloc(0);
    SwsContext *scaler = sws_alloc_context();
    AVFrame *canvas = roundtrip_canvas(hlg);
    AVFrame *p010 = new_frame(AV_PIX_FMT_P010, ROUNDTRIP_SIZE, ROUNDTRIP_SIZE, AVCOL_PRI_BT2020, transfer,
            AVCOL_SPC_BT2020_NCL, AVCOL_RANGE_MPEG);
    AVFrame *back = new_frame(AV_PIX_FMT_RGBAF16, ROUNDTRIP_SIZE, ROUNDTRIP_SIZE, AVCOL_PRI_BT709, AVCOL_TRC_LINEAR,
            AVCOL_SPC_RGB, AVCOL_RANGE_JPEG);
    AVFrame *again = new_frame(AV_PIX_FMT_P010, ROUNDTRIP_SIZE, ROUNDTRIP_SIZE, AVCOL_PRI_BT2020, transfer,
            AVCOL_SPC_BT2020_NCL, AVCOL_RANGE_MPEG);
    AVFrame *planar = new_frame(AV_PIX_FMT_GBRP16, ROUNDTRIP_SIZE, ROUNDTRIP_SIZE, AVCOL_PRI_BT2020, transfer,
            AVCOL_SPC_RGB, AVCOL_RANGE_JPEG);
    /* swscale's P010 of the exact signal, scaled so that swscale writes BT.2100's codes. */
    AVFrame *exact = canvas ? exact_planar(canvas, hlg, SWSCALE_RANGE_FACTOR) : NULL;
    AVFrame *reference = new_frame(AV_PIX_FMT_P010, ROUNDTRIP_SIZE, ROUNDTRIP_SIZE, AVCOL_PRI_BT2020, transfer,
            AVCOL_SPC_BT2020_NCL, AVCOL_RANGE_MPEG);
    double canvas_minimum;
    double canvas_maximum;
    double minimum;
    double maximum;
    double transfer_error;
    double grey_error;
    double excess;
    double allowed;
    int encode_error;
    int failed;
    int failures = 0;
    int result;
    if (!converter || !scaler || !canvas || !p010 || !back || !again || !planar || !exact || !reference) {
        printf("FAIL %s round trip: out of memory\n", name);
        return 1;
    }
    if ((result = ffmpegkmp_frame_convert(converter, p010, canvas)) < 0 ||
            (result = ffmpegkmp_frame_convert(converter, back, p010)) < 0 ||
            (result = ffmpegkmp_frame_convert(converter, again, back)) < 0 ||
            (result = ffmpegkmp_frame_convert(converter, planar, canvas)) < 0 ||
            (result = sws_scale_frame(scaler, reference, exact)) < 0) {
        printf("FAIL %s round trip: convert failed (%d)\n", name, result);
        return 1;
    }
    component_range(canvas, &canvas_minimum, &canvas_maximum);
    component_range(back, &minimum, &maximum);
    transfer_error = planar_error(planar, canvas, hlg);
    encode_error = p010_error(p010, reference, canvas);
    excess = reencode_excess(again, p010, canvas, hlg, &allowed);
    grey_error = grey_signal_error(canvas, back, hlg);
    failed = transfer_error > 4 || encode_error > 1 || excess > 1 || grey_error > 1.0 ||
            maximum <= 1.0 || fabs(maximum - canvas_maximum) > 0.01 * canvas_maximum ||
            minimum >= 0 || fabs(minimum - canvas_minimum) > 0.01 * -canvas_minimum;
    failures += failed;
    printf("%s linear F16 -> P010 %s -> linear F16: transfer step within %.1f 16-bit codes of exact; P010 within %d "
            "10-bit codes of BT.2100's from the exact signal; re-encoded within %.1f codes beyond the %.0f half floats "
            "allow; greys within %.2f codes; components %g..%g, canvas %g..%g\n",
            failed ? "FAIL" : "ok  ", name, transfer_error, encode_error, excess, allowed, grey_error,
            minimum, maximum, canvas_minimum, canvas_maximum);
    if (!av_frame_get_side_data(p010, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA)) {
        printf("FAIL %s round trip: the P010 frame lost the mastering-display metadata\n", name);
        failures++;
    }
    if (!hlg) {
        AVFrame *mapped = new_frame(AV_PIX_FMT_P010, ROUNDTRIP_SIZE, ROUNDTRIP_SIZE, AVCOL_PRI_BT2020, transfer,
                AVCOL_SPC_BT2020_NCL, AVCOL_RANGE_MPEG);
        if (!mapped || av_frame_copy_props(mapped, canvas) < 0) {
            printf("FAIL swscale mapping check: out of memory\n");
            failures++;
        } else {
            mapped->color_primaries = AVCOL_PRI_BT2020;
            mapped->color_trc = transfer;
            mapped->colorspace = AVCOL_SPC_BT2020_NCL;
            mapped->color_range = AVCOL_RANGE_MPEG;
            if (sws_scale_frame(scaler, mapped, exact) < 0) {
                printf("FAIL swscale mapping check: convert failed\n");
                failures++;
            } else {
                encode_error = p010_error(mapped, reference, canvas);
                failed = encode_error <= 4;
                failures += failed;
                printf("%s the same P010 conversion straight through swscale into a frame with HDR10 metadata the "
                        "source lacks: %d codes off (swscale mapped colours)\n", failed ? "FAIL" : "ok  ",
                        encode_error);
            }
        }
        av_frame_free(&mapped);
    }
    sws_free_context(&scaler);
    av_frame_free(&canvas);
    av_frame_free(&p010);
    av_frame_free(&back);
    av_frame_free(&again);
    av_frame_free(&planar);
    av_frame_free(&exact);
    av_frame_free(&reference);
    ffmpegkmp_converter_free(&converter);
    return failures;
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
    if (!decoded && avcodec_send_packet(context, NULL) >= 0)
        decoded = avcodec_receive_frame(context, frame) >= 0;
done:
    avcodec_free_context(&context);
    avformat_close_input(&format);
    av_packet_free(&packet);
    if (!decoded)
        av_frame_free(&frame);
    return frame;
}

/*
 * The player's RGBA8 output without the tone map keeps the source's
 * primaries and transfer: only the matrix changes, as the legacy swscale
 * setup the bridge used before did. Compared with that setup directly.
 */
static int check_matrix_only(const char *fixtures, const char *clip) {
    char path[1024];
    AVFrame *source;
    AVFrame *rgba = av_frame_alloc();
    ffmpegkmp_converter *converter = ffmpegkmp_converter_alloc(0);
    struct SwsContext *legacy;
    uint8_t *planes[4] = { NULL };
    int strides[4] = { 0 };
    int worst = 0;
    int x;
    int y;
    snprintf(path, sizeof(path), "%s/%s", fixtures, clip);
    source = first_frame(path);
    if (!source || !rgba || !converter || ffplaykmp_rgb_output(rgba, source, AV_PIX_FMT_RGBA, 0) < 0 ||
            ffmpegkmp_frame_convert(converter, rgba, source) < 0) {
        printf("FAIL %s matrix-only RGBA8: convert failed\n", clip);
        return 1;
    }
    legacy = sws_getContext(source->width, source->height, source->format, source->width, source->height,
            AV_PIX_FMT_RGBA, SWS_BILINEAR, NULL, NULL, NULL);
    sws_setColorspaceDetails(legacy, sws_getCoefficients(source->colorspace == AVCOL_SPC_UNSPECIFIED
            ? SWS_CS_ITU709 : source->colorspace), source->color_range == AVCOL_RANGE_JPEG,
            sws_getCoefficients(SWS_CS_ITU709), 1, 0, 1 << 16, 1 << 16);
    av_image_alloc(planes, strides, source->width, source->height, AV_PIX_FMT_RGBA, 1);
    sws_scale(legacy, (const uint8_t *const *)source->data, source->linesize, 0, source->height, planes, strides);
    for (y = 0; y < source->height; y++) {
        for (x = 0; x < source->width * 4; x++) {
            const int error = abs(rgba->data[0][y * rgba->linesize[0] + x] - planes[0][y * strides[0] + x]);
            worst = error > worst ? error : worst;
        }
    }
    printf("%s %s matrix-only RGBA8 against the legacy swscale setup: max error %d codes\n",
            worst > 1 ? "FAIL" : "ok  ", clip, worst);
    av_freep(&planes[0]);
    sws_freeContext(legacy);
    ffmpegkmp_converter_free(&converter);
    av_frame_free(&rgba);
    av_frame_free(&source);
    return worst > 1;
}

int main(int argc, char **argv) {
    const char *fixtures = argc > 1 ? argv[1] : ".";
    const char *golden = argc > 2 ? argv[2] : ".";
    const char *mode = argc > 3 ? argv[3] : "check";
    const int record = !strcmp(mode, "record");
    int failures = 0;
    size_t index;
    for (index = 0; index < sizeof(CASES) / sizeof(CASES[0]); index++)
        failures += check_case(fixtures, golden, &CASES[index], record);
    if (!record) {
        failures += check_matrix_only(fixtures, "hdr10-pq.mp4");
        failures += check_matrix_only(fixtures, "hlg.mp4");
        failures += check_roundtrip(0);
        failures += check_roundtrip(1);
    }
    printf("%d failure(s)\n", failures);
    return failures ? 1 : 0;
}
