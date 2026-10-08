# Async, parallel and zero-copy media

Status: change sets 1–7 and 9–11 are built; 8, the probe without fftools, is
not. 13–18, from compositing several sources, are built, 17 and 18 short of
their phone measurements, and so is 12's decoder half, short of its device
runs. What is left is in [Remaining work](#remaining-work): those runs and
measurements, and 12's encoder half, built and not yet run on a device. The work is rebased onto
main at 78e9934 (v0.3.1, with its structured cancellation and session events).
A [public surface pass](#public-surface-pass) after the over-engineering audit
then gave a stream one typed colour and HDR description and narrowed the public
API to the golden path.
The measurements come from
[`scripts/bench-video-decoder.sh`](../../../scripts/bench-video-decoder.sh).

FFmpegKMP decodes, converts and encodes video more slowly, and with more copies,
than the FFmpeg underneath it allows, and only one fftools command runs at a
time. This plan fixes that in a series of change sets built around one idea: a single
frame type whose memory FFmpeg owns, in a format the caller picks. Decoders
produce it, Compose draws into it and encoders consume it.

## Where things stand

Measured on an M4 Pro (12 cores) with synthetic clips
(`scripts/bench-video-decoder.sh all`):

| Case | Today | With decoder threads |
|---|---|---|
| 1080p H.264 → RGBA8, forward step | 4.85 ms | 0.73 ms |
| 1080p H.264, backward step (120- / 30-frame GOP) | 312 / 112 ms | 64 / 59 ms |
| 4K H.264 → RGBA8, forward step | 20.9 ms | 4.4 ms |
| 4K HEVC 10-bit PQ → RGBA8, forward step | 618 ms | 587 ms, 581 ms of it the tone map |
| 4K HEVC PQ, VideoToolbox → PixelBuffer | 2.2 ms | – |

The causes, roughly in order of cost:

- Software decoders run on one thread. `ffplaykmp_video_codec_open` never sets
  `threads`, and FFmpeg's default is 1; `ffplay.c` itself sets `auto`.
- The HDR tone map calls double-precision `pow` for every pixel, working through
  a 12-byte-per-pixel float copy of the frame.
- The decoder converts every frame to RGBA or F16 before anyone asks for pixels.
  Kotlin then copies it three or four more times before Compose draws it, so a
  4K LinearF16 frame exists in about five buffers, roughly 300 MB.
- Android's CPU outputs never use MediaCodec, so a phone decodes Rgba8 and
  LinearF16 in software on one thread.
- Every `FFmpegClient` and `FFprobeClient` command waits in one process-wide
  FIFO, because fftools keeps its state in globals. A 50 ms probe waits behind a
  10-minute encode. That state also leaks between commands: `ffmpeg_entry.c`
  resets the file and graph arrays but not the options, so `-y`, `-copyts` and
  a dozen others carry into the next command.
- `VideoDecoder` answers one request at a time, a cancelled call keeps
  decoding, and there is no encoder that takes frames.

## Design rules

These keep the change set small:

1. **One frame type.** `VideoFrame` wraps a reference to an FFmpeg `AVFrame`.
   Decoders return it, Compose renders into it, encoders take it.
2. **One format descriptor, one export option.** `FrameFormat` is a pixel
   layout plus a colour description. An export chooses SDR or HDR once, with
   `DynamicRange`, and every `FrameFormat` along the way follows from that.
   Code below the export level changes format by passing a different
   `FrameFormat`, and nothing else.
3. **One converter.** Pixels change format in exactly one place,
   `ffmpegkmp_frame_convert`, driven by the formats of the two frames. swscale
   does layout, matrix, range and chroma; a small table-driven step does the HDR
   transfer functions, so HDR survives wherever the target can hold it.
4. **FFmpeg owns the memory.** Kotlin sees pixels through scoped views. No frame
   is copied into a `ByteArray` on its way anywhere.
5. **Each engine owns its threads.** Decoders, writers and renderers run on
   threads of their own, suspend while they work, and apply backpressure through
   bounded channels. Cancelling a call interrupts the native work.
6. **fftools stays serialized** on native platforms. Parallel work moves to the
   engines that use the FFmpeg libraries directly, which already run
   concurrently.

## Modules

A new Compose-free module, `:library:codec`, holds everything an exporter
needs, so a plain JVM server can decode and encode without depending on Compose:

| Module | Holds | Depends on |
|---|---|---|
| `:library:codec` (new) | `FrameFormat`, `FrameColor`, `PixelLayout`, `DynamicRange`, `VideoFrame`, `FramePlane`, `FrameBytes`, `VideoDecoder` and `MediaWriter`, plus the source and metadata types they share with playback: `MediaSource`, `DecoderPreference`, `DecoderKind`, `VideoInfo` and `HdrMetadata` | `core`, `bindings` |
| `:library:ffplay` | Playback (`FFplayPlayer`, `FFplaySurface`), `VideoFrame.toImageBitmap()` and `ComposeFrameRenderer` | `codec`, `player`, Compose |
| `:library:player` | Unchanged: `AudioDecoder` and `AudioPlayer` | `core`, `bindings` |

- `VideoDecoder` moves out of `ffplay` before it reaches `main`, so it is only
  ever published from `codec`.
- The shared types move under codec names, and `ffplay` uses them as they are:
  `FFplaySource` is `MediaSource`, `FFplayVideoInfo` is `VideoInfo`, and so on.
  Source code and binaries built against 0.2 need recompiling, which is
  acceptable before 1.0. (An earlier version of this plan kept typealiases; the
  [public surface pass](#public-surface-pass) removed them.)
- The native code stays in `:bindings` with the rest of the bridge.

## The frame model

### FrameFormat

```kotlin
public enum class PixelLayout { RGBA8, BGRA8, RGBA_1010102, RGBA_F16, NV12, P010, YUV420P, YUV420P10 }

public data class FrameColor(
    val primaries: ColorPrimaries, // BT709, BT2020, DISPLAY_P3
    val transfer: ColorTransfer,   // SRGB, BT709, LINEAR, PQ, HLG
    val matrix: ColorMatrix,       // RGB, BT709, BT2020_NCL
    val range: ColorRange,         // LIMITED, FULL
) {
    public companion object {
        public val Srgb: FrameColor
        /** Linear light, sRGB primaries, values beyond 0..1 allowed; 1.0 is 203 nits (BT.2408). */
        public val LinearExtendedSrgb: FrameColor
        public val DisplayP3: FrameColor
        public val Bt709: FrameColor
        public val Bt2020Pq: FrameColor
        public val Bt2020Hlg: FrameColor
    }
}

public data class FrameFormat(val layout: PixelLayout, val color: FrameColor) {
    public companion object {
        public val Rgba8: FrameFormat = FrameFormat(PixelLayout.RGBA8, FrameColor.Srgb)
        public val RgbaF16: FrameFormat = FrameFormat(PixelLayout.RGBA_F16, FrameColor.LinearExtendedSrgb)
        public val Nv12: FrameFormat = FrameFormat(PixelLayout.NV12, FrameColor.Bt709)
        public val P010Hdr10: FrameFormat = FrameFormat(PixelLayout.P010, FrameColor.Bt2020Pq)
        public val P010Hlg: FrameFormat = FrameFormat(PixelLayout.P010, FrameColor.Bt2020Hlg)
    }
}
```

The layouts are the ones swscale, Skia and the platform encoders have in
common. `LinearExtendedSrgb` keeps the convention `VideoOutput.LinearF16` uses
today, in both directions, so a decoded HDR frame drawn onto a linear canvas and
encoded back to PQ needs no rescaling. It is also where swscale puts SDR white
(`format.c` gives non-HDR transfers a 203-nit peak), so its SDR tone mapping
agrees with it.

### VideoFrame

```kotlin
public class VideoFrame : AutoCloseable {
    public val pts: Duration
    public val duration: Duration
    public val width: Int
    public val height: Int
    public val rotationDegrees: Double
    public val sampleAspectRatio: Double
    /** Null for frames in GPU memory, such as Android's `GpuBuffers` output. */
    public val format: FrameFormat?

    /** Scoped access to the pixels without a copy; null when the frame has no CPU-visible memory. */
    public fun <R> usePlanes(block: (List<FramePlane>) -> R): R?

    /** One threaded pass into a new pooled frame; a retain of this frame when the format already matches. */
    public fun convert(to: FrameFormat): VideoFrame

    /** One threaded pass into memory another frame owns, such as a render target or an encoder input. */
    public fun convertInto(target: VideoFrame)

    /** Another reference to the same memory; each reference needs its own close(). */
    public fun retain(): VideoFrame

    override fun close()
}

public class FramePlane(public val bytes: FrameBytes, public val rowBytes: Int, public val rows: Int)

/** Native memory: a direct ByteBuffer on JVM and Android, a CPointer on Kotlin/Native, a Uint8Array on the web. */
public expect class FrameBytes {
    public val size: Int
    public operator fun get(index: Int): Byte
    public fun copyInto(destination: ByteArray, offset: Int = 0)
}
```

Ownership follows `AVFrame` references:

- Every `VideoFrame` holds one reference. `close()` releases it, and the memory
  goes back to its pool when the last reference closes.
- A function that takes a frame, such as `VideoTrack.write`, takes that
  reference. Call `retain()` first to keep using the frame.
- `usePlanes` views are valid only inside the block. A direct `ByteBuffer` can't
  be invalidated, so the scoped form is what stops reads after `close()`.
- On Apple, a frame backed by a `CVPixelBuffer` exposes it as
  `VideoFrame.cvPixelBuffer`. On Android, a frame backed by an `AHardwareBuffer`
  exposes `VideoFrame.hardwareBuffer`. Both are extensions in the platform
  source sets.

### The converter

One native entry point replaces `ffplaykmp_convert_rgba`,
`ffplaykmp_convert_linear_f16`, the tone map and the PixelBuffer upload scaler:

```c
/* One threaded, colour-managed pass. Layout and colour come from the frames. */
int ffmpegkmp_frame_convert(ffmpegkmp_converter *converter, AVFrame *destination, const AVFrame *source);
```

- It calls `sws_scale_frame` with `threads = 0` on a context the converter
  keeps. swscale reads matrix, range, primaries and transfer from the two
  frames, so none of that is set up by hand any more.
- swscale's own colour management can't carry HDR into an extended-range
  output. Whenever primaries or transfer differ between the two frames, it maps
  colours through a 3D table in `RGBA64` (`ff_sws_lut3d_pick_pixfmt` in
  `lut3d.c`), 16-bit unsigned and normalized to the destination's range. It
  treats a linear destination as SDR with a 203-nit peak, tone-mapping PQ
  highlights down to it, and pulls colours outside the destination gamut inside
  it. So nothing above 1.0 or below 0 comes out. Today's LinearF16 output keeps
  both, so the converter only uses swscale's colour management where losing HDR
  is the point.

The converter takes one of three routes, chosen from the two formats:

| From → to | Route | HDR |
|---|---|---|
| Same primaries and transfer, such as YUV → YUV or PQ YUV → PQ RGB | swscale only: layout, matrix, range, chroma | kept |
| HDR → an HDR-capable target: PQ or HLG YUV → `RgbaF16`, or an F16 canvas → P010 PQ or HLG | swscale for layout, plus the transfer step below | kept |
| HDR → an SDR target: `Rgba8`, `Nv12`, BT.709 | the transfer step's BT.2390 tone map, then swscale's perceptual gamut mapping (change set 10) | tone-mapped to SDR |

The transfer step is the current LinearF16 maths made fast, and it keeps its
semantics: values above 1.0 and negative, out-of-sRGB components survive.

- **Decoding into `RgbaF16`:** swscale converts YUV into a 16-bit intermediate
  tagged with the source's own primaries and transfer, so it does no colour
  mapping and clips nothing. A threaded pass then applies the PQ or HLG curve,
  using a table indexed by the 16-bit code, and the BT.2020 → sRGB matrix in
  float, and packs half floats into the target. The intermediate is planar
  `GBRP16`, 6 bytes per pixel instead of today's 12, and comes from a pool.
  swscale's packed `RGBA64` output repeats each chroma sample across two pixels
  instead of interpolating it, and with `SWS_FULL_CHR_H_INT` it is wrong from
  10-bit YUV in FFmpeg 9.0.1, so it cannot be the intermediate.
- **Encoding from an F16 canvas:** a threaded pass works in place on the canvas.
  It applies the sRGB → BT.2020 matrix and the linear → PQ or HLG curve from a
  table. swscale then converts that `RGBA_F16` frame, now tagged BT.2020 PQ or
  HLG, into P010 or `YUV420P10`, with nothing left to map. Half floats hold
  PQ-coded values finer than a 10-bit code step.
- **Copy the HDR side data too.** The intermediate or canvas frame gets the
  source's properties, mastering-display metadata included
  (`av_frame_copy_props`). Otherwise swscale sees different peaks on the two
  sides and maps colours after all (`ff_sws_color_map_noop` in `cms.c`
  compares them).
- VideoToolbox buffers are locked and read in place. Other hardware frames are
  downloaded once with `av_hwframe_transfer_data`.

### Frame pools and platform memory

Frames come from pools keyed by layout and size. The pool decides where the
memory lives, which is how the platform zero-copy paths work without extra API:

| Platform | Memory | What it enables |
|---|---|---|
| All | `av_frame_get_buffer` | CPU frames for software codecs and swscale |
| Apple | IOSurface-backed `CVPixelBufferPool` for NV12, P010, BGRA8 and RGBA_F16 | VideoToolbox encodes the NV12, P010 and BGRA8 ones with no copy, and Metal can import all of them. Every such frame has a `cvPixelBuffer`, which is what `VideoOutput.PixelBuffer` gives today |
| Android, API 26+ | `AHardwareBuffer` for RGBA8, RGBA_1010102 and RGBA_F16 | `Bitmap.wrapHardwareBuffer` (API 29) shows a frame with no copy, and `HardwareBufferRenderer` (API 34) can draw Compose into it |
| Android, API 24–33, canvas formats | `Bitmap` (`ARGB_8888`, or `RGBA_F16` on API 26+), read through `AndroidBitmap_lockPixels` | the Compose fallback draws into the frame with a software `Canvas` |

## SDR or HDR output

An export chooses its dynamic range with one option, and every format along the
way follows from it:

```kotlin
public enum class DynamicRange {
    /** 8-bit BT.709. HDR sources are tone mapped to it. */
    SDR,
    /** 10-bit BT.2020 PQ, with mastering-display and content-light metadata. */
    HDR10,
    /** 10-bit BT.2020 HLG. */
    HLG,
}
```

It is set once, as `VideoEncoderConfig.dynamicRange`, and the track reports what
follows from it:

| | `SDR` | `HDR10` | `HLG` |
|---|---|---|---|
| `track.config.canvasFormat`: what Compose draws into, and what decoded frames are delivered as for drawing | `Rgba8`, sRGB | `RgbaF16`, linear extended sRGB | `RgbaF16` |
| `track.inputFormat`: what the encoder receives | NV12, or YUV420P for a software encoder; BT.709 | P010, or YUV420P10 for a software encoder; BT.2020 PQ | the same, with HLG |
| Codecs | H.264, HEVC, AV1 | HEVC Main10, AV1 10-bit | HEVC Main10, AV1 10-bit |
| Stream metadata | BT.709 | BT.2020 and PQ, with mastering display and content light | BT.2020 and HLG |
| An HDR source | tone mapped (BT.2390, change set 10) | kept | kept; PQ highlights above HLG's 1000-nit reference peak clip |
| An SDR source | as it is | placed at 203 nits, BT.2408's graphics white | the same |

The caller never picks a pixel layout. The dynamic range decides the colour,
and the encoder that takes the track decides the layout: NV12 or P010 for
hardware encoders, YUV420P or YUV420P10 for software ones. The Android HDR10
profile is set from `avctx->profile`, as [Binding generation](../../bindings.md)
already documents for commands.

- **Matching a source:** `DynamicRange.of(info: VideoInfo)` follows the
  source's transfer function, `info.color.transfer`: PQ to `HDR10`, HLG to
  `HLG` and the rest to `SDR`. Dolby Vision and HDR10+ are flags on
  `info.hdrMetadata`, not ranges: a Dolby Vision 8.4 clip is HLG and stays HLG.
  A match-the-source export is then
  `dynamicRange = DynamicRange.of(decoder.info)`.
- **HDR10 metadata:** decoded HDR10 frames carry mastering-display and
  content-light side data, and the writer forwards what the first frame has.
  `VideoEncoderConfig.hdrMetadata` overrides it, or supplies it for content with
  no source, such as a pure Compose render.
- **Availability:** HDR needs a 10-bit encoder, and the default LGPL builds have
  no software HEVC encoder, because libx265 is GPL.
  - Apple: VideoToolbox HEVC Main10.
  - Android: a MediaCodec HEVC encoder that takes P010 and the HDR10 profile,
    on Android 13+ and depending on the device.
  - JVM desktop: libaom AV1 when it's added to the build, or a platform encoder
    the build enables.
  - Web: WebCodecs where the browser encodes 10-bit.

  `MediaWriter.canEncode(config)` answers before opening, and `addVideoTrack`
  fails with the reason instead of quietly writing SDR. Falling back to SDR is
  the caller's decision.
- **Playback** keeps `FFplayHdrPolicy`: whether HDR can be shown depends on the
  display, while an export's dynamic range doesn't.

## Compose rendering into frames FFmpeg owns

This works on every platform, with limits. The renderer draws only into RGB
frames, straight into their memory, because Skia can't draw YUV:

| Canvas format | Colour | Used for |
|---|---|---|
| `RGBA8`, `BGRA8` | sRGB or Display P3 | SDR |
| `RGBA_1010102` | sRGB or Display P3 | 10-bit SDR |
| `RGBA_F16` | linear extended sRGB | HDR10 and HLG |

Turning the canvas into the encoder's YUV is the one conversion, and
`VideoTrack.write` does it on the encoder's thread. For an export,
`track.config.canvasFormat` is the canvas, so the SDR or HDR choice above decides it.

Skiko's `ColorSpace` offers three canvas spaces: `sRGB`, `sRGBLinear` and
`displayP3`. HDR exports therefore draw in linear extended sRGB, whose extended
range can hold any BT.2020 colour. The conversion then applies the target's
gamut and transfer while keeping HDR (see [The converter](#the-converter)).

### API

```kotlin
public class ComposeFrameRenderer<T>(
    width: Int,
    height: Int,
    /** An RGB format: RGBA8, BGRA8, RGBA_1010102 or RGBA_F16. */
    format: FrameFormat = FrameFormat.Rgba8,
    density: Density = Density(1f),
    content: @Composable (T) -> Unit,
) : AutoCloseable {
    /** Takes the size and the canvas format from [track]. */
    public constructor(track: VideoTrack, density: Density = Density(1f), content: @Composable (T) -> Unit)

    /** Composes [value] at [time] and draws it into a pooled frame FFmpeg owns, in [format]. */
    public suspend fun render(time: Duration, value: T): VideoFrame
}
```

On Android the constructors also take a `Context`. Each renderer composes and
draws on a thread of its own, so several can render in parallel.

Decoding, overlaying and encoding in one loop:

```kotlin
MediaWriter.open(MediaOutput.File("export.mp4")).use { writer ->
    val track = writer.addVideoTrack(
        VideoEncoderConfig(3840, 2160, FrameRate(30), VideoCodec.HEVC, dynamicRange = DynamicRange.HDR10),
    )
    VideoDecoder.open(source, VideoOutput.Memory(track.config.canvasFormat)).use { decoder ->
        ComposeFrameRenderer<ImageBitmap>(track) { background ->
            Image(background, contentDescription = null)
            Titles()
        }.use { renderer ->
            decoder.frames(step = FrameStep.Rate(FrameRate(30))).collect { frame ->
                val background = frame.use { it.toImageBitmap() }
                track.write(renderer.render(frame.pts, background))
            }
        }
    }
    writer.finish()
}
```

With `DynamicRange.HDR10`, the decoder delivers linear F16, Compose draws onto an
F16 canvas, and `write` converts once to P010 PQ. With `DynamicRange.SDR`, the
decoder tone-maps to RGBA8, Compose draws onto an 8-bit canvas, and `write`
produces NV12 in BT.709. Nothing else in the loop changes.

### On Skiko: JVM, iOS, macOS and web

Both routes below wrap the target frame's memory in a Skia surface with
`Surface.makeRasterDirect(ImageInfo(width, height, colorType, alphaType, colorSpace), address, rowBytes)`,
and both drive the frame clock from `time`, so animations land exactly on each
frame's time.

**Primary route: Compose's internal scene API.** This is what
`ImageComposeScene.render` does inside, pointed at FFmpeg's surface instead of
its own. In Compose 1.12 that is four calls:

1. `FrameRecomposer(coroutineContext, onNewAwaiters)` and
   `CanvasLayersComposeScene(frameRecomposer, density, layoutDirection, size, platformContext, invalidate)`
   host the composition, with
   `scene.setContent(frameRecomposer.compositionContext) { … }`.
2. Each frame: `frameRecomposer.performFrame(nanoTime)`, then
   `scene.measureAndLayout()`, then
   `scene.draw(surface.canvas.asComposeCanvas())`.

There is no second surface, no display-list replay and no image snapshot. The
`PlatformContext` the shim implements needs only `windowInfo` and
`inputModeManager`.

The API is `@InternalComposeUiApi` and does change between releases. 1.11.1 had
`CanvasLayersComposeScene(density, layoutDirection, size, coroutineContext, platformContext, invalidate)`
and a single `ComposeScene.render(canvas, nanoTime)`. 1.12 split that into
`FrameRecomposer.performFrame`, `measureAndLayout` and `draw`. So the route
lives in one small shim file per supported Compose version range, and three
guards keep it honest:

- A test renders the same content through both routes and compares pixels, so
  a Compose bump that changes behaviour fails CI.
- The shim is the only file that opts in to `InternalComposeUiApi`.
- At runtime, a linkage failure falls back to the public route. That covers an
  app resolving a different Compose version than the one the library was built
  against: `LinkageError` on the JVM, and the partial-linkage error
  Kotlin/Native and Kotlin/Wasm raise for an unlinked symbol. The fallback is
  logged once.

**Fallback route: public API.**

1. `ImageComposeScene(width, height, density, coroutineContext) { … }` hosts the
   composition, and `render(nanoTime)` drives its clock.
2. The content is wrapped in
   `Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() } }`
   on a `GraphicsLayer`. That records a display list and draws nothing onto the
   scene's own surface.
3. `CanvasDrawScope().draw(density, layoutDirection, surface.canvas.asComposeCanvas(), size) { drawLayer(layer) }`
   replays the display list into the target frame.

This route costs an extra 8-bit surface at the scene size, 33 MB at 4K, and a
clear of it per frame. It never costs a copy.

Limits:

- `ImageComposeScene` is `@ExperimentalComposeUiApi`. It and the internal scene
  types are in the 1.12 desktop, iOS and wasm-js artifacts; macOS still needs
  checking.
- Compose on Skiko sets paint colours through `toArgb()`, so the shapes and text
  Compose draws are 8-bit sRGB whatever the canvas. On an HDR target they sit at
  reference white (203 nits), which is where BT.2408 puts graphics in HDR
  video. HDR highlights come from drawn images, such as a decoded frame shown
  with `toImageBitmap()` from an F16 frame.
- On the web the frame memory lives in the page's Skia heap, not the FFmpeg
  worker's, so each finished frame is copied once into a transferable buffer.

### On Android

Android has no off-screen scene, because a composition needs a window.

1. The renderer hosts a `ComposeView` in a `Presentation` on a private
   `VirtualDisplay`, which an app may create for its own content without a
   permission.
2. `render(time, value)` sets the content's state and waits for the next drawn
   frame. Compose animations on Android follow the Choreographer, not `time`, so
   exported content has to be driven by `time` directly rather than by
   `animate*AsState`.
3. **API 34+:** the content records into a `GraphicsLayer`, which the renderer
   draws into a `RenderNode` through `CanvasDrawScope`. `HardwareBufferRenderer`
   renders that node on the GPU into the target frame's `AHardwareBuffer`.
   `RenderRequest.setColorSpace` selects sRGB, `LINEAR_EXTENDED_SRGB` or
   `DISPLAY_P3`. No copy.
4. **API 24–33 fallback:** the renderer draws the hosted `ComposeView` with
   `View.draw` onto a software `Canvas` over a `Bitmap`, and that `Bitmap` is the
   frame. On these levels the Android frame pool hands out `Bitmap`-backed frames
   for canvas formats, and FFmpeg reads their memory in place through
   `AndroidBitmap_lockPixels`, so there is no copy here either. The bitmap
   follows `canvasFormat`, so both dynamic ranges work:
   - **SDR:** `ARGB_8888` in sRGB, on every supported level.
   - **HDR10 and HLG:** `RGBA_F16` in `LINEAR_EXTENDED_SRGB`, on API 26+, the
     same floor `VideoOutput.LinearF16` has today. On API 24–25 the renderer
     fails with the reason, and `MediaWriter.canEncode` already reports no
     10-bit encoder there.

The fallback rasterizes on the CPU, so it is slower than the GPU path, but it
produces the same formats and colour. In both paths the canvas is linear extended
sRGB for HDR, and the converter applies PQ or HLG, so the two paths hand the
encoder the same frames. A test renders the same content through both on an
API 34 device and compares pixels.

This is the part most likely to change after device testing, in particular
whether `View.draw` onto a software canvas draws every Compose layer type.

## Change sets

| # | Change set | Depends on | Size |
|---|---|---|---|
| 1 | Benchmark and decoder threads | – | S |
| 2 | Command runtime fixes | – | S |
| 3 | Converter on swscale | 1 | M |
| 4 | Frame model, with the decoder and player on it | 3 | L |
| 5 | Async decoder | 4 | M |
| 6 | `MediaWriter` | 4 | L |
| 7 | `ComposeFrameRenderer` | 4; 6 for export | M |
| 8 | Probe without fftools (optional) | – | M |
| 9 | Web decode and encode | 4, 6 | M |
| 10 | BT.2390 tone map | 3 | S |
| 11 | Export bit depth | 6, 7 | S |
| 12 | Android zero-copy: the decoder half, and the encoder half if measured | 7, 14, 18 | M; L for the encoder half |
| 13 | Decode at a size | 3, 4 | M |
| 14 | `FrameImage`: reuse, rotation and aspect | 4, 7; 12 for hardware frames | S |
| 15 | Android frame clock | 7 | M |
| 16 | HDR metadata for composites | 6 | S |
| 17 | One decoder per video: slice threads, not frame threads | 1, 3 | S |
| 18 | Frames one ahead, in reusable rings | 5, 13, 14, 17 | M |

1, 2 and 8 can start at any time. 4 should land before `VideoDecoder` reaches
`main`, so its public API changes only once.

### 1. Benchmark and decoder threads

The largest measured win for the least code.

- Commit `native-build/bench/video_decoder_bench.c` and
  `scripts/bench-video-decoder.sh`. Every later change set quotes before-and-after
  numbers from them.
- In `ffplaykmp_video_codec_open`, set `threads` and `thread_type` (frame and
  slice) for software decoders, as `ffplay.c` does.
- Add `threads: DecoderThreads = DecoderThreads.Auto` to `VideoDecoder.open` and
  `FFplayConfiguration`. `Auto` caps at 8, because frame threading keeps about
  one frame in flight per thread, roughly 24 MB each at 4K 10-bit.

Verify: the decoder and player suites (`jvmTest`, `nativeTest`, `systemTest`,
`androidDeviceTest`) pass with threads on. Targets: 1080p forward step ≤ 1 ms,
4K H.264 ≤ 5 ms, 4K HEVC software → PixelBuffer ≤ 3 ms.

### 2. Command runtime fixes

Commands stop affecting each other and the host process. This is independent of
the frame work.

- Reset the option globals of `ffmpeg_opt.c` before every run, as
  `ffprobe_entry.c` already does for ffprobe: `copy_ts`, `start_at_zero`,
  `copy_tb`, `stats_period`, `print_stats`, `stdin_interaction`,
  `exit_on_error`, `abort_on_flags`, `max_error_rate`, `debug_ts`,
  `do_benchmark`, `filter_nbthreads`, `vstats_filename`, and cmdutils'
  `hide_banner`. `file_overwrite` and `no_file_overwrite` are `static`, so
  `ffmpeg_opt.c` has to be compiled through a wrapper that `#include`s it and
  adds the reset, the way the entry files already wrap `ffmpeg.c` and
  `ffprobe.c`. That wrapper replaces `ffmpeg_opt.o` in `FFTOOLS_OBJECTS`.
- Rename `term_init` in the included `ffmpeg.c` with a macro and define a no-op
  `term_init`. fftools then stops installing SIGINT, SIGTERM and SIGQUIT handlers
  and changing the terminal in the host process.
- Run native command calls on a dedicated thread instead of `Dispatchers.Default`.
- `execute` waits for room in the queue. `enqueue` keeps failing fast.
- Add `platformCommandLanes`: 2–4 on the web, where each command already gets
  its own worker and Wasm instance, and 1 elsewhere. The web bridge tracks
  running commands by id instead of allowing one.

On main (v0.3.1) cancellation already follows the coroutine, and the bridge makes
the blocking native call on `Dispatchers.IO` and waits for it to return, so a
dedicated thread per lane was not needed and was dropped. The queue is main's line
of tickets, handed to `platformCommandLanes` sessions at a time instead of one;
`execute` waits for a ticket and `enqueue` still fails fast. The web bridge's
`cancel(executionId)` is gone with the interface method; its workers are
terminated by the continuation's cancellation.

Verify: new cases in `CommandRuntimeTest` and `CompiledRuntimeIntegrationTest`.
A run with `-y` followed by one without it, writing to an existing output, must
refuse to overwrite. `-copyts` must not carry over. Two web commands overlap.

### 3. Converter on swscale

Fast conversion, in one place.

- Add `ffmpegkmp_frame_convert` (see [The converter](#the-converter)) to
  `ffplaykmp_core.c`. The converter holds the swscale context, the transfer
  tables and a small worker pool for the transfer step.
- Move the RGBA, LinearF16 and PixelBuffer paths onto it.
  - Rewrite the transfer and primaries helpers as tables plus float matrices,
    keeping their semantics.
  - Replace the 12-byte float scratch buffer with the pooled `RGBA64`
    intermediate.
  - Delete `ffplaykmp_configure_scaler_colors`.
- The FFplay Canvas fallback uses the same converter, so the player gets faster
  too.

Verify:

- Golden images per layout and colour from the clips in
  `library/ffplay/src/commonTest/resources/video-decoder`, plus an HLG clip added
  to its `generate.py`. `FFplayHdrPolicyTest` and `FFplayColorMetadataTest`
  still pass.
- **HDR preservation, against today's implementation:** decoding `hdr10-pq.mp4`
  to LinearF16 keeps its maximum above 1.0 and its negative out-of-gamut
  components within half-float precision. An F16 → P010 PQ → F16 round trip
  lands within one 10-bit code.
- **Targets:** 4K PQ → RGBA8 ≤ 20 ms (581 today), 4K 10-bit YUV → RGBA ≤ 3 ms
  (10.8 today), and 4K PQ → LinearF16 well under today's 477 ms.

Risk: swscale's perceptual tone mapping looks different from today's curve.
Change set 10 settled it: the tone map stays a table-driven pass in the
transfer step, with BT.2390's curve in place of Reinhard's.

### 4. Frame model, with the decoder and player on it

One frame type, conversion only when asked for, and no copies. This lands on
`feature/video-decoder` before it merges, reshaping `VideoDecoder` while it is
still unreleased.

Module:

- Create `:library:codec` and move `VideoDecoder` and the shared types into it,
  with the `ffplay` typealiases (see [Modules](#modules)). `ffplay` then depends
  on `codec`.

Native:

- `ffmpegkmp_video_decoder_frame_at` hands out a new reference to the decoded
  frame instead of converting it. `ffmpegkmp_present` keeps only Surface
  rendering.
- Frame pools with the platform memory above.
- Frame handles in the bindings: ref, unref, plane pointers and convert.

Kotlin:

- `FrameFormat`, `VideoFrame`, `FramePlane` and `FrameBytes`.
- `VideoOutput` becomes `Memory(format: FrameFormat? = null)` and
  `Surface(surface)`. With a `Memory` format, the decoder thread converts, so
  conversion overlaps with the caller's work. `null` returns frames as decoded.
- `VideoFrame.toImageBitmap()` in the Compose module keeps an RGB frame's format
  and converts YUV to `Rgba8`.
  - On Skiko it writes straight into a Skia `Bitmap`'s own memory
    (`peekPixels().addr`), marks it immutable and wraps it with
    `asComposeImageBitmap()`.
  - On Android it writes into a `Bitmap` through `AndroidBitmap_lockPixels`, or
    wraps an `AHardwareBuffer` frame with `Bitmap.wrapHardwareBuffer`.
- The FFplay Canvas fallback delivers `VideoFrame`s instead of
  `NativeVideoFrame` byte arrays.
- On Android, `AUTO` opens MediaCodec for `Memory` output too, in ByteBuffer
  mode, keeping the software fallback.

Verify:

- The existing decoder suites, ported: `VideoDecoderSystemTest`,
  `VideoDecoderPixelBufferTest`, `VideoDecoderSurfaceDeviceTest` and
  `VideoDecoderTimeoutDeviceTest`.
- Pool allocation counts show a 4K frame's pixels in one decoder buffer and one
  bitmap.
- On devices that have a hardware decoder, Android `Memory` output reports it.

Risk: MediaCodec's ByteBuffer output varies by device in colour formats and
strides. FFmpeg normalizes most of it, and the software fallback covers the rest.

### 5. Async decoder

Decoding overlaps with the caller's work and stops when cancelled.

- A `Mutex` around each native call and its reference hand-off makes concurrent
  calls safe. That removes the "must not be called concurrently" rule and the
  current PixelBuffer retain race.
- `ffmpegkmp_video_decoder_interrupt()` fails only the call in flight and makes
  the next call re-seek. The Kotlin call path fires it when the caller is
  cancelled, so `collectLatest { frameAt(it) }` drops stale work instead of
  queueing it. `abort()` stays for closing.
- `frames(from, until, step): Flow<VideoFrame>` decodes ahead on the decoder
  thread into a bounded channel, built on
  `frameAt(previous.pts + previous.duration)`. Frames the collector never
  receives are closed through `onUndeliveredElement`. `step` is
  `FrameStep.Decoded` or `Rate(FrameRate)`: `Rate` computes each tick from its
  fraction, so 30 fps stays exact where `1.seconds / 30` drifts by a nanosecond
  every three frames.
  `FrameRate` is the fraction `VideoEncoderConfig` takes in change set 6.
- `AudioDecoder` moves to the same model: a thread of its own, with suspending
  `open`, `read` and `seek`.

Verify: a cancelled seek returns within the deadline grace and the next
`frameAt` succeeds. Two coroutines call `frameAt` at once. The benchmark shows
decoding overlapping a consumer's work.

### 6. MediaWriter

An encoder that takes frames, suspends while it's full, and runs alongside
commands and other writers.

```kotlin
public class MediaWriter : AutoCloseable {
    public companion object {
        public suspend fun open(
            output: MediaOutput,
            fastStart: Boolean = true,
            timeout: Duration = 10.seconds,
        ): MediaWriter
        /** Whether this platform and build have an encoder for [config], HDR included. */
        public suspend fun canEncode(config: VideoEncoderConfig): Boolean
    }
    /** Opens the track's encoder on the track's own thread. */
    public suspend fun addVideoTrack(config: VideoEncoderConfig): VideoTrack
    public suspend fun addAudioTrack(config: AudioEncoderConfig): AudioTrack
    public val progress: StateFlow<WriterProgress>
    public suspend fun finish(): WriterResult
}

public data class VideoEncoderConfig(
    val width: Int,
    val height: Int,
    val frameRate: FrameRate?,                               // null for variable frame rate
    val codec: VideoCodec = VideoCodec.H264,
    val dynamicRange: DynamicRange = DynamicRange.SDR,       // decides every format; see "SDR or HDR output"
    val hdrMetadata: HdrMetadata? = null,                    // HDR10 only; defaults to the first frame's
    val bitRate: Long? = null,
    val keyframeInterval: Duration = 2.seconds,
    val encoder: EncoderPreference = EncoderPreference.AUTO,
)

public class VideoTrack {
    /** `config.canvasFormat` is what to draw into and decode to for this track: Rgba8 for SDR, RgbaF16 for HDR. */
    public val config: VideoEncoderConfig
    /** Frames in this format reach the encoder without a conversion. */
    public val inputFormat: FrameFormat
    /** Android hardware encoders: a decoder's Surface output can render straight into this. */
    public val inputSurface: Any?
    /** Suspends while the encoder is full. Takes ownership of [frame]; another format is converted once. */
    public suspend fun write(frame: VideoFrame)
}

public class AudioTrack {
    public suspend fun write(pcm: FloatArray, frames: Int)
}

public sealed interface MediaOutput {
    public data class File(val path: String) : MediaOutput
    public data class Handle(val fileHandle: FileHandle) : MediaOutput
}
```

Native, in a new `ffmpegkmp_writer.c`:

- libavcodec encoders and a libavformat muxer, with per-instance I/O through the
  same host callbacks the decoder uses, and deadlines and interrupts like the
  decoder's.
- One thread per encoder, because MediaCodec binds a session to a thread. The
  tracks' packets meet in the muxer under one lock rather than on a mux thread
  of its own, since libavformat interleaves them anyway. `write` hands frames
  over a bounded queue, two deep.
- Software encoders get `threads=auto`.
- Hardware: MediaCodec (with the existing P010 and HDR10 overlay) and
  VideoToolbox. `AUTO` falls back to a software encoder when no hardware
  encoder accepts the configuration or the sessions run out, but only if the
  build has one for the codec. The default LGPL builds have none for H.264 or
  HEVC, because libx264 and libx265 are GPL, so there `AUTO` fails with the
  reason instead.
- `dynamicRange` sets the stream's colour metadata, bit depth and profile.
  Mastering-display and content-light metadata are forwarded from the frames or
  taken from `hdrMetadata`, as the Android overlay already does for commands.
- MP4 with `faststart` needs a seekable output, `File` or `Handle`, which is why
  there is no forward-only output (see the [public surface pass](#public-surface-pass)).

Inputs without copies:

- A frame already in `inputFormat`, from a decoder or a renderer, goes to the
  encoder as it is. A frame in `config.canvasFormat` is converted once, on the encoder
  thread, so conversion overlaps with rendering the next frame.
- Apple: `CVPixelBuffer`-backed frames go to VideoToolbox as
  `AV_PIX_FMT_VIDEOTOOLBOX`.
- Android: a Surface output (no longer public) would make MediaCodec decode
  straight into MediaCodec encode on the GPU. The decoder already stamps each
  rendered buffer with its pts. FFmpeg's MediaCodec encoder
  takes Surface input only through a hardware frames context, so change set 12's
  encoder half uses a platform `MediaCodec` of its own instead, for rendered
  frames (`VideoTrack.zeroCopy`); a decoder's Surface output into an encoder is
  still not built.
- The converter corrects swscale's 16-bit range scale, which put 10-bit white on
  943 rather than BT.2100's 940 (see [The converter](#the-converter)); the HDR
  fixtures are regenerated with BT.2100's codes.
- Each write and track end has a deadline (`MediaWriter.open(timeout)`), because
  the Android emulator's MediaCodec encoders take frames and never give packets
  through FFmpeg.

Verify:

- Decode → encode → probe round trips per codec and platform, checking size,
  frame count, timestamps and colour metadata.
- Each `DynamicRange` probes back with its bit depth, primaries, transfer and,
  for HDR10, mastering-display metadata. An SDR source in an HDR10 export sits
  at 203 nits; an HDR10 source in an SDR export is tone mapped.
- Where no 10-bit encoder exists, `canEncode` returns false for HDR10 and
  `addVideoTrack` fails with the reason.
- Two writers run in parallel with an `FFmpegClient` command.
- Cancelling mid-write closes cleanly.
- An HDR10 round trip on an Android 13+ device.

### 7. ComposeFrameRenderer

Compose draws into frames FFmpeg owns, in the format the caller picks (see
[Compose rendering into frames FFmpeg owns](#compose-rendering-into-frames-ffmpeg-owns)).

- Skiko first: JVM, iOS, macOS and web.
  - The internal-scene shim is the primary route.
  - The public `ImageComposeScene` route is the fallback, with the runtime
    switch between them.
- Android second: the API 34 GPU path, then the API 24–33 software fallback,
  both handling SDR and HDR.

Status: the Skiko part (7a) is built for the JVM, macOS and iOS, both routes
drawing the same pixels. Frames come from the process-wide pool and are mapped
for writing in place (`ffmpegkmp_frame_map_writable`, which locks a
CVPixelBuffer for writing), and each render clears the frame first, since
pooled memory holds an earlier frame. In the browser the frame is drawn in
Skia's heap and read back into an RGBA8 frame in three copies: the surface into
a bitmap, the bitmap into a Kotlin array, and the array's rows into the frame.
A `VideoTrack` copies it again into the buffer its worker takes, twice on
Kotlin/Wasm, and WebCodecs copies that buffer into its `VideoFrame`.

Android (7b and 7c) is built: the Presentation host, the API 34 GPU path through
`HardwareBufferRenderer`, and the software fallback, both checked against each
other on a device. Unlike the plan, both copy the drawn pixels once into a
pooled frame (`ffmpegkmp_frame_convert_from_android_bitmap` and
`..._from_android_hardware_buffer`) rather than having FFmpeg read the Bitmap or
AHardwareBuffer in place: reading in place needs a native pool of Java objects
released from FFmpeg's buffer callbacks, and the one copy is small beside the
drawing. Each `render` also waits two Choreographer frames rather than one, for
Compose to recompose and lay out the change, so it renders at about half the
display's frame rate.

Verify:

- Golden images per canvas format on the JVM.
- The internal and public routes produce the same pixels.
- A direct RGBA8 render matches `ImageComposeScene`'s own `render()` pixel for
  pixel.
- With the internal route forced to fail, the renderer falls back and still
  renders.
- An animation sampled at time t matches between runs.
- An F16 canvas keeps a decoded HDR frame's highlights above 1.0, on Skiko and on
  both Android paths.
- On an API 34 device, the GPU path and the software fallback match for SDR and
  HDR.
- Renderers run in parallel.

Risks:

- The internal scene API changes with Compose releases. Each bump may need a new
  shim file, and the pixel-comparison test is what flags it.
- Hosting on Android through a virtual display, `HardwareBufferRenderer`, and
  `View.draw` onto a software canvas all need device testing. Keep them behind
  API-level checks.

### 8. Probe without fftools (optional)

Probes run in parallel and never wait behind a command.

- `ffmpegkmp_probe.c` opens the input and reads its stream information with the
  FFmpeg libraries. It reuses `ffplaykmp_open_input` with a per-call I/O host and
  deadline, and fills structs that Kotlin maps to `MediaInformation`.
- `FFprobeClient.inspect` routes queries that need only format, streams,
  chapters, programs and stream groups there, which includes `ProbeQuery.Default`.
  Packet, frame, data and `entries` queries stay on fftools.

Verify: both paths return equal `MediaInformation` for the fixture corpus, and
50 parallel `inspect` calls finish while a long command runs.

Trade-off: this is a second probe implementation to keep in step with ffprobe.
It deletes code only if `ProbeQuery` narrows to what it covers. The ffprobe glue
could then go: `ffprobe_entry.c` and the temporary-file redirect in
`ffmpegkmp_bridge.c`.

### 9. Web decode and encode

- `VideoDecoder` on the web through FFmpeg's demuxer in the decoder's worker and
  WebCodecs `VideoDecoder`. `VideoFrame` wraps a WebCodecs `VideoFrame`, and `usePlanes`
  copies once through `copyTo`.
- `MediaWriter` through WebCodecs `VideoEncoder`, with libavformat muxing in
  the worker.
- `ComposeFrameRenderer` frames reach the encoder as WebCodecs `VideoFrame`s
  built from the rendered buffer.

Verify: browser integration tests in the style of the existing `BrowserPlayer`
ones.

Status: built, and tested in headless Chrome on Kotlin/JS and Kotlin/Wasm
(`BrowserCodecTest`). The decoder and the writer each run in a worker of their
own. Two things differ from the plan:

- The decoder runs WebCodecs in the worker, next to the demuxer, not on the page:
  the frame-at logic is a port of `ffmpegkmp_decoder.c` over the stream's own
  timestamps, which `ffplaykmp_web_player_packet_timing` hands the worker, so a
  position rounds to a frame exactly as the native decoder rounds it. `usePlanes`
  is synchronous, so the worker copies each new frame once with `copyTo`, in the
  format asked for, and transfers the buffer, which Kotlin/Wasm copies once more
  into its own heap: frames are page memory, not
  wrapped WebCodecs `VideoFrame`s. Repeated positions on the same frame send no
  pixels.
- The writer's video tracks are packet tracks (`ffmpegkmp_writer_add_packet_track`),
  which mux what WebCodecs encoded, with its `avcC` or `hvcC`, or the parameter
  sets `extract_extradata` finds. Audio goes through the ordinary AAC track.
  The browser encodes SDR only: its frames are 8-bit RGB, and HDR would need the
  converter on the page.

The native decoder and writer calls became `suspend` for this; on native
platforms they never suspend, and `DecoderThread` runs each to its end on its
thread.

### 10. BT.2390 tone map

- HDR to SDR takes BT.2390's EETF in PQ with SDR white, 203 nits, as the target
  peak, on each pixel's brightest component so colours keep their hue: below
  the knee the source keeps its nits, and highlights roll off to white at the
  clip's peak.
- The peak is the lower of MaxCLL and the mastering display's, 1,000 nits when
  the clip gives neither and at most 1,000 for HLG. The converter keeps the
  curve as an octave table, rebuilt when the peak changes.
- The tone map keeps the source's primaries; swscale then maps BT.2020 into
  BT.709 with its perceptual intent, which leaves greys alone and compresses
  saturated colours instead of clipping them.
- SDR frames no longer carry the source's mastering display or content light
  metadata, which swscale took for the display it maps to.

Status: built. The decoder's HDR10 fixture gives 186 for 100 nits and 255 for
its 1,000-nit peak, the HLG one 230 for reference white, all within one code of
the EETF; the RGBA8 references for both were recorded again, and every other
reference stays bit-exact.

A 4K PQ frame to RGBA8 takes about 23 ms, over the 20 ms target: about 10 ms of
it is swscale's gamut pass. Compressing the gamut in the transfer step instead,
after ACES's reference gamut compression, took it to 13 ms but tinted saturated
colours, in gamut ones included (BT.2020 red came out as 239, 27, 58), so the
swscale pass stays.

### 11. Export bit depth

- `VideoEncoderConfig.bitDepth`, 8 or 10, beside `DynamicRange`: SDR at 8 or 10
  bits, HDR at 10. 10-bit SDR takes HEVC Main10 or AV1 and BT.709 P010; Compose
  draws it into an `RGBA_1010102` canvas.

Status: built. `bitDepth` is null by default, following the range, so that
`config.copy(dynamicRange = HDR10)` still takes 10 bits; `canvasFormat` moved to
the config. The native config gained a `bit_depth` field at its end. A 10-bit SDR
round trip through VideoToolbox probes as HEVC Main 10, BT.709 and limited range,
with no mastering display. Android draws `RGBA_1010102` from API 33, on both its
paths; before that a 10-bit SDR track's renderer draws `Rgba8`. The browser
encodes 8-bit only.

### 12. Android zero-copy: the decoder half, and the encoder half if measured

On Android 14 (API 34) and later, MediaCodec decodes into hardware buffers that
the decoder owns in a small ring, and `FrameImage` draws them between Compose's
layers with no copy, crop included. Proven on a Galaxy S25 Ultra: a decoded YUV
buffer wraps as a hardware bitmap and draws between a layer below and one above
with the right frame. The buffer is larger than the picture (192×128 for a
128×128 clip), so drawing takes its crop rectangle. The encoder half is built
only if the measurement at the end makes the case.

```kotlin
public sealed interface VideoOutput {
    public data class Memory(…) : VideoOutput
    /** Android 14+: frames in GPU memory from a MediaCodec decoder, with no CPU pixels. */
    public data object GpuBuffers : VideoOutput
}

// codec androidMain
public val VideoFrame.hardwareBuffer: HardwareBuffer?
public val VideoFrame.hardwareBufferCrop: Rect?
```

`FrameImage.update` takes such frames with its signature unchanged.

The frame:

- The groundwork saved from the probe applies with one move: its `GpuBuffer`
  becomes `NativeGpuBuffer` in `bindings` commonMain, and `NativeDecodedFrame`
  gains `gpu: NativeGpuBuffer? = null`, because the Android decoder in
  `bindings` creates it and `bindings` cannot see `codec`. `VideoFrame` gains
  the `gpu` slot and `of(gpu, …)`, and releases the buffer with its last
  reference. A frame over one has null `format` and `usePlanes`, and `convert`
  fails with a clear message.

The decoder, in Kotlin only:

- `NativeVideoDecoderOutput` gains `GPU_BUFFERS`, which reaches C as the Surface
  output, mapped explicitly rather than by ordinal. `frames()` decodes ahead
  within the ring.
- `createPlatformVideoDecoder` (`NativeExecutionBridge.android.kt`) creates an
  `ImageReader` of `ImageFormat.PRIVATE` with `USAGE_GPU_SAMPLED_IMAGE` and 3
  images, hands its Surface to the decoder as Surface output does, and wraps
  the decoder in a `GpuBufferVideoDecoder`. The readers' listeners share one
  process-wide `HandlerThread`. Below API 34 `open` fails as unsupported.
- `GpuBufferVideoDecoder.frameAt` takes the image whose timestamp equals the new
  frame's pts, which the decoder already stamps on each rendered buffer, waiting
  at most the call's time left, and closes the image with the frame's last
  reference. The same position again returns the same buffer. A software frame,
  from the `AUTO` fallback or a 10-bit source, passes through as it is.
- Three images make the reader the ring: while the caller holds all three,
  MediaCodec cannot render, and a fourth `frameAt` waits, then times out, as in
  change set 18.
- The reader is created before the stream's size is known. The probe's 128×128
  reader took a 192×128 buffer, so a nominal size should do; if the first device
  run says otherwise, `ffmpegkmp_video_decoder_start` splits so the Surface
  attaches after the stream opens.

`FrameImage` on Android:

- A frame with a `hardwareBuffer` shows through a new
  `Bitmap.wrapHardwareBuffer` on every update, with the crop as `source`, which
  `drawUpright` already takes. A wrap is not cached by buffer id: the ring's
  buffers take turns, and HWUI keeps the GPU texture of a hardware bitmap with
  the bitmap, so a wrap made once goes on drawing the first frame its buffer
  held. `FrameImage` retains the frame it shows and closes it on the next
  update, since a wrap is valid only while its image is held, and keeps the last
  two wraps open beside the last two frames. Nothing is written.
- SDR buffers wrap as sRGB. 10-bit buffers first wrapped as `BT2020_PQ` or
  `BT2020_HLG`, which failed the HDR check below on the phone; they now wrap as
  linear sRGB and draw through the renderer's own shader (see the status).
- The renderer's software path cannot draw a hardware bitmap, so `GpuBuffers`
  frames need the GPU renderer.
- `Host.awaitWindow()` in `ComposeFrameRenderer.android.kt` stops waiting after
  5 seconds, with a clear error.

Verify:

- `VideoFrameTest`: a frame over a `NativeGpuBuffer` has no CPU pixels, and its
  release runs once, after the last reference closes.
- A `GpuBuffers` device test on `cfr-30-h264-128.mp4`: a hardware decoder; every
  frame has a GPU-sampled buffer with a 128×128 crop and null `format`; the same
  position twice gives the same buffer; holding three frames, a fourth returns
  once one closes and times out otherwise; `frames()` delivers frames 0–149; the
  fallback gives memory frames, and a 10-bit source stays in a buffer when a
  hardware decoder takes it.
- `FrameImageDeviceTest`: a `GpuBuffers` frame drawn through `FrameImage` matches
  the same position from `Memory()` within 4 per component on flat regions, with
  crop, rotation and aspect right; no bitmap allocations, one wrap per update
  and each frame drawn showing its own contents over 150 updates; the software
  path fails clearly.
- The HDR check: `hdr10-pq.mp4` through `GpuBuffers` into an F16 renderer reads
  about 4.9 (1,000/203) at the fixture's highlight, as the memory path does.
  The precision check: a grey PQ ramp (`hdr10-pq-gradient.mp4`, 256 steps four
  codes apart) drawn the same two ways stays within one PQ code, in linear
  light, in every column.
- On the phone, a 4K H.264 `update` falls from a 4K conversion to under 1 ms.

The gate for the encoder half: a four-source composite export on the phone
(`CompositeExportBudgetDeviceTest`, shared with change set 18) draws four 4K
sources as 2×2 tiles into a 4K HEVC export, from `Memory(canvasFormat)`,
`Memory()` and `GpuBuffers`. It reports frames a second, peak memory, peak
threads, and each frame's `update`, GPU draw, copy out of the `HardwareBuffer`
and `write`. The encoder half is built if, with `GpuBuffers`, that copy is at
least 30% of the frame's time, so that removing it makes the export at least
1.4 times faster. Otherwise change set 12 ends with the decoder half, and the
numbers go here.

If it is built: a MediaCodec encoder of the platform's own with an input
surface, since FFmpeg's surface mode does not fit; `ComposeFrameRenderer`
drawing with `HardwareBufferRenderer` into buffers from an `ImageWriter` on that
surface, stamped with their pts; and the packets muxed through the packet tracks
the browser uses, with the codec-config buffer as extradata. SDR first; HDR only
where the encoder takes 10-bit input. Older devices, the renderer's software
path, and HDR without a 10-bit encoder input keep the one-copy path. It is
planned as a change set of its own then.

Commits: the `NativeGpuBuffer` slot; `GpuBuffers` with its device test and the
window timeout; `FrameImage` drawing hardware buffers, with the HDR check; the
measurement and the decision.

Risks: the reader's size, when images arrive, and GPU fences are settled on the
first device run, and the pixel comparison over many frames catches a late or
torn buffer. The HDR scale is the likely surprise, hence the check. MediaCodec
starts threads of its own, which change set 17 cannot bound and the budget
tests report.

Status: the decoder half is built and, as of 2026-10-08, run on a Galaxy S25
Ultra (Snapdragon 8 Elite, Android 16). Its device tests pass. The four-source
4K H.264 export through `GpuBuffers` runs at 34 frames/s with a 521 MB peak
(update 0.7 ms, GPU draw 3.8 ms, copy 6.3 ms = 22% of a frame), against 5.1
frames/s and 1381 MB from memory; a 4K `update` takes 0.30 ms through
`GpuBuffers`, 2.11 ms from memory. The copy share is under the 30% gate, so the
encoder half is not justified by this plan's rule; the decoder half is a 6.7×
export speed-up on its own. Two phone findings changed the build: a frame
recorded one update behind, because `Dispatchers.Main` dispatches the
renderer's draw as an asynchronous message that a Choreographer sync barrier
runs ahead of Compose's posted draw invalidation (the renderer now awaits a plain
main-handler post before each frame); and `FrameImage` wraps a buffer on every
update, since HWUI's texture for a hardware bitmap never learns of a rewrite.
The HDR check failed first: HWUI maps a BT.2020 PQ hardware bitmap to SDR when
it composites offscreen (the 1000-nit highlight read 0.79 on the F16 canvas,
4.93 from memory), through its own colour management, which a `BT2020_PQ` or
`BT2020_HLG` label cannot avoid. The check is now met by decoding PQ and HLG in
the renderer's own shader over a `LINEAR_SRGB`-labelled wrap: HWUI then applies
no tone map and no curve, the GPU converts the P010 YUV with the buffer's
BT.2020 matrix, and an AGSL `RuntimeShader` takes the codes as plain numbers,
applies the PQ EOTF (or the HLG inverse OETF with the OOTF for 1000 nits) as
the CPU converter does, divides by 203 nits, and maps BT.2020 to sRGB primaries.
10-bit sources therefore stay on the GPU by default (`gpuBuffersKeepDeepSources`
is gone, and `GpuBufferVideoDecoder` no longer reopens them into memory). The
gradient test (`hdr10-pq-gradient.mp4`) is the precision proof that the GPU's
sampler keeps 10 bits. Phone numbers for the highlight and the ramp's worst
column: to be filled in after the next run. Both checks run on request with
`hdrGpuCheck=true`, over two-second fixtures, because the
hardware decoder holds up to its output delay (19 frames here) before the
first picture. What the build settled differently from the plan:

- `NativeGpuBuffer` also carries the buffer's id, which `FrameImage` finds its
  wraps by, and the source's transfer, which picks the wrap's colour space and
  whether the draw uses the HDR shader (`FrameImage.Shown.transfer`).
- The reader is made at a nominal 128×128 and `ffmpegkmp_video_decoder_start`
  stays whole, as the probe's reader suggests; the first device run confirms
  it.
- `GPU_BUFFERS` reaches C as Surface output, which keeps sources deeper than
  8 bits in hardware. `GpuBufferVideoDecoder` first opened such a source a
  second time, for memory output, until the HDR check passed; it no longer does.
- The wait for an image counts against the call's deadline and is reported
  through `timeLeftMicros`, so one that runs out is a timeout like the ring's,
  and leaves the decoder timed out. Each image's fence is awaited before its
  frame is handed out.
- The ring's three are the decoder's latest frame, which is also the one
  `frames()` holds ahead, and two for the caller: `FrameImage` holds the frames
  of its last two updates, the one on screen and the one a drawing may still
  use, and closes each two updates later. So `frames()` with the default
  prefetch into one `FrameImage` uses exactly the three and never waits on
  itself. `VideoOutput.GpuBuffers`' KDoc and the codec README say what a caller
  may hold.
- The plan's "cached by buffer id, at most three wraps" did not survive the
  phone (see the first phone run below): `FrameImage` wraps on every update and
  keeps the last two wraps open. The device test checks one wrap per update and
  logs how many buffers the frames took turns in; MediaCodec dequeues buffers of
  its own from the reader's queue, so there may be more than three.
- On a software canvas `drawFrameImage` fails with the reason itself, rather
  than leaving it to `Canvas`'s own error for a hardware bitmap.
- The measurement's `GpuBuffers` case decodes `clip=h264`, a 4K H.264 clip
  `scripts/generate-budget-clip.sh` now makes too, since a 10-bit source took
  the memory path then. `render` splits into `draw`, which includes waiting for the
  GPU, and `copy`, and the log gives the copy's share of a frame's time, which
  decides the encoder half:

  ```
  ./gradlew :library:ffplay:connectedAndroidDeviceTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.github.aftrolle.ffmpegkmp.ffplay.CompositeExportBudgetDeviceTest \
    -Pandroid.testInstrumentationRunnerArguments.compositeBudget=true \
    -Pandroid.testInstrumentationRunnerArguments.clip=h264
  ```

First phone run (Galaxy S25 Ultra, SM-S938B, Snapdragon 8 Elite, Android 16),
three findings, all fixed:

- A wrap of a `HardwareBuffer` kept showing the first frame its buffer held.
  `framesUpdatingAFrameImageBeforeEachRender…` failed with "the frame drawn at 3
  expected:<3> but was:<2>": the ring's buffers take turns, and when buffer X
  came round again with frame 3, the wrap cached for X still drew frame 2. HWUI
  keeps the GPU texture of a hardware bitmap with the bitmap, and a hardware
  bitmap's generation never changes, so nothing tells it the decoder wrote the
  buffer again; there is no call that invalidates it short of making a new
  bitmap. `update` now wraps the frame's buffer anew each time, which copies and
  allocates no pixels, and closes the wrap two updates later. The test asserts
  one wrap per update and zero bitmap allocations. The 4K `update` stays a
  wrap, not a conversion; its under-1-ms check runs again on the phone.
- `pts` differed from `index.seconds / 30` by 1 ns ("frame 2
  expected:<66.666666ms> but was:<66.666667ms>"): the decoder rescales with
  `av_rescale_q`, which rounds to the nearest, and Kotlin's division truncates.
  Rounding to the nearest stays the contract, now in `VideoFrame`'s KDoc, and
  the GPU tests compute their expectation with the same rounding.
- The hardware HEVC decoder refused the 96x64 HDR10 fixture, so the HDR check
  skipped. `hdr10-pq-large.mp4`, 320x192 with the same recipe, replaces it in
  that test; whether the phone's decoder takes it is the next run's answer.

#### The encoder half

Built despite the gate. The four-source export's copy was 22% of a frame, under
the 30% rule above, but the owner chose to build it anyway: the copy is the last
one in the SDR pipeline, and with it gone the draw and the encode overlap, since
the encoder reads the buffer the GPU wrote while the next frame is composed.
Status: built and host-verified; not yet run on a device.

What a user sees: nothing new to call. `MediaWriter.open`, `addVideoTrack`,
`ComposeFrameRenderer(context, track)`, `renderer.render(time, value)` and
`track.write(frame)` are as before, and `VideoTrack.zeroCopy` reads whether the
track took the surface path (false until a renderer asks).

How it is built:

- `ffmpegkmp_writer_use_packets` turns a video track that has taken no frame into
  a packet track, freeing its FFmpeg encoder on the track's thread. Its packets
  then go through `ffmpegkmp_writer_write_packet`, which the browser's worker
  already used. `NativeVideoTrackInfo.bitRate` carries the bit rate the FFmpeg
  encoder opened with, so the platform encoder gets the same one.
- `SurfaceVideoEncoder` (`bindings`, Android) opens a hardware `MediaCodec` encoder
  with `createInputSurface()` and an `ImageWriter` over it (4 images, RGBA_8888,
  `USAGE_GPU_COLOR_OUTPUT`, sRGB data space). It is opened beside the track's own
  encoder, so a device that cannot run two leaves the track as it was, and
  proves a first buffer can be drawn into before the track gives its encoder up.
  Only 8-bit SDR H.264 and HEVC on a hardware encoder qualify, API 34 and later.
- `VideoTrack.openInputSurface()` (internal API) is the switch the renderer calls
  at its first `render`; it runs on the track's thread, after the writes queued
  before it, and fails without effect once the track has taken a frame.
  `dequeueFrameBuffer()` lends the next buffer, waiting for the encoder.
- `ComposeFrameRenderer.render` then draws with `HardwareBufferRenderer`, the same
  `RenderNode` recording and fence wait as before, into the lent buffer (one
  renderer and display list per buffer, kept by the buffer) and returns a
  `VideoFrame` over it, as `GpuBuffers` frames are: `format` null, `hardwareBuffer` set.
  `track.write(frame)` queues the image to the encoder stamped with the frame's pts.
  Closing a frame that was never written gives the buffer back.
- A thread of the encoder's own takes the packets: the config buffer (or the
  output format's `csd-0` and `csd-1`) is the track's extradata, sent with the
  first packet; the keyframe flag, `presentationTimeUs` as pts (and dts, the
  encoder being told not to reorder frames with `KEY_MAX_B_FRAMES = 0`) and the
  frame period as duration go to `ffmpegkmp_writer_write_packet`. At the end,
  `signalEndOfInputStream()`, then the drain waits for the end-of-stream flag, bounded
  by the writer's `timeout`; an encoder that holds frames that long without a
  packet fails the track and is released, which also wakes a `dequeue` waiting on it.

What stays on the one-copy path, and how the caller knows: HDR and 10-bit
exports, AV1, software encoders, Android before 14, a renderer made by size
instead of for a track, the renderer's software path (it needs the GPU path, and
fails where that fails once it has taken the surface), and any track that
has taken a frame before its renderer's first `render`. For all of them
`track.zeroCopy` stays false and `render` returns a pooled frame as before. A
`zeroCopy` track takes only the frames its renderer drew: a frame from memory
fails the track with the reason.

Measure and verify on the phone:

```
./gradlew :library:ffplay:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.aftrolle.ffmpegkmp.ffplay.CompositeExportBudgetDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.compositeBudget=true \
  -Pandroid.testInstrumentationRunnerArguments.clip=h264 \
  -Pandroid.testInstrumentationRunnerArguments.cases=GpuBuffers,GpuBuffersToSurface
./gradlew :library:ffplay:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.aftrolle.ffmpegkmp.ffplay.SurfaceEncoderDeviceTest
```

`GpuBuffersToSurface` reports `copy` as the wrap of the buffer (about 0), `write`
as the queue time, and `draw` with the wait for the encoder's next buffer and
the render fence. `SurfaceEncoderDeviceTest` encodes 30 rendered frames, H.264
and HEVC, through the surface and decodes them back frame for frame with their
pts, and compares a colour patch with the one-copy path's.

Risks, settled on the first device run: the `ImageWriter` taking the encoder's
surface with GPU-writable buffers (the probe makes this fall back, not fail);
the colour of the encoder's own RGB to BT.709 conversion against swscale's;
`signalEndOfInputStream` after images queued through an `ImageWriter`; a buffer
wrapper per image staying valid across dequeues; and the encoder's own queue
depth, which sets how far the draw runs ahead.

### 13. Decode at a size

A 4K source shown as a 960×540 tile still decodes, converts and uploads 4K
frames today, and Skia scales them when drawing.

- `VideoOutput.Memory(format, size)`: with a size, the decoder's thread scales
  as it converts, in the same swscale pass, so the conversion, and the
  transfer step for HDR sources, run at the smaller size. A size needs a
  format. Changing it means opening another decoder.
- The converter takes a destination of another size. On the HDR routes, the
  first swscale step scales into the intermediate, so the tone map and the
  gamut pass see the small frame. The scaler stays bilinear, which swscale
  widens for downscaling.
- Frames keep their rotation, and report the sample aspect ratio of the new
  size, so a source with non-square pixels scaled to its display aspect comes
  out with square ones.
- The web scales in the worker with `createImageBitmap(frame, { resizeWidth,
  resizeHeight })` before reading the pixels back. On Android API 34+, frames on
  change set 12's path stay full size, since the GPU scales them as it draws.

Verify: 4K PQ and SDR sources at 960×540 against `ffmpeg -vf scale` within a
code or two, a 4K PQ frame to a 960×540 RGBA8 one well under the 23 ms of a
full-size one, and the aspect ratio reported for an anamorphic fixture.

Status: built, and checked on the JVM, macOS and in headless Chrome. The size
is a `FrameSize`, and goes through `ffmpegkmp_video_decoder_create`, JavaCPP,
cinterop and the browser worker. It needs a format, and must be even for the
4:2:0 layouts. Scaled frames report the sample aspect ratio that keeps the
source's display aspect (`ffmpegkmp_scaled_aspect`): a 2:1 anamorphic clip
scaled to 192×64 reports 1.0. Unlike the plan, `VideoFrame.convertInto` scales
into a target of another size too, except from one RGBA F16 frame into
another, where the transfer step works pixel for pixel. The browser scales
RGBA8 and BGRA8 output by drawing each frame into a canvas of the size, the
existing fallback, rather than with `createImageBitmap`.

Against FFmpeg's own `scale=…:flags=bilinear` the frames match bit for bit: SDR
against one pass into RGBA, PQ and HLG against a scale into 16-bit 4:4:4 then
the same conversion, into Rgba8 and RgbaF16. On an M4 Pro a 4K PQ frame
converts into a 960×540 RGBA8 one in 1.9 ms, against 23.6 ms at full size, and
into linear F16 in 1.1 ms against 13.2 ms. SDR is swscale alone and fast either
way (0.22 ms at full size); there the gain is the smaller frame everything after
the decoder handles.

### 14. `FrameImage`: reuse, rotation and aspect

Every `toImageBitmap` allocates a bitmap of the source's size that only the
garbage collector frees: about 130 MB a tick for four 4K sources in SDR and
265 MB in HDR. Rotation and the sample aspect ratio are reported on every
frame, and the scene has to apply them itself.

- `FrameImage`, in `:library:ffplay`, one per source: `update(frame)` converts
  into a bitmap it keeps, only when the frame's pts changes, and allocates
  another only when the size or format changes. It bumps a snapshot state, so
  whatever draws it is invalidated, and `close()` frees the bitmap.
- `DrawScope.drawFrameImage(image, topLeft, size)` draws it rotated about its
  centre and stretched by its sample aspect ratio, which costs nothing on the
  GPU. `displaySize` is the size after both, for layout.
- On change set 12's path, `update` wraps the frame's `HardwareBuffer` with no
  copy, and the draw takes the `Image`'s crop rectangle as its source.
- One bitmap per source, written in place: `update` has to run where the
  drawing does not, before `render` in an export and on the UI thread in a
  preview. The renderers already finish drawing before `render` returns.
  Double-buffering for updates from another thread is left out until a preview
  needs it.

Verify: 100 updates with ten distinct pts allocate ten conversions and one
bitmap, a rotated and an anamorphic fixture draw upright at their display
aspect on Skiko and on both Android paths, and a renderer that draws a
`FrameImage` matches one that draws `toImageBitmap`.

Status: built, and checked on the JVM, macOS and both Android paths on a
device. `FrameImage` and `toImageBitmap` share one bitmap type per platform.
On Skiko the bitmap stays mutable and `notifyPixelsChanged` gives it a new
generation after each write; on Android `AndroidBitmap_unlockPixels` does the
same. In the browser each write replaces the bitmap's pixels in Skia's heap,
which the page cannot write in place. The draw goes through an internal source
rectangle, ready for change set 12's crop. The geometry is shared with the
player's Compose canvas and Android surface.

One finding changed the player: `rotationDegrees` is
`av_display_rotation_get`'s angle, which counts anticlockwise, and the player's
surfaces had been turning frames clockwise by it, so 90° and 270° clips showed
upside down. They now turn the way FFmpeg's own autorotation does, the web
player's canvas included, which is compiled but not yet run in a browser.

On an M4 Pro a warm 4K render takes 8.3 ms drawing a `FrameImage` and 8.1 ms
drawing a `toImageBitmap` bitmap in RGBA8, 11.1 and 10.3 ms in RGBA F16: Skia
draws a mutable bitmap from a copy. A loop calling `toImageBitmap` on each 4K
frame hit JavaCPP's 2 GB physical-memory cap within 60 frames unless it ran the
garbage collector every frame.

### 15. Android frame clock

Compose on Android animates by the Choreographer, not by `render`'s time, and
`render` waits two Choreographer frames, which caps it at about half the
display's frame rate.

- The `ComposeView` in the Presentation gets a `Recomposer` of the renderer's
  own, through `setParentCompositionContext`, on the main dispatcher with a
  `BroadcastFrameClock`. `render(time, value)` sets the state, sends a frame at
  `time`, waits for the recomposer to go idle, then measures, lays out and draws
  the view itself, as Skiko's scene does, instead of waiting for the
  Choreographer.
- `animate*AsState`, `withFrameNanos` and infinite transitions then follow
  `time` on Android as they do on Skiko, and `render` is no longer bound to the
  display.
- This needs a spike on a device first: the Android Compose view may still
  schedule work of its own on the Choreographer. If it does, the fallback is to
  keep the clock, wait one Choreographer frame instead of two, and keep
  documenting the animation rule.

Verify: Skiko's animation-at-time test as a device test, with the same values,
and more renders a second than the display's refresh rate.

Status: built as planned, without the fallback, and checked on a Galaxy S25
Ultra. The recomposer's coroutines run on a main-thread queue that `render`
runs to the end around `sendFrame(time)`, as Skiko's `FrameRecomposer` does,
rather than on `Dispatchers.Main`, whose posts would leave the recomposer a
frame behind. The frame composes inside `sendFrame`, so there is no idle wait,
which an infinite animation would never reach: one render is one frame, as on
Skiko. Only the first render waits for the Choreographer, until the window
attaches the view, and the Presentation's window is hidden, since only the
renderer draws it, which saves about 10%. The frame clock, a one-second
`animateFloatAsState` and a 400 ms infinite transition give Skiko's exact
values at the same six times, on both paths. The system's animation scale no
longer applies.

Renders a second on the 120 Hz phone, before and after:

| Scene | GPU | Software |
|---|---|---|
| 320×180 | 30 → about 260 | 58 → about 790 |
| 3840×2160 | 30 → about 70 | 15 → 22 |

At 4K on the GPU the copy out of the `HardwareBuffer` takes about 9 ms of a
14 ms render, which is change set 12's to remove. Only API 36 was tested.

### 16. HDR metadata for composites

The writer takes the mastering display and content light levels from
`VideoEncoderConfig.hdrMetadata`, or else from the first frame written. A
renderer frame carries none, and `VideoInfo.masteringDisplay` is a map of
strings that no helper turns into a `MasteringDisplay`.

- `VideoInfo.hdrMetadata`: a typed `HdrMetadata`, filled from the values the
  bindings already read as numbers, beside the raw map, which stays for
  compatibility.
- `HdrMetadata.combine(main, others)`: the main source's mastering display, and
  the highest MaxCLL and MaxFALL of all of them. MaxFALL is then an upper
  bound, not the composite's own average.
- The caller passes the result to `VideoEncoderConfig.hdrMetadata`; nothing
  fills it automatically. The ffplay README's export example shows it.

Verify: the HDR10 fixture's `info.hdrMetadata` equals what ffprobe reads, and a
composite of two HDR10 sources probes with the combined values.

Status: built, and checked on the JVM and macOS. `VideoInfo.hdrMetadata` is a
new last constructor parameter. Its `MasteringDisplay` is set only when the
stream gives both the primaries and a valid luminance range, since the writer
always marks both as present, so a stream with only one of them, or a bad
range, gives none rather than failing the open. Content light levels stand on
their own. `combine` takes `others` as a vararg, so two sources read
`combine(a.info.hdrMetadata, b.info.hdrMetadata)`. An HEVC export combining the
HDR10 fixture (1,000/400) with a 4,000-nit P3 source (2,000/300) probes with the
fixture's mastering display and content light 2,000/400.

### 17. One decoder per video: slice threads, not frame threads

`VideoDecoder` decodes each video with one decoder and one frame in progress.
`DecoderThreads` stays the count the caller sets, and becomes the budget for
every thread working on one video's frames, decoding and converting alike.
Playback keeps frame threading, since it shows one video in real time.

`ffplaykmp_video_codec_open` sets `FF_THREAD_FRAME | FF_THREAD_SLICE`, and FFmpeg
takes frame threading wherever the codec has it. H.264, HEVC and VP9 then run up
to 8 copies of the decoder for one video, each holding a decoded frame (about
12 MB at 4K 8-bit, 25–30 MB at 4K 10-bit), with a pipeline to refill after every
seek. On the phone, four 4K 10-bit decoders used 2.7 GB on `Auto` and 1.9 GB on
`Fixed(1)`.

A decoder's threads on an 8-core phone at `Auto`:

| Source | Threads |
|---|---|
| The decoder's own thread | 1 |
| Codec threads, `min(cores + 1, 8)` | 8 |
| swscale, a worker per core in each context: one on the SDR route, two on the HDR routes | 8 or 16 |
| The converter's pool, `min(cores, 8) − 1`, HDR routes only | 7 |
| **Per decoder** | **17 SDR, 32 HDR** |

Each swscale thread also keeps a scaler of its own, so threads cost memory too.

Native:

- `ffplaykmp_video_codec_open` takes a `thread_type`: the decoder passes
  `FF_THREAD_SLICE`, the player `FF_THREAD_FRAME | FF_THREAD_SLICE`.
- `ffplaykmp_decoder_thread_count` returns the resolved count, never 0, so the
  decoder can share it with its converter. FFmpeg's own automatic count is
  `min(cores + 1, 16)` for both kinds of threading, so the player is unchanged.
- `ffmpegkmp_converter_alloc` takes a thread count: a decoder's converter gives
  its swscale contexts that many threads and its pool one fewer, capped as now.
  0 keeps today's defaults, for the shared converters, the player's and the
  writer's. The Emscripten build keeps its fixed counts.

Kotlin needs no API change. `DecoderThreads`' documentation and the codec and
ffplay READMEs say what slice threads split: HEVC with wavefront rows, x265's
default, keeps most of its speed; H.264 written as one slice a frame decodes as
if on one thread; hardware decoders and libaom are unaffected.

The benchmark gains a `parallel` mode, several decoders at once over 4K clips,
reporting frames a second, peak threads and peak footprint. A device test
reports the same for four 4K 10-bit decoders on the phone, run only when asked,
since it takes about ten minutes.

Verify:

- `VideoDecoderSystemTest` already compares `Fixed(1)`, `Fixed(3)` and `Auto`
  frame by frame; the HEVC fixtures join it, since slice threads must give the
  same pixels. Every other decoder and player suite passes as it is.
- `scripts/bench-video-decoder.sh steps`, before and after on the same machine:
  a 4K HEVC PQ forward step at `Auto` within 1.5 times today's. The H.264 clips
  may fall to their one-thread numbers (4.85 ms at 1080p, 20.9 ms at 4K) and are
  reported.
- `parallel`, four 4K HEVC PQ decoders: total frames a second at least today's,
  at least 150 MB less per decoder, and at most 40 threads at `Fixed(2)`.
- On the phone, four 4K 10-bit decoders at `Auto`: at most 2.1 GB, and total
  frames a second at least today's.

Status: built, and measured on a Galaxy S25 Ultra on 2026-10-08: four 4K HEVC
10-bit PQ decoders at `Auto`, full size, peak at 1898 MB resident, within the
2.1 GB target, at 8.4 frames/s in total; at 960×540 they peak at 1099 MB and
40 frames/s, and `Fixed(2)` threads each give the best throughput (43.7
frames/s). `ParallelDecoderBudgetDeviceTest` runs only with the
`parallelBudget=true` instrumentation argument. The HEVC
fixtures in the thread-count comparison are 96×64, one row of coding blocks, so
the slices that split are the benchmark's 4K clips, which x265 writes with
wavefront rows. Auto resolves to `min(cores + 1, 8)`; FFmpeg's own count also
stops at a thread per 16 rows of picture, so on a host of seven cores or fewer
a player of a picture under about a hundred rows gets a few more threads than
before.

On the M4 Pro, on battery, so slower throughout than the table at the top, a
forward step at `Auto` before and after:

| Case | Before | After |
|---|---|---|
| 4K HEVC PQ → RGBA8 | 35.7 ms | 40.0 ms |
| 4K HEVC PQ, as decoded | 5.0 ms | 7.7 ms |
| 1080p H.264 → RGBA8 | 1.49 ms | 6.91 ms (6.82 at `Fixed(1)` before) |
| 4K H.264 → RGBA8 | 7.99 ms | 29.5 ms (29.3) |

The PQ step to RGBA8 stays within 1.5 times; as decoded it is 1.54 times. The
first frame as decoded takes 70 ms rather than 207, with no frame pipeline to
fill. At `Fixed(1)` the PQ step goes from 69 to 253 ms, since the tone map and
swscale now run on the decoder's one thread too.

`parallel`, four decoders at once over a 10-second 4K clip into RGBA8, each
case in a process of its own (footprint is the growth per decoder; threads the
peak, and the peak once every decoder is past its first frames):

| Case | Frames a second | Footprint each | Threads |
|---|---|---|---|
| HEVC PQ, `Auto` | 26.2 → 27.0 | 707 → 441 MB | 210 (162) → 166 (118) |
| HEVC PQ, `Fixed(2)` | 26.2 → 23.1 | 462 → 431 MB | 186 (138) → 70 (22) |
| HEVC PQ at 960×540, `Auto` | 110.8 → 112.4 | 502 → 240 MB | 210 (162) → 166 (118) |
| H.264, `Auto` | 214.7 → 122.1 | 331 → 142 MB | 86 → 62 |

Frames a second hold at `Auto`, at 266 MB less per decoder. At `Fixed(2)` the
four decode 12% slower and run on 22 threads, but peak at 70 on their first
frames: swscale builds
the gamut pass's 3D table on a pool of its own, one thread per core
(`avpriv_slicethread_create` with its automatic count, in `cms.c`), which no
context option bounds. H.264 written as one slice a frame decodes at its
one-thread speed.

Commits: slice threads with the docs; the converter's bound; the benchmark and
the device measurement, with the numbers written here.

Risk: HEVC recorded on phones often has no wavefront rows, so a 4K 10-bit
software decode there runs on one thread. The device test shows what that costs
a composite.

### 18. Frames one ahead, in reusable rings

Each source decodes frame N+1 while frame N is drawn or encoded, into memory
that goes round. Nothing allocates per frame once running.

`frames()` decodes one frame ahead of the collector, down from 2: the decoder
holds the next frame ready while the collector works on the current one. This
was the `prefetch` parameter, 0 to 2, until the public surface pass fixed it at 1.

```kotlin
public fun frames(
    from: Duration = Duration.ZERO,
    until: Duration = Duration.INFINITE,
    step: FrameStep = FrameStep.Decoded,
): Flow<VideoFrame>
```

The ring:

- A decoder's pool holds at most three frames per layout and size: the one the
  caller holds, the one decoded ahead, and one so a caller can keep the previous
  frame while taking the next. While all three are out, the decoder waits for
  one to close instead of allocating.
- `ffmpegkmp_frame_pool_alloc` takes a capacity, where 0 is today's unbounded
  pool, which the shared pool and the writer keep. A bounded pool is a ring of
  buffers made once, whose free callbacks hand them back and wake the waiting
  decoder. It outlives its decoder while frames from it are open. On Apple the
  `CVPixelBufferPool` takes the capacity as its allocation threshold.
- The wait ends on the decoder's deadline, `interrupt` and `abort`. A wait that
  reaches the deadline is a timeout like any other and closes the decoder:
  holding more frames than the ring is the leak it exists to stop. The exported
  `ffmpegkmp_frame_pool_get` never waits; it fails with `EAGAIN`.
- `retain()` shares a frame's slot, and `convert()` copies into the shared pool.
  Either is how a caller keeps more than three frames.
- The ring covers `Memory(format)` on every native platform, and `Memory()` on
  Apple, whose software frames are pooled pixel buffers. Elsewhere `Memory()`
  hands out avcodec's own buffers, which avcodec reuses, and VideoToolbox's and
  MediaCodec's buffers are theirs. On Android, change set 12's `ImageReader` is
  the ring.

`FrameImage` with two bitmaps:

- `update` converts into the bitmap not on screen, then swaps. The one on screen
  is never written, so `update` can run off the drawing thread: a preview
  collects on `Dispatchers.Default` and the UI thread never converts. An export
  calls `update` then `render`, as now. Updates run one at a time, and two
  bitmaps are allocated per size and format.
- With `Memory()`, a 4K 10-bit source costs its decoder's frames and two F16
  bitmaps, about 133 MB, and no pooled RGB frames. With `Memory(RgbaF16)` it
  costs a ring of three F16 frames more, about 200 MB, and converts on the
  decoder's thread, overlapping the drawing. Tiles take `Memory(format, size)`,
  where both are small. The phone measurement picks the README's example.

On the web, only the `prefetch` default and the two bitmaps apply: the worker
still copies each frame into page memory, and there is no ring.

Verify:

- A collector spending 7 ms a frame over the 72-frame 4K H.264 walk waits at most
  60 ms in all with the default `prefetch` (95 ms at 0 and 26 ms at 2 today).
  `prefetch = 3` is rejected, and the test of frames the collector never takes
  runs at 2.
- The pool tests that hold 10 frames hold 3, and a fourth `frameAt` on another
  coroutine returns once one of them closes, or fails as a timeout with a
  1-second deadline. The pool grows by exactly 3 buffers over the test.
- `FrameImageTest` and `FrameImageDeviceTest`: 100 updates alternate between the
  same two bitmaps. An `update` off the drawing thread while a renderer draws
  gives the old frame or the new one, never a mix. Two renderers drawing two
  `FrameImage`s, updated from two coroutines, 50 frames each, each match their
  own source.
- On the JVM, 300 frames of 1080p through `frames()`, `update`, `render` and
  `close`: after 10 frames the native frame counts stay flat and the heap after
  a collection within 16 MB.
- On the phone, the four-source 4K 10-bit composite export (shared with change
  set 12's gate): at most 1.6 GB with `Memory()` and two bitmaps per source
  (1.9 GB today at `Fixed(1)`), native memory flat after warming up, and each
  frame's `update`, `render` and `write` times.

Commits: the `prefetch` default with the docs; the ring, with the test changes;
`FrameImage` on two bitmaps, with the parallel renderers test; the phone
measurement, with the README example updated.

Risks: code that holds three frames and asks for a fourth now times out; the
README says so, with `retain` and `convert` as the way out. A drawing thread two
frames behind `update` would see a bitmap being written; the mixed-frame test
looks for it, and a third bitmap would fix it.

Status: built, and measured on a Galaxy S25 Ultra on 2026-10-08: the four-source
4K composite of the PQ clip into an HDR10 export peaks at 2163 MB with
`Memory()`, over the 1.6 GB target by about 560 MB, at 1.4 frames/s (update 505
ms, draw 153 ms, copy 39 ms), with the native heap at 1905 MB after 24 frames
and 269 MB once closed; `Memory(canvasFormat)` peaks at 2943 MB, and
`GpuBuffers` takes the memory path for a 10-bit source, with the same numbers.
The SDR H.264 numbers are under change set 12. `CompositeExportBudgetDeviceTest`
in `ffplay` runs only with the `compositeBudget=true` instrumentation argument, over change set 17's clip, and
takes its cases as a list and each frame's phases by name, so change set 12 adds
`GpuBuffers` and splits `render` into the GPU draw and the copy as one line each.

`frames()` defaults to one frame ahead and rejects more than 2. The plan's
timing case no longer fits since change set 17: 4K H.264 written as one slice a
frame decodes in about 29 ms, slower than the 7 ms collector, so on the M4 Pro,
on battery, before this change its collector waited 2.15 s in all at
`prefetch = 0` and 1.42 s at 1 and at 2, against the plan's 95 and 26 ms from
frame threads. The test walks 72 frames of 1080p MPEG-4 Part 2 instead, which
decode well within the 7 ms even while other test tasks load the machine: about
190 to 250 ms in all at 0, and 6 to 44 ms at the default. Under that load a few
frames stall, so the test checks the median wait, at most 2 ms, not the total.

The ring is `ffmpegkmp_frame_pool_alloc(3)`. Its buffers are made one by one up
to three and handed back by their free callbacks, which also hold a reference
on the pool, so a pool freed with frames out goes with the last of them; on
Apple the `CVPixelBufferPool` takes the threshold, with buffer age-out off.
The decoder's wait polls its deadline, interrupt and abort every 10 ms. Over the
pool tests the ring makes exactly 3 buffers, as AVBuffers on the JVM and as
`CVPixelBuffer`s on macOS; a fourth `frameAt` returns once a frame closes, and
with a 1-second timeout fails after 1.0 s and leaves the decoder timed out.
Unlike the plan, `retain()` keeps more references but no more frames, since a
reference keeps its frame's slot taken, and `convert()` into the frame's own
format is a retain too; a conversion into another format is the way to keep
more, and the KDoc and README say so. `Memory()` cannot give `FrameImage` F16
bitmaps as the plan assumed: it converts YUV into `Rgba8`, tone mapping HDR, so
an HDR composite takes `Memory(canvasFormat)`, and the ffplay README's example
keeps it, with each source's cost beside it.

`FrameImage` swaps two bitmaps under a lock and publishes the one written by
writing its state in a snapshot of its own, whose apply tells every observer
before `update` returns. Written in the global snapshot, the change reached a
renderer only once some thread sent the apply notifications, and on macOS one
run in three of the parallel renderers test drew a stale frame: another
renderer had taken the change and was still delivering it. The skiko scenes
also ran on `Dispatchers.Unconfined`, so a change from another thread
invalidated a scene on that thread, even mid-draw, where it was lost; each
renderer's scene now runs on the renderer's thread. The mixed-frame test
starts each update as the renderer starts drawing; with the bitmap written in
place it saw 2 mixed drawings in 59 frames and 3 in 149, and with two none. The
ring's wait first timed itself with `clock_gettime`, which the Emscripten
build's strict C11 lacks, and now uses `av_gettime`. On the JVM, 300 frames of
1080p through `frames()`, `update`, `render` and `close` keep the native frame
counts flat from frame 10 on (the test makes the ring's three buffers first,
since the third otherwise comes whenever a hand-out overtakes a close), and
the heap after a collection within 40 KB.

## Public surface pass

The audit of this branch found the centre sound and the edges wide, and two of
its proposals were taken. The library is for decoding several videos frame by
frame, while an encoder encodes frame by frame what Compose renders into each
frame, SDR or HDR without losing quality or colour; the public surface is what
that needs, and the rest is internal or gone. Nothing was published yet, so
removals are deletions.

**One colour and HDR description.** `VideoInfo` carries `color: FrameColor`, the
type a frame's `FrameFormat` carries, in place of five colour-name strings, the
`MasteringDisplayMetadata` string map, a second copy of the HDR10 metadata and
`HdrType`. It reads a stream's values as the converter reads them, so decoding a
frame as it is gives the colour `info` reports. `HdrMetadata` is the one HDR
description, shared with `VideoEncoderConfig`: the mastering display and content
light levels, with `dolbyVision` and `hdr10Plus` as flags. HDR is decided by the
transfer, PQ or HLG, alone. This fixes an iPhone Dolby Vision 8.4 clip, whose
transfer is HLG: the bridge reported it as Dolby Vision, and `DynamicRange.of`
then exported it as HDR10, in PQ. `DynamicRange.of(info)` now maps PQ to `HDR10`,
HLG to `HLG` and the rest to `SDR`.
`sampleAspectRatio` is a `Double`, and `hdrMetadata` is ignored by ranges other
than HDR10, so a source's can be passed as it is. The native snapshot's
`hdr_type` became `hdr_flags`, its Dolby Vision and HDR10+ bits; the colour
fields were already raw FFmpeg values. The eight `FFplay*` typealiases are gone, and FFplay's
capability sets and `FFplayOutputInfo` use `ColorPrimaries` and `ColorTransfer`
rather than names.

**The public surface.** Kept: `VideoDecoder`, `VideoFrame`, `FrameFormat`,
`VideoInfo`, `FrameImage`, `ComposeFrameRenderer`, `MediaWriter`, with
`VideoOutput.Memory` and `GpuBuffers`, `DecoderPreference`, `DecoderThreads`,
`MediaSource`, `FrameRate`, `VideoEncoderConfig`, `DynamicRange` and
`VideoCodec`. Removed from the public API:

- `VideoOutput.Surface`, which only the `GpuBuffers` path used, inside the
  bindings; and `NativeVideoDecoderOutput.SURFACE`.
- The `prefetch` parameter of `frames()`, fixed at 1, and `FrameStep.Every`.
- `MediaOutput.Stream`, `ContainerFormat` with its Matroska, MPEG-TS and
  fragmented MP4 variants: a writer writes an MP4 to a `File` or a seekable
  `Handle`, and `open` takes `fastStart`. The bridge's container switch stays.
- `DynamicRange.canvasFormat` and `VideoTrack.canvasFormat`; the canvas is
  `VideoEncoderConfig.canvasFormat`.
- `ContentProtection` in `codec`, which a `VideoDecoder` could only reject. It is
  `FFplayContentProtection` in `ffplay`, an argument of `FFplayPlayer.prepare`.
- `BrowserDemuxer` and the worker's pull-demux messages, which nothing used.

## Remaining work

In order:

1. Done on 2026-10-08 (change set 17): 1898 MB, within 2.1 GB.
2. Done (change set 18): 2163 MB with `Memory()`, over the 1.6 GB target; the
   `FrameImageDeviceTest` cases pass. Bringing the HDR composite under budget
   is open.
3. Done (change set 12): the device tests pass after the two fixes recorded
   there; the HDR check failed through HWUI's colour management and is now met
   by the renderer's own PQ and HLG shader over a linear sRGB wrap: on the phone
   the 1000-nit highlight reads 4.93 through `GpuBuffers` and 4.95 from memory,
   and the PQ ramp's worst column is 0.86 of one code, so 10-bit sources stay on
   the GPU; the 4K `update` is 0.30 ms. The four-source 4K HDR10 composite then
   runs at 10.5 frames/s with a 910 MB peak (1.4 frames/s and 2163 MB from
   memory), within change set 18's 1.6 GB; its frame is now the 51 ms copy of the
   F16 canvas into the encoder (53%), which a 10-bit input surface would remove.
4. The copy is 22% of a frame, under the 30% gate, and the decoder half alone
   exports 6.7× faster than from memory; the owner built the encoder half
   anyway. Built and measured on the phone: `GpuBuffersToSurface` exports the
   four-source 4K H.264 composite at 31.7 frames/s with the copy at 0 ms, 400 MB
   resident (519 MB with the copy) and a 25 MB native heap (320 MB): the rate is
   now bounded by the four concurrent 4K hardware decoders, since the draw with
   its wait for the encoder's next buffer is 9.6 ms of a 10.6 ms frame.
5. Follow-ups:
   - fewer copies in the browser renderer, and one copy of the aspect formula in
     `PlatformFFplaySurface.web.kt`'s JavaScript;
   - running the web player's rotation fix in a browser, with `rotated-90.mp4`
     in a `BrowserPlayer` integration test.

Change set 8 stays optional.

## Code that goes away

| Removed | Replaced by | Change set |
|---|---|---|
| `ffplaykmp_convert_rgba`, `ffplaykmp_convert_linear_f16`, `ffplaykmp_configure_scaler_colors`, the 12-byte float scratch buffer, and the tone map if swscale's is chosen | `ffmpegkmp_frame_convert`; the transfer helpers stay as tables | 3 |
| The PixelBuffer upload scaler and its `CVPixelBufferPool` in `ffmpegkmp_decoder.c` | the Apple frame pool | 4 |
| `NativeDecodedFrame`, `NativePixelBuffer`, the reused `PixelBuffer` array, `VideoPixelBuffer`, `VideoFrame.retainedCopy` and `ownedByDecoder`, `VideoDecoder.current` | `VideoFrame` references | 4 |
| `VideoOutput.Rgba8`, `LinearF16` and `PixelBuffer` | `FrameFormat` presets | 4 |
| `NativeVideoFrame.rgba` and its per-frame `ByteArray` copies | `VideoFrame` | 4 |
| Four `rasterImage` actuals, three of them identical | `toImageBitmap`, one Skiko and one Android implementation | 4 |
| The "hardware only with a Surface" branch in `ffmpegkmp_open_codec` | MediaCodec for every output | 4 |
| The "must not be called concurrently" rule | the decoder's mutex | 5 |
| Four `FFplayOperationLock` actuals with two implementations | one in `jvmAndroidMain`, one in an Apple source set | any time |

## Decisions

Decided:

- **`VideoDecoder` is reshaped before it merges** (change set 4).
- **A Compose-free `:library:codec`** holds the frame model, `VideoDecoder` and
  `MediaWriter` (see [Modules](#modules)).
- **An export chooses SDR, HDR10 or HLG** with `DynamicRange`, which decides
  every format (see [SDR or HDR output](#sdr-or-hdr-output)).
- **The Android fallback below API 34 handles SDR and HDR.** It draws through a
  software canvas into `ARGB_8888` or `RGBA_F16` frames; HDR needs API 26.
- **Compose's internal scene API is in scope** as the primary Skiko route, with
  the public route as a runtime fallback.
- **HDR to SDR is BT.2390's EETF onto SDR white** (change set 10), from each
  clip's own peak (the lower of MaxCLL and the mastering display's), on the
  brightest component, then swscale's perceptual gamut mapping into BT.709. One
  look everywhere, with no option. swscale's own perceptual tone map was
  measured first and is much darker: a 1,000-nit master puts reference white at
  66% and 100 nits at 54%, where BT.2390 gives 90% and 73%.
- **Exports pick a bit depth**, 8 or 10, beside `DynamicRange` (change set 11):
  SDR at 8 or 10 bits, HDR at 10. Not 12-bit, FFV1 or ProRes.
- **Android gets a GPU-only zero-copy path on API 34+** (change set 12): decoded
  frames stay in HardwareBuffers and Compose draws them between its layers.
  Frames on it have no CPU pixels; older devices, and HDR where the encoder
  takes no 10-bit buffer, keep the one-copy path.
- **Decoders scale at open** (change set 13): `VideoOutput.Memory(format,
  size)`, not a size per call.
- **Rotation and sample aspect ratio apply when drawing** (change set 14), on
  the GPU, through `FrameImage`; frames stay as coded.
- **Composite HDR metadata is the caller's to set** (change set 16), from
  `VideoInfo.hdrMetadata` and `HdrMetadata.combine`.
- **Decoded frames are drawn through a reused `FrameImage`** (change set 14),
  one per source, converted when its pts changes.

- **One decoder per video** (change set 17): `VideoDecoder` uses slice threads
  and no frame threads, so a video has one decoder and one frame in progress,
  because memory matters more than one video's speed in a composite. Threads
  stay allowed, and playback keeps frame threading.
- **A decoder's threads are a budget** (change set 17): `DecoderThreads` bounds
  one video's decoding and converting threads alike.
- **Frame by frame means one frame ahead, in rings** (change set 18): each
  source decodes the next frame while the current one draws, into a fixed ring
  of frames and bitmaps that nothing grows.
- **The ring is three frames, fixed** (change set 18): the caller's, the one
  ahead, and one to spare. A fourth waits, then times out.
- **Android GPU frames are a `VideoOutput`** (change set 12): `GpuBuffers`, on
  Android 14 and later, 10-bit sources included: PQ and HLG draw through the
  renderer's own shader.
- **Android zero-copy comes in two halves** (change set 12): the decoder half
  first, the encoder half only if, in a measured composite export, the copy out
  of the `HardwareBuffer` is at least 30% of a frame's time.
  The owner built the encoder half although the copy measured 22%: it is the
  last copy in the SDR pipeline, and without it the draw and the encode overlap.

Still open:

1. **Probe without fftools.** Worth a second implementation, or not?

## Not planned

- **Parallel fftools commands in one process.** Compiling fftools once per slot
  with renamed globals, or running commands in child processes, would each work
  on some platforms. Neither works everywhere, and both cost more than the
  engines above.
- **A GPU path for Compose rendering on Android API 29–33** through
  `HardwareRenderer` and an `ImageReader`. The software fallback already covers
  those levels with the same formats.
- **A backward-step cache of decoded GOPs.** Threads bring a backward step down
  to about 60 ms. A cache would be the most complex piece of this plan for the
  narrowest gain.
- **GPU rendering of Compose into Metal textures on Apple.**
- **Per-command log routing.** FFmpeg's log callback is process-global, so while
  a command runs, library logs from decoders and writers appear in its logs. This
  plan leaves that as it is.

Change set 4 is the load-bearing piece. Once decoders, renderers and writers
share `VideoFrame` and `FrameFormat`, moving an export from 8-bit SDR to 10-bit
HDR is a change to one argument.
