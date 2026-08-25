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
│ Apple cinterop   shared JVM/Android JNI   JS/Wasm web adapters │
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

The process-wide FIFO accepts at most 64 waiting commands; further submissions
fail their session explicitly. Each client accepts `CommandRuntimeLimits` for
bounding the native-event handoff, captured stdout/stderr, and structured logs.
Active event collectors apply backpressure to accepted native log/output events;
progress reports parsed from one native callback are coalesced to the latest
report. If the non-suspending native callback outruns its bounded handoff,
execution fails explicitly. `ExecutionResult.captureStatus` reports any stdout,
stderr, or log data omitted from the retained result after a configured limit is
reached.

## Bindings

The single [`:bindings` module](../bindings/README.md) stages FFmpeg headers once
as the common input to each generator. Apple targets use Kotlin/Native cinterop,
JVM and Android share one generated JNI bridge, and Kotlin/JS and Kotlin/Wasm
browser builds share a worker-protocol bridge with small interop adapters.

Keeping binding generation behind common Kotlin interfaces minimizes handwritten
native mappings and aligns every backend with the same pinned FFmpeg revision.

Apple uses one umbrella interop so an `AV*` declaration has exactly one Kotlin
identity. JVM and Android use per-library JavaCPP presets within the same module.
Both browser targets use the same Emscripten ES module in a dedicated worker
and never try to consume a Kotlin/Native klib.

## Native builds

The `native-build` modules own target-specific local build pipelines for Apple,
Android, JVM desktop, and Wasm. These outputs feed binding generation but are not
published by FFmpegKMP. See [Native builds](native-builds.md) and
[Licensing and distribution](licensing.md).

[Back to the project README](../README.md)
