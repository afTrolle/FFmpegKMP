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
