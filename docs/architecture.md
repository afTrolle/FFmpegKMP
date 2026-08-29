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
│  core        ffmpeg       ffprobe       ffplay       filters   │
│  sessions    command DSL  metadata     playback     graph DSL │
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
- `ffprobe` exposes typed media-inspection models;
- `ffplay` owns a per-player lifecycle and Compose video-output contract; and
- `filters` provides the optional filter-graph DSL.

Unlike command sessions, FFplay instances do not use the process-wide command
FIFO. Their decoder, queues, clocks, mounted I/O, and output negotiation are
per-player so multiple videos can run independently. Compose Canvas is the
portable software-frame fallback; native surfaces and hardware-frame import
are selected only when a platform backend reports that capability. On Android,
clear video can use FFmpeg's MediaCodec decoder and timed direct presentation to
an `AndroidExternalSurface`; decoder-open failures fall back to the software
surface renderer in `Auto` mode and fail in `RequireHardware` mode. Protected
video remains disabled until the decoder is connected to a platform DRM session.
On iOS, FFmpeg VideoToolbox frames cross the bridge as borrowed `CVPixelBuffer`
handles and are retained by `CMSampleBuffer` only for asynchronous submission to
`AVSampleBufferDisplayLayer`; software decoder fallback is drawn by the Compose
overlay. The display-layer seam is intentionally reusable by a later iOS PiP
controller, but it does not claim protected playback without a content-key session.
JVM desktop prefers VideoToolbox on macOS, D3D11VA then DXVA2 on Windows, and
VAAPI on Linux when libva was detected by the native build. Hardware frames are
currently downloaded before the Compose renderer, and source flags prevent that
boundary from accepting protected content. Native GPU-handle import remains an
explicit output capability rather than being inferred from decoder selection.
Browser Compose output uses `HtmlElementView` to host a native HTML canvas. Its
software boundary copies RGBA into reusable canvas storage and applies the same
fit/crop/fill policy as other surfaces. Kotlin/JS and Kotlin/Wasm both control the
same C FFplay engine inside a dedicated Emscripten worker. Mounted input is copied
once into worker-owned native memory, while state and the latest scheduled RGBA
frame cross a bounded mailbox polled by the owning worker; decoder pthreads never
invoke page JavaScript directly. For supported codecs, FFmpeg demuxes packets into
WebCodecs and the worker schedules, draws, and closes real `VideoFrame` objects.
Unsupported configurations fall back to the bounded Wasm software mailbox.
Android HDR negotiation uses the attached display's advertised HDR types and
wide-color support and applies them only to the direct-fit MediaCodec surface.
Preparing a replacement source first cancels and joins the outgoing decode worker
before mounted resource ids are reused. The native clock drops frames that miss
their bounded frame interval; its counter is combined with output-rejection drops
without being cleared by ordinary surface detach/reattach cycles.
The player snapshot carries stream and per-frame color/HDR metadata end to end.
HDR preservation is reported only after output capability negotiation confirms
both the source transfer function and color space. PQ and HLG software fallbacks
perform deterministic linear-light gamut conversion and tone mapping before an
SDR surface reports `TONE_MAPPED`; unsupported transfer functions remain explicit.

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
