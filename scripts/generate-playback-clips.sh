#!/usr/bin/env bash
# Generates the clips PlaybackTimingDeviceTest plays (scripts/measure-playback.sh pushes them to the phone): ten seconds
# at 30 fps each, of moving test pattern under temporal noise, so that software decoding pays for fine detail, at about
# the bitrate a phone camera records at:
#
#   h264-1080p30.mp4        1920x1080 H.264 High, 16 Mb/s
#   hevc-13mp30.mp4         3240x4050 HEVC Main, 32 Mb/s (a 13 MP portrait frame)
#   hevc-8k30.mp4           7680x4320 HEVC Main, 80 Mb/s
#   hevc-2160p30-hlg.mp4    3840x2160 HEVC Main 10, HLG (BT.2020, ARIB STD-B67), 40 Mb/s
#
# Each frame carries its number in a strip along the top: 16 cells, cell i white when bit i of the frame number is set
# and black otherwise, the strip a twelfth of the picture's height. The Surface measurements read it back to tell which
# frame reached the Surface; on screen it shows repeats and skips. One keyframe a second, as cameras write.
#
# Needs an ffmpeg with libx264 and libx265 on PATH (Homebrew's has them). The clips, about 260 MB together, are too large
# to commit, so git ignores their directory. An existing clip is kept unless --force is given. The 8K clip takes a few
# minutes to encode.
#
#   scripts/generate-playback-clips.sh [--force]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dir="$root/library/ffplay/src/androidDeviceTest/playback"
force="${1:-}"
mkdir -p "$dir"

# clip <name> <width> <height> <pixel format> <codec arguments...>
clip() {
  local name="$1" width="$2" height="$3" format="$4"
  shift 4
  if [[ -f "$dir/$name" && "$force" != --force ]]; then
    echo "$dir/$name (kept)"
    return
  fi
  local band=$((height / 12 / 2 * 2))
  # The code strip, drawn at 512x32 (16 cells of 32 pixels) and scaled up unfiltered so that the cells stay solid.
  local code="nullsrc=s=512x32:r=30,format=yuv420p,geq=lum='if(mod(floor(N/pow(2,floor(X/32))),2),235,16)':cb=128:cr=128"
  ffmpeg -v error -y \
    -filter_complex "testsrc2=s=${width}x${height}:r=30,noise=alls=8:allf=t,format=yuv420p[base];${code},scale=${width}:${band}:flags=neighbor[code];[base][code]overlay=0:0:shortest=1,format=${format}[out]" \
    -map "[out]" -t 10 -r 30 -g 30 "$@" "$dir/$name.tmp.mp4"
  mv "$dir/$name.tmp.mp4" "$dir/$name"
  echo "$dir/$name"
}

clip h264-1080p30.mp4 1920 1080 yuv420p \
  -c:v libx264 -preset medium -profile:v high -b:v 16M -maxrate 20M -bufsize 32M \
  -color_primaries bt709 -color_trc bt709 -colorspace bt709
clip hevc-13mp30.mp4 3240 4050 yuv420p \
  -c:v libx265 -preset fast -b:v 32M -x265-params "log-level=error:keyint=30:min-keyint=30" \
  -color_primaries bt709 -color_trc bt709 -colorspace bt709 -tag:v hvc1
clip hevc-8k30.mp4 7680 4320 yuv420p \
  -c:v libx265 -preset faster -b:v 80M -x265-params "log-level=error:keyint=30:min-keyint=30" \
  -color_primaries bt709 -color_trc bt709 -colorspace bt709 -tag:v hvc1
clip hevc-2160p30-hlg.mp4 3840 2160 yuv420p10le \
  -c:v libx265 -preset fast -b:v 40M \
  -x265-params "log-level=error:keyint=30:min-keyint=30:colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc" \
  -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc -tag:v hvc1
