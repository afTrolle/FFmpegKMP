#!/usr/bin/env bash
# Generates the 4K clips the phone measurements decode. hevc-2160p-pq-4s.mp4, the benchmark's hevc-2160p-pq recipe
# 4 seconds long, is what ParallelDecoderBudgetDeviceTest and ffplay's CompositeExportBudgetDeviceTest decode;
# h264-2160p-4s.mp4, 8-bit SDR H.264, is what the GpuBuffers cases decode, since MediaCodec's frames reach the GPU in
# SDR only. At about 13 MB each they are too large to commit, so git ignores them.
#
# Needs an ffmpeg with libx264 and libx265 on PATH (Homebrew's has them).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dir="$root/library/codec/src/androidDeviceTest/resources/budget"
mkdir -p "$dir"
ffmpeg -v error -y -f lavfi -i "testsrc2=s=3840x2160:r=24,noise=alls=8:allf=t,format=yuv420p10le" -t 4 \
  -c:v libx265 -preset fast -b:v 25M \
  -x265-params "log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc" \
  -pix_fmt yuv420p10le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -tag:v hvc1 \
  "$dir/hevc-2160p-pq-4s.mp4"
ffmpeg -v error -y -f lavfi -i "testsrc2=s=3840x2160:r=24,noise=alls=8:allf=t,format=yuv420p" -t 4 \
  -c:v libx264 -preset fast -b:v 25M -maxrate 25M -bufsize 50M -pix_fmt yuv420p \
  -color_primaries bt709 -color_trc bt709 -colorspace bt709 "$dir/h264-2160p-4s.mp4"
echo "$dir/hevc-2160p-pq-4s.mp4"
echo "$dir/h264-2160p-4s.mp4"
