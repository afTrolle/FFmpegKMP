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
  errors, and the `MediaSource` every decoder and player opens;
- `ffmpeg` provides typed command construction and a raw-argument escape hatch;
- `ffprobe` exposes typed media-inspection models;
- `codec` holds the Compose-free frame model (`VideoFrame`, `FrameFormat`),
  frame-accurate decoding (`VideoDecoder`), and the decoder and stream types
  that the decoders and players share (`CodecPreference`, `DecoderThreads`,
  `VideoInfo`, …);
- `ffplay` owns a per-player lifecycle and Compose video-output contract, and
  draws frames through a `FrameImage`, which reuses its bitmaps;
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
worker, frame queue, clock, mounted I/O and output negotiation, so several videos run
independently. Preparing a replacement source cancels and joins the outgoing
worker before its mounted resource ids are reused. Compose Canvas is the portable
software-frame fallback; native surfaces and hardware-frame import are selected
only when a platform backend reports that capability, and `AUTO` falls back to
software where `REQUIRE_HARDWARE` fails. Protected sources never reach a
software boundary. On the web, Kotlin/JS and Kotlin/Wasm drive the same C engine
inside an Emscripten worker: decoder pthreads never call page JavaScript, and
state and the latest frame cross a bounded mailbox; the worker paces its own
frames, so the queue, the refresh and the external clock below are not used
there. When the source has audio,
FFplay plays it through the `player` engine and reports the audible position
back as the video worker's master clock. In the browser that engine runs in the
player's worker and feeds an AudioWorklet directly over a `MessageChannel`. See
[`library/ffplay/README.md`](../library/ffplay/README.md) for the per-platform
decoders, renderers and HDR handling.

### Decoding ahead, presenting on the display's time

The player is ffplay's structure with no presenter thread of its own, as ffplay
has none:

```text
session thread:  read → decode → PREPARE (the output makes the frame drawable) → queue [frameQueueSize]
display:         frame clock or timer → ffplaykmp_player_refresh(now, lead) → PRESENT (a flip) / RELEASE
```

