# Codec

`codec` holds what FFmpegKMP's decoders and players share, without Compose, so an exporter or a
plain JVM server can decode video without depending on it:

- the frame model: `VideoFrame`, one reference to a frame whose memory FFmpeg owns, described by a
  `FrameFormat` (a `PixelLayout` and a `FrameColor`), with its pixels read through `FramePlane`s
  of `FrameBytes`;
- `VideoDecoder`, frame-accurate pull decoding;
- `MediaWriter`, encoding video and audio tracks into an MP4, with the export's
  `DynamicRange` (`SDR`, `HDR10` or `HLG`) deciding every format along the way;
- `MediaSource`, a path, URL or mounted `CommandIo` input;
- `DecoderPreference` (`AUTO`, `REQUIRE_HARDWARE`, `SOFTWARE`), the `DecoderKind` that actually took
  a source, and `DecoderThreads`, the software decoder's threads;
- `VideoInfo`, a stream's size, aspect ratio, rotation, pixel format, bit depth and its colour as
  the same `FrameColor` a frame carries, with the `HdrMetadata` it carries: HDR10's mastering
  display and content light levels, which an HDR10 export takes, and Dolby Vision and HDR10+ as
  flags. Whether a stream is HDR, and which kind, follows from its `color.transfer` alone.

`ffplay` depends on `codec` and adds `VideoFrame.toImageBitmap()` for Compose. It uses the
`codec` types (`MediaSource`, `VideoInfo`, `DecoderPreference`) as they are. Binaries built against
0.2 need recompiling.

`codec` publishes every target the other library modules do, including tvOS and watchOS.

## Frames

A `VideoFrame` is shown from `pts` for `duration`, and reports its `rotationDegrees` and
`sampleAspectRatio` without applying them. Its `format` says how its memory is laid out and what
colour it holds:

| Preset | Layout | Colour |
| --- | --- | --- |
| `FrameFormat.Rgba8` | `RGBA8` | `FrameColor.Srgb` |
| `FrameFormat.RgbaF16` | `RGBA_F16` | `FrameColor.LinearExtendedSrgb`: linear light, 1.0 at 203 nits (BT.2408), values beyond 0..1 kept |
| `FrameFormat.Nv12` | `NV12` | `FrameColor.Bt709` |
| `FrameFormat.P010Hdr10` | `P010` | `FrameColor.Bt2020Pq` |
| `FrameFormat.P010Hlg` | `P010` | `FrameColor.Bt2020Hlg` |

The layouts are `RGBA8`, `BGRA8`, `RGBA_1010102`, `RGBA_F16`, `NV12`, `P010`, `YUV420P` and
`YUV420P10`; RGB layouts take `ColorMatrix.RGB` and YUV ones a YUV matrix (`BT709`, `BT2020_NCL`
or `BT601`). `format` is null for a frame in GPU memory (Android's `GpuBuffers` output), and for
one decoded in another layout, such as 4:2:2, whose planes `usePlanes` still reaches. Colour values
FFmpeg has but the enums do not name are reported as the converter takes them: unknown primaries as
BT.709, SDR curves as BT.709, an unspecified YUV matrix as BT.709. `VideoInfo.color` reads a
stream's colour the same way, so decoding a frame as it is gives the colour `info` reports.

Each `VideoFrame` is one reference to its memory:

- `close()` releases it; the memory goes back to its pool when the last reference closes. Closing
  twice does nothing.
- `retain()` makes another reference to the same memory, which needs a `close()` of its own.
- A function that takes a frame takes its reference; `retain()` first to keep using it.
- `usePlanes { planes -> … }` reads the pixels in place, one `FramePlane` (`bytes`, `rowBytes`,
  `rows`) per plane. The views are valid only inside the block; `FrameBytes` read after it throw.
  `FrameBytes` is a direct `ByteBuffer` on JVM and Android (`asByteBuffer()`), a `CPointer` on
  Kotlin/Native (`pointer`), and the frame's bytes in the page in the browser.
- Using a closed frame throws `IllegalStateException`; closing a frame while another thread is inside
  `usePlanes` releases the memory once the block returns.

Frames change format in one place, the bridge's converter (see
[Architecture](../../docs/architecture.md)):

