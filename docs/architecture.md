# Architecture

FFmpegKMP is a Kotlin-first public API over platform-specific FFmpeg
bindings and locally generated native artifacts.

```text
                         Application code
                 Android / JVM / Apple / Web
                                │
                                ▼
┌──────────────────────────────────────────────────────────────┐
│                      Public Kotlin API                       │
│                                                              │
│  core      ffmpeg   ffprobe   filters  codec  player  ffplay │
│  sessions  command  metadata  graph    media  audio   video  │
│            DSL                DSL      types                 │
└───────────────────────────────┬──────────────────────────────┘
                                │ internal runtime abstraction
                                ▼
┌──────────────────────────────────────────────────────────────┐
│                   Single :bindings module                    │
│                                                              │
│  Apple cinterop    shared JVM/Android JNI   JS/Wasm adapters │
└───────────────────────────────┬──────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────┐
│       Target-specific FFmpeg local build outputs only        │
│                                                              │
│  Apple toolchains   Android NDK   Desktop tools   Emscripten │
│     native-build modules: apple / android / jvm / wasm       │
└───────────────────────────────┬──────────────────────────────┘
                                ▼
                 Pinned FFmpeg source and patches
```

## Public API

The `library` modules provide the platform-neutral API:

- `core` owns sessions, execution, cancellation, logging, progress, results,
  and errors;
- `ffmpeg` provides typed command construction and a raw-argument escape hatch;
- `ffprobe` exposes typed media-inspection models;
- `codec` holds the Compose-free frame model (`VideoFrame`, `FrameFormat`),
  frame-accurate decoding (`VideoDecoder`), and the source, decoder and stream
  types that the decoders and players share (`MediaSource`,
  `DecoderPreference`, `DecoderThreads`, `VideoInfo`, …);
- `ffplay` owns a per-player lifecycle and Compose video-output contract, and
  draws frames with `VideoFrame.toImageBitmap()`;
- `filters` provides the optional filter-graph DSL; and
- `player` provides audio decoding and playback with live volume, mute, and
  track controls.

`AudioLevel` in `core` is the one loudness model shared by the command DSL
(`audioLevel`), the filter DSL (`volume`, `mixAudio`) and the player, so a
preview and an export apply identical gains.

The player does not use fftools. `native-build/bridge/ffmpegkmp_player.c` demuxes
with libavformat, decodes each enabled audio track with libavcodec, resamples
it with libswresample into a per-track FIFO, and mixes the FIFOs with atomic
per-track and master gains. Each track is aligned to the playback position by
its first frame's timestamp after an open, a seek, or being enabled. That makes
seeking sample-accurate and lets a track join mid-playback without shifting the
timeline. With every track disabled, the default track keeps decoding unmixed,
so the silence follows the real timeline. The engine holds no process-global
state and runs concurrently with the command FIFO below.

FFplay does not use the command FIFO either.
`native-build/bridge/ffplaykmp_player.c` gives each player its own demux/decode
worker, queues, clock, mounted I/O and output negotiation, so several videos run
independently. Preparing a replacement source cancels and joins the outgoing
worker before its mounted resource ids are reused. Compose Canvas is the portable
software-frame fallback; native surfaces and hardware-frame import are selected
only when a platform backend reports that capability, and `AUTO` falls back to
software where `REQUIRE_HARDWARE` fails. Protected sources never reach a
software boundary. On the web, Kotlin/JS and Kotlin/Wasm drive the same C engine
inside an Emscripten worker: decoder pthreads never call page JavaScript, and
state and the latest frame cross a bounded mailbox. When the source has audio,
FFplay plays it through the `player` engine and reports the audible position
back as the video worker's master clock. In the browser that engine runs in the
player's worker and feeds an AudioWorklet directly over a `MessageChannel`. See
[`library/ffplay/README.md`](../library/ffplay/README.md) for the per-platform
decoders, renderers and HDR handling.

