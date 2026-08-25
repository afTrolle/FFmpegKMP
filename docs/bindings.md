# Binding generation

FFmpegKMP keeps one internal `:bindings` KMP module while preserving the seven
FFmpeg library boundaries inside generated packages. `libpostproc` is not part
of the build or bindings.

## JVM and Android

JavaCPP 1.5.13 preset classes live under `bindings/src/javacpp`. They parse the
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

Generated comments and header documentation are removed after parsing. Output
stays under `bindings/build`, is excluded from caches and publications, and is
validated together so cross-library types cannot silently diverge.

The JVM execution adapter loads the locally generated shims from
`-Dffmpegkmp.jni.path=<path-list>`; the binding test task configures this path
automatically. JVM and Android compile the same JavaCPP execution actual. The
Android tasks cross-compile every declaration family with NDK r30 for
`armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`; the local runtime AAR contains
the generated declarations, all JNI shims, and the matching seven FFmpeg shared
libraries. It remains an ignored local build input and is never published.

## Apple

Every declared Apple target creates one `ffmpeg` cinterop using the matching
`native-build/apple/out/<profile>/<target>` tree. The umbrella header includes
all seven public APIs plus the project bridge. `ffmpeg.def` embeds the static
archives into one klib and supplies inline wrappers for `AVERROR` and
`AV_VERSION_INT`, which Kotlin/Native cannot import as function-like macros.
Mounted Okio resources are exposed to FFmpeg through the `ffmpegkmp:` URL
protocol. Its open/read/write/size/seek/close callbacks dispatch directly to an
Okio `FileHandle`, `Source`, or `Sink`; Apple commands no longer create staging
files. `FileHandle` mounts are seekable and use offset-based reads and writes,
while stream mounts deliberately report themselves as non-seekable.

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

Kotlin/Wasm cannot consume cinterop klibs. `linkFfmpegKmpWorker` therefore links
the Emscripten archives into an ES module. `ffmpegkmp-worker.mjs` receives
transferable buffers, exposes them through the same `ffmpegkmp:` protocol,
executes in a Web Worker, and transfers events and writable buffers back.

The Kotlin/Wasm actual starts the module worker, translates structured events,
and transfers mounted buffers to a worker-side `ffmpegkmp:` random-access
registry. It does not create virtual filesystem staging files. It terminates the
worker on session cancellation. Deployments may override the default adjacent asset names through
`globalThis.FFMPEGKMP_WORKER_URL` and `globalThis.FFMPEGKMP_MODULE_URL`.
Because the pinned `ffmpeg` scheduler requires pthreads, Web hosting must enable
`SharedArrayBuffer` with COOP/COEP cross-origin-isolation headers; all command
and scheduler work still runs outside the browser UI thread.
Running the native Wasm link requires the Emscripten SDK (`emconfigure` and
`emcc`) on `PATH`, or `ffmpegkmp.wasm.emscriptenDir`.

[Back to the project README](../README.md)
