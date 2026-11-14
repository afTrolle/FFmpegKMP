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
demux/decode worker, which decodes ahead into a bounded frame queue, with seek/pause/stop and
replayable mounted input; the display shows the queued frames as they fall due (see
[Timing](#timing)).

`FFplaySnapshot.output` reports the decoder and renderer that actually became active: a hardware
request is never reported as hardware by itself, and `REQUIRE_HARDWARE` fails when the hardware
path cannot be opened. Queued frames that another due frame overtakes are dropped, as ffplay drops
them; native drops, the frames a catch-up skips and renderer rejections are summed in
`FFplaySnapshot.droppedFrames`, kept across surface recreation and reset by a new source or
`stop()`.

`VideoInfo` carries pixel format, bit depth, aspect ratio, rotation, the colour as a `FrameColor`,
and the mastering display and content-light metadata, with Dolby Vision and HDR10+ as flags on
`HdrMetadata`. Whether a source is HDR follows from its transfer, PQ or HLG, whatever the flags say.
HDR is reported as preserved only when the whole active output path advertises the source transfer
and primaries; otherwise PQ and HLG are tone mapped to BT.709 (`TONE_MAPPED`), or are `UNSUPPORTED`
where the output cannot tone map. `FFplayOutputInfo` reports the source's and the output's
`ColorPrimaries`. `FFplayHdrPolicy.FORCE_SDR` keeps HDR sources off the direct surfaces and tone maps
them in software.

## Types from `codec`

The decoder and stream types come from the Compose-free [`codec`](../codec/README.md) module,
which `ffplay` exposes as an API dependency: `CodecSelection` (a `CodecPreference` or a
`NamedCodec`) and `DecoderThreads` configure the player, and `VideoInfo` and `DecoderKind` describe what it plays. They live in
`io.github.aftrolle.ffmpegkmp.codec`. What `prepare` takes, `MediaSource`, is in
`io.github.aftrolle.ffmpegkmp.core`; binaries built against 0.2 need recompiling.

## Platforms

| Target | Output | Hardware decode |
| --- | --- | --- |
| Android | `AndroidExternalSurface`; `COMPOSE_CANVAS` on request | MediaCodec direct to the `Surface`, software upload on fallback; on the Canvas, Android 14+, MediaCodec's frames from GPU memory |
| iOS | `AVSampleBufferDisplayLayer`; Compose overlay on fallback | VideoToolbox `CVPixelBuffer`s wrapped in `CMSampleBuffer`s |
| JVM desktop | Compose Canvas | VideoToolbox, D3D11VA/DXVA2, VAAPI (when libva is present), downloaded to the canvas |
| JS / Wasm | HTML canvas via `HtmlElementView` | WebCodecs `VideoFrame`s, Wasm software decode on fallback |

On Android the direct `ContentScale.Fit` surface advertises PQ, HLG, BT.2020 and P3 only from the
display's runtime capabilities. The Canvas takes hardware frames as described under
[The Canvas on Android 14](#the-canvas-on-android-14). Desktop hardware frames go through the software renderer, so
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
module's engine (`ffmpegkmp_player.c`) from the same input: a path or URL, or a mounted `FileHandle`
or `Source`, whose bytes the video and the audio share through one replay cache. The audio opens it
with what the video's prepare found when it probed it, its demuxer, start and duration, so an input
whose header declares its audio (MP4, MOV, Matroska, WebM, NUT) is probed once per prepare; one that
does not, such as MPEG-TS, is probed by each, as before. The audible position
is reported to the video worker through `ffplaykmp_player_set_master_clock`, so audio is the master
clock: frames are scheduled against what the listener hears, and late frames are dropped. When the
audio ends before the video, scheduling continues on the wall clock from that point.

`FFplayPlayer.audio` holds the prepared source's `AudioMixControls`, the same controls as
`AudioPlayer`'s; they apply live. While there is no audio it has no tracks, but its master level
belongs to the player: it is kept across sources and can be set before the first one.

```kotlin
val audio = player.audio.value
audio.setVolume(0.8)             // master level, kept across sources
audio.setMuted(true)
audio.selectTrack(1)             // switch language
audio.setTrackEnabled(2, true)   // mix in commentary
audio.setTrackVolume(2, 0.4)
```

Levels are the same `AudioLevel`s the command and filter DSLs render, so a preview matches an
export. Set `FFplayConfiguration(audio = false)` for silent previews. Protected sources skip audio
until a secure audio path exists.

In the browser the same engine decodes the audio inside the player's worker, from the input bytes
it already holds, and streams PCM straight to an AudioWorklet; the page applies the master level
immediately, while track changes are heard after the quarter second decoded ahead. Browsers only
start audio after a user gesture: until then video plays on its own, the player emits one
`FFplayEvent.Warning`, and the audio rejoins at the current position on the first gesture.

## Timing

The player is a port of ffplay's display loop. The session thread reads, decodes and hands each
frame to the output as it enters a queue of `FFplayConfiguration.frameQueueSize` frames (3 by
default, ffplay's; at least 1), and blocks only while the queue is full. Whoever drives the display
calls the player's refresh, ffplay's `video_refresh`: of the queued frames that are due it shows the
newest and drops the older ones, so a decoder that falls behind still shows what it decodes, and it
says how long until the next frame is due.

- The Compose Canvas refreshes once per display frame from the composition's frame clock
  (`withFrameNanos`), choosing the frame for the time it will be visible, one display frame ahead.
  On Android the frame clock's times are the player's own clock, so every player in a window
  chooses its frame for the same vsync time; elsewhere it chooses the frame for the time the
  refresh runs. Outputs without a frame clock (Android's `SurfaceView`, iOS's layer) are refreshed
  by one coroutine per player that waits as long as the last refresh said. No output has a thread
  of its own for presenting.
- A MediaCodec decoder rendering to a Surface hands its buffers over about two display frames ahead,
  with the frame's due time as the render timestamp, so the compositor shows each at its vsync. The
  player's waits on MediaCodec are bounded (50 ms), so a stalled codec cannot hang it.
- When a decoded frame is more than 500 ms behind the clock, the player skips forward to the first
  keyframe at or after the clock, drops what it passed over (counted in `droppedFrames`) and shows
  the frame it lands on when the clock reaches it. It is not a seek: no `SEEKING`, no preview. A
  source with no later keyframe decodes on. ffplay's decoder-side early drop is not ported: on a
  decoder slower than real time it drops every frame.

Each slot of the queue holds one frame: on the Canvas, the bitmap it was converted into, drawn width
× height × 4 bytes, with the Canvas keeping `FFplayConfiguration.canvasFrames` bitmaps, by default `frameQueueSize` + 2
(the queued frames, the frame on screen and the one before it, which a drawing may still read); on
other outputs the decoded frame; for a hardware decoder, one of its output buffers, as many as the
codec gives up to the queue's size. `canvasFrames` goes down to 1 to save memory: below the default
the frame shown before is reused at once, queued frames without a bitmap are converted as they are
shown, on the drawing thread, and at 1 each frame is written over the one on screen, which a
drawing under way may show partly written. A player's memory is therefore its decoder's frame
threads × one picture, its queue × one drawable frame, and the decoder's own reference pictures.
`frameQueueSize = 1` costs no more bitmaps than a player held before 0.6. The browser's player paces
its own frames and ignores `frameQueueSize`.

### Following a clock

`player.follow(clock)` makes the player show, at each display frame, the frame for the media
position an `FFplayClock` gives, instead of pacing itself. Several players on one clock show the
same instant in the same display frame. The clock is a function per player, so an editor derives
each clip's position from its one timeline clock with that clip's offset, trim and loop:

```kotlin
// timeline.positionAt(frameTime): where the editor's one timeline is at that display frame.
player.follow { frameTime ->
    val local = (timeline.positionAt(frameTime) - clip.start).coerceAtLeast(Duration.ZERO)
    val inClip = if (clip.loops) (local.inWholeMicroseconds % clip.length.inWholeMicroseconds).microseconds else local
    clip.trimStart + inClip
}
```

- The frame shown is the one whose span contains the position, the frame `VideoDecoder.frameAt`
  returns for it, so a preview and a frame-by-frame export of one position show the same frame.
- The video decodes ahead in one run and is never paused per frame: a position that advances
  consumes the queue, one that stands still or repeats holds its frame. A jump of the clock itself,
  back or more than 500 ms further than the frame time moved, seeks, as a loop wrap does, and the
  first frames after it wait for that seek. Positions before the start or past the end hold the
  first or last frame, silent.
- Audio plays while the position advances within 25 % of real time, judged over 100 ms, and is
  silent otherwise, also when no frame has asked the clock for 200 ms. It seeks when the clock jumps
  and is sought back when it drifts more than 100 ms; it is not resampled.
- While following, the clock is the transport: `play`, `pause` and `seekTo` throw
  `IllegalStateException`. `follow(null)` stops following and pauses; the player's own clock (the audio,
  else the wall clock) takes over at the next `play`.
- `positionAt` is called once per display frame on the refreshing thread, so it must be fast and
  thread-safe. The browser cannot follow a clock and throws `UnsupportedOperationException`.

### The Canvas on Android 14

On Android 14 (API 34) and later the Compose Canvas output draws a hardware decoder's frames from
GPU memory, with no copy and no bitmaps. The player renders each MediaCodec frame, as it is queued,
into an `ImageReader` of the output's own (the class `VideoOutput.GpuBuffers` uses), and the output
waits on the session thread, bounded, for the image that carries it; presenting flips to that image.
The reader holds `canvasFrames` images, down to 1. Nothing needs choosing: until the stream is
known, and for a stream the Canvas does not take, frames come in memory and are converted as above,
and `FFplayOutputInfo` says which path is in use (decoder `HARDWARE` with `zeroCopy`, or
`SOFTWARE`). A resize costs nothing, since the GPU scales the frame as it draws it.

- BT.709 SDR draws as it is.
- PQ and HLG draw tone mapped by an AGSL shader that follows the CPU converter step by step: BT.2390
  to SDR white at the clip's peak, then the converter's own gamut map into BT.709, looked up
  tetrahedrally. The peak comes from the stream's metadata, not from each frame, and the result
  assumes an sRGB canvas. On an S25 the drawn frames are within 3 (HLG) and 4 (PQ) 8-bit codes of
  the software conversion.
- SDR in Display P3 or BT.2020 primaries stays converted in software, because Android's mapping of
  such a buffer into an sRGB canvas differs from the conversion by up to 16 8-bit codes.

Older Android versions, and other platforms, keep the software path.

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

The Compose Canvas output draws the player's frames the same way, ahead of their time: the player
hands over each frame as decoded as it enters the frame queue, and the canvas output converts it
then, on the player's session thread, into a free bitmap of a ring of `frameQueueSize` + 2
(see [Timing](#timing)); showing it later is a flip. Android's Surface fallback draws each frame onto the surface before it takes the next,
so it converts every frame into one reused bitmap. A desktop hardware frame is downloaded as it is
converted, once, into one of two buffers the player reuses. Both convert straight to the size the frame is
drawn at, not the source's (see [Bitmaps at the drawn size](#bitmaps-at-the-drawn-size)). The
browser's worker converts its frames into RGBA8 bytes, at the drawn size too, before they cross to
the page.

`FFplayConfiguration(threads = …)` sets the software decoder's threads, as `VideoDecoder.open`
does (see [`codec`](../codec/README.md#decoder-threads)). `DecoderThreads.Auto` is two frame
threads for a player: it decodes about a frame ahead of real time rather than as fast as it can, and
each frame thread holds one decoded picture, so several players at once stay bounded and leave the
other cores to the app. `DecoderThreads.Fixed(count, types)` sets FFmpeg's `thread_count` and
`thread_type` exactly; the browser ignores the types.

`FFplayConfiguration(decoder = …)` takes a `CodecSelection`: a `CodecPreference`, or a
`NamedCodec` that opens exactly that FFmpeg decoder with its options and nothing in its place. A
named decoder that does not open or fails to decode fails the player with the reason in
`FFplayFailure.message`; a named platform decoder (`h264_mediacodec`) renders to the native surface
as the hardware one does. The player opens its decoder through the same helper as `VideoDecoder`, so
the `AUTO` fallback is one rule for both: a hardware decoder that fails to open falls back to
software at once, reporting `FAILED`. The browser fails a `NamedCodec` as unsupported.

The player keeps its video decoder between seeks, pauses and plays, and releases it once it has had
nothing to decode for `FFplayConfiguration(decoderIdleRelease = …)`, 2 s by default: paused,
ended, waiting for an output, or showing a seek's frame. A scrub, or a play soon after a pause, uses
the decoder it has and goes on from the frame on screen; a play or seek after the release opens one
and decodes from the keyframe before the position, as the first seek did. The released decoder's
frames and threads are freed (a paused 4K software decoder holds about 315 MB of them), and so is a
MediaCodec instance; the frame on screen stays. `Duration.ZERO` releases it as soon as a decode
ends, and null keeps it until the source, the output or its Surface changes.

A hardware decoder takes a place in `DecoderBudget` (see [`codec`](../codec/README.md#hardware-decoder-budget)),
which players share with `VideoDecoder`s, and the release gives it back. A player that finds every
place taken decodes in software under `AUTO`, and `FFplayOutputInfo.decoderFallback` and the
`FFplayEvent.DecoderFallback` say `BUDGET`; under `REQUIRE_HARDWARE` the call that needed the
decoder throws a `NativePlayerException` whose `errorCode` is `NativePlayerError.BUSY`, and an
output attached after prepare fails the player with it as `snapshot.failure.cause`. A budget fallback lasts
for that decoder only: the next one the player opens, after its release or for a new output, asks
the budget again. On Android it lasts for the Surface instead, since the canvas has drawn the
software frames on it and MediaCodec can no longer connect to it; a new Surface asks again.

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
- `targetSize` converts frames at the size the image is drawn at rather than their own; see below.

### Bitmaps at the drawn size

A bitmap at the source's size is wasted on a picture drawn smaller: a paused player keeps its
bitmaps, so sixteen 4K players drawn as tiles held about 1.6 GB of bitmaps they drew at a sixteenth
of their area. Frames are therefore converted straight to the size they are drawn at, in the same
pass as the conversion to RGB, on whichever thread converts:

- `FrameImage.targetSize` is the size, in pixels, the image is drawn at, upright as `displaySize`
  is; null, the default, keeps the frame's own size. A player's Canvas surface sets it to
  `FFplaySurface`'s `resolution`, null by default. While that is null, as it draws, it records the
  canvas size and its `ContentScale` in two atomic fields (no snapshot state, so drawing neither
  invalidates nor loops), and the next update places the frame there itself. The first frame after
  a surface has drawn once is converted at its drawn size, even before anything was shown.
  `targetSize`, when set, wins.
- A surface cannot see a transform drawn around it: `Modifier.graphicsLayer` and `Modifier.scale`
  leave the canvas its unscaled size, so a zoomed surface would show its drawn-size bitmap
  stretched. An editor that zooms passes the size the picture is rendered at, such as the drawn
  size times the zoom, or the video's own size for every pixel:
  `FFplaySurface(player, Modifier.graphicsLayer(scaleX = zoom, scaleY = zoom), resolution = …)`.
  The browser's worker and Android's Surface fallback take it too; frames a platform's own video
  view shows from a hardware decoder are full size whatever it says.
- The size is not used raw: the bitmap is one of 16 steps of the frame's own size,
  `size × step / 16`, the smallest that covers the drawn size on both axes after turning a quarter
  turn back to the frame's orientation. So the aspect is the frame's, and the bitmap is never larger
  than the frame. A larger step is taken at the next update; a smaller one only when it is two steps
  or more below the current one, so a size wobbling about a step's edge never reallocates. A step
  change replaces the image's bitmaps one per frame, through the usual reuse: one allocation per
  bitmap per change, none within a step.
- `ContentScale.Crop` keeps the whole picture: the bitmap covers the placed frame, not only the
  part inside the canvas, capped at the frame's size.
- Layout never follows the bitmaps: `displaySize` comes from the frame's coded size, sample aspect
  ratio and rotation, and `drawFrameImage` stretches the smaller bitmap into the same rectangle.
- A player's Canvas output keeps the decoded frame on screen until a newer one replaces it, so a
  surface that is resized while the player is paused converts that frame again at the new size,
  off the drawing thread. While it plays, frames already queued before a resize are drawn scaled
  until frames converted at the new size replace them; nothing is flushed.
- An `RgbaF16` frame is converted at its own size whatever the target: the converter takes a
  half-float frame into a bitmap pixel for pixel, and refuses another size. On the web the page
  tells the worker the step the canvas draws at, and the worker converts to it; the page copies
  those bytes as they are.
- Android's Surface fallback sizes its one bitmap the same way, from where it places the frame on
  the surface.

A player's Canvas output keeps `canvasFrames` bitmaps (`frameQueueSize` + 2 unless set: five by
default, three at `frameQueueSize = 1`; one at `canvasFrames = 1`), each in RGBA8:

| Drawn at | Bitmap | Each, from 1080p | Each, from 4K |
|---|---|---|---|
| source size | 1920 × 1080 / 3840 × 2160 | 8.3 MB | 33.2 MB |
| 1920 × 1080 | step 16 / step 8 | 8.3 MB | 8.3 MB |
| 960 × 540 tile | step 8 / step 4 | 2.1 MB | 2.1 MB |
| 256 × 144 thumbnail | step 3 (360 × 203) / step 2 (480 × 270) | 0.29 MB | 0.52 MB |

Sixteen paused 4K players with `frameQueueSize = 1` therefore hold about 100 MB as tiles instead of
1.6 GB at the source size. RGBA F16 doubles each figure, and Android's Surface fallback holds one
bitmap. The converter's own
HDR intermediate is made at the bitmap's size too, so it shrinks with them.

What it does to the conversion's time depends on the route (`scripts/bench-video-decoder.sh convert`
on an M4 Pro, which is noisy at this scale): a 4K PQ frame tone mapped into RGBA8 takes 3 to 8 ms at
960 × 540 against 40 to 64 ms at its own size, since the tone map runs after the first scaling step.
An SDR frame does not get faster: the same-size YUV to RGBA8 pass is swscale's unscaled fast path,
0.1 to 0.3 ms at 1080p and 0.3 to 1.1 ms at 4K, and the scaled one takes 0.4 to 0.6 ms and 0.9 to
1.6 ms. Below a millisecond either way, it buys the memory and a 16 times smaller upload per frame.
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
  anew each time, with `Bitmap.wrapHardwareBuffer`, and draws the frame's crop of it, SDR in its own
  primaries (sRGB, Display P3 or BT.2020, with the sRGB curve), which HWUI maps into the canvas.
  On an S25 a Display P3 buffer drawn this way is up to 16 8-bit codes from the software conversion
  on flat areas.
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
renderer drawing a `FrameImage` matches one drawing `toImageBitmap`. With 4K frames it checks that
a target of 1920 × 1080, 960 × 540 and 256 × 144 gives bitmaps at the step that covers it, that a
step change allocates three bitmaps and 100 updates within a step none, that a 60-frame shrink and
grow sweep allocates at most three bitmaps per step change, that a player's image drawn by a
`ContentScale.Fit` or `Crop` surface converts its first frame at the drawn size, that `displaySize`
is the video's at every target, rotated and anamorphic frames included, and that an `RgbaF16` frame
keeps its size. `FrameImageDeviceTest` does
the same on both Android paths, the GPU and the software canvas, and draws `GpuBuffers` frames
against the same frames from memory; it checks a target size there too, and that the Surface
fallback sizes its one bitmap from where it draws the frame. With `hdrGpuCheck=true` it also runs the HDR checks: an HDR10
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