- `convert(to)` makes a new pooled frame in one threaded pass, or returns a `retain()` when the
  frame already is in `to`. HDR survives wherever the target can hold it: PQ or HLG into
  `RgbaF16` keeps highlights above 1.0 and the negative components of colours outside sRGB, and
  into an SDR format is tone mapped. SDR curves convert as they are shown, so BT.709 video becomes
  sRGB without a curve change.
- The tone map is BT.2390's EETF with SDR white, 203 nits, as its target peak, on each pixel's
  brightest component so colours keep their hue. Shadows and mid-tones keep their nits (100 nits
  comes out at 73% of white, as on an HDR screen) and highlights roll off to white at the clip's
  peak: the lower of its MaxCLL and its mastering display's peak, or 1,000 nits when it gives
  neither. A 1,000-nit master puts reference white at about 90%, a 4,000-nit one at about 82%.
  Colours outside BT.709 are then compressed into it with swscale's perceptual mapping rather than
  clipped. Decoder frames, `convert`, the player's Compose canvas and SDR exports all share it.
- `convertInto(target)` converts into memory another frame owns, such as a render target or an
  encoder input; the target keeps its format and size. A target of another size is scaled into,
  bilinearly in the same pass, except from one `RgbaF16` frame into another.

Frames come from pools keyed by layout and size, which decide where the memory lives: pooled
AVBuffers, and on Apple, for `NV12`, `P010`, `BGRA8` and `RGBA_F16`, IOSurface-backed buffers from a
`CVPixelBufferPool`. Those frames, and VideoToolbox's own, have a `VideoFrame.cvPixelBuffer`
(Apple source sets), which Metal and VideoToolbox take without a copy.

## Frame-accurate decoding

`VideoDecoder` returns the frame shown at a position, for exporters and tests that walk their own
clock. It has no clock or scheduler and never drops a frame: each call decodes on the decoder's own
thread (MediaCodec sessions are bound to one), outside the command FIFO.

```kotlin
VideoDecoder.open(MediaSource("clip.mp4"), VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
    decoder.seekTo(trimStart)
    for (tick in 0 until ticks) {
        decoder.frameAt(trimStart + tick.seconds / 30).use { frame ->
            frame.usePlanes { planes -> encode(planes.single()) }
        }
    }
}
```

The frame at position s is the decoded frame with the largest pts <= s, held until the next frame's
pts; before the first frame the first is returned and past the end the last is held. Positions
count from the input's start time, as FFplay and `-ss` do, and match a pts to the nearest unit of
the stream's time base, which absorbs the container's own timestamp rounding (Matroska stores
milliseconds). A frame's end is the next frame's pts, found by decoding one frame ahead, so
variable frame rates are exact. `frameAt` returns the current frame without decoding or converting
it again while it covers the position, decodes forward to a later one, and seeks to the keyframe
before an earlier one.

Each `frameAt` returns a new reference, which the caller closes; calls the same decoded frame covers
share its memory. Frames stay valid past the decoder's next call and past `close()`, so a frame
can be drawn or encoded while the next one decodes.

A decoder hands out its frames from a ring of three per layout and size, made once and handed out
again as they close, so nothing allocates per frame: the frame the caller works on, the one decoded
ahead, and one so the caller can keep the previous frame while taking the next. Holding more than
three frames from one decoder makes the next `frameAt` wait for one of them to close, and at the
decoder's timeout fail, which leaves the decoder timed out: a frame that is never closed stops the
decoder rather than growing its memory. To keep more, keep conversions into another format
(`convert(format)`), which copy into the process-wide pool, which has no bound; a `retain()` shares
its frame's place in the ring, so it costs no other place but keeps that one taken. The ring
covers:

- `Memory(format)`, on every native platform: the frames the decoder's thread converts into.
- `Memory()` on Apple, whose software frames are copies into pooled `CVPixelBuffer`s.

