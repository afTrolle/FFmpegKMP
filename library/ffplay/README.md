# FFplay

`ffplay` is the state-driven Compose Multiplatform playback API for FFmpegKMP.

```kotlin
val player = rememberFFplayPlayer()

LaunchedEffect(source) {
    player.prepare(MediaSource(source))
    player.play()
}

FFplaySurface(player, Modifier.fillMaxSize())
```

A player can be prepared before an output is attached; it publishes its whole observable state as
one immutable `FFplaySnapshot`. Preparation opens the container and inspects its video stream, but
no decoder is created until an output has been negotiated. Each player runs its own native
demux/decode worker with scheduled frames, seek/pause/stop, and replayable mounted input.

`FFplaySnapshot.output` reports the decoder and renderer that actually became active: a hardware
request is never reported as hardware by itself, and `REQUIRE_HARDWARE` fails when the hardware
path cannot be opened. Frames more than one frame interval late are dropped; native drops and
renderer rejections are summed in `FFplaySnapshot.droppedFrames`, kept across surface recreation
and reset by a new source or `stop()`.

`VideoInfo` carries pixel format, bit depth, aspect ratio, rotation, the colour as a `FrameColor`,
and the mastering display and content-light metadata, with Dolby Vision and HDR10+ as flags on
`HdrMetadata`. Whether a source is HDR follows from its transfer, PQ or HLG, whatever the flags say.
HDR is reported as preserved only when the whole active output path advertises the source transfer
and primaries; otherwise PQ and HLG are tone mapped to BT.709 (`TONE_MAPPED`), or are `UNSUPPORTED`
where the output cannot tone map. `FFplayOutputInfo` reports the source's and the output's
`ColorPrimaries`. `FFplayHdrPolicy.FORCE_SDR` keeps HDR sources off the direct surfaces and tone maps
them in software.

## Types from `codec`

The source, decoder and stream types come from the Compose-free [`codec`](../codec/README.md)
module, which `ffplay` exposes as an API dependency: `MediaSource` is what `prepare` takes,
`DecoderPreference` and `DecoderThreads` configure the player, and `VideoInfo` and `DecoderKind`
describe what it plays. They live in `io.github.aftrolle.ffmpegkmp.codec`; binaries built against
0.2 need recompiling.

## Platforms

| Target | Output | Hardware decode |
| --- | --- | --- |
| Android | `AndroidExternalSurface`; `COMPOSE_CANVAS` on request | MediaCodec direct to the `Surface`, software upload on fallback |
| iOS | `AVSampleBufferDisplayLayer`; Compose overlay on fallback | VideoToolbox `CVPixelBuffer`s wrapped in `CMSampleBuffer`s |
| JVM desktop | Compose Canvas | VideoToolbox, D3D11VA/DXVA2, VAAPI (when libva is present), downloaded to the canvas |
| JS / Wasm | HTML canvas via `HtmlElementView` | WebCodecs `VideoFrame`s, Wasm software decode on fallback |

On Android the direct `ContentScale.Fit` surface advertises PQ, HLG, BT.2020 and P3 only from the
display's runtime capabilities. Desktop hardware frames go through the software renderer, so
`zeroCopy` is false there and protected content is never allowed. In the browser the engine runs in
the Emscripten worker and hands frames over through a one-frame mailbox; WebCodecs is reported as
hardware only when the browser's decoder configuration confirms it.

## Protected content

Mark protected inputs with `prepare(source, FFplayContentProtection.REQUIRE_SECURE_PATH)`. Capability negotiation
then requires a platform-verified secure decoder and protected native surface; Compose Canvas,
software downloads, screenshots, and ordinary GPU texture fallbacks are rejected. License and key
exchange remains owned by the platform DRM backend—keys are never passed through the FFmpegKMP
command bridge or exposed in Kotlin snapshots.

Android already marks its external `SurfaceView` secure while such a source is prepared, but it does
not advertise a secure path until a MediaDrm/MediaCrypto-backed secure decoder session is connected.
The current clear-video MediaCodec path therefore rejects protected sources and never copies their
pixels through the software renderer.

The iOS display-layer backend likewise does not advertise `protectedContent` until a platform
content-key session can prove that encrypted samples and decoded frames remain protected. Merely
using VideoToolbox is not treated as a DRM guarantee.

