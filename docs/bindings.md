# Binding generation

FFmpegKMP keeps one internal `:bindings` KMP module while preserving the seven
FFmpeg library boundaries inside generated packages. `libpostproc` is not part
of the build or bindings.

## JVM and Android

JavaCPP 1.5.14 preset classes live under `bindings/src/javacpp`. They parse the
headers installed by the selected native profile, not Bytedeco binary bundles.
The presets inherit in FFmpeg dependency order and carry the pinned-version
`InfoMap` rules for C enum typedefs, opaque declarations, attributes, and
function-like channel-layout macros.

```shell
./gradlew :bindings:generateJavaCppBindings
./gradlew :bindings:verifyJavaCppBindings
./gradlew :bindings:buildJavaCppHostBindings
./gradlew :bindings:jvmTest
./gradlew :bindings:buildJavaCppAndroidBindings
./gradlew :bindings:assembleJavaCppAndroidRuntime
```

Generated comments and header documentation are removed after parsing and an
LGPL/provenance header is added. Intermediates stay under `bindings/build` and
are excluded from caches. The declaration classes and corresponding generated
sources are included in JVM and Android publications; JNI shims and FFmpeg
runtime libraries are excluded. All declaration families are validated together
so cross-library types cannot silently diverge.

The JVM execution adapter loads the locally generated shims from
`-Dffmpegkmp.jni.path=<path-list>`; the binding test task configures this path
automatically. JVM and Android compile the same JavaCPP execution actual. The
Android tasks cross-compile every declaration family with NDK r30 for
`armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`; the local runtime AAR contains
the generated declarations, all JNI shims, and the matching seven FFmpeg shared
libraries. It remains an ignored local build input and is never published.

## Apple

Every declared Apple target creates one declaration-only `ffmpeg` cinterop using
the matching `native-build/apple/out/<profile>/<target>` headers. The umbrella
header includes all seven public APIs plus the project bridge. `ffmpeg.def`
supplies inline wrappers for `AVERROR` and `AV_VERSION_INT`, which Kotlin/Native
cannot import as function-like macros. Static FFmpeg archives are deliberately
not embedded in the published klib.
Mounted Okio resources are exposed to FFmpeg through the `ffmpegkmp:` URL
protocol. Its open/read/write/size/seek/close callbacks dispatch directly to an
Okio `FileHandle`, `Source`, or `Sink`. `FileHandle` mounts are seekable and use
offset-based reads and writes; `Source`/`Sink` stream mounts deliberately
report themselves as non-seekable, identically on every platform — no bridge
stages `Sink` output implicitly.

Formats that seek to patch their own header after writing (regular,
non-fragmented MP4 chief among them) need a seekable destination. Prefer these
zero-copy options first:

- **The destination is really a file**: mount it as a `FileHandle` directly —
  FFmpeg writes it once, with real seeking, no extra copy.
- **The destination is genuinely a stream**: use a format that never seeks
  (`-f mpegts`, or MP4 with `-movflags frag_keyframe+empty_moov`) with a plain
  `Sink` mount.

Only when neither applies — the destination is a stream but the format must
seek — reach for `output(path, sink, Staging())`. This is an explicit,
caller-visible opt-in rather than automatic bridge behavior: it writes to a
real temporary file — implemented once in common code, so it works the same
way on every target that has a synchronous filesystem — and copies the
finished bytes to the sink after a successful command, deleting the temporary
file afterward. A command that reports success without ever writing the
staged mount fails loudly instead of silently handing back an empty sink.
`Staging` throws on Kotlin/JS and Kotlin/Wasm browser targets, which have no
synchronous filesystem; the two zero-copy options above remain available
there.

