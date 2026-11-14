# Changelog

All notable changes to FFmpegKMP are recorded in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0, a minor release
may break the public API; every break is listed here with a migration note.

## [Unreleased]

### Fixed

- The hardware Canvas's PQ and HLG frames no longer draw dark and dull in a wide-gamut or HDR window
  (`Window.colorMode` `COLOR_MODE_WIDE_COLOR_GAMUT` or `COLOR_MODE_HDR`). HWUI draws such a window in
  Display P3 or extended linear sRGB, and the GPU shaders took their samples and output as sRGB codes.
  They now read the frame's codes back with `toLinearSrgb` and hand their light to whatever canvas
  draws with `fromLinearSrgb`. On a canvas wider than sRGB, tone-mapped frames go through the
  converter's gamut map into Display P3 instead of BT.709, matching `VideoOutput.Memory` frames in
  Display P3 within 3 8-bit codes on an S25, as they match sRGB frames on an sRGB canvas.

## [0.6.0]

### Added

- The hardware Canvas also takes PQ and HLG streams and tone-maps them on the GPU the way the CPU
  conversion does (BT.2390 to SDR white at the clip's peak, then the converter's own gamut map into
  BT.709, looked up tetrahedrally), so posters, playback and export of one clip look the same. On an
  S25 it is within 3-4 8-bit codes of the software conversion. The peak comes from the stream's
  metadata, not from each frame, and the result assumes an sRGB canvas.
- Android 14+: the Compose Canvas output draws a hardware decoder's frames from GPU memory with no
  copy and no bitmaps. The player renders each MediaCodec frame into the output's own ImageReader
  as it is queued; until the stream is known, frames come in memory. `FFplayOutputInfo` reports
  the path in use (decoder HARDWARE with zeroCopy, or SOFTWARE), and a resize costs nothing. It
  takes BT.709 SDR streams; SDR in Display P3 or BT.2020 primaries stays converted in software on
  the Canvas, because Android's mapping of such a buffer into an sRGB canvas differs from the
  conversion by up to 16 8-bit codes. Older Android versions keep the software path.
