# FFmpegKMP

**FFmpegKMP** is a Kotlin Multiplatform wrapper around FFmpeg and
FFprobe.
Its goal is to expose one Kotlin-first API for media processing and inspection
across Apple platforms, Android, JVM desktop, and the browser through Kotlin/JS
or Kotlin/Wasm backed by the same WebAssembly runtime.

## Product vision

The final library should let applications use the same high-level API on every
supported platform while still allowing raw FFmpeg arguments when the typed API
does not cover an advanced use case.

The intended developer experience includes:

- asynchronous FFmpeg and FFprobe sessions with cancellation;
- structured logging, progress, results, and errors;
- a command DSL for common inputs, outputs, codecs, and formats;
- typed FFprobe models for formats, streams, chapters, and metadata;
- an optional DSL for composing FFmpeg filter graphs;
- a raw argument API as an escape hatch;
- platform bindings hidden behind common Kotlin interfaces.

The common API exposes coroutine sessions and immutable commands:

```kotlin
FFprobeClient().use { probe ->
    val media = probe.inspect("input.mp4")
}

FFmpegClient().use { ffmpeg ->
    val result = ffmpeg.execute(FFmpegCommand.build {
        overwrite()
        input("input.mp4")
        videoCodec("libx264")
        audioCodec("aac")
        output("output.mp4")
    })
}
```

Mounted I/O is backed by Okio. A `FileHandle` is read and written on demand at
the offsets requested by FFmpeg, so no full temporary-file staging is needed:

```kotlin
val input = FileSystem.SYSTEM.openReadOnly("input.mp4".toPath())
val output = FileSystem.SYSTEM.openReadWrite("output.mp4".toPath())

val io = CommandIo {
    input("mounted-input.mp4", input)
    output("mounted-output.mp4", output)
}

FFmpegClient().use { ffmpeg ->
    ffmpeg.execute(listOf("-i", "mounted-input.mp4", "mounted-output.mp4"), io)
}
```

Okio `Source` and `Sink` mounts are non-seekable streams on every platform.
Formats that seek to patch their own header (regular, non-fragmented MP4 chief
among them) need a seekable destination: mount a `FileHandle` directly, or
opt into `output(path, sink, Staging())` to have FFmpeg write to a real
temporary file that gets copied to the sink once the command succeeds. Staging
is a caller choice, not implicit bridge behavior, so streaming-friendly
formats (`-f mpegts`, or MP4 with `-movflags frag_keyframe+empty_moov`) can
mount a plain `Sink` and avoid the extra write.
On Android, overloads accept file descriptors, `ParcelFileDescriptor`,
`AssetFileDescriptor`, content `Uri`, and Java input/output streams; seekable
descriptors use random access and pipe-backed descriptors automatically fall
back to stream semantics.

Long-running commands use bounded queues and retained-result capture. Pass a
`CommandRuntimeLimits` to `FFmpegClient` or `FFprobeClient` to tune the native
event handoff and stdout, stderr, and log limits. Live event collectors apply
backpressure; `ExecutionResult.captureStatus` reports retained output that was
truncated by those limits.

The module layers, binding backends, and native build flow are described in the
[architecture documentation](docs/architecture.md).

The optional `filters` artifact includes color-managed HDR mappings for HDR10
BT.2020/PQ output. Android runtime builds expose P010 to MediaCodec encoders;
callers must still select HEVC Main10 HDR10 only on Android 13+ devices whose
codecs advertise P010 and the HDR10 profile.

## Target platforms

| Platform family | Kotlin target | Planned interop |
| --- | --- | --- |
| Apple | iOS, macOS, tvOS, and watchOS devices and simulators | Kotlin/Native cinterop |
| Android | Android | Generated JNI bindings |
| Desktop | JVM on supported desktop hosts | Generated JNI bindings |
| Web | Kotlin/JS and Kotlin/Wasm in the browser | Shared worker protocol over an Emscripten Wasm runtime |

The `ffmpegkmp.multiplatform-library` convention currently declares Android,
JVM, browser Kotlin/JS, browser Kotlin/Wasm, and the supported Kotlin/Native
Apple architectures in one shared target policy.

## Repository layout

```text
FFmpegKMP/
├── build-logic/       Gradle conventions and shared target policy
├── ffmpeg/            Pinned FFmpeg source checkout
├── native-build/      Android, Apple, JVM, and Wasm build pipelines
├── bindings/          One KMP module for native, JNI, and Wasm interop
├── library/           Public core, FFmpeg, FFprobe, and filter APIs
└── samples/           Android, desktop, iOS, and web examples
```

- `build-logic/` contains the included Gradle build and convention plugins.
- `ffmpeg/` is reserved for the pinned FFmpeg Git submodule.
- `native-build/` produces traceable, target-specific FFmpeg artifacts locally
  on the user's machine.