`VideoDecoder` (`native-build/bridge/ffmpegkmp_decoder.c`, in `codec`) is
FFplay's pull counterpart for exporters: no clock or queue, one decoded frame
per call on the decoder's own thread, with accurate seeks and one frame of
lookahead so each frame's end is the next frame's pts. Every call has a native
deadline, checked by the input's interrupt callback, the decode loop and
(through the Android build's `ffmpegkmp_wait_timeout` overlay of FFmpeg's
MediaCodec decoder) the MediaCodec waits; a Kotlin watchdog aborts calls blocked
past it in a host read. Calls take a Kotlin `Mutex`, so coroutines queue on the
decoder instead of racing on it. A cancelled caller fires
`ffmpegkmp_video_decoder_interrupt`, which the same checks see: the call fails
with `AVERROR_EXIT`, the mutex passes on once it has unwound, and the next call
clears the input's failed read and seeks to its own position first. `frames()`
runs `frameAt` in a producer coroutine ahead of its collector, through a channel
whose undelivered frames are closed. It shares input, stream selection, hardware decoder
setup and frame conversion with the player through `ffplaykmp_core.c`, whose
`ffplaykmp_video_codec_open` gives software decoders `DecoderThreads` threads
(FFmpeg's automatic count capped at 8 by default) and leaves hardware decoders
at one: slice threads for the decoder, so each video has one frame in progress,
and frame and slice threads for the player, as `ffplay` does.

Decoded frames reach Kotlin as frame handles (`ffmpegkmp_frame.c`): each
`VideoFrame` holds one reference to an FFmpeg `AVFrame`, so a frame is never
copied into a Kotlin array on its way anywhere, and its memory goes back to its
pool when its last reference closes. `ffmpegkmp_video_decoder_frame_at` hands out
a new reference to the frame it presents: with a `VideoOutput.Memory` format,
the decoder's thread converts each frame once into a frame from the decoder's
pool; without one, frames go out as decoded, and hardware frames downloaded.
A decoder's pool is a ring of three frames per layout and size
(`ffmpegkmp_frame_pool_alloc(3)`), made once: while all three are out, the
decoder waits in the bridge for one to close, until its call's deadline,
interrupt or abort. The process-wide pool and the writer's have no bound.
On Android, `Memory` output opens MediaCodec without a Surface for 8-bit
sources, so it decodes into memory (ByteBuffer mode) and FFmpeg copies each
frame out as NV12 or YUV420P; deeper sources, whose P010 output FFmpeg does not
copy, decode in software. The FFplay player's Canvas path stays in software:
the player has no per-call deadline to bound a MediaCodec decoder that stalls.
Pools are keyed by layout and size, pooled AVBuffers by default. The
Kotlin/Native Apple runtimes (`FFMPEGKMP_PIXEL_BUFFER` in `bridge.mk`; the JVM
bridge does not link CoreVideo) pool `NV12`, `P010`, `BGRA8` and `RGBA_F16`
frames in IOSurface-backed `CVPixelBufferPool`s, read VideoToolbox's buffers in
place by locking them, and hand out every frame as decoded as a `CVPixelBuffer`:
VideoToolbox's own, or a software frame copied into a pooled NV12 or P010 one in
its own colour. The FFplay Canvas fallback gets the player's frames as decoded
the same way, and `ffplay`'s `VideoFrame.toImageBitmap()` converts a frame once,
straight into a Skia or Android `Bitmap`'s own memory.

Pixels change format in one place, `ffmpegkmp_frame_convert` in
`ffplaykmp_core.c`, driven by the pixel formats and colour tags of the two
frames: the decoder's `Memory` formats, its CVPixelBuffer copies, `VideoFrame`'s
`convert`, `convertInto` and `toImageBitmap`, and the browser worker's RGBA
frames all go through it. Frame handles use a small process-wide set of
converters; each decoder and player has its own. swscale (`sws_scale_frame`, with
its automatic thread count) does layout, matrix, range and chroma, but only
between frames of the same primaries, transfer and mastering display, because
its colour management works in 16-bit RGB bounded to the destination's range
and would tone map or clip HDR. The converter copies the source's properties
and side data onto every frame it hands swscale, so that holds, and refuses the
conversion when it does not. Where the colour changes, a transfer step does the
curves and primaries in float from tables (PQ and HLG by 16-bit code, the
inverse curves by float exponent), on rows split across a pool of at most eight
workers (four on the web) that each converter owns: into linear extended sRGB
for `RgbaF16`, into PQ or HLG for 10-bit targets, and BT.2390's tone map for PQ
or HLG to SDR, after which swscale maps the gamut perceptually. Its intermediate is 16-bit planar RGB, 6 bytes a pixel. The
tables are built on first use and shared by every converter. swscale takes YUV
deeper than 8 bits through 16 bits, where limited range spans 219 × 257 codes
rather than BT.2100's 219 × 256, so 10-bit white would land on 943 instead of
940; the transfer step scales its RGB side by 256/257 into swscale and back out
of it, which gives BT.2100's codes in both directions. A destination of
another size, such as a `Memory` output with a `FrameSize`, is scaled into by
the same swscale call, bilinearly; on the HDR routes that is the first one, into
the intermediate, so the transfer step and the gamut pass run at the smaller
size. The destination gets the sample aspect ratio that keeps the source's
display aspect.

A stream's colour has one description, `FrameColor`, shared by `VideoInfo`,
`VideoFrame.format`, `VideoEncoderConfig` and `DynamicRange`. The bridge reports
FFmpeg's primaries, transfer, matrix and range values as they are, with the
Dolby Vision and HDR10+ side data it found as `ffplaykmp_snapshot.hdr_flags`, and
`VideoInfo.color` reads them the way the converter does, so decoding a frame as it
is gives the colour `info` reports. Whether a stream is HDR follows from the
transfer alone, PQ or HLG; the flags are signalling over one of them, and a Dolby
Vision 8.4 clip is HLG, so `DynamicRange.of` keeps it HLG.

`MediaWriter` (`ffmpegkmp_writer.c`, in `codec`) encodes with libavcodec and
muxes with libavformat, through a path or the same host I/O callbacks the
decoder reads with. Each track opens, feeds and drains its encoder on a thread
of its own, as MediaCodec needs, and packets meet in the muxer under one lock:
the header is written once every track has started (an audio track when added,
a video track at its first frame, when HDR10 metadata can still come from it),
and packets encoded before then wait for it. A frame in the track's input
format goes to the encoder as it is, and another is converted once on the
track's thread; the Kotlin/Native Apple runtimes hand VideoToolbox pooled
`CVPixelBuffer`s as `AV_PIX_FMT_VIDEOTOOLBOX`. Each write and track end has a
native deadline, so an encoder that takes input and never outputs fails its
track instead of spinning. Hardware encoders come first where the preference
allows, then software ones the build has.

Every `FFmpegClient` and `FFprobeClient` submits to one process-wide FIFO that
starts commands in order on a fixed number of lanes. On JVM, Android and Apple
there is one lane, because FFmpeg's command tools and logging retain
process-global state, so commands run one at a time. The bridge makes the
blocking native call on `Dispatchers.IO`, never on `Dispatchers.Default`. In the
browser every command already runs in its own Worker and Wasm instance, so there
are two to four lanes, from `navigator.hardwareConcurrency`, and commands can
overlap and finish out of order. Sessions use `StateFlow` and `Flow`, own transferred I/O, and treat
nonzero tool return codes as results. Bridge loading and serialization errors
are exceptions.

The native bridge resets the tools' option state before every run, so an option
such as `-y`, `-copyts` or `-stats_period` applies to its own command only, and
restores the libavutil log level, CPU flags, CPU count and allocation limit
afterwards. Embedded commands leave the host process alone: they install no
signal handlers, never change the terminal, and never read standard input, so
`-stdin` is ignored and an existing output file is overwritten only with `-y`.
`-timelimit` is ignored because it would limit the whole host process.

At most 64 commands wait in the FIFO. `execute` suspends until there is room,
and cancelling it while it waits withdraws the command; `enqueue` never waits
and fails its session instead. Each client accepts `CommandRuntimeLimits` for
bounding the native-event handoff, captured stdout/stderr, and structured logs.
Event collectors never slow a run down: a session's `events` flow drops a slow
collector's oldest events and completes when the session ends, while the result
keeps the full capture. Progress reports parsed from one native callback are
coalesced to the latest report. If the non-suspending native callback outruns
its bounded handoff, execution fails explicitly. `ExecutionResult.captureStatus`
reports any stdout, stderr, or log data omitted from the retained result after a
configured limit is reached.

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
and never try to consume a Kotlin/Native klib. Each browser `VideoDecoder` and
`MediaWriter` gets a worker of its own, where WebCodecs decodes and encodes next
to FFmpeg's demuxer and muxer. The `NativeVideoDecoder` and `NativeMediaWriter`
calls are `suspend` so that the page can wait for that worker; on native
platforms they block their `DecoderThread` and never suspend.

## Native builds

The `native-build` modules own target-specific local build pipelines for Apple,
Android, JVM desktop, and Wasm. These outputs feed binding generation but are not
published by FFmpegKMP. See [Native builds](native-builds.md) and
[Licensing and distribution](licensing.md).

[Back to the project README](../README.md)
