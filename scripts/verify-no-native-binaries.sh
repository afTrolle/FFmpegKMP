#!/usr/bin/env bash
set -euo pipefail

repository="${1:-build/release-check-repository}"

if [[ ! -d "$repository" ]]; then
  echo "Publication repository does not exist: $repository" >&2
  exit 1
fi

forbidden='(^|/)(ffmpeg|ffprobe)(\.exe)?$|\.so(\.[0-9]+)*$|\.dylib$|\.dll$|\.a$|\.o$|\.wasm$|(^|/)[^/]+\.(framework|xcframework)(/|$)'
scan_tmp="$(mktemp -d)"
trap 'rm -rf "$scan_tmp"' EXIT
archive_index=0
found=0

scan_archive() {
  local archive="$1"
  local display_name="$2"
  local entries
  local matches
  local extracted
  local nested

  if ! entries="$(unzip -Z1 "$archive")"; then
    echo "Could not inspect archive: $display_name" >&2
    found=1
    return
  fi

  matches="$(printf '%s\n' "$entries" | grep -Eai "$forbidden" || true)"
  if [[ -n "$matches" ]]; then
    echo "Forbidden native content in $display_name:" >&2
        while IFS= read -r match; do
            printf '  %s\n' "$match" >&2
        done <<< "$matches"
    found=1
  fi

  archive_index=$((archive_index + 1))
  extracted="$scan_tmp/$archive_index"
  mkdir -p "$extracted"
  unzip -qq "$archive" -d "$extracted"

  while IFS= read -r -d '' nested; do
    scan_archive "$nested" "$display_name!/${nested#"$extracted"/}"
  done < <(find "$extracted" -type f \( -name '*.jar' -o -name '*.aar' -o -name '*.klib' -o -name '*.zip' \) -print0)
}

while IFS= read -r -d '' published_file; do
  relative="${published_file#"$repository"/}"
  if [[ "$relative" =~ $forbidden ]]; then
    echo "Forbidden native publication file: $relative" >&2
    found=1
  fi
done < <(find "$repository" -type f -print0)

while IFS= read -r -d '' archive; do
  scan_archive "$archive" "${archive#"$repository"/}"
done < <(find "$repository" -type f \( -name '*.jar' -o -name '*.aar' -o -name '*.klib' -o -name '*.zip' \) -print0)

if [[ "$found" -ne 0 ]]; then
  echo "Publication verification failed: native FFmpeg/runtime binaries must not be uploaded." >&2
  exit 1
fi

echo "Publication verification passed: no native FFmpeg/runtime binaries were found."
