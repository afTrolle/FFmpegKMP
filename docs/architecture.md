# Architecture

FFmpegKMP is a Kotlin-first public API over platform-specific FFmpeg
bindings and locally generated native artifacts.

```text
                         Application code
                 Android / JVM / Apple / Web
                                │
                                ▼
┌──────────────────────────────────────────────────────────────┐
│                    Public Kotlin API                          │
│                                                               │
│  core           ffmpeg           ffprobe           filters    │
│  sessions       command DSL      metadata models   graph DSL  │
└───────────────────────────────┬───────────────────────────────┘
                                │ internal runtime abstraction
┌───────────────────────────────┴───────────────────────────────┐
│                    Single :bindings module                    │
│                                                               │
│  Apple cinterop      shared JVM/Android JNI       Wasm interop │
└───────────────────────────────┬───────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────┐
│        Target-specific FFmpeg local build outputs only        │
│                                                               │
│    Apple toolchains   Android NDK   Desktop tools   Emscripten │
│       native-build modules: apple / android / jvm / wasm      │
└───────────────────────────────┬───────────────────────────────┘
                                ▼
                 Pinned FFmpeg source and patches
```

## Public API

The `library` modules provide the platform-neutral API:

- `core` owns sessions, execution, cancellation, logging, progress, results,
  and errors;
- `ffmpeg` provides typed command construction and a raw-argument escape hatch;
- `ffprobe` exposes typed media-inspection models; and
- `filters` provides the optional filter-graph DSL.

Every `FFmpegClient` and `FFprobeClient` submits to one process-wide FIFO. This
is intentional: FFmpeg's command tools and logging retain process-global state.
Sessions use `StateFlow` and `Flow`, own transferred I/O, and treat nonzero tool
return codes as results. Bridge loading and serialization errors are exceptions.

## Bindings

The single [`:bindings` module](../bindings/README.md) stages FFmpeg headers once
as the common input to each generator. Apple targets use Kotlin/Native cinterop,
JVM and Android share one generated JNI bridge, and browser builds use Wasm
interop.

Keeping binding generation behind common Kotlin interfaces minimizes handwritten
native mappings and aligns every backend with the same pinned FFmpeg revision.

Apple uses one umbrella interop so an `AV*` declaration has exactly one Kotlin
identity. JVM and Android use per-library JavaCPP presets within the same module.
Browser Wasm uses an Emscripten ES module in a dedicated worker and never tries
to consume a Kotlin/Native klib.

## Native builds

The `native-build` modules own target-specific local build pipelines for Apple,
Android, JVM desktop, and Wasm. These outputs feed binding generation but are not
published by FFmpegKMP. See [Native builds](native-builds.md) and
[Licensing and distribution](licensing.md).

[Back to the project README](../README.md)