- `bindings/` is one KMP module containing generated interop code and internal
  adapters. JVM and Android share one JNI C++/Java binding implementation.
- `library/` contains the platform-neutral API exposed to consumers.
- `samples/` contains FFmpegKMP Studio, a shared Compose Multiplatform multi-clip
  editor with Android, iOS, desktop, and browser launchers.

Native artifacts are generated locally through target-specific pipelines. See
the [native build documentation](docs/native-builds.md) for the intended build
model and reproducibility requirements.

## Use from another application

Add the APIs you use from Maven Central to `commonMain`:

```kotlin
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("io.github.aftrolle.ffmpegkmp:ffmpeg:<version>")
        implementation("io.github.aftrolle.ffmpegkmp:ffprobe:<version>")
        // Optional typed filter graph DSL:
        implementation("io.github.aftrolle.ffmpegkmp:filters:<version>")
    }
}
```

The Maven modules contain Kotlin APIs and binding declarations only. Build the
matching local runtime from the same Git tag, then add it to the final app:

The high-level modules bring in `core` and `bindings` transitively. Add
`io.github.aftrolle.ffmpegkmp:bindings:<version>` directly only when using its
low-level API. On JVM and Android, the binding artifact exposes JavaCPP as a
transitive API dependency because its generated public declarations use
JavaCPP types. FFmpeg and JNI binaries are still supplied only by the separate
local runtime.

```shell
git clone --recurse-submodules --branch <release-tag> https://github.com/afTrolle/FFmpegKMP.git
cd FFmpegKMP
./gradlew assembleAndroidSampleBinaries   # Android AAR with JNI/FFmpeg .so files
./gradlew assembleDesktopSampleBinaries   # Current-host JNI + shared libraries
./gradlew assembleIosSampleBinaries       # iOS device and simulator static archives
./gradlew assembleWebSampleBinaries       # .mjs/.wasm runtime (requires Emscripten)
```

`./gradlew assembleSampleBinaries` runs all four on a macOS machine with the
Android, Xcode, desktop, and Emscripten toolchains installed. Each sample build
also invokes its own preparation task automatically. See
[Using FFmpegKMP in an application](docs/consuming.md) for the generated paths
and platform-specific integration steps. Generated native binaries remain
ignored local outputs and are never part of a Maven publication.

## Delivery status

- Native binary builds cover Android, Apple, JVM desktop, and Emscripten.
- JavaCPP 1.5.14 generates and verifies eight declaration families: the seven
  FFmpeg libraries and the project bridge. The JVM adapter and repeated-command
  smoke test use those generated JNI libraries; Apple uses one umbrella
  cinterop klib.
- `core`, `ffmpeg`, `ffprobe`, and `filters` contain the session API, scheduler,
  command/tokenizer DSL, typed JSON model, and filter AST.
- FFmpegKMP Studio exercises the public API with FileKit import, FFprobe media
  inspection, a responsive timeline editor, multi-clip concat, and MP4 export.
- The portable C bridge is installed and hashed with each native build. Its weak
  fallback reports that the embedded CLI is unavailable if a target was built
  without the reviewed `fftools` entry objects.
- JVM and Android share one JavaCPP execution actual. All eight binding families
  cross-compile for Android's four ABIs and assemble into an ignored local
  runtime AAR. The browser Kotlin/JS and Kotlin/Wasm actuals share a browser
  bridge and drive the same Emscripten module in a Web Worker with mounted byte
  I/O and structured events. Emscripten link/runtime verification still
  requires an installed `emconfigure` and `emcc` toolchain.

## Documentation

- [Contributing](docs/contributing.md)
- [Maven Central publishing](docs/publishing.md)
- [Using FFmpegKMP in an application](docs/consuming.md)
- [Architecture](docs/architecture.md)
- [Native builds](docs/native-builds.md)
- [Binding generation](docs/bindings.md)
- [Licensing and distribution](docs/licensing.md)

## Licence and distribution

Most FFmpegKMP-authored code is licensed under [Apache License 2.0](LICENSE),
while the [`bindings` module](bindings/README.md) is licensed under
`LGPL-2.1-or-later`. FFmpeg and other third-party components retain their own
licences.

FFmpegKMP's Maven packages contain the high-level libraries and the separately
licensed bindings module, but not compiled FFmpeg libraries, JNI shims, or
WebAssembly modules. Anyone distributing locally generated binaries or an
application containing them is responsible for the applicable open-source,
patent, and platform requirements. Read the full
[licensing and distribution policy](docs/licensing.md) and
[third-party notices](THIRD_PARTY_NOTICES.md) before distributing an output.