- `FFplayPlayer.follow(clock: FFplayClock?)` drives a player from the app's clock, so several
  videos on one timeline show the same instant in the same display frame, each with its own offset,
  trim and loop. At each display frame the player asks `FFplayClock.positionAt(frameTime)` (the
  Compose frame time on a Canvas, the player's monotonic clock on a native surface) and shows the
  frame `VideoDecoder.frameAt` returns for that position. While following, the video decodes ahead
  in one run and is never paused per frame: a still position holds its frame. Going back, or moving
  more than 500 ms further than the frame time did, is a jump the player seeks for (a loop wrap is
  one seek); a decoder that falls behind skips to a keyframe without seeking. Positions before the
  start or past the end hold the first or last frame, silent. The audio plays while the position
  advances within 25 % of real time, judged over 100 ms, is never resampled, and goes silent when no
  frame has asked the clock for 200 ms. `play`, `pause` and `seekTo` throw `IllegalStateException`
  while following; `follow(null)` stops following and pauses. The browser cannot follow a clock.
  Not measured on a phone yet.
- `FFplayConfiguration.decoder` takes a `CodecSelection`: `NamedCodec(name, options)`
  makes the player open exactly that decoder, with no fallback, and a failure carries the decoder's
  reason. The player also honours `DecoderThreads.Fixed(count, types)`. The browser fails a
  `NamedCodec` as unsupported and ignores thread types.
- `FFplayConfiguration.frameQueueSize` (default 3, minimum 1): the player decodes ahead into a
  bounded queue of frames. A slot costs draw width × height × 4 bytes for software frames and one
  decoder output buffer for hardware frames. Nothing caps it: a MediaCodec decoder that gives its
  client fewer output buffers than the queue is long queues as many as it gives, and waiting for
  one back is not a stall. The browser ignores it.
- **Breaking renames:** `FFplayConfiguration.decoderPreference` is `decoder`; `FrameScale.FILL` is
  `CROP` (in Compose, "fill" stretches and "crop" covers); `FFplayOutputInfo.sourceColorSpace` and
  `outputColorSpace` are `sourcePrimaries` and `outputPrimaries`; `FFplaySurface(resolution =)` is
  `targetSize`, as `FrameImage.targetSize`.
- **Breaking:** `FFplayConfiguration.releaseDecoderWhenPaused: Duration?` is
  `decoderIdleRelease: Duration` (default 2 s); `Duration.INFINITE` keeps the decoder, where null
  did.
- **Breaking:** `FFplayRendererKind.GPU_TEXTURE`, which no output reported, is removed. The
  ffprobe `*Serializer` objects and `JsonObjectBackedSerializer` are internal (use
  `MediaInformation.serializer()` and the like), and `CommandLineTokenizer` needs the library's
  internal opt-in.
- `@ExperimentalFFmpegKmpApi` (opt-in, a warning) marks `FFplayPlayer.follow`, `FFplayClock` and
  `VideoDecoder.extractFrame`: they work as documented and have not been measured on devices
  yet. The thresholds the clock follower uses are no longer part of its documented contract.
- Fixed limits that stay: a `MediaWriter` takes at most 8 tracks, and 64 commands may wait in the
  command queue.
- `DecoderThreads.Auto` is FFmpeg's own automatic count and thread types everywhere, as
  `-threads 0`. Before, it meant slice threads capped at 8 in `VideoDecoder` and 2 frame threads
  in the player. The player's default is now written out,
  `FFplayConfiguration.threads = DecoderThreads.Fixed(2, setOf(DecoderThreadType.FRAME))`, and
  behaves as before on native platforms; in the browser the default is now 2 threads rather than
  the automatic count. `VideoDecoder.open` keeps `Auto` as its default, which now uses frame
  threads: faster, and one decoded picture per thread. Pass
  `DecoderThreads.Fixed(n, setOf(DecoderThreadType.SLICE))` for the former memory use.
- `VideoDecoder.open(ringSize = 3)`: how many of the decoder's frames the caller may hold at once
  (minimum 2), for memory frames and GPU buffers. The browser keeps 3.
- `FFplayConfiguration.catchUpLag` (default 500 ms, `Duration.INFINITE` never skips): how far
  behind its clock a frame may be decoded before the player skips forward to a keyframe. Each
  skip is reported as `FFplayEvent.CaughtUp`. The browser keeps 500 ms.
- Frame conversion follows the configured `threads` instead of a fixed pool of up to 8: a
  `VideoDecoder`'s frames and a player's frames converted for the Canvas use as many threads as
  the decoder was given, and one per core under `DecoderThreads.Auto`.
- `FFplayConfiguration.canvasFrames` (default `frameQueueSize` + 2, minimum 1): how many frames
  the Compose Canvas keeps ready to draw, bitmaps for frames in memory and GPU buffers for a
  hardware decoder's. Fewer saves memory: below the default the frame shown before goes back at
  once; with fewer than the queue's frames + 1, queued frames are converted as they are shown, on
  the drawing thread, and a hardware decoder queues only as many frames as there are buffers; at
  1 each frame is written over the one on screen.
- Every published module is compiled in Kotlin's explicit API mode, and its public ABI (JVM,
  Android and klib targets) is recorded in committed dumps under the module's `api/` directory.
  `check` fails when the ABI changes without the dumps being updated.
- `VideoDecoder.resize(size: FrameSize?)` changes the size of the frames an open decoder hands
  out, without closing and reopening it; `null` goes back to the source's size. The next frame, at
  the same position too, is converted again from the decoded frame at the new size, with no
  decoding and no seeking, and is identical to what a decoder opened with
  `VideoOutput.Memory(format, size)` returns. Frames already handed out keep their size and pixels
  until closed, and `frames()` may deliver its one frame decoded ahead at the old size. The decoder
  frees its pooled frames of the old size, so a decoder that is resized often holds only the
  current size's frames plus those the caller still holds. It throws
  `UnsupportedOperationException` for `VideoOutput.GpuBuffers`, whose frames the GPU scales as it
  draws them, and in the browser, where it is not implemented yet.
- `VideoDecoder.extractFrame(source, position, size, scale, decoder, timeout)` returns one frame as
  a poster or thumbnail: RGBA8 sRGB with HDR tone-mapped, upright, square pixels, never enlarged.
  `size` is a box in upright pixels; `FrameScale.FIT` fits inside it and `FrameScale.CROP` covers it
  and cuts the centre. The upright step applies the display matrix with libavfilter as FFmpeg's own
  tools autorotate, mirrors included, and the result reports rotation 0. The decoder is resized
  before anything is converted, so a 4K or HDR source is tone-mapped and scaled once, straight to
  the thumbnail size. `timeout` bounds the call: under `CodecPreference.AUTO` an attempt that
  timed out or failed on a hardware decoder is retried once in software within the same timeout
  (the first attempt gets half of it). On a timeout or cancellation the frame and the decoder are closed. The browser
  throws `UnsupportedOperationException`.
