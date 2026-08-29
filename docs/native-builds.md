# Native FFmpeg builds

The Gradle modules under `native-build` compile the pinned FFmpeg source into
client-consumable libraries. Every target also installs the internal static
`libffmpegkmp_bridge.a`. Android produces a Prefab AAR, Apple produces raw
static install trees and one XCFramework per FFmpeg library, and JVM desktop
produces raw shared libraries for each configured machine that the active host
can compile. Wasm produces Emscripten static archives for the browser bindings
link step. Binding generation is a separate stage and is intentionally not part
of these tasks.

## Quick start

Initialize the FFmpeg submodule, then build the default `standard` profile:

```shell
git submodule update --init --recursive
./gradlew assembleNativeBinaries
```

To build the exact runtime outputs consumed by one sample, use the root sample
lifecycle tasks. The aggregate requires all platform toolchains and therefore
normally runs on macOS:

```shell
./gradlew assembleAndroidSampleBinaries
./gradlew assembleDesktopSampleBinaries
./gradlew assembleIosSampleBinaries
./gradlew assembleWebSampleBinaries
./gradlew assembleSampleBinaries
```

The platform sample tasks stage runtime files under ignored `build/`
directories and are wired into the corresponding sample build or launch task.
None is a Maven publication input.

The root task selects a profile with `-Pffmpegkmp.profile=min`, `standard`, or
`full`. A family can be built independently:

```shell
./gradlew :native-build:android:assembleFfmpegStandard
./gradlew :native-build:apple:assembleFfmpegStandard
./gradlew :native-build:jvm:assembleFfmpegStandard
./gradlew :native-build:wasm:assembleFfmpegStandard
```

`assemble` in a family module builds only its configured default profile.
`assembleAllFfmpegProfiles` builds all profiles. Target tasks follow the form
`buildFfmpeg<Profile><Target>`, for example
`buildFfmpegStandardArm64V8a`, `buildFfmpegStandardIosArm64`, and
`buildFfmpegStandardWasm32`.

Use `-Pffmpegkmp.jobs=<count>` to limit native make parallelism. Android uses
NDK `30.0.15729638` (r30 beta 2) by default. Override its location with
`-Pffmpegkmp.android.ndkDir=/absolute/path/to/ndk` when it is not installed
under the Android SDK's normal `ndk/30.0.15729638` directory.

## Profiles and configuration

The authoritative shared configuration is
[`native-build/build.gradle.kts`](../native-build/build.gradle.kts). Family
overrides live in each child module. Resolution order is:

1. task and toolchain safety defaults;
2. shared `common` configuration;
3. the named profile, including its ancestors;
4. platform `common` and profile overrides;
5. target overrides; and
6. raw `extraConfigureArgs`, which FFmpeg receives last.

The built-in profiles do not download optional codec libraries:

- `min` keeps FFmpeg's built-in LGPL components but disables network, devices,
  JNI hardware integration, and SDK hardware APIs;
- `standard` is the default and adds network plus safe SDK-provided hardware
  decode and encode paths; and
- `full` adds target devices and other available system capabilities without
  downloading third-party sources.

These names describe this project's presets; they do not claim compatibility
with the old FFmpegKit package matrix. `full` does not include libx264, libx265,
libvpx, libass, FreeType, dav1d, Opus, or any other separately built library.

The one exception is Android, where the `standard` and `full` profiles set
`thirdPartyLibraries.add("libaom")` to provide a software AV1 encoder. libaom is
cross-compiled per ABI from the pinned `third_party/aom` submodule (CMake + NDK
toolchain, static, linked into `libavcodec.so`; BSD-2-Clause plus the AOM patent
grant, LGPL-compatible). This adds two host requirements: `cmake` and
`pkg-config` (FFmpeg's configure locates libaom through a pinned
`PKG_CONFIG_LIBDIR`). x86 and x86_64 build with `AOM_TARGET_CPU=generic` so no
NASM is needed.

