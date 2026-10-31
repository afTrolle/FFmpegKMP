#!/usr/bin/env bash
# Benchmarks the FFplay player engine (native-build/bridge/ffplaykmp_player.c, ffplaykmp_core.c and
# ffmpegkmp_frame.c, compiled unchanged) on macOS arm64: what each transition FFplayPlayer makes costs in time,
# input opens, reads and bytes, and what several players at once cost in threads and memory.
#
# Links the FFmpeg the macosArm64 native build leaves in native-build/apple/out/standard/macosArm64, or else the
# JVM host build's dylibs in native-build/jvm/out/standard/macos-arm64 (FFMPEGKMP_BENCH_FFMPEG overrides both).
# Needs an ffmpeg with libx264 and libx265 on PATH to generate the clips (Homebrew's has both); it shares them
# with scripts/bench-video-decoder.sh. FFMPEGKMP_BENCH_IO_LATENCY_US adds that many microseconds to every read, and
# FFMPEGKMP_BENCH_HARDWARE_DECODERS sets the hardware decoder budget (0 for no limit; the platform's default otherwise).
#
#   scripts/bench-player.sh [transitions|parallel|all]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
mode="${1:-all}"
work="$root/build/bench/player"
clips="$root/build/bench/video-decoder"
bridge="$root/native-build/bridge"
source "$root/scripts/bench-ffmpeg.sh"
mkdir -p "$work" "$clips"

clip() {
  local name="$1"
  shift
  [[ -f "$clips/$name" ]] || ffmpeg -v error -y "$@" "$clips/$name"
}

clip h264-1080p.mp4 -f lavfi -i "testsrc2=s=1920x1080:r=30,noise=alls=8:allf=t" -t 4 \
  -c:v libx264 -preset fast -b:v 12M -maxrate 12M -bufsize 24M -pix_fmt yuv420p
clip h264-2160p-10s.mp4 -f lavfi -i "testsrc2=s=3840x2160:r=24,noise=alls=8:allf=t" -t 10 \
  -c:v libx264 -preset fast -b:v 40M -maxrate 40M -bufsize 80M -pix_fmt yuv420p
clip hevc-2160p-pq-10s.mp4 -f lavfi -i "testsrc2=s=3840x2160:r=24,noise=alls=8:allf=t,format=yuv420p10le" -t 10 \
  -c:v libx265 -preset fast -b:v 25M \
  -x265-params "log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc" \
  -pix_fmt yuv420p10le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -tag:v hvc1

clang -O2 -std=c11 -w \
  -I"$bridge" -I"$ffmpeg_out/include" \
  "$root/native-build/bench/player_bench.c" "$bridge/ffplaykmp_player.c" "$bridge/ffplaykmp_core.c" \
  "$bridge/ffmpegkmp_frame.c" "${ffmpeg_link[@]}" -o "$work/player_bench"

echo "$(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores, FFmpeg from ${ffmpeg_out#"$root"/}"
run() { "$work/player_bench" "$clips" "$@"; }
if [[ "$mode" == transitions || "$mode" == all ]]; then
  for clip in h264-1080p.mp4 h264-2160p-10s.mp4 hevc-2160p-pq-10s.mp4; do
    for preference in SOFTWARE AUTO; do
      run transitions "$clip" "$preference"
    done
  done
fi
if [[ "$mode" == parallel || "$mode" == all ]]; then
  # Each case in a process of its own, so its footprint is its own.
  for preference in AUTO SOFTWARE; do
    for players in 1 4 8 16; do
      run parallel h264-2160p-10s.mp4 "$preference" "$players"
    done
  done
fi