- `DecoderThreadType { FRAME, SLICE }` and `DecoderThreads.Fixed(count, types)`: FFmpeg's
  `thread_type` next to its thread count, for `VideoDecoder` on Android, JVM and Apple (the browser
  ignores the types). Each frame thread holds one decoded picture and reads one packet further
  ahead.
- `NamedCodec(name, options)` opens exactly one FFmpeg encoder by its `-c:v` name with its
  AVOptions, with no ranking and no fallback, so an app can run its own series of tries. A
  MediaCodec component is pinned with FFmpeg's `codec_name` option, for example
  `NamedCodec("h264_mediacodec", mapOf("codec_name" to "c2.android.avc.encoder"))` for Android's
  software AVC encoder; libaom's speed is set with its own options, for example
  `NamedCodec("libaom-av1", mapOf("usage" to "realtime", "cpu-used" to "8"))`. Options are applied
  after the config's fields, so they override them (`b`, `g`, `profile`, …); an option that changes
  the size, pixel format, colour or the container's global header fails the track. `addVideoTrack`
  fails with a `MediaWritingException` giving the reason for an unknown encoder name, an encoder
  for another codec, unknown options (each named), an option that is not a video encoding option,
  a value the encoder does not take, or a failed open; `MediaWriter.canEncode` answers false for
  each. On Android the zero-copy Surface path keeps a pinned MediaCodec component. In the browser a
  `NamedCodec` fails as unsupported, since WebCodecs cannot open FFmpeg's encoders.
- `VideoDecoder.open(decoder = NamedCodec(name, options))` and
  `VideoDecoder.extractFrame(decoder = NamedCodec(...))` open exactly that FFmpeg decoder with its
  AVOptions and no fallback; `extractFrame` makes one attempt. An unknown name, a decoder for
  another codec, unknown options (each named), an option that is no decoding option, or a bad value
  fails with a `VideoDecodingException` giving the reason. A MediaCodec decoder named this way
  counts as hardware and takes a `DecoderBudget` place. The browser fails a `NamedCodec` as
  unsupported.
- `CodecSelection`, the sealed interface `CodecPreference` and `NamedCodec` implement.

### Changed

- Build: Android binaries are built with the stable NDK r30 (`30.0.16248370`, was r30 beta 2) and
  libaom 3.15.3 (was 3.14.1). Kotlin 2.4.21, AGP 9.5.1, Gradle 9.9.1, kotlinx-serialization 1.13.0
  and Compose Multiplatform 1.12.2. FFmpeg stays at n9.0.2.
- `VideoOutput.GpuBuffers` frames of Display P3 or BT.2020 SDR sources are drawn in those primaries
  with the sRGB curve, instead of as sRGB (they used to show the wrong primaries). Android's mapping
  into an sRGB canvas still differs from the software conversion by up to 16 8-bit codes.
- Android: the player bounds its waits on MediaCodec (50 ms), so a stalled codec cannot hang it and
  a packet the codec could not take yet is retried instead of dropped. Hardware buffers going to a
  Surface are handed over about two display frames ahead with their due time as the render
  timestamp, instead of being rendered when a timer found them due; on an S25 that brought repeated
  frames on a Surface from 9-11 per 10 s down to 1, the same as Media3. A codec that takes no packet
  and gives no frame for 5 s fails the run with that reason (under `AUTO`, before the first frame,
  it falls back to software).
- **Breaking behaviour:** a player decodes on 2 frame threads by default instead of up to 8
  threads, which bounds memory when several videos play at once (each frame thread holds one
  decoded picture). Migration: pass another `DecoderThreads.Fixed(n)`, or `Auto` for FFmpeg's.
- **Breaking:** `FFplayConfiguration.decoder` is a `CodecSelection`; code that passes a
  `CodecPreference` compiles unchanged, code that reads it back needs a `when`.
- The player opens its decoder with the same preference and fallback rule as `VideoDecoder` (one
  helper instead of four copies).
- The Compose Canvas output converts each frame as it is queued, into a reused ring of
  `frameQueueSize` + 2 bitmaps, so showing a frame is a flip and no bitmap is allocated after
  warm-up. It refreshes the player once per display frame from the composition's frame clock, one
  display frame ahead; other outputs keep a timer. A resize while playing converts new frames at
  the new size with no flush, and a paused resize redraws the frame on screen at the new size. On an
  S25 at 120 Hz, wrong-cadence frames per 10 s fell from 31-48 to 1-2, the HLG clip's 400 ms start
  stall and its drops are gone, and four 13 MP videos at once drop no frames. Known cost: with one
  decoder thread, a 13 MP video's conversion now shares that thread and falls behind (27.7 fps).