Elsewhere `Memory()` hands out avcodec's own buffers, which avcodec reuses, or a hardware frame read
into memory of its own; VideoToolbox's frames are VideoToolbox's. A `GpuBuffers` output's ring is the three images of its `ImageReader` (see below).
The browser copies each frame into page memory and has no ring.

### Frame flows

`frames()` walks a video as a `Flow`, decoding ahead of its collector on the decoder's thread:

```kotlin
decoder.frames(from = trimStart, until = trimEnd, step = FrameStep.Rate(FrameRate(30))).collect { frame ->
    frame.use { encode(it) }
}
```

`FrameStep.Decoded`, the default, delivers each decoded frame once. `FrameStep.Rate(rate)`
delivers the frame shown at each frame time of `rate`, as a constant-rate export samples the video,
so a frame that covers several ticks comes once for each. `Rate` computes every tick from its
fraction, so 30 fps or 30000/1001 stays exact over any length, where `1.seconds / 30` rounds to a
nanosecond and drifts by one every three frames. The flow ends at `until` or at the end of the
video. One frame is decoded ahead while the collector works, and a frame it never takes, because it
stopped early or failed, is closed. That keeps the next frame ready while the collector works on
the current one, and leaves two frames of the ring for the collector's own.

On the JVM, with a collector that spends 7 ms on each of 72 frames of 1080p MPEG-4 Part 2 into
`Rgba8`, which decode well within that, the collector finds most frames already decoded, waiting
6 to 44 ms in all while other work loads the machine, against about 200 ms with no decoding ahead.
A decoder slower than its collector gains nothing beyond one frame ahead: 4K H.264 written as one
slice a frame decodes in about 29 ms, and the same collector waits 2.15 s with none ahead and
1.42 s with one.

### Concurrency and cancellation

The decoder's calls run one at a time on its thread; calls from several coroutines queue in order.
Cancelling a call stops its native work instead of letting it run to its end. The call returns at
once, and the next call waits for the interrupted work to unwind, then seeks to its own position,
so `positions.collectLatest { decoder.frameAt(it) }` drops stale work instead of queueing it. On
the JVM and Android the decoder's thread is interrupted as well, which ends an Okio read the call is
blocked in; elsewhere such a read has to return on its own first.

- `VideoOutput.Memory(format)` converts each frame into a pooled frame of `format` on the decoder's
  thread, so conversion overlaps with the caller's work. `FrameFormat.Rgba8` tone maps PQ and HLG
  to BT.709, and `FrameFormat.RgbaF16` keeps HDR, HLG shown as on a 1000-nit display.
- `VideoOutput.Memory(format, FrameSize(960, 540))` also scales each frame to that size, bilinearly
  and in the same pass, so a 4K source shown as a small tile converts, and tone maps, at the
  small size. On an M4 Pro a 4K PQ frame converts into a 960x540 `Rgba8` frame in 1.9 ms, against
  23.6 ms at full size. SDR is swscale alone and fast either way, 0.4 ms scaled against 0.2 ms at
  4K, so there the gain is the smaller frame everything after the decoder handles. The source
  still decodes at its own size, and hardware frames, VideoToolbox's included, are read into memory
  before they scale. Frames keep their rotation and report the sample aspect ratio of the new size,
  so an anamorphic source scaled to its display aspect comes out with square pixels. A size needs a
  format and is even for the 4:2:0 layouts; to change it, open another decoder.
- `VideoOutput.Memory()`, with no format and the default, hands out frames as decoded, in the
  source's own layout and colour, and hardware frames downloaded. On Apple every frame is then a
  `CVPixelBuffer`, whatever the source's bit depth: VideoToolbox's own, or for software decoding a
  pooled IOSurface-backed, Metal-compatible NV12 (`'420v'`/`'420f'`) buffer for 8-bit sources,
  P010 (`'x420'`/`'xf20'`) for deeper ones such as HDR10, or BGRA for RGB, in the source's range
  and colour (4:2:2 and 4:4:4 are subsampled). Its colour attachments match the frame's format.
