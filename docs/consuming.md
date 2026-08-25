# Using FFmpegKMP in an application

FFmpegKMP is split into two delivery layers:

1. Maven Central supplies the Kotlin APIs and declaration-only bindings.
2. Your application supplies a compatible FFmpeg runtime built for its final
   platform.

Official Maven artifacts intentionally contain no FFmpeg, JNI, Apple, or Wasm
binaries. Build runtime files from the repository tag matching the Maven
version so declarations and native symbols stay in sync.

## Add the Maven dependency

Maven Central is already part of most Kotlin Multiplatform builds:

```kotlin
repositories {
    mavenCentral()
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation("io.github.aftrolle.ffmpegkmp:ffmpeg:<version>")
        implementation("io.github.aftrolle.ffmpegkmp:ffprobe:<version>")
        implementation("io.github.aftrolle.ffmpegkmp:filters:<version>") // optional
    }
}
```

The high-level modules bring in `core` and `bindings` transitively. Add
`io.github.aftrolle.ffmpegkmp:bindings:<version>` directly only when using its
low-level API.

Check out the matching source and initialize all pinned submodules:

```shell
git clone --recurse-submodules --branch <release-tag> https://github.com/afTrolle/FFmpegKMP.git
cd FFmpegKMP
```

For example, Maven version `1.2.3` is normally built from release tag `v1.2.3`
or `1.2.3`.

Select `min`, `standard` (the default), or `full` with
`-Pffmpegkmp.profile=<profile>`. Keep the same profile for every build and app
preparation step.

## Android

Build the binary-only local runtime AAR:

```shell
./gradlew :bindings:assembleJavaCppAndroidRuntime -Pffmpegkmp.profile=standard
```

Copy
`bindings/build/generated/android-runtime/ffmpegkmp-runtime-standard-local.aar`
to your application's `libs/` directory and add it only as a runtime artifact:

```kotlin
dependencies {
    runtimeOnly(files("libs/ffmpegkmp-runtime-standard-local.aar"))
}
```

The AAR contains the generated JNI shims and FFmpeg shared libraries for the
configured Android ABIs, but no Java/Kotlin declarations. Those classes come
from the Maven dependencies, avoiding duplicate classes. The Android sample's
`:samples:android:prepareFFmpegKmpRuntime` task demonstrates the alternative of
extracting `jni/**` into an app `jniLibs` source directory.

## JVM desktop

Build and package the current host runtime:

```shell
./gradlew :bindings:assembleJavaCppHostRuntime -Pffmpegkmp.profile=standard
```

The unpacked files are under
`bindings/build/generated/host-runtime/<os>-<arch>/`; a distributable ZIP is
under `bindings/build/generated/host-runtime-archives/`. Ship both `jni/` and
`lib/` with the desktop application. At launch, point FFmpegKMP at them:

```kotlin
tasks.withType<JavaExec>().configureEach {
    val runtime = layout.projectDirectory.dir("runtime/<os>-<arch>").asFile
    systemProperty("ffmpegkmp.jni.path", runtime.resolve("jni").absolutePath)
    jvmArgs("-Djava.library.path=${runtime.resolve("jni")}${File.pathSeparator}${runtime.resolve("lib")}")
}
```

Also expose `runtime/<os>-<arch>/lib` through `DYLD_LIBRARY_PATH` on macOS,
`LD_LIBRARY_PATH` on Linux, or `PATH` on Windows. The desktop sample's
`prepareFFmpegKmpRuntime` and run-task configuration are a working reference.

## Apple applications

For a reusable package containing every configured Apple slice, run on macOS:

```shell
./gradlew :native-build:apple:packageFfmpegStandardXcframeworks
```

Add all eight XCFrameworks from
`native-build/apple/out/standard/xcframework/` to the final Xcode application
target:

- `libffmpegkmp_bridge.xcframework`
- `libavdevice.xcframework`, `libavfilter.xcframework`,
  `libavformat.xcframework`, and `libavcodec.xcframework`
- `libswresample.xcframework`, `libswscale.xcframework`, and
  `libavutil.xcframework`

Link `z`, `bz2`, and `iconv`, plus the Apple frameworks enabled by the selected
profile (the built-in standard profile uses AudioToolbox, VideoToolbox,
CoreFoundation, CoreMedia, CoreVideo, CoreAudio, and CoreServices). The iOS
sample instead stages the two required raw-library slices with:

```shell
./gradlew :samples:ios:prepareFFmpegKmpRuntime
```

Its Xcode build phase invokes that task automatically and links the archives at
the final app boundary. The Kotlin/Native KLIB from Maven remains
declaration-only.

## Browser WebAssembly

Activate Emscripten, then build the runtime bundle:

```shell
./gradlew :bindings:assembleWasmRuntime -Pffmpegkmp.profile=standard
```

Copy `ffmpegkmp.mjs`, `ffmpegkmp.wasm`, and `ffmpegkmp-worker.mjs` from
`bindings/build/generated/wasm-runtime/standard/` into your web application's
resources. The web sample wires that directory through a Gradle `Sync` task.

FFmpeg uses pthreads in this build. Serve the application with
`Cross-Origin-Opener-Policy: same-origin` and
`Cross-Origin-Embedder-Policy: require-corp` so `SharedArrayBuffer` is
available.

## Distribution responsibility

Building locally does not remove FFmpeg's licence obligations. If you
distribute an application containing these binaries, review the exact build
manifest and follow the notice, corresponding-source, reverse-engineering, and
relinking requirements for that configuration. Apple and WebAssembly use
static linking in the built-in pipeline. See [Licensing and
distribution](licensing.md) before shipping.

[Back to the project README](../README.md)