On a Linux JVM host, `standard` and `full` probe `pkg-config` for the system
`libva` package. A successful probe enables FFmpeg's VAAPI device backend; the
resulting runtime consequently requires a compatible libva installation on the
deployment machine. If libva is absent at build time, VAAPI is disabled and the
player reports or uses its software fallback. Windows JVM builds enable the
SDK-provided D3D11VA and DXVA2 backends, while macOS builds enable VideoToolbox.
Every backend is probed again when a player opens its decoder.

Custom profiles can extend a built-in profile. The DSL has typed sets for
encoders, decoders, muxers, demuxers, parsers, protocols, filters, input and
output devices, and hardware accelerators, plus compiler, linker, and configure
arguments:

```kotlin
ffmpegNativeBuild {
    profiles {
        create("playback") {
            extendsFrom("min")
            disableEverything()
            decoders.addAll("aac", "h264")
            demuxers.addAll("mov", "matroska")
            parsers.add("h264")
            protocols.addAll("file", "pipe")
        }
    }
}
```

A family or architecture can refine the result:

```kotlin
ffmpegNativeBuild {
    android {
        apiLevel.set(24)
        profiles.named("standard") {
            hardwareAcceleration.androidMediaCodec.set(true)
        }
        targets.named("arm64-v8a") {
            extraCompilerArgs.add("-O2")
        }
    }
}
```

Collections are additive across layers. Use a later raw `--disable-*` argument
when a custom profile must remove something inherited from an earlier layer.

## Outputs

All work and install trees are isolated by profile and target. They are local,
ignored outputs rather than Maven publications.

### Android

Android builds API 24 shared `libav*.so` libraries for `armeabi-v7a`,
`arm64-v8a`, `x86`, and `x86_64` with NDK `30.0.15729638` (r30 beta 2).
`standard` and `full` enable
JNI and MediaCodec decode and encode; `min` explicitly disables both.

The package is
`native-build/android/out/<profile>/ffmpeg-android-n9.0.1-<profile>.aar`.
It contains runtime libraries under `jni/<abi>`, seven Prefab modules with
headers and transitive FFmpeg library declarations, per-ABI build manifests,
FFmpeg licence texts, and the redistribution disclaimer.

### Apple

Apple builds require macOS with Xcode. SDK paths and compiler tools are resolved
through `xcrun`. Deployment defaults are iOS/tvOS 15, macOS 11, and watchOS 8.
The configured Kotlin/Native device and simulator targets receive static
libraries under `native-build/apple/out/<profile>/<kotlin-target>/`.

`standard` and `full` enable VideoToolbox and AudioToolbox decode and encode on
iOS, macOS, and tvOS. Those integrations are disabled on watchOS. The assembled
XCFrameworks are under
`native-build/apple/out/<profile>/xcframework/lib<name>.xcframework`, with
per-target manifests and licence files alongside them. The package contains the
seven FFmpeg XCFrameworks plus `libffmpegkmp_bridge.xcframework`, which the final
application must link for command execution.

### JVM desktop

The JVM family accepts a `machines` set containing `current`, `current-arm64`,
`current-x64`, or explicit `macos-arm64`, `macos-x64`, `linux-arm64`,
`linux-x64`, and `windows-x64` names. Outputs are under
`native-build/jvm/out/<profile>/<os>-<arch>/`.

```kotlin
ffmpegNativeBuild {
    jvm {
        machines.set(setOf("current", "current-x64", "linux-arm64"))
    }
}
```

On macOS, Xcode can produce both macOS arm64 and x64. A matching Linux host can
produce its native Linux architecture, and a matching Windows/MinGW host can
produce Windows x64. Cross-OS Linux builds require a Linux compiler and sysroot;
Windows cross-builds require MinGW-w64. When a listed machine lacks its required
host toolchain, its target task emits a prominent explanation and the family
aggregate continues with the machines it can build.