- On Android, `Memory` output decodes 8-bit sources with MediaCodec straight into memory (its
  ByteBuffer mode, as NV12 or YUV420P), with the software decoder as the `AUTO` fallback. Deeper
  sources such as HDR10 decode in software, because MediaCodec's memory output would drop their
  precision, so `REQUIRE_HARDWARE` fails on them.
- `VideoOutput.GpuBuffers` (Android 14, API 34, and later) has MediaCodec decode each frame into GPU
  memory, a `HardwareBuffer` from an `ImageReader` the decoder keeps: `frame.hardwareBuffer`, with
  the picture in `frame.hardwareBufferCrop`, since the buffer can be larger. Such a frame has no
  CPU-visible pixels: its `format` and `usePlanes` are null and `convert` fails. The reader's three images are the decoder's ring, and a frame's buffer
  goes back to MediaCodec when its last reference closes. The decoder keeps the latest frame's, so
  the same position again gives the same buffer, and `frames()` holds its one frame ahead, which is
  that latest frame: the caller may hold two more, which is what ffplay's `FrameImage`, which draws
  these frames with no copy, holds. So `frames()` into one `FrameImage` uses exactly the three. Holding more makes the next `frameAt` that needs a new frame wait, and at
  the timeout fail; these frames cannot be converted, so hold fewer.
  Sources deeper than 8 bits, and sources no MediaCodec decoder takes under `AUTO`, come in memory
  as decoded and `decoderKind` reports `SOFTWARE`. Elsewhere, and before Android 14, `open` fails
  with an `IllegalArgumentException`.

```kotlin
VideoDecoder.open(MediaSource("clip.mp4")).use { decoder ->
    decoder.frameAt(position).use { frame ->
        val buffer = frame.cvPixelBuffer!! // Apple: for CVMetalTextureCacheCreateTextureFromImage
    }
}
```

Rotation and sample aspect ratio are reported on `info` and every frame, never applied to pixels.

### In the browser

FFmpeg demuxes in a worker of the decoder's own and WebCodecs decodes there. Frames are counted as
the native decoder counts them, from the stream's own timestamps, so positions, `frames()` and
seeking behave the same. Differences:

- Only mounted inputs (`CommandIo`) decode: the worker takes the whole input as bytes.
- Frames come as decoded, typically `YUV420P` or `NV12`, or as `Rgba8` or `Bgra8` in sRGB or
  Display P3, which WebCodecs converts to. Other formats, `RgbaF16` included, fail at `open`.
  Each frame is copied once, into a buffer the worker hands the page, and on Kotlin/Wasm once
  more into Kotlin's heap; `convert` works between
  RGBA8 frames only.
- A `Memory` size scales `Rgba8` and `Bgra8` by drawing each frame into a canvas of that size,
  with the canvas's high-quality smoothing, so the pixels differ slightly from the native bilinear
  scale.
- `decoderKind` is `UNKNOWN`: WebCodecs takes a preference but does not say which decoder it
  chose, so `REQUIRE_HARDWARE` fails and `SOFTWARE` asks for a software decoder.
- A browser without WebCodecs fails at `open`; there is no Wasm software fallback yet.

### Decoder threads

`threads` on `VideoDecoder.open` and on `FFplayConfiguration` sets how many threads a software
decoder gets:

- `DecoderThreads.Auto`, the default, is FFmpeg's automatic count (one more than the CPU cores)
  capped at 8.
- `DecoderThreads.Fixed(count)` uses exactly `count`; `Fixed(1)` decodes on the decoder's own
  thread.

A `VideoDecoder` decodes each video with one decoder and one frame in progress, because in a
composite of several videos memory matters more than one video's speed. It uses FFmpeg's slice
threads, never its frame threads, and the count is the budget for every thread working on that
video's frames: converting them, on any decoder, takes at most as many. Slice threads split what
the stream lets them split:

- HEVC with wavefront rows, x265's default, keeps most of its speed. HEVC recorded on phones often
  has none, and then decodes as if on one thread.
- H.264 written as one slice a frame, as most encoders write it, decodes as if on one thread.
- Hardware decoders (MediaCodec, VideoToolbox and the desktop hwaccels), which ignore the count,
  and libaom, which threads AV1 its own way, are unaffected.