Failed protected prepares discard the prepared source and mounted-resource generation completely.
Replacing an output cannot revive it; the caller must establish a secure session and prepare again.

This release deliberately provides the secure-output contract and fail-closed behavior, not a DRM
license client. A future Android integration will bind `MediaDrm`/`MediaCrypto` to the MediaCodec
decoder before advertising `protectedContent`. A future Apple integration will bind the existing
sample-buffer display view to the app's FairPlay content-key session. Browser support will use EME
and must never expose protected frames to the Canvas fallback. Applications continue to own license
requests, credentials, renewal, and policy decisions.

This follows [Android's secure `SurfaceView` guidance](https://developer.android.com/media/media3/ui/surface)
for DRM playback and the [Encrypted Media Extensions](https://www.w3.org/TR/encrypted-media-2/)
rule that CDM-decoded pixels may be unavailable to Canvas APIs.

## Audio

FFplay plays the source's audio in sync with its video. Audio is decoded and mixed by the `player`
module's engine (`ffmpegkmp_player.c`) from the same input — a path/URL, or a mounted `FileHandle`
(mount a seekable handle rather than a `Source` so the audio can read it too). The audible position
is reported to the video worker through `ffplaykmp_player_set_master_clock`, so audio is the master
clock: frames are scheduled against what the listener hears, and late frames are dropped. When the
audio ends before the video, scheduling continues on the wall clock from that point.

Volume, mute and track controls apply live and are published on `FFplayPlayer.audio`:

```kotlin
player.setVolume(0.8)            // master level, kept across sources
player.setMuted(true)
player.selectAudioTrack(1)       // switch language
player.setAudioTrackEnabled(2, true)  // mix in commentary
player.setAudioTrackVolume(2, 0.4)
```

Levels are the same `AudioLevel`s the command and filter DSLs render, so a preview matches an
export. Set `FFplayConfiguration(audio = false)` for silent previews. Protected sources skip audio
until a secure audio path exists.

In the browser the same engine decodes the audio inside the player's worker, from the input bytes
it already holds, and streams PCM straight to an AudioWorklet; the page applies the master level
immediately, while track changes are heard after the quarter second decoded ahead. Browsers only
start audio after a user gesture: until then video plays on its own, the player emits one
`FFplayEvent.Warning`, and the audio rejoins at the current position on the first gesture.

## Frames as images

`VideoFrame.toImageBitmap()` turns a [`codec`](../codec/README.md) frame, from `VideoDecoder` for
example, into a Compose `ImageBitmap`, converting it once, straight into the bitmap's own memory:

```kotlin
VideoDecoder.open(MediaSource("clip.mp4")).use { decoder ->
    val image = decoder.frameAt(position).use { it.toImageBitmap() }
}
```

- An RGB frame keeps its format where a bitmap holds it: a `FrameFormat.RgbaF16` frame becomes a
  half-float bitmap in linear extended sRGB, so HDR highlights stay above 1.0. YUV frames become
  RGBA8 in sRGB, with PQ and HLG tone mapped to SDR, as `FrameFormat.Rgba8` does.
- On Skiko (JVM, Apple and the web) one implementation, `src/skikoMain`, allocates a Skia `Bitmap`,
  converts into its pixels (`peekPixels().addr`), marks it immutable and wraps it with
  `asComposeImageBitmap()`. RGBA8, BGRA8, RGBA_1010102 and RGBA_F16 frames in sRGB, Display P3 or
  (half floats) linear extended sRGB keep their layout. The browser's frames are RGBA8 bytes in
  the page, which `installPixels` copies into Skia's heap once.
- On Android it converts into an `ARGB_8888` `Bitmap`, or `RGBA_F16` in linear extended sRGB for
  half-float frames (Android 8.0, API 26), through `AndroidBitmap_lockPixels`.

A frame as decoded is then in two buffers, the decoder's and the bitmap's, with no copy in
between. `toImageBitmap` leaves the frame open.

The Compose Canvas fallback draws the player's frames the same way: the player hands over each
frame as decoded (hardware frames downloaded), and the canvas output converts it with
`toImageBitmap` on the player's thread. The browser's worker converts its frames into RGBA8 bytes
before they cross to the page.

`FFplayConfiguration(threads = …)` sets the software decoder's threads, as `VideoDecoder.open`
does (see [`codec`](../codec/README.md#decoder-threads)). The player decodes with FFmpeg's frame and
slice threads, as `ffplay` does, since it shows one video in real time. A `VideoDecoder` uses slice
threads only, keeping one frame in progress: HEVC with wavefront rows keeps most of its speed there,
single-slice H.264 decodes as if on one thread, and hardware decoders and libaom are unaffected.

### Two bitmaps per source

`toImageBitmap` allocates a bitmap for every frame, which only the garbage collector frees: four
4K sources leave about 130 MB behind them each tick in SDR, and 265 MB in HDR. A `FrameImage`
keeps one source's frames in two bitmaps instead, and draws them upright:

```kotlin
val image = remember { FrameImage() }
DisposableEffect(image) { onDispose { image.close() } }
LaunchedEffect(decoder) {
    // Off the UI thread: update writes the bitmap that is not on screen.
    withContext(Dispatchers.Default) {
        decoder.frames(step = FrameStep.Rate(FrameRate(30))).collect { frame -> frame.use(image::update) }
    }
}
Canvas(Modifier.fillMaxSize()) { drawFrameImage(image) }
```

- `update(frame)` converts the frame only when its pts differs from the last one converted, into
  the bitmap that is not on screen, then shows that one. It allocates bitmaps for the first two
  frames of each size and format only. It takes the formats `toImageBitmap` does, and leaves the
  frame open.
- Each `update` changes snapshot state, written once the bitmap is complete, so whatever draws or
  measures the image is invalidated and draws a whole frame, in ordinary UI and inside a
  `ComposeFrameRenderer` alike.
- The bitmap on screen is never written, so `update` can run off the drawing thread: a preview
  collects on `Dispatchers.Default` and the UI thread never converts, and an export calls `update`
  then `render`. Updates run one at a time. A drawing still under way two updates later would see
  its bitmap written, which one update per drawn frame never does. `close()` frees both bitmaps.
- `DrawScope.drawFrameImage(image, topLeft, size)` turns the frame upright by its rotation and
  stretches it to fill the rectangle, on the GPU where Compose draws on one. `displaySize` is the
  frame's size after its sample aspect ratio and its rotation; give the rectangle that aspect to
  show the picture undistorted. Frames themselves stay as coded.
- FFmpeg reports rotation anticlockwise, as `av_display_rotation_get` does, so a frame with
  `rotationDegrees = 90.0` is drawn turned a quarter anticlockwise, as FFmpeg's own tools show
  it. The player's surfaces turn their frames the same way.
- On Skiko the bitmaps stay mutable, and each write gives one a new generation: Skia draws a
  mutable bitmap from a copy of its pixels, and caches what it uploads by generation. That copy
  is small beside the drawing: on an M4 Pro a warm 4K render takes 8.3 ms drawing a `FrameImage`
  and 8.1 ms drawing a `toImageBitmap` bitmap in RGBA8, 11.1 and 10.3 ms in RGBA F16. In the
  browser each write replaces the bitmap's pixels in Skia's heap rather than writing over them,
  as `toImageBitmap` does.
- On Android `AndroidBitmap_unlockPixels` gives a bitmap a new generation after each write,
  which is what the renderer uploads it by.
- On Android 14 (API 34) and later, frames from `VideoOutput.GpuBuffers` lie in GPU memory, and
  `update` shows them with no copy and no bitmap of its own: it wraps the frame's `HardwareBuffer`
  anew each time, with `Bitmap.wrapHardwareBuffer`, and draws the frame's crop of it, SDR as sRGB.
  PQ and HLG buffers wrap as linear sRGB and draw through the renderer's own AGSL shader (Android
  13 and later, which `GpuBuffers` already exceeds): the HDR bitmap colour spaces failed the HDR check:
  below, because HWUI tone maps a `BT2020_PQ` bitmap to SDR when it composites offscreen, the
  1000-nit highlight reading 0.79 on an F16 canvas against 4.93 from memory. A linear sRGB label
  makes HWUI apply no tone map and no curve, so the GPU's external-texture path converts the P010
  YUV with the buffer's BT.2020 matrix and the shader receives the PQ or HLG codes as numbers. It
  decodes them as the CPU converter does, to linear light with 1.0 at 203 nits (PQ's EOTF; HLG's
  inverse OETF with the BT.2100 OOTF for a 1000-nit display) in sRGB primaries, and the F16 canvas
  keeps values above 1.0 and below 0. Draw such frames on an `RgbaF16` canvas: they are not tone
  mapped, so an SDR export takes HDR sources from `Memory(canvasFormat)`.
  It wraps every time because HWUI keeps a hardware bitmap's GPU texture with the bitmap, so a wrap
  of a buffer the decoder has since rewritten would still draw the old frame. A wrap shows its
  buffer only while the buffer's frame is open, so the image keeps the
  frames of its last two updates, the one on screen and the one a drawing may still use, and
  closes each two updates later. That is two of the decoder's ring of three; the third is the
  frame `frames()` decodes ahead, so the loop above never waits on itself. Such frames draw only
  on a GPU canvas, a window's or `ComposeFrameRenderer`'s GPU path, and drawing one on a software
  canvas, such as the renderer's software path, fails with the reason.

## Rendering Compose into frames

`ComposeFrameRenderer` composes content and draws it straight into frames FFmpeg owns, for an
export: overlays, titles, or a whole scene. Each `render(time, value)` composes the content with
`value`, drives the frame clock from `time`, so `withFrameNanos` and animations land exactly on each
frame, and returns a new pooled `VideoFrame` that the caller owns:

```kotlin
MediaWriter.open(MediaOutput.File("export.mp4")).use { writer ->
    VideoDecoder.open(source, VideoOutput.Memory(FrameFormat.RgbaF16)).use { decoder ->
        val config = VideoEncoderConfig(
            3840, 2160, FrameRate(30), VideoCodec.HEVC, DynamicRange.HDR10,
            // Rendered frames carry no HDR10 metadata: take the source's.
            hdrMetadata = decoder.info.hdrMetadata,
        )
        val track = writer.addVideoTrack(config)
        FrameImage().use { background ->
            ComposeFrameRenderer<FrameImage>(track) { image ->
                Canvas(Modifier.fillMaxSize()) { drawFrameImage(image) }
                Titles()
            }.use { renderer ->
                // One frame ahead: the next frame decodes and converts while this one renders and encodes.
                decoder.frames(step = FrameStep.Rate(FrameRate(30))).collect { frame ->
                    frame.use(background::update)
                    track.write(renderer.render(frame.pts, background))
                }
            }
        }
    }
    writer.finish()
}
```

- The canvas is an RGB layout, since Skia draws RGB: `RGBA8` or `BGRA8` in sRGB or Display P3,
  `RGBA_1010102` for 10-bit SDR, and `RGBA_F16` in linear extended sRGB for HDR. The
  `ComposeFrameRenderer(track)` constructor takes the track's `config.canvasFormat`, which its dynamic range
  and bit depth decide, and `track.write` converts the canvas into the encoder's format once, on the
  track's thread. Android draws 10-bit bitmaps from Android 13 (API 33); before it, a 10-bit SDR
  track's renderer draws into `Rgba8`, which the track converts to 10 bits.
- On HDR canvases, the shapes and text Compose draws sit at SDR white, 1.0, which is 203 nits
  once encoded, BT.2408's graphics white, because Compose sets paint colours as 8-bit sRGB. Drawn
  images keep their highlights, such as a decoded HDR frame drawn through a `FrameImage` from an
  F16 frame.
- A rendered frame carries no HDR10 metadata, so an HDR10 export has none unless
  `VideoEncoderConfig.hdrMetadata` gives it, and nothing fills it for you. For several sources,
  `HdrMetadata.combine(main, others)` takes the main source's mastering display and the highest
  MaxCLL and MaxFALL of all the sources' `VideoInfo.hdrMetadata`. MaxFALL is then an upper bound,
  not the composite's own frame average.
- Each source costs its decoder's frames, the ring of three it converts into, and the
  `FrameImage`'s two bitmaps: at 4K in `RgbaF16` about 200 MB for the ring and 133 MB for the
  bitmaps. `VideoOutput.Memory()` has no ring to convert into, but `FrameImage` then converts the
  YUV frames into `Rgba8` bitmaps, tone mapping HDR, so an HDR export decodes into
  `Memory(track.config.canvasFormat)` as above, and an SDR one may take either.
  On Android 14 and later an 8-bit source through `VideoOutput.GpuBuffers` costs neither:
  `FrameImage` draws MediaCodec's buffers as they are. `CompositeExportBudgetDeviceTest` measures four 4K sources all three ways on
  a phone.
- A source drawn smaller than the export, such as a 4K clip in a 960x540 tile, can decode at that
  size: `VideoOutput.Memory(format, FrameSize(960, 540))` scales each frame as it converts, so the
  tone map and the `FrameImage` bitmap work at the tile's size. The frames report the sample aspect
  ratio of the new size, which `drawFrameImage` applies as before.
- What the content leaves uncovered is transparent black, and pixels are premultiplied, so
  transparency comes out as if drawn over black.
- Each renderer composes on a thread of its own, one frame at a time, so several renderers can run
  at once. A 4K frame with a translucent shape and a title renders in about 2 ms on the JVM.
- It uses Compose's own scene API (`FrameRecomposer` and `CanvasLayersComposeScene`, Compose 1.12),
  as `ImageComposeScene` does inside, pointed at the frame. Where the Compose an app resolves lacks
  that API, it falls back to `ImageComposeScene`, which renders into a surface of its own and replays
  the content into the frame, at the cost of that 8-bit surface; it logs the fallback once. A test
  renders the same content through both and compares the pixels.
- It runs on the JVM, macOS and iOS. In the browser Skia has a heap of its own, so the drawn frame
  takes three copies to reach the RGBA8 frame: Skia reads its surface into a bitmap, the bitmap
  into a Kotlin array, and the array's rows into the frame. A `VideoTrack` then copies the frame
  into the buffer it hands its worker, twice on Kotlin/Wasm, and WebCodecs copies that buffer into
  the `VideoFrame` it encodes.
- On Android the constructors take a `Context`. A composition needs a window there, so the
  renderer hosts the content in a `Presentation` on a private `VirtualDisplay`, which needs no
  permission, and hides the window, since only the renderer draws it. The composition has a
  `Recomposer` and frame clock of the renderer's own, as on Skiko: each `render` composes at `time`,
  runs the frame's animations and effects, then measures, lays out and draws the view itself, so
  `withFrameNanos`, `animate*AsState` and infinite transitions follow `time`, and the system's
  animation scale does not apply. Only the first `render` waits for the display, until the window
  has attached the view, and fails with the reason if that takes 5 seconds. It draws on Android 14 (API 34) and later on the GPU, recording the view
  into a `RenderNode` that `HardwareBufferRenderer` draws into a `HardwareBuffer`, and earlier, or
  where that fails, onto a software canvas over a bitmap. One conversion copies the pixels into the
  pooled frame. The canvas is `Rgba8`, or `RgbaF16` from API 26. On a Galaxy S25 Ultra, whose
  display runs at 120 Hz, a small scene renders about 260 times a second on the GPU and 790 in
  software, and a 4K scene 70 and 22, where the copy out of the GPU's buffer takes most of a GPU
  frame's 14 ms.
- On Android 14 and later the copy can go too, for an 8-bit SDR H.264 or HEVC track, or an HDR10 or
  HLG HEVC track, on a hardware encoder (`track.zeroCopy`, see the codec README): the first `render`
  of a renderer made for the track opens the encoder's input surface, and each frame is drawn on the
  GPU with `HardwareBufferRenderer` into the next buffer an `ImageWriter` on that surface lends, the
  render fence awaited as before. `render` returns a frame over that buffer, with a null `format` and
  no pixel copy, which `track.write(frame)` queues to the encoder with the frame's pts, so the
  encoder reads the buffer the GPU wrote while the next frame is composed. An HDR track's buffers
  are `RGBA_1010102` and hold PQ or HLG codes, which HWUI cannot be told to write into a buffer, so
  the view is drawn onto the renderer's F16 canvas as on the one-copy path and a second GPU pass
  encodes that through an AGSL shader: sRGB to BT.2020, 203 nits at 1.0, then the PQ curve, or
  HLG's inverse OOTF and OETF, the inverse of the shader that draws 10-bit `GpuBuffers` frames. A
  renderer that has taken the surface draws on the GPU only and fails where it cannot, and the
  renderer made by size, with no track, keeps the copy. A phone's four-source 4K export spent 6.3 ms
  of each frame, 22% of it, in that copy in SDR, and 51 ms, 53%, in HDR (change set 12);
  `CompositeExportBudgetDeviceTest`'s `GpuBuffersToSurface` case measures the frame without it.
  Colours match the one-copy path up to the encoder's own RGB to YUV conversion, which
  `SurfaceEncoderDeviceTest` checks in SDR and in HDR10.

## Deferred picture-in-picture

Android PiP can wrap the existing external surface with a media session and PiP action adapter.
iOS PiP can reuse the current `AVSampleBufferDisplayLayer` view with
`AVPictureInPictureController.ContentSource`, plus an audio session and playback delegate. Both
must keep surface detach/reattach independent from the prepared source and route remote play,
pause, and seek commands through the existing `FFplayPlayer` methods.

## Tests

Run the shared lifecycle, scheduling, capability, surface-churn, concurrency, and protected-content
tests on every supported target with:

```shell
./gradlew :library:ffplay:commonTestAllTargets
```

`toImageBitmap` runs against the real bridge and the decoder's golden references on the JVM and
Kotlin/Native (`src/systemTest`, e.g. `./gradlew :library:ffplay:jvmTest
:library:ffplay:macosArm64Test`), over the clips in codec's `src/commonTest/resources/video-decoder`.
`ComposeFrameRendererTest` renders into every canvas format through both routes, drives the frame
clock and encodes an HDR canvas, on the same targets. `FrameImageTest` checks that 100 updates over
ten pts convert ten times into the same two bitmaps in turn, that an update off the drawing thread
while a renderer draws gives the old frame or the new one, never a mix, and that two renderers
drawing two `FrameImage`s updated from two coroutines each match their own source; it draws a
rotated clip and synthesised anamorphic frames upright at their display aspect, and checks that a
renderer drawing a `FrameImage` matches one drawing `toImageBitmap`. `FrameImageDeviceTest` does
the same on both Android paths, the GPU and the software canvas, and draws `GpuBuffers` frames
against the same frames from memory. With `hdrGpuCheck=true` it also runs the HDR checks: an HDR10
highlight reads 1000/203 on an F16 canvas through `GpuBuffers` as from memory, and a PQ ramp of 256
grey steps four codes apart (`hdr10-pq-gradient.mp4`) stays within one PQ code of the memory path in
every column, which shows the GPU's sampler keeps 10 bits, and saturated BT.2020 patches in PQ
(`hdr10-pq-patches.mp4`) and HLG (`hlg-large.mp4`) land on their linear colours through `GpuBuffers` as
from memory, which a GPU converting with BT.709's matrix or ignoring the buffer's data space would
fail; `ComposeFrameRendererDeviceTest` checks the
Android formats, the GPU and software paths against each other, and a MediaCodec encode;
`SurfaceEncoderDeviceTest` encodes 30 rendered frames through the encoder's input surface and decodes them
back frame for frame, compares their colours with the one-copy path's, and sends six patches of known
linear light through an HDR10 track's surface and through the converter, which must decode back as BT.2020
PQ within a few HEVC codes of each other and of what was drawn.
`src/jvmTest` checks that a 4K frame reaches its bitmap without a bridge allocation, and that 300
frames of 1080p through `frames()`, `update`, `render` and `close` keep the native frame counts flat
after the first 10 and the heap within 16 MB, and the
Android conversion through `AndroidBitmap_lockPixels` is a device test
(`./gradlew :library:ffplay:connectedAndroidDeviceTest`). `CompositeExportBudgetDeviceTest` measures a
four-source 4K export on a phone, timing each frame's GPU draw apart from the copy out of its
`HardwareBuffer` and from the write, with a case that draws into the encoder's input surface, and runs only with the `compositeBudget=true` instrumentation argument, after
`scripts/generate-budget-clip.sh` has made its clips.
