#!/usr/bin/env bash
# Measures FFplay playback timing on an attached Android phone against Media3 (PlaybackTimingDeviceTest) and prints the
# results as markdown tables. Run with no command it does everything: generates the clips if missing
# (scripts/generate-playback-clips.sh), pushes them to /data/local/tmp/ffmpegkmp-playback, records the phone's state,
# runs the test class while capturing its PLAYBACK_TIMING lines from logcat, and prints the tables. The lines are kept
# in build/playback-timing/<time>.log.
#
#   scripts/measure-playback.sh [all] [--scenario NAMES] [--rounds N] [--test timing|threadSweep|fourPlayers]
#   scripts/measure-playback.sh prepare          # clips generated and pushed, phone state printed
#   scripts/measure-playback.sh table LOG...     # the tables of captured lines
#   scripts/measure-playback.sh clean            # removes the pushed clips from the phone
#
# NAMES keeps the scenarios whose names start with one of its '+'-separated parts, for example
# "canvas-sw-1080p+media3-surfaceview-1080p" or "threads-8k"; --test picks one test method of the class (all three by
# default). ANDROID_SERIAL picks the phone when several are attached. Scenario names:
#   canvas-sw-CLIP, canvas-auto-CLIP, surface-hw-CLIP, media3-reader-CLIP, media3-surfaceview-CLIP, media3-compose-CLIP
#     (test timing; CLIP is 1080p, 13mp, 8k or hlg)
#   threads-13mp-N, threads-8k-N for N in 1 2 3 4 8 (test threadSweep)
#   four-canvas-sw-1080p, four-canvas-sw-13mp (test fourPlayers)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
clips="$root/library/ffplay/src/androidDeviceTest/playback"
remote=/data/local/tmp/ffmpegkmp-playback
out="$root/build/playback-timing"
class=io.github.aftrolle.ffmpegkmp.ffplay.PlaybackTimingDeviceTest
adb_cmd=(adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"})

command="${1:-all}"
[[ $# -gt 0 ]] && shift
scenario=""
rounds=1
test_method=""
while [[ $# -gt 0 && "$command" != table ]]; do
  case "$1" in
    --scenario) scenario="$2"; shift 2 ;;
    --rounds) rounds="$2"; shift 2 ;;
    --test) test_method="$2"; shift 2 ;;
    *) echo "Unknown option $1" >&2; exit 2 ;;
  esac
done

prepare() {
  "$root/scripts/generate-playback-clips.sh"
  "${adb_cmd[@]}" shell mkdir -p "$remote"
  for clip in "$clips"/*.mp4; do
    local name size remote_size
    name="$(basename "$clip")"
    size="$(stat -f %z "$clip" 2>/dev/null || stat -c %s "$clip")"
    remote_size="$("${adb_cmd[@]}" shell stat -c %s "$remote/$name" 2>/dev/null | tr -d '\r' || true)"
    if [[ "$size" != "$remote_size" ]]; then
      "${adb_cmd[@]}" push "$clip" "$remote/$name" >/dev/null
    fi
    echo "$remote/$name ($size bytes)"
  done
  state
}

# The phone's state: model, Android, display mode and refresh rate, thermal status, battery.
state() {
  echo "Phone: $("${adb_cmd[@]}" shell getprop ro.product.model | tr -d '\r'), Android $("${adb_cmd[@]}" shell getprop ro.build.version.release | tr -d '\r') (SDK $("${adb_cmd[@]}" shell getprop ro.build.version.sdk | tr -d '\r'))"
  "${adb_cmd[@]}" shell dumpsys display | grep -m1 -o 'mActiveModeId=[0-9]*' || true
  "${adb_cmd[@]}" shell dumpsys display | grep -m1 -o 'mActiveRenderFrameRate=[0-9.]*' || true
  "${adb_cmd[@]}" shell dumpsys thermalservice | grep -m1 'Thermal Status' || true
  echo "Battery: $("${adb_cmd[@]}" shell dumpsys battery | grep -m4 -E '^  (AC powered|USB powered|level|temperature):' | tr -d '\r' | tr -s ' ' | tr '\n' ';')"
}

table() {
  python3 - "$@" <<'EOF'
import json, sys

rows = []
for path in sys.argv[1:]:
    for line in open(path, errors="replace"):
        start = line.find("{")
        if "PLAYBACK_TIMING" not in line and not line.startswith("{"):
            continue
        if start < 0:
            continue
        try:
            rows.append(json.loads(line[start:]))
        except ValueError:
            pass

def d(row, key, part):
    value = row.get(key, {})
    return "" if not isinstance(value, dict) or part not in value else f"{value[part]:.1f}"

def trio(row, key):
    return "/".join(d(row, key, part) for part in ("med", "p95", "max"))

def fps(row):
    value = row.get("fps")
    if isinstance(value, list):
        return " ".join(f"{v:.1f}" for v in value)
    return "" if value is None else f"{value:.1f}"

def res(row, *path):
    value = row.get("resources", {})
    for key in path:
        value = value.get(key, {}) if isinstance(value, dict) else {}
    return "" if value in ({}, None) else f"{value:g}"

def decoder(row):
    value = row.get("decoder")
    if isinstance(value, list):
        value = ",".join(sorted(set(str(v) for v in value)))
    return str(value or "")

print("### Timing (ms: median/p95/max)\n")
print("| scenario | round | decoder | threads | fps shown | released | shown | dropped | skipped | repeats | short "
      "| release late | shown late | jitter | \\|gap - 33.3\\| | convert | gaps in vsyncs | Hz |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for row in rows:
    if "error" in row:
        print(f"| {row['scenario']} | {row['round']} | failed: {row['error'][:90]} |" + " |" * 15)
        continue
    vsyncs = " ".join(f"{k}:{v}" for k, v in row.get("gapVsyncs", {}).items())
    hz = row.get("display", {}).get("hz", "")
    print(f"| {row['scenario']} | {row['round']} | {decoder(row)} | {row.get('threads', '')} | {fps(row)} "
          f"| {row.get('released', '')} | {row.get('shown', '')} | {row.get('dropped', '')} | {row.get('skipped', '')} "
          f"| {row.get('repeats', '')} | {row.get('short', '')} | {trio(row, 'releaseLate')} | {trio(row, 'late')} "
          f"| {trio(row, 'jitter')} | {trio(row, 'gapErr')} | {trio(row, 'convert')} | {vsyncs} | {hz} |")

print("\n### Cost (process: peak during the run; idle before it)\n")
print("| scenario | round | native heap MB | RSS MB | PSS MB | Java heap MB | threads | GCs | GC ms | allocated MB "
      "| CPU cores | thermal before/after |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|")
for row in rows:
    if "error" in row:
        continue
    def pair(key):
        return f"{res(row, 'peak', key)} ({res(row, 'idle', key)})"
    thermal = row.get("thermal", {})
    therm = f"{thermal.get('before', {}).get('status', '')}/{thermal.get('after', {}).get('status', '')} " \
            f"h{thermal.get('after', {}).get('headroom', '')}"
    print(f"| {row['scenario']} | {row['round']} | {pair('nativeMB')} | {pair('rssMB')} | {pair('pssMB')} "
          f"| {pair('javaMB')} | {pair('threads')} | {res(row, 'gc', 'count')} | {res(row, 'gc', 'timeMs')} "
          f"| {res(row, 'gc', 'allocMB')} | {res(row, 'cpuCores')} | {therm} |")
EOF
}

case "$command" in
  prepare) prepare ;;
  table) table "$@" ;;
  clean) "${adb_cmd[@]}" shell rm -rf "$remote" ;;
  all)
    prepare
    mkdir -p "$out"
    log="$out/$(date +%Y%m%d-%H%M%S).log"
    "${adb_cmd[@]}" logcat -c
    "${adb_cmd[@]}" logcat -v raw -s PLAYBACK_TIMING:I > "$log" &
    capture=$!
    trap 'kill $capture 2>/dev/null || true' EXIT
    target="$class${test_method:+#$test_method}"
    "$root/gradlew" -p "$root" :library:ffplay:connectedAndroidDeviceTest \
      -Pandroid.testInstrumentationRunnerArguments.class="$target" \
      -Pandroid.testInstrumentationRunnerArguments.playbackTiming=true \
      -Pandroid.testInstrumentationRunnerArguments.rounds="$rounds" \
      ${scenario:+-Pandroid.testInstrumentationRunnerArguments.scenario="$scenario"} || true
    sleep 1
    kill "$capture" 2>/dev/null || true
    state
    echo "Lines in $log"
    table "$log"
    ;;
  *) echo "Unknown command $command" >&2; exit 2 ;;
esac