The player keeps frame threading as well, as `ffplay` does, since it shows one video in real time:
it decodes several frames at once and keeps about one frame in flight per thread, roughly 24 MB
each for 4K 10-bit video.

Threading changes how far ahead the decoder reads, not what it returns: `frameAt`, `seekTo`, the
end-of-stream hold, the AUTO fallback and the timeouts behave the same for every count, and the
software fallback under AUTO gets the same threads. `scripts/bench-video-decoder.sh` measures both
settings, and its `parallel` mode several decoders at once.

### Timeouts

`open(..., timeout = 10.seconds)` bounds `open`, `seekTo` and `frameAt` each. A call that runs out
of time, whether its input stalls or a MediaCodec decoder takes input and never outputs, throws a
`VideoDecodingException` ("… timed out after …"); the decoder is then unusable, and `close()`
returns promptly and releases the codec once the stuck call has unwound. Under `AUTO` a hardware
decoder that fails or times out before its first frame is replaced once by the software one, with
a timeout of its own, and `decoderKind` reports `SOFTWARE`. FFmpeg's I/O and decoding stop at the
deadline by themselves; only a mounted `Source` blocked in `read` needs the watchdog, which on the
JVM and Android interrupts the thread (ending an Okio `Pipe` read) and elsewhere leaves the read to
finish on its own. A frame decoded for a call nobody waits for any more is closed.

## Encoding

`MediaWriter` encodes tracks into one output on FFmpeg's libraries directly, alongside commands,
decoders and other writers. An export chooses its dynamic range and bit depth once, and each
track reports the formats that follow from them:

```kotlin
MediaWriter.open(MediaOutput.File("export.mp4")).use { writer ->
    val track = writer.addVideoTrack(VideoEncoderConfig(3840, 2160, FrameRate(30), VideoCodec.HEVC, DynamicRange.HDR10))
    VideoDecoder.open(source, VideoOutput.Memory(track.config.canvasFormat)).use { decoder ->
        decoder.frames(step = FrameStep.Rate(FrameRate(30))).collect { track.write(it) }
    }
    writer.finish()
}
```

| | `SDR`, 8-bit | `SDR`, `bitDepth = 10` | `HDR10` | `HLG` |
|---|---|---|---|---|
| `config.canvasFormat`, what to draw into and decode to | `Rgba8`, sRGB | `RGBA_1010102`, sRGB | `RgbaF16`, linear, 1.0 at 203 nits | `RgbaF16` |
| `inputFormat`, what the encoder takes | NV12 (or YUV420P), BT.709 | P010 (or YUV420P10), BT.709 | P010 (or YUV420P10), BT.2020 PQ | the same with HLG |
| Codecs | H.264, HEVC, AV1 | HEVC Main10, AV1 | HEVC Main10, AV1 | HEVC Main10, AV1 |

`bitDepth` is 8 or 10, or null, the default, to follow the dynamic range: SDR is 8-bit, HDR 10-bit
and takes 10 only. 10-bit SDR keeps the gradients smooth that 8 bits band; `canEncode` answers whether
an encoder takes it, as for HDR.

- `write(frame)` takes ownership of the frame and suspends only while its encoder is full, two
  frames deep. A frame in `inputFormat` goes to the encoder as it is; another, such as one in
  `config.canvasFormat`, is converted once on the track's thread, so conversion overlaps with drawing the
  next frame. Each track encodes on a thread of its own, since MediaCodec binds an encoder to one.
  On Apple, pooled frames are `CVPixelBuffer`s, which VideoToolbox takes without a copy.
- A source keeps its colour through `DynamicRange.of(decoder.info)`, which follows the transfer
  function: PQ is `HDR10`, HLG is `HLG` and the rest `SDR`. Dolby Vision and HDR10+ are flags over
  one of those, so an iPhone's Dolby Vision 8.4 clip, which is HLG, stays HLG. An SDR source in an
  HDR export sits at 203 nits, BT.2408's graphics white; an HDR source in an SDR export is tone
  mapped.