macOS `standard`/`full` uses VideoToolbox and AudioToolbox and emits relocatable
`@rpath` dylib IDs. Windows uses FFmpeg's SDK-provided D3D paths where available.
Linux hardware APIs stay disabled until a pinned native dependency layer is
added.

### WebAssembly

Wasm builds use the active Emscripten SDK to compile `wasm32` static archives
under `native-build/wasm/out/<profile>/wasm32/`. The output contains the seven
`libav*.a` archives, public headers, licence files, the redistribution
disclaimer, and `build-manifest.json`. `linkFfmpegKmpWorker` links these archives
and the bridge into ignored local `ffmpegkmp.mjs` and `.wasm` outputs. The
committed worker facade keeps execution off the browser UI thread.

Activate Emscripten in the shell before running the task:

```shell
source /path/to/emsdk/emsdk_env.sh
./gradlew :native-build:wasm:assembleFfmpegStandard
```

Alternatively, point Gradle at the directory containing `emcc`, `emconfigure`,
and `emmake`:

```shell
./gradlew :native-build:wasm:assembleFfmpegStandard \
  -Pffmpegkmp.wasm.emscriptenDir=/path/to/emsdk/upstream/emscripten
```

Browser builds disable host devices, network sockets, and hardware acceleration.
The pinned `ffmpeg` scheduler itself requires pthreads, so
the final module uses a bounded Emscripten pthread pool inside the command Web
Worker. The linked module also exports the per-player FFplay facade used by both
browser Kotlin targets. Its software decoder owns mounted input inside Wasm memory
and exposes only a latest-frame mailbox to JavaScript, which bounds memory when
the renderer falls behind. Deployments must provide `SharedArrayBuffer` through cross-origin
isolation (`Cross-Origin-Opener-Policy: same-origin` and
`Cross-Origin-Embedder-Policy: require-corp`). Profile component
selection and target overrides remain available through `wasm { ... }`; the
only registered target name is `wasm32`.

After linking, run the committed browser smoke harness from the repository root:

```shell
python3 native-build/wasm/browser-smoke/server.py
# Open http://127.0.0.1:8765/native-build/wasm/browser-smoke/
```

It executes a generated-video FFmpeg command in the module worker and checks
that a main-page heartbeat continues while the command is running. Open
`player.html` to run the mounted-input FFplay lifecycle and verify that a decoded,
scheduled RGBA frame reaches an HTML canvas.

## Traceability and licensing checks

Native-producing tasks are excluded from Gradle's build cache but retain normal
up-to-date checks. Every target install tree contains `build-manifest.json` with
the FFmpeg revision and dirty state, profile inheritance, target, toolchain,
resolved configure arguments, detected external dependencies, effective known
licence mode, redistribution marker, and SHA-256 hashes. Packages include these
manifests, all FFmpeg licence texts, and `DISCLAIMER.txt`.

Defaults are LGPL-oriented: no `--enable-gpl`, `--enable-nonfree`, libx264,
libx265, or downloaded third-party libraries. Custom flags are allowed. Known
GPL, version-3, nonfree, and external-library switches produce prominent Gradle
warnings; GPL/nonfree Android artifacts gain a classifier suffix, and nonfree
metadata is marked `redistributable: false`.

These checks are advisory, not a legal or patent determination. Anyone
distributing a generated binary or an application containing it remains
responsible for licence compliance, relinking requirements, source offers,
patents, export rules, and app-store policy. See
[Licensing and distribution](licensing.md).

## Binding inputs

The stable `include/` and `lib/` directories feed `:bindings`. Build manifests
hash the bridge header and archive alongside the seven FFmpeg libraries.
Kotlin/Native consumes them with one umbrella cinterop; JavaCPP consumes the
matching JVM/Android headers and libraries. Generated declarations, JNI code,
klibs, and WebAssembly modules remain ignored local outputs.

The app-facing staging and packaging tasks are documented in [Using FFmpegKMP
in an application](consuming.md).

[Back to the project README](../README.md)
