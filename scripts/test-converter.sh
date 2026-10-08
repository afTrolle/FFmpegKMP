#!/usr/bin/env bash
# Tests the bridge's frame converter (native-build/bridge/ffplaykmp_core.c) on macOS arm64, against
# the FFmpeg build the macosArm64 native build leaves in native-build/apple/out/standard/macosArm64:
# the decoder's outputs against the golden references in the video-decoder fixtures, then the
# encode-direction round trip (linear F16 to P010 PQ and HLG and back).
#
#   scripts/test-converter.sh [check|record]
#
# record rewrites the references from the current implementation; only do that for a deliberate change.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
mode="${1:-check}"
ffmpeg_out="${FFMPEGKMP_BENCH_FFMPEG:-$root/native-build/apple/out/standard/macosArm64}"
work="$root/build/bench/converter"
bridge="$root/native-build/bridge"
fixtures="$root/library/codec/src/commonTest/resources/video-decoder"

if [[ ! -f "$ffmpeg_out/lib/libavcodec.a" ]]; then
  echo "No FFmpeg build at $ffmpeg_out; build the macosArm64 native runtime first" >&2
  exit 1
fi
mkdir -p "$work" "$fixtures/golden"

clang -O2 -std=c11 -w -DFFMPEGKMP_PIXEL_BUFFER=1 \
  -I"$bridge" -I"$ffmpeg_out/include" \
  "$root/native-build/bench/converter_test.c" "$bridge/ffmpegkmp_decoder.c" "$bridge/ffmpegkmp_frame.c" "$bridge/ffplaykmp_core.c" \
  "$ffmpeg_out/lib/libavformat.a" "$ffmpeg_out/lib/libavcodec.a" "$ffmpeg_out/lib/libswscale.a" \
  "$ffmpeg_out/lib/libswresample.a" "$ffmpeg_out/lib/libavutil.a" \
  -framework VideoToolbox -framework CoreFoundation -framework CoreMedia -framework CoreVideo \
  -framework CoreServices -framework AudioToolbox -framework Security -lz -lbz2 -liconv -lm \
  -o "$work/converter_test"

"$work/converter_test" "$fixtures" "$fixtures/golden" "$mode"