The session thread never sleeps for presentation. It decodes ahead into a queue
of `frameQueueSize` frames (`ffplaykmp_configuration.frame_queue_size`, 3 by
default, ffplay's picture queue) and blocks only while the queue is full. Each
frame an output takes meets three events on the output's video frame callback:
PREPARE on the session thread as it enters the queue, ahead of its time, then
exactly one of PRESENT, when a refresh shows it, or RELEASE, when it is dropped,
flushed by a seek, stop, output change or codec release, or the player is
destroyed. The Canvas output converts at PREPARE, so PRESENT is a flip. Frames
carry the queue serial they were decoded under, and a frame is presented only
under that serial, so no frame from before a seek is shown after it.

`ffplaykmp_player_refresh(now_us, lead_us)` is a port of ffplay's
`video_refresh`, with its frame timer and clocks: of the queued frames that are
due at `now + lead` it presents the newest and drops the older ones, counting
them in `dropped_frames`, and returns how long until the next is due (at most
ffplay's 10 ms refresh rate), or `FFPLAYKMP_REFRESH_IDLE` while not playing. A
frame is due one frame duration after the one before it, corrected towards the
master clock (the audio position the `player` engine reports, else a clock that
runs from the first frame shown) only once they are further apart than ffplay's
sync threshold, so a coarse audio report does not jerk the video. Calls never
wait for decoding or I/O; pause, seek and stop wait for a refresh in flight, so
none of them can be overtaken by a refresh's snapshot.

Who calls it depends on the output. The Compose Canvas output calls it once per
display frame from the composition's frame clock (`withFrameNanos`), with the
display frame's period as `lead`, so the frame chosen is the one due when the
drawing becomes visible. On Android the frame clock's times are on the player's
own clock (`System.nanoTime`, which is `av_gettime_relative`'s), so every player
in a window chooses its frame for the same vsync. Outputs without a frame clock,
Android's `SurfaceView` and iOS's layer, are refreshed by one engine coroutine
per player, which waits as long as the last refresh returned. A MediaCodec
decoder that renders to a Surface takes its buffers at least about two 60 Hz
display frames ahead (`FFPLAYKMP_MEDIACODEC_RENDER_AHEAD_US`) and releases each
with `av_mediacodec_render_buffer_at_time` at its due time, so the compositor
does the timing. The queue is not capped for it: a codec gives its client only
so many output buffers (Codec2 about four while it decodes on), so a longer queue
fills as far as the codec lets it, and a decoder that takes no packet while
frames are queued is waiting for a buffer back, not stalled. The player opens MediaCodec with the build's `ffmpegkmp_wait_timeout`
overlay at 50 ms, so a stalled codec cannot hang the session thread, and a packet
the codec could not take yet is kept and retried after an abort check.

### Falling behind

A decoder that is slower than real time for a while shows fewer frames, through
the refresh's drop, rather than showing them late. A decoder that falls further
behind catches up: when a frame is decoded more than 500 ms behind the master
clock (`FFPLAYKMP_CATCH_UP_LAG_US`), the session thread finds the first keyframe
at or after the clock in the stream's index, seeks the video demuxer there with
`avformat_seek_file` (the clock as the minimum, so never backwards and never to
the same keyframe twice) and flushes the decoder. It is not a user seek: the
serial stays, nothing is published, there is no preview, and the audio, which
reads its own input, is untouched. The queued frames are released and counted
as dropped with an estimate of the frames passed over; the frame it lands on is
shown when the clock reaches it. With no later keyframe in the index it decodes
on. 500 ms is about where a seek to the next keyframe costs less than decoding
the frames in between.

ffplay also drops frames early, in the decoder, before they are queued. That was
ported, measured and removed: on a decoder slower than real time every frame is
already late when it is decoded, so it dropped every frame and the picture froze.
The refresh's drop and the keyframe catch-up cover the same cases without that.

### An external clock

`FFplayPlayer.follow(clock)` drives a player from a clock the app keeps. Each
refresh asks `FFplayClock.positionAt(frameTime)` for the media position of that
display frame and calls `ffplaykmp_player_refresh_at(now, lead, position)`,
which presents the newest queued frame at or before the position, compared in
the stream's time base with the conversion `VideoDecoder.frameAt` uses
(`ffplaykmp_stream_time`, shared by both), and holds the frame on screen while
none has been reached. The frame timer and the
audio clock play no part; the position is the master clock that the catch-up
compares decoded frames with. The video has no transport while following: one
run decodes ahead and blocks on a full queue, so a still position holds its
frame and a repeated one costs nothing. A seek follows only a jump of the clock
itself, back or more than 500 ms further than the frame time moved (a loop wrap
among them), and seeks coalesce; a decoder that lags is left to the catch-up,
which while following skips to the keyframe at or before the position so a
clock that then stands still gets its frame. The audio follows the clock's
motion over a 100 ms window, silent outside 25 % of real time or when no frame
has asked for 200 ms, and is sought back past 100 ms of drift. The
clock is one function per player, so an editor applies each clip's offset, trim
and loop to its one timeline clock, and every Canvas in a window asks about the
same Compose frame time in a display frame.

### Memory

A player's video memory is three numbers the caller can see:

- the decoder's frame threads × one decoded picture (`DecoderThreads`; a
  player's default is `Fixed(2, setOf(FRAME))`);
- `frameQueueSize` × one drawable frame: on the Canvas a bitmap at the size it is
  drawn at, width × height × 4 bytes, plus the decoded frame on screen, which is
  kept so that a resize while paused can convert it again; on other outputs the
  decoded frame; for a hardware decoder one of its output buffers;
- the decoder's own reference pictures.

The software Canvas converts into a ring of `FFplayConfiguration.canvasFrames`
bitmaps in its `FrameImage`, by default `frameQueueSize` + 2: the queued frames,
the frame on screen, and the one shown before it, which a drawing already
recorded may still read. The app may pick fewer, down to one, and gives those up
in that order: below `frameQueueSize` + 2 the frame shown before returns to the
ring at once; a frame queued while no bitmap is free stays decoded and is
converted at PRESENT, on the presenting thread, into the bitmap that left the
screen; with one bitmap it is written over the picture on screen, which a
drawing under way may show partly written. The ring reuses its bitmaps
while the drawn size step and format stay, so nothing is allocated per frame after
warm-up, and a resize while playing converts newer frames at the new size with no
flush; frames already queued draw scaled until they are replaced. At
`frameQueueSize = 1` the ring is three bitmaps, what a player held before the
queue.

### The hardware Canvas

With decoding off the presenting path, the Compose Canvas can take hardware
frames, on Android 14 (API 34) and later. An output that sets
`FFPLAYKMP_OUTPUT_PREPARE_HARDWARE_FRAMES` owns the Surface MediaCodec renders
to, an `ImageReader`'s (`GpuBufferImages` in `bindings`, the same class
`VideoOutput.GpuBuffers` uses, on its existing handler thread). The player
renders each MediaCodec frame into it as it is queued, stamped with its
presentation time; PREPARE waits, bounded, for the image that carries it, PRESENT
flips to its `HardwareBuffer` and RELEASE closes it. The reader holds
`canvasFrames` images. With fewer than `frameQueueSize` + 1 the output tells the
player, in bits 24 to 30 of its flags, to queue only as many hardware frames as
the other images hold; with one image a frame waits rendered in the reader's
queue and is taken at PRESENT, after the image on screen went back. Until the stream is known the output takes
frames in memory, and it takes from GPU memory only what it can draw as the
conversion would:

- BT.709 SDR, wrapped as sRGB;
- PQ and HLG, through an AGSL shader that follows the CPU converter step by step:
  decode in the source's primaries, BT.2390's EETF on the brightest component to
  SDR white at the clip's peak (from the stream's HDR metadata, not per frame),
  sRGB encode, then the converter's own gamut lattice
  (`ffmpegkmp_frame_gamut_map`, 65 slices tiled into an F16 bitmap, one per
  source primaries) looked up tetrahedrally. It assumes an sRGB canvas.

SDR in Display P3 or BT.2020 primaries stays in software: `NativeGpuBuffer`
carries the primaries and such a buffer is wrapped in its own colour space, but
HWUI's mapping of it into an sRGB canvas differs from the conversion by up to
16 8-bit codes on flat areas (measured on an S25), so the Canvas converts those.
Which path a player uses shows in `FFplayOutputInfo`.

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
of the `thread_type` asked for and leaves hardware decoders at one. 
`DecoderThreads.Auto` is FFmpeg's own automatic count and thread types for both
(the browser caps the count at its worker's pool); `Fixed(count, types)` sets
both knobs, and the player's default is two frame threads. A converter's worker
pool has as many threads as its video's decoder was given, one per core for
`Auto`. Both open their decoder
through one helper, `ffplaykmp_video_codec_open_preferred`, which applies the
preference and the `AUTO` rule once: a hardware decoder that fails to open, or
that has failed for this source before, gives way to software with the reason
reported. A `NamedCodec` opens exactly that decoder with its AVOptions,
never falling back. On Android the MediaCodec decoder is found by
codec id among FFmpeg's decoders with the `mediacodec` wrapper
(`av_codec_iterate`), not by appending `_mediacodec` to the first decoder's
name, which with libaom built in was `libaom-av1` and left AV1 in software. A
hardware decoder also takes a place there in the process's hardware decoder budget
(`ffplaykmp_decoder_budget_set`, codec's `DecoderBudget`), which
`ffplaykmp_video_codec_close` gives back, so players and `VideoDecoder`s share
one count. With every place taken the open returns `FFPLAYKMP_ERROR_BUSY` at
once, which AUTO turns into a software decoder reporting
`FFPLAYKMP_DECODER_FALLBACK_BUDGET` and REQUIRE_HARDWARE into a failure; nothing
waits for a place.

Decoded frames reach Kotlin as frame handles (`ffmpegkmp_frame.c`): each
`VideoFrame` holds one reference to an FFmpeg `AVFrame`, so a frame is never
copied into a Kotlin array on its way anywhere, and its memory goes back to its
pool when its last reference closes. `ffmpegkmp_video_decoder_frame_at` hands out
a new reference to the frame it presents: with a `VideoOutput.Memory` format,
the decoder's thread converts each frame once into a frame from the decoder's
pool; without one, frames go out as decoded, and hardware frames downloaded.
A decoder's pool is a ring of three frames per layout and size
(`ffmpegkmp_frame_pool_alloc(3)`), made once: while all three are out, the
hand-out fails with `FFPLAYKMP_ERROR_RING_FULL` after a short grace for one to
close, and the decoder stays usable. The process-wide pool and the writer's
have no bound.
On Android, `Memory` output opens MediaCodec without a Surface for 8-bit
sources, so it decodes into memory (ByteBuffer mode) and FFmpeg copies each
frame out as NV12 or YUV420P; deeper sources, whose P010 output FFmpeg does not
copy, decode in software. The FFplay player's Canvas takes MediaCodec's frames
from GPU memory instead (see above). `VideoDecoder.resize`
(`ffmpegkmp_video_decoder_set_size`) changes a `Memory` output's size on the open
decoder: frames are converted as they are handed out, so the next one is the
decoded frame converted again at the new size, with nothing decoded or sought,
and `ffmpegkmp_frame_pool_trim` frees the old size's ring.
`VideoDecoder.extractFrame` builds on it: it opens a decoder, resizes it to the
size the box needs, takes one exact frame and turns it upright with libavfilter
(`ffmpegkmp_frame_upright`: `transpose`, `hflip` and `vflip` chosen from the
display matrix as FFmpeg's autorotate does, and a `CROP` crop in the same graph),
on one thread.
Pools are keyed by layout and size, pooled AVBuffers by default. The
Kotlin/Native Apple runtimes (`FFMPEGKMP_PIXEL_BUFFER` in `bridge.mk`; the JVM
bridge does not link CoreVideo) pool `NV12`, `P010`, `BGRA8` and `RGBA_F16`
frames in IOSurface-backed `CVPixelBufferPool`s, read VideoToolbox's buffers in
place by locking them, and hand out every frame as decoded as a `CVPixelBuffer`:
VideoToolbox's own, or a software frame copied into a pooled NV12 or P010 one in
its own colour. The FFplay Canvas fallback gets the player's frames as decoded
the same way, and `ffplay`'s `FrameImage` converts a frame once, at PREPARE,
straight into one of its ring's reused Skia or Android `Bitmap`s' own memory.

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
or HLG to SDR. The tone map keeps the source's primaries, and its gamut map
then compresses colours outside the destination's into it, as swscale's
perceptual intent does: the converter samples that mapping through swscale once
per pair of primaries and SDR curve, on a lattice of SDR codes with
swscale's own 65 points per side, and looks each pixel up in it by tetrahedral
interpolation as swscale does, which keeps greys grey.
Through swscale itself every frame would go through a 3D table that swscale
remakes with every change of the destination's size, about half a second on a
phone, which stalled a resized player; the lattice reproduces swscale's own
output to within a few hundredths of an 8-bit code (`scripts/test-converter.sh`). Its
intermediate is 16-bit planar RGB, 6 bytes a pixel. The tables are built on
first use, shared by every converter and kept for the process: a gamut map,
3.3 MB (one or two in a typical process, at most twelve), is made outside the lock the converters share, so a new one holds up no
other video. swscale takes YUV
deeper than 8 bits through 16 bits, where limited range spans 219 × 257 codes
rather than BT.2100's 219 × 256, so 10-bit white would land on 943 instead of
940; the transfer step scales its RGB side by 256/257 into swscale and back out
of it, which gives BT.2100's codes in both directions. A destination of
another size, such as a `Memory` output with a `FrameSize`, is scaled into by
the same swscale call, bilinearly; on the HDR routes that is the first one, into
the intermediate, so the transfer step and the gamut map run at the smaller
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
track instead of spinning, and at `finish` a track whose encoder took frames and
produced no packets fails rather than leaving an empty track. Hardware encoders come first where the preference
allows, then software ones the build has. A `NamedCodec` skips that ranking: the
encoder is found by name, must encode the config's codec, and takes its options
with `av_opt_set` on the context after the writer has set the config's fields
and before `avcodec_open2`; an option that changes what the writer relies on
(size, pixel format, colour, global header) fails the track. A MediaCodec
component is pinned with FFmpeg's `codec_name` option, which the Surface-input
path below honours.

FFmpeg's MediaCodec wrappers are opened without `AV_CODEC_FLAG_GLOBAL_HEADER`:
asked for it, they make the header at open by encoding a dummy frame to the end
of stream and flushing the codec, and Google's Codec2 software encoders
(`c2.android.avc.encoder`) take frames after that flush but never give another
packet. Their first packet carries the parameter sets in band; the track starts
at that packet, its stream's extradata taken with `extract_extradata`, as a
packet track's is.

On Android 14 and later an 8-bit SDR H.264 or HEVC track on a hardware encoder
can drop FFmpeg's encoder for a platform `MediaCodec` with an input surface
(`SurfaceVideoEncoder` in `bindings`), when a `ComposeFrameRenderer` asks for it
before the first frame (`ffmpegkmp_writer_use_packets` turns the track into a
packet track). The renderer draws on the GPU into `HardwareBuffer`s from an
`ImageWriter` on that surface and `write` queues them with their pts, so no pixel
is copied; a thread of the encoder's own takes its packets and gives them to
`ffmpegkmp_writer_write_packet`, the codec-config buffer as extradata. Everything
else, and `VideoFrame`s from memory, keep the path above.

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