- HDR10 metadata comes from `VideoEncoderConfig.hdrMetadata`, or else from the first frame, as a
  decoded HDR10 source carries it, and reaches both the encoder and the container's `mdcv` and
  `clli` boxes. Other dynamic ranges ignore `hdrMetadata`, and its Dolby Vision and HDR10+ flags
  are never written. VideoToolbox adds Dolby Vision 8.4 metadata to HLG, which players without
  Dolby Vision ignore. HEVC in MP4 is tagged `hvc1`, which Apple's players need.
- A source reports its metadata as `VideoInfo.hdrMetadata`, the type the config takes, with a
  `MasteringDisplay` only when the stream gives both its primaries and its luminance, as the writer
  needs. Frames drawn for a composite carry none, and nothing fills the config for you: pass
  `HdrMetadata.combine(main, others)`, the main source's mastering display and the highest MaxCLL
  and MaxFALL of them all, which makes MaxFALL an upper bound.
- `EncoderPreference.AUTO` tries the platform's hardware encoders (MediaCodec, VideoToolbox, and on
  Windows NVENC, Quick Sync, AMF and Media Foundation), then a software one. The default LGPL builds
  have no software H.264 or HEVC encoder, libx264 and libx265 being GPL, so `SOFTWARE` H.264 fails;
  the Android standard build has libaom for AV1. `MediaWriter.canEncode(config)` answers before
  opening, and `addVideoTrack` fails with the reason, HDR10 without a 10-bit encoder included:
  falling back to SDR is the caller's choice.
- Outputs are a `File`, or a seekable read-write `Handle`, which an MP4's fast start reads back to
  put its index first. The container is an MP4; `open(..., fastStart = true)` moves its index to
  the front.
- `open(..., timeout = 10.seconds)` bounds each write and each track's draining at `finish()`: an
  encoder that takes frames without giving packets, as the Android emulator's MediaCodec encoders
  do through FFmpeg, fails its track with a `MediaWritingException` instead of blocking. `close()`
  without `finish()` abandons the output and returns promptly.
- `progress` reports frames taken so far; `finish()` returns the size, duration and frame counts.
- An `AudioTrack` encodes interleaved float PCM as AAC.

In the browser WebCodecs encodes the video and FFmpeg's AAC encoder the audio, in a worker of the
writer's own, where FFmpeg's muxer writes the output into memory; `finish()` then writes it to the
`Handle`. There is no file system, so a `File` output fails. The browser encodes 8-bit
SDR only: its frames are 8-bit RGB, so `canEncode` is false for HDR10, HLG and `bitDepth = 10`. Each frame is copied
into a buffer the worker takes, once on Kotlin/JS and twice on Kotlin/Wasm, and WebCodecs copies it
again into its `VideoFrame`; the tracks take `Rgba8`, as `ComposeFrameRenderer` draws it, without a
conversion.

## Tests

```shell
./gradlew :library:codec:commonTestAllTargets
```

The frame model's reference counting and scoping run on every target (`src/commonTest`).
`VideoDecoder` and the frames' conversions and pools run against the real bridge on the JVM and
Kotlin/Native (`src/systemTest`, e.g. `./gradlew :library:codec:jvmTest
:library:codec:macosArm64Test`), over the clips in `src/commonTest/resources/video-decoder`;
`generate.py` there rebuilds them, and `golden/` holds the conversion references that
`scripts/test-converter.sh` records and checks. The `CVPixelBuffer` tests (`src/pixelBufferTest`)
run on `iosSimulatorArm64Test` and `macosArm64Test`. The Android Memory, GpuBuffers and timeout tests
are device tests (`./gradlew :library:codec:connectedAndroidDeviceTest`). The MediaCodec checks need
a real device: the emulator's decoders reject FFmpeg's input, and there the GpuBuffers tests are
skipped.
`ParallelDecoderBudgetDeviceTest` measures several 4K software decoders at once on a phone, over a
clip that `scripts/generate-budget-clip.sh` makes, since it is too large to commit; it takes about
ten minutes, so it runs only with the `parallelBudget=true` instrumentation argument (see the test
for the command).
