# FFmpegKMP

**FFmpegKMP** is a planned Kotlin Multiplatform wrapper around FFmpeg and
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

An illustrative future API might look like this:

```kotlin
val media = FFprobe.inspect("input.mp4")

val result = FFmpeg.execute {
    input("input.mp4")
    videoCodec("h264")
    audioCodec("aac")
    output("output.mp4")
}
```

The exact API is not settled; this example describes the intended level of
abstraction rather than a committed interface. The proposed module layers,
binding backends, and native build flow are described in the
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
- `samples/` will verify the public API from real applications on each platform.

Native artifacts are generated locally through target-specific pipelines. See
the [native build documentation](docs/native-builds.md) for the intended build
model and reproducibility requirements.

## Planned delivery stages

1. Establish the repository, Gradle modules, conventions, and target matrix.
2. Pin FFmpeg and implement reproducible native builds per platform family.
3. Generate native, shared JVM/Android JNI, and Wasm bindings locally from the
   single `:bindings` module without publishing compiled FFmpeg artifacts.
4. Implement sessions, execution, cancellation, logging, and progress in `core`.
5. Add the FFmpeg command API and FFprobe metadata models.
6. Add the optional filter-graph DSL, samples, tests, and publishing.

The current repository covers the first stage and the Android, Apple, JVM
desktop, and WebAssembly binary pipelines from the second stage. Generated
bindings remain later work.

## Documentation

- [Contributing](docs/contributing.md)
- [Architecture](docs/architecture.md)
- [Native builds](docs/native-builds.md)
- [Licensing and distribution](docs/licensing.md)

## Licence and distribution

Most FFmpegKMP-authored code is licensed under [Apache License 2.0](LICENSE),
while the [`bindings` module](bindings/README.md) is licensed under
`LGPL-2.1-or-later`. FFmpeg and other third-party components retain their own
licences.

FFmpegKMP distributes source and build logic, not compiled FFmpeg artifacts or
generated bindings. Anyone distributing locally generated binaries or an
application containing them is responsible for the applicable open-source,
patent, and platform requirements. Read the full
[licensing and distribution policy](docs/licensing.md) and
[third-party notices](THIRD_PARTY_NOTICES.md) before distributing an output.
