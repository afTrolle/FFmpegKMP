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

The same preparation gives FFmpeg's MediaCodec decoders an opt-in
`ffmpegkmp_wait_timeout` option (microseconds, 0 by default). Upstream,
`receive_frame` spins on a zero-timeout `dequeueInputBuffer` for as long as a
codec holds every input buffer without outputting a frame; with the option set it
waits on the input queue instead and, once the timeout passes, returns `EAGAIN`
from both `avcodec_send_packet` and `avcodec_receive_frame`. `VideoDecoder` sets
it so its decode loop can keep checking its deadline. Callers that do not set it,
including fftools commands, keep the upstream behaviour.

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
FFmpeg main loop and FFprobe packet-read path. Before every FFprobe run it clears
the option state `ffprobe.c` keeps in statics (`-show_*` flags,
`-select_streams`, `-show_entries` selections, output format, file names, forced
decoders and the cmdutils option dictionaries), so one probe's options never
carry into the next. Before every FFmpeg run it does the same for the globals of
`ffmpeg_opt.c`: `bridge.mk` compiles `ffmpeg_opt.c` and `opt_common.c` through
the wrappers `ffmpeg_opt_reset.c` and `opt_common_reset.c`, which reach their
statics (`-y`/`-n`, the `-report` file), in place of the objects FFmpeg's own
build makes. After every run the bridge restores the libavutil state that
`-loglevel`, `-cpuflags`, `-cpucount` and `-max_alloc` change for the whole
process.

`ffmpeg_entry.c` replaces fftools' `term_init` with one that installs no signal
handlers, leaves `SIGPIPE` and the terminal alone, and forces
`stdin_interaction` off: an embedded command never reads keystrokes or overwrite
prompts from the host's standard input, so `-stdin` is ignored with a warning and
an existing output file is kept unless the command passes `-y`. The
`ffmpeg_opt.c` wrapper also turns `-timelimit` into a warning, since
`RLIMIT_CPU` would limit the host process. Cancellation stands in for the
missing handlers: `ffmpegkmp_cancel` records a signal the way fftools' `SIGTERM`
handler did, so the main loop stops within one `-stats_period` (0.5 s by
default), writes the trailers and returns, and an input still being opened is
interrupted.

The JVM, Android and Apple bindings call `ffmpegkmp_execute` synchronously, on
`Dispatchers.IO`. The browser bridge suspends instead and tracks each execution
by id, each in its own Worker, so cancelling a caller and `close` reach every
running command.

Cancellation follows the coroutine. On JVM, Android and Apple,
`NativeExecutionBridge.execute` runs the blocking native entry point on
`Dispatchers.IO`; when its caller is cancelled the bridge raises the context's
cancel flag with `ffmpegkmp_cancel` (FFprobe polls that flag; for an active
FFmpeg run it also leaves fftools' signal count the way one SIGTERM does) and
then waits for the native call to return before rethrowing, so the single-run
runtime is idle by the time a cancelled caller resumes. The flag stays on the
context until `ffmpegkmp_context_reset_cancel` clears it, which those bridges
do right before each run, so a cancel that lands while FFmpeg is still
resetting its own flags is picked up by the entry wrapper rather than lost. The
browser bridge terminates its Web Worker from the continuation's cancellation
handler instead; each run there has a fresh context. In `library:core` a
session is a coroutine `Job`, and the process-wide queue is a FIFO of tickets
taken at submission and handed to one session per command lane, so `ExecutionSession.cancel()`, closing the client,
and cancelling the caller of `execute()` are all the same path.

If the bridge is compiled without its `fftools` objects, its weak fallback
returns `-ENOSYS`; Kotlin converts that condition to
`NativeBridgeUnavailableException` rather than pretending a command ran.

## Frame handles

`ffmpegkmp_frame.h` is the frame ABI the decoder and player hand frames through,
bound like the rest of the bridge: JavaCPP generates `ffmpegkmp_frame` and its
structs into the `bridge` family, and the Apple umbrella interop includes the
header. A `ffmpegkmp_frame *` is one reference to an `AVFrame`:

- `ffmpegkmp_frame_ref` and `ffmpegkmp_frame_unref` make and release
  references; `ffmpegkmp_frame_get_info` reports the layout, colour and size as
  the ordinals of codec's `PixelLayout` and `FrameColor` enums, and the
  `CVPixelBufferRef` on Apple.
- `ffmpegkmp_frame_map` and `ffmpegkmp_frame_unmap` bracket reading the planes
  and strides: a CVPixelBuffer is locked for reading, and another hardware frame
  is downloaded once per handle.
- `ffmpegkmp_frame_pool_get` allocates from a pool keyed by layout and size, a
  ring when `ffmpegkmp_frame_pool_alloc` gave it a capacity, where it fails with
  `EAGAIN` rather than wait while the ring is out;
  `ffmpegkmp_frame_convert_to` and `ffmpegkmp_frame_convert_into` convert
  through `ffmpegkmp_frame_convert`, and `ffmpegkmp_frame_wrap` makes a frame
  over memory the caller owns, such as a Skia bitmap's.
- `ffmpegkmp_frame_get_statistics` counts the pixel memory the bridge allocated
  (pool buffers, converter intermediates and hardware downloads), which the
  tests use to show where a frame's pixels are.

Kotlin wraps each reference in a `NativeFrame` (`JavaCppFrame`,
`CInteropFrame`), whose planes are direct `ByteBuffer`s on the JVM and Android
and `CPointer`s on Kotlin/Native; `codec`'s `VideoFrame` guards it against use
after close. In the browser, frames stay RGBA8 bytes the worker converts and
transfers to the page (`BrowserRgbaFrame`). On Android,
`ffmpegkmp_frame_convert_into_android_bitmap` (`ffmpegkmp_android_bitmap.c`)
converts into a `Bitmap`'s pixels through `AndroidBitmap_lockPixels`; it is kept
out of the generated declarations and reached through the raw-JNI seam
`AndroidBitmapFrames`, built with `AndroidPlayerSurface` by
`buildJavaCppAndroid<Abi>Surface`, so only that library links `libjnigraphics`.

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
