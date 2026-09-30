# FFplay

`ffplay` is the state-driven Compose Multiplatform playback API for FFmpegKMP.

```kotlin
val player = rememberFFplayPlayer()

LaunchedEffect(source) {
    player.prepare(FFplaySource(source))
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

`FFplayVideoInfo` carries pixel format, bit depth, aspect ratio, rotation, color description,
mastering and content-light metadata, and the HDR10/HLG/HDR10+/Dolby Vision classification. HDR is
reported as preserved only when the whole active output path advertises the source transfer and
color space; otherwise PQ and HLG are tone mapped to BT.709 (`TONE_MAPPED`) and other HDR transfers
are `UNSUPPORTED`. `FFplayHdrPolicy.FORCE_SDR` keeps HDR sources off the direct surfaces and tone maps
them in software.

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

Mark protected inputs with `FFplayContentProtection.REQUIRE_SECURE_PATH`. Capability negotiation
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

## Frame-accurate decoding

`VideoDecoder` returns the frame shown at a position, for exporters and tests that walk their own
clock. It has no clock, scheduler or queue and never drops a frame: each call decodes on the
decoder's own thread (MediaCodec sessions are bound to one), outside the command FIFO.

```kotlin
VideoDecoder.open(FFplaySource("clip.mp4"), VideoOutput.Rgba8).use { decoder ->
    decoder.seekTo(trimStart)
    for (tick in 0 until ticks) {
        val frame = decoder.frameAt(trimStart + tick.seconds / 30)
        frame.image // drawn with frame.rotationDegrees and frame.sampleAspectRatio applied
    }
}
```

The frame at position s is the decoded frame with the largest pts <= s, held until the next frame's
pts; before the first frame the first is returned and past the end the last is held. Positions
count from the input's start time, as FFplay and `-ss` do, and match a pts to the nearest unit of
the stream's time base, which absorbs the container's own timestamp rounding (Matroska stores
milliseconds). A frame's end is the next frame's pts, found by decoding one frame ahead, so
variable frame rates are exact. `frameAt` returns the current frame without decoding while it
covers the position, decodes forward to a later one, and seeks to the keyframe before an earlier
one.

- `VideoOutput.Rgba8` gives sRGB images; PQ and HLG are tone mapped to BT.709.
- `VideoOutput.LinearF16` gives half-float images in linear extended sRGB with 1.0 = 203 nits
  (BT.2408), keeping HDR highlights above 1.0. FFmpeg's own colour-managed swscale cannot do this:
  it has no `rgbaf16le` output, and its float output goes through a 16-bit LUT bounded to the
  destination's luminance range, so it either tone maps to 1.0 or normalises to the peak. The
  bridge converts the matrix with swscale and applies the transfer and primaries itself.
- `VideoOutput.Surface(surface)` (Android) has MediaCodec render each frame into the Surface, for
  example an `ImageReader`'s, stamped with its pts; `VideoFrame.image` is then null. When no
  MediaCodec decoder takes the source, frames fall back to RGBA8 images and `decoderKind` reports
  `SOFTWARE`.
- `VideoOutput.PixelBuffer` (Apple) hands out the `CVPixelBuffer` VideoToolbox decoded into as
  `VideoFrame.pixelBuffer` (`cvPixelBuffer` is the `CVPixelBufferRef`), with no copy;
  `VideoFrame.image` is then null. Where VideoToolbox does not take the source, `decoderKind`
  reports `SOFTWARE` and each frame is copied into an IOSurface-backed, Metal-compatible buffer
  from a `CVPixelBufferPool`: NV12 (`'420v'`/`'420f'`) for 8-bit sources, P010
  (`'x420'`/`'xf20'`) for deeper ones such as HDR10, in the source's range (4:2:2 and 4:4:4 are
  subsampled; RGB software frames fail with `VideoDecodingException`). `VideoPixelBuffer`
  reports the pixel format, whether an IOSurface backs it, and the frame's primaries, transfer,
  matrix and range, which are also the buffer's colour attachments. The JVM, Android and the
  browser throw `UnsupportedOperationException` from `open`.

With `PixelBuffer`, frames own a retain of their buffer:

- Each `frameAt` returns a new `VideoFrame` holding one retain, also when the same decoded frame is
  still shown (the frames then share the buffer). `close()` releases it, and a second `close()`
  does nothing. The buffer stays valid past the decoder's next call and past `VideoDecoder.close()`,
  so a frame can be drawn while the next one decodes. A frame that is never closed leaks its buffer,
  and the pools allocate new ones in its place.
- `current` is the decoder's own frame: its buffer is valid until the next `seekTo`, `frameAt` or
  `close`, and closing it does nothing. `CVPixelBufferRetain` it to keep it longer.
- The pools recycle a buffer only once every retain on it is gone, so a closed frame's buffer can
  come back for a later frame, and an open one is never overwritten.

```kotlin
VideoDecoder.open(FFplaySource("clip.mp4"), VideoOutput.PixelBuffer).use { decoder ->
    decoder.frameAt(position).use { frame ->
        val buffer = frame.pixelBuffer!!.cvPixelBuffer // for CVMetalTextureCacheCreateTextureFromImage
    }
}
```

Rotation and sample aspect ratio are reported on `info` and every frame, never applied to pixels.
The browser does not support `VideoDecoder` yet.

`open(..., timeout = 10.seconds)` bounds `open`, `seekTo` and `frameAt` each. A call that runs out
of time, whether its input stalls or a MediaCodec decoder takes input and never outputs, throws a
`VideoDecodingException` ("… timed out after …"); the decoder is then unusable, and `close()`
returns promptly and releases the codec once the stuck call has unwound. Under `AUTO` a hardware
decoder that fails or times out before its first frame is replaced once by the software one, with
a timeout of its own, and `decoderKind` reports `SOFTWARE`. FFmpeg's I/O and decoding stop at the
deadline by themselves; only a mounted `Source` blocked in `read` needs the watchdog, which on the
JVM and Android interrupts the thread (ending an Okio `Pipe` read) and elsewhere leaves the read to
finish on its own.

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

`VideoDecoder` runs against the real bridge on the JVM and Kotlin/Native (`src/systemTest`, e.g.
`./gradlew :library:ffplay:jvmTest :library:ffplay:macosArm64Test`), over the clips in
`src/commonTest/resources/video-decoder`; `generate.py` there rebuilds them. The `PixelBuffer`
tests (`src/nativeTest`) run on `iosSimulatorArm64Test` and `macosArm64Test`. The Android Surface
test is a device test (`src/androidDeviceTest`).
