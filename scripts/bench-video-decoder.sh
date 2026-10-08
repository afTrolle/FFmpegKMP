#!/usr/bin/env bash
# Benchmarks the pull VideoDecoder's native path (native-build/bridge/ffmpegkmp_decoder.c,
# ffmpegkmp_frame.c and ffplaykmp_core.c, compiled unchanged) on macOS arm64, against the FFmpeg build the macosArm64
# native build leaves in native-build/apple/out/standard/macosArm64.
#
# Needs an ffmpeg with libx264 and libx265 on PATH to generate the clips (Homebrew's has both).
# Each step benchmark runs with one decoder thread (FFMPEGKMP_BENCH_THREADS=1, DecoderThreads.Fixed(1)),
# then with the bridge's default of 0 (DecoderThreads.Auto: FFmpeg's automatic count, capped at 8).
# The parallel mode, which all leaves out, runs 1, 2 and 4 software decoders at once on 10-second 4K clips,
# each decoding forward into RGBA8 at full size and at 960x540, with Auto and with 1, 2 and 4 threads each. It
# reports the frames a second, the process's peak threads (and their peak once every decoder runs) and its peak
# physical footprint (phys_footprint, what Activity Monitor shows).
#
#   scripts/bench-video-decoder.sh [steps|hardware|convert|parallel|all]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
mode="${1:-all}"
ffmpeg_out="${FFMPEGKMP_BENCH_FFMPEG:-$root/native-build/apple/out/standard/macosArm64}"
work="$root/build/bench/video-decoder"
bridge="$root/native-build/bridge"

if [[ ! -f "$ffmpeg_out/lib/libavcodec.a" ]]; then
  echo "No FFmpeg build at $ffmpeg_out; build the macosArm64 native runtime first" >&2
  exit 1
fi
mkdir -p "$work"

clip() {
  local name="$1"
  shift
  [[ -f "$work/$name" ]] || ffmpeg -v error -y "$@" "$work/$name"
}

# Synthetic noise keeps the bitrate realistic (about 12, 42 and 26 Mbit/s).
clip h264-1080p.mp4 -f lavfi -i "testsrc2=s=1920x1080:r=30,noise=alls=8:allf=t" -t 4 \
  -c:v libx264 -preset fast -b:v 12M -maxrate 12M -bufsize 24M -pix_fmt yuv420p
clip h264-1080p-g30.mp4 -f lavfi -i "testsrc2=s=1920x1080:r=30,noise=alls=8:allf=t" -t 4 \
  -c:v libx264 -preset fast -g 30 -b:v 12M -maxrate 12M -bufsize 24M -pix_fmt yuv420p
clip h264-2160p.mp4 -f lavfi -i "testsrc2=s=3840x2160:r=24,noise=alls=8:allf=t" -t 3 \
  -c:v libx264 -preset fast -b:v 40M -maxrate 40M -bufsize 80M -pix_fmt yuv420p
clip hevc-2160p-pq.mp4 -f lavfi -i "testsrc2=s=3840x2160:r=24,noise=alls=8:allf=t,format=yuv420p10le" -t 2 \
  -c:v libx265 -preset fast -b:v 25M \
  -x265-params "log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc" \
  -pix_fmt yuv420p10le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -tag:v hvc1
if [[ "$mode" == parallel ]]; then
  clip h264-2160p-10s.mp4 -f lavfi -i "testsrc2=s=3840x2160:r=24,noise=alls=8:allf=t" -t 10 \
    -c:v libx264 -preset fast -b:v 40M -maxrate 40M -bufsize 80M -pix_fmt yuv420p
  clip hevc-2160p-pq-10s.mp4 -f lavfi -i "testsrc2=s=3840x2160:r=24,noise=alls=8:allf=t,format=yuv420p10le" -t 10 \
    -c:v libx265 -preset fast -b:v 25M \
    -x265-params "log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc" \
    -pix_fmt yuv420p10le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -tag:v hvc1
fi

clang -O2 -std=c11 -w -DFFMPEGKMP_PIXEL_BUFFER=1 \
  -I"$bridge" -I"$ffmpeg_out/include" \
  "$root/native-build/bench/video_decoder_bench.c" "$bridge/ffmpegkmp_decoder.c" "$bridge/ffmpegkmp_frame.c" "$bridge/ffplaykmp_core.c" \
  "$ffmpeg_out/lib/libavformat.a" "$ffmpeg_out/lib/libavcodec.a" "$ffmpeg_out/lib/libswscale.a" \
  "$ffmpeg_out/lib/libswresample.a" "$ffmpeg_out/lib/libavutil.a" \
  -framework VideoToolbox -framework CoreFoundation -framework CoreMedia -framework CoreVideo \
  -framework CoreServices -framework AudioToolbox -framework Security -lz -lbz2 -liconv -lm \
  -o "$work/video_decoder_bench"

echo "$(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores"
run() { "$work/video_decoder_bench" "$work" "$@"; }
if [[ "$mode" == steps || "$mode" == all ]]; then
  echo "== One decoder thread (FFMPEGKMP_BENCH_THREADS=1)"
  FFMPEGKMP_BENCH_THREADS=1 run steps
  echo "== DecoderThreads.Auto (FFmpeg's automatic count, capped at 8)"
  run steps
fi
if [[ "$mode" == hardware || "$mode" == all ]]; then
  echo "== Hardware (VideoToolbox)"
  run hardware
fi
if [[ "$mode" == convert || "$mode" == all ]]; then
  run convert
fi
if [[ "$mode" == parallel ]]; then
  # Each case in a process of its own, so its footprint is its own; 0 threads is DecoderThreads.Auto.
  for threads in 0 1 2 4; do
    echo "== FFMPEGKMP_BENCH_THREADS=$threads"
    for decoders in 1 2 4; do
      for clip in hevc h264; do
        for size in full small; do
          FFMPEGKMP_BENCH_THREADS=$threads run parallel "$clip" "$size" "$decoders"
        done
      done
    done
  done
fi