Android runtime source preparation adds P010 byte-buffer input to FFmpeg's
MediaCodec encoder without modifying the pinned FFmpeg submodule. This enables
HEVC Main10 HDR10 commands on Android 13+ devices whose codecs advertise P010
and the HDR10 profile; capability selection and SDR fallback remain caller
policy. PQ commands select Android's dedicated Main10 HDR10 profile, while HLG
continues to use the regular Main10 profile. The overlay also forwards any
`AV_FRAME_DATA_MASTERING_DISPLAY_METADATA`/`AV_FRAME_DATA_CONTENT_LIGHT_LEVEL`
frame side data present on the encoder's `AVCodecContext` to Android's
`hdr-static-info` MediaFormat key (CTA-861.3), so mastering-display and
MaxCLL/MaxFALL metadata from an HDR source survives re-encoding instead of
being silently dropped.

The Android HDR10 profile is selected from `avctx->profile`, not inferred from
pixel format or color metadata, so a caller must set it explicitly. A minimal
HDR10-to-HDR10 command on Android looks like:

```
-i input.mp4 -vf "scale=out_color_matrix=bt2020:out_primaries=bt2020:out_transfer=smpte2084:out_range=tv:intent=absolute_colorimetric,format=p010le,setparams=colorspace=bt2020nc:color_primaries=bt2020:color_trc=smpte2084:range=tv"
-c:v hevc_mediacodec -profile:v main10 -pix_fmt p010le output.mp4
```

(the `-vf` chain is `ToneMap.ToHdr10Bt2020` + `ToneMap.Hdr10P010Output` from the
`filters` artifact.) Omitting `-profile:v main10` silently produces a non-HDR
Main/Main10 stream even though the pixel format and color metadata are correct.

## Command bridge

`native-build/bridge` defines a small C ABI for context lifetime, execution,
events, cancellation, and host I/O. The native build applies a source overlay
that adds the `ffmpegkmp:` protocol without modifying the pinned FFmpeg
submodule, compiles it for each target, and adds
it to the install manifest. The bridge serializes embedded command entry, turns
`exit()` into a return to the host, resets the wrapper-controlled tool state,
routes `av_log` events, captures FFprobe output, and checks cancellation in the
FFmpeg scheduler and FFprobe packet-read path.

If the bridge is compiled without its `fftools` objects, its weak fallback
returns `-ENOSYS`; Kotlin converts that condition to
`NativeBridgeUnavailableException` rather than pretending a command ran.

## Web

Browser Kotlin targets cannot consume the native cinterop klibs.
`linkFfmpegKmpWorker` therefore links the Emscripten archives into an ES module.
`ffmpegkmp-worker.mjs` receives
transferable buffers, exposes them through the same `ffmpegkmp:` protocol,
executes in a Web Worker, emits event text directly, and transfers writable
buffers back with their logical lengths.

Kotlin/JS and Kotlin/Wasm share metadata serialization, mounted-I/O, event,
result, and coroutine lifecycle code. File contents never enter JSON: small
target-specific adapters transfer typed arrays alongside the metadata using
each compiler's JavaScript interop model. Kotlin/JS transfers the temporary
array backing storage directly; Kotlin/Wasm performs one required copy between
Kotlin memory and a JavaScript typed array at each edge. The worker uses received
arrays directly and transfers output capacity buffers without first compacting
them. The bridge exposes those buffers through a worker-side `ffmpegkmp:`
random-access registry; it does not create virtual filesystem staging files,
and it terminates the worker on session cancellation. Deployments may override the default adjacent asset names through
`globalThis.FFMPEGKMP_WORKER_URL` and `globalThis.FFMPEGKMP_MODULE_URL`.
Because the pinned `ffmpeg` scheduler requires pthreads, Web hosting must enable
`SharedArrayBuffer` with COOP/COEP cross-origin-isolation headers; all command
and scheduler work still runs outside the browser UI thread.
Running the native Wasm link requires the Emscripten SDK (`emconfigure` and
`emcc`) on `PATH`, or `ffmpegkmp.wasm.emscriptenDir`.

[Back to the project README](../README.md)
