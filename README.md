# FFmpegKMP

**FFmpegKMP** is a Kotlin Multiplatform wrapper around FFmpeg and
FFprobe.
Its goal is to expose one Kotlin-first API for media processing and inspection
across Apple platforms, Android, JVM desktop, and the browser through
WebAssembly.

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

The module layers, binding backends, and native build flow are described in the
[architecture documentation](docs/architecture.md).

## Target platforms

| Platform family | Kotlin target | Planned interop |
| --- | --- | --- |
| Apple | iOS, macOS, tvOS, and watchOS devices and simulators | Kotlin/Native cinterop |
| Android | Android | Generated JNI bindings |
| Desktop | JVM on supported desktop hosts | Generated JNI bindings |
| Web | Kotlin/Wasm in the browser | Emscripten and Wasm interop |

The `ffmpegkmp.multiplatform-library` convention currently declares Android, JVM,
browser Wasm, and the supported Kotlin/Native Apple architectures in one shared
target policy.

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

## Delivery status

- Native binary builds cover Android, Apple, JVM desktop, and Emscripten.
- JavaCPP 1.5.13 generates and verifies the seven declaration families and the
  project bridge locally. The JVM adapter and repeated-command smoke test use
  those generated JNI libraries; Apple uses one umbrella cinterop klib.
- `core`, `ffmpeg`, `ffprobe`, and `filters` contain the session API, scheduler,
  command/tokenizer DSL, typed JSON model, and filter AST.
- FFmpegKMP Studio exercises the public API with FileKit import, FFprobe media
  inspection, a responsive timeline editor, multi-clip concat, and MP4 export.
- The portable C bridge is installed and hashed with each native build. Its weak
  fallback reports that the embedded CLI is unavailable if a target was built
  without the reviewed `fftools` entry objects.
- JVM and Android share one JavaCPP execution actual. All eight binding families
  cross-compile for Android's four ABIs and assemble into an ignored local
  runtime AAR. The browser Kotlin actual drives the Emscripten module in a Web
  Worker with mounted byte I/O and structured events. Emscripten link/runtime
  verification still requires an installed `emconfigure` and `emcc` toolchain.

## Documentation

- [Contributing](docs/contributing.md)
- [Maven Central publishing](docs/publishing.md)
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