- When a decoded video frame is more than 500 ms behind the clock, the player skips forward to the
  first keyframe at or after the clock, with no seek events and no preview, and counts the frames
  passed over as dropped; the frame it lands on is shown when the clock reaches it. A source with no
  later keyframe decodes on. Audio has its own reader and keeps playing. 8K on one software thread
  on an S25 now plays through (13.6 fps shown) where it used to time out.
- The player shows frames with a port of ffplay's `video_refresh` instead of sleeping on the
  decoding thread: each refresh presents the newest frame that is due and drops older queued ones,
  counted in `droppedFrames`. A decoder that falls behind no longer drops nearly every frame (S25,
  8K on two threads: 28.6 fps shown against 14.3; 13 MP on one thread: 297 frames shown against 1).
  ffplay's decoder-side early drop is deliberately not ported: it freezes a decoder slower than real
  time.
- **Breaking:** `VideoEncoderConfig.encoder` is a `CodecSelection` instead of a `CodecPreference`;
  the default is still `CodecPreference.AUTO`. Migration: code that passes a `CodecPreference`
  compiles unchanged; code that reads `config.encoder` as a `CodecPreference` needs a `when`
  (`is CodecPreference` / `is NamedCodec`).
- `VideoTrack.isHardware` for a named `*_mediacodec` encoder reports Android's answer for its
  pinned or default component, and a track reports bit rate 0 when an option leaves the rate to the
  encoder.
- **Breaking:** `MediaWriter.finish()` throws `MediaWritingException` ("encoder X took N frames
  and produced no packets") when a track's encoder took frames and produced no packets, instead of
  writing an output with an empty track. It is checked on JVM, Android and Apple, not in the
  browser. A track never given a frame finishes as before, and MP4 leaves it out of the file.
- HDR-to-SDR frames are gamut-mapped through a table sampled from swscale's own mapping, on its 65
  points per side and interpolated tetrahedrally as swscale does, instead of a swscale pass per
  frame. Output matches the previous conversion to within a few hundredths of an 8-bit code. Each
  pair of primaries and SDR curve keeps a 3.3 MB table for the life of the process (usually one or
  two, at most twelve).
- **Breaking behaviour:** `DecoderThreads.Fixed(n)` allows frame and slice threading, as `ffmpeg`
  does; `VideoDecoder` used to decode on slice threads only. Migration: to keep one frame in
  progress and the memory of before, use `DecoderThreads.Fixed(n, setOf(DecoderThreadType.SLICE))`.
- Under `CodecPreference.AUTO`, a hardware decoder that fails to open falls back to software at
  once and reports `DecoderFallbackReason.FAILED`; before, only a failure in the first decode did.
- The `decoder` parameter of `VideoDecoder.open` and `extractFrame` is a `CodecSelection`; code that
  passes a `CodecPreference` compiles unchanged. The JVM signatures change, so code compiled
  against 0.5 must be recompiled.
- The size given to `VideoOutput.Memory(size = …)` is no longer fixed for the decoder's lifetime.
  Migration: call `decoder.resize(newSize)` instead of opening another decoder to change size.

### Removed

### Fixed

- `MediaWriter`: a track on Android's software H.264 encoder,
  `NamedCodec("h264_mediacodec", mapOf("codec_name" to "c2.android.avc.encoder"))`, timed out with
  "it took input without giving output": FFmpeg makes a MediaCodec encoder's global header with a
  dummy encode and a flush at open, after which Codec2's software encoder gives no more packets. The
  writer now opens FFmpeg's MediaCodec encoders, hardware ones too, without asking for a global
  header and takes the MP4's parameter sets from the first packet.
- Android picked its MediaCodec decoder by guessing a name from FFmpeg's first decoder for the
  codec. With libaom built in, AV1's first decoder is `libaom-av1`, so the guess found nothing and
  AV1 always decoded in software. The decoder is now found by codec id, so AV1 decodes in hardware
  where the device has a decoder for it, in `VideoDecoder` and in the player. Not yet run on a
  phone.
- Changing the output size of an HDR video tone-mapped to SDR no longer stalls: the gamut mapping
  is sampled once per colour pair and process rather than rebuilt for every size. The first frame at
  a new size costs about 5 ms instead of about 400 ms on a desktop host; per-frame cost at a fixed
  size is within about 10 % of before (6.6 against 6.1 ms for 1080p).

## [0.5.0]

See the [GitHub release](https://github.com/afTrolle/FFmpegKMP/releases/tag/v0.5.0).

[Unreleased]: https://github.com/afTrolle/FFmpegKMP/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/afTrolle/FFmpegKMP/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/afTrolle/FFmpegKMP/releases/tag/v0.5.0
