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
Mounted `Source`/`Sink` data is staged through `kotlinx-io` in the system
temporary directory and removed after native cleanup.

## Command bridge

`native-build/bridge` defines a small C ABI for context lifetime, execution,
events, and cancellation. The native build compiles it for each target and adds
it to the install manifest. The bridge serializes embedded command entry, turns
`exit()` into a return to the host, resets the wrapper-controlled tool state,
routes `av_log` events, captures FFprobe output, and checks cancellation in the
FFmpeg scheduler and FFprobe packet-read path.

If the bridge is compiled without its `fftools` objects, its weak fallback
returns `-ENOSYS`; Kotlin converts that condition to
`NativeBridgeUnavailableException` rather than pretending a command ran.

## Web

Kotlin/Wasm cannot consume cinterop klibs. `linkFfmpegKmpWorker` therefore links
the Emscripten archives into an ES module. `ffmpegkmp-worker.mjs` mounts byte
inputs, executes in a Web Worker, transfers events and outputs, and removes the
session files afterward.

The Kotlin/Wasm actual starts the module worker, translates structured events,
stages mounted byte inputs and outputs, and terminates the worker on session
cancellation. Deployments may override the default adjacent asset names through
`globalThis.FFMPEGKMP_WORKER_URL` and `globalThis.FFMPEGKMP_MODULE_URL`.
Because the pinned `ffmpeg` scheduler requires pthreads, Web hosting must enable
`SharedArrayBuffer` with COOP/COEP cross-origin-isolation headers; all command
and scheduler work still runs outside the browser UI thread.
Running the native Wasm link requires the Emscripten SDK (`emconfigure` and
`emcc`) on `PATH`, or `ffmpegkmp.wasm.emscriptenDir`.

[Back to the project README](../README.md)
