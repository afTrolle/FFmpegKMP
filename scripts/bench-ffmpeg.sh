# Sourced by the bench scripts: picks the FFmpeg to link and sets ffmpeg_out and ffmpeg_link.
# FFMPEGKMP_BENCH_FFMPEG, else the macosArm64 native build's static libraries, else the JVM host build's dylibs.
apple_ffmpeg="$root/native-build/apple/out/standard/macosArm64"
jvm_ffmpeg="$root/native-build/jvm/out/standard/macos-arm64"
ffmpeg_out="${FFMPEGKMP_BENCH_FFMPEG:-}"
if [[ -z "$ffmpeg_out" ]]; then
  if [[ -f "$apple_ffmpeg/lib/libavcodec.a" ]]; then ffmpeg_out="$apple_ffmpeg"; else ffmpeg_out="$jvm_ffmpeg"; fi
fi
frameworks=(-framework VideoToolbox -framework CoreFoundation -framework CoreMedia -framework CoreVideo)
if [[ -f "$ffmpeg_out/lib/libavcodec.a" ]]; then
  ffmpeg_link=(
    "$ffmpeg_out/lib/libavformat.a" "$ffmpeg_out/lib/libavcodec.a" "$ffmpeg_out/lib/libswscale.a"
    "$ffmpeg_out/lib/libswresample.a" "$ffmpeg_out/lib/libavutil.a"
    "${frameworks[@]}" -framework CoreServices -framework AudioToolbox -framework Security -lz -lbz2 -liconv -lm
  )
elif [[ -f "$ffmpeg_out/lib/libavcodec.dylib" ]]; then
  # The dylibs carry their own copy of the bridge; the bench's compiled sources take precedence for its own calls.
  ffmpeg_link=(
    -L"$ffmpeg_out/lib" -lavformat -lavcodec -lswscale -lswresample -lavutil -Wl,-rpath,"$ffmpeg_out/lib"
    "${frameworks[@]}"
  )
else
  echo "No FFmpeg build at $ffmpeg_out; build the macosArm64 or JVM host native runtime first" >&2
  exit 1
fi
