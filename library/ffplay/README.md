# FFplay

`ffplay` is the state-driven Compose Multiplatform playback API for FFmpegKMP.

The public Kotlin API deliberately contains no SDL or native frame handles. A player can be
prepared before a Compose output is attached, and its complete observable state is published as
one immutable `FFplaySnapshot`. Preparation opens the container and inspects its video stream, but
does not create a decoder or expose a frame until an output has been negotiated.

The current implementation provides per-player native demux/decode, scheduled video frames,
seek/pause/stop behavior, replayable mounted input, and a portable Compose Canvas renderer. Android
uses `AndroidExternalSurface` by default. In `AUTO` mode it negotiates an FFmpeg MediaCodec decoder
and presents clear-video hardware frames directly to the attached `Surface` when the codec is
available. If MediaCodec cannot be opened, it reports a decoder fallback and uploads the
software-decoded frame to the same surface. `COMPOSE_CANVAS` remains available as an explicit,
color-managed fallback.

The native clock drops a frame once it is more than one bounded frame interval late. Native decoder
drops and renderer-boundary rejections are combined in `FFplaySnapshot.droppedFrames`; the count is
retained across surface recreation and reset for a new source or `stop()`.

On Android, the direct `ContentScale.Fit` surface advertises PQ, HLG, BT.2020, and P3 only from the
attached display's runtime HDR/wide-color capabilities. A scaled software path does not inherit
those claims. This lets MediaCodec preserve HDR metadata on a capable direct surface while keeping
fallback reporting truthful.

`FFplaySnapshot.output` reports the decoder and renderer that actually became active; requesting
hardware acceleration is never reported as hardware success by itself. `REQUIRE_HARDWARE` fails
when the negotiated hardware path cannot be opened. Demux inspection and decoded-frame updates carry
pixel format, bit depth, aspect ratio, rotation, primaries, transfer, matrix, range, chroma location,
mastering display data, content-light levels, and HDR10/HLG/HDR10+/Dolby Vision classification into
`FFplayVideoInfo`. Output reporting claims HDR preservation only when the complete active output path
advertises the source transfer and color space. When it cannot preserve PQ or HLG, the software
boundary applies the same deterministic linear-light BT.2020-to-BT.709 tone mapper on every native
target and reports `TONE_MAPPED`; unknown HDR transfers remain `UNSUPPORTED`. On iOS, VideoToolbox
`CVPixelBuffer` frames remain opaque through timed
FFplay scheduling and are wrapped in `CMSampleBuffer` objects for `AVSampleBufferDisplayLayer`.
Decoder-open fallback uses the Compose overlay without changing the public API. JVM desktop already
negotiates VideoToolbox on macOS, D3D11VA then DXVA2 on Windows, and VAAPI on Linux builds where
libva is available. Until native GPU-handle import is connected, those hardware frames are
downloaded into the reusable software-renderer boundary; `zeroCopy` therefore remains false and
protected content is never allowed through it. Audio and native desktop GPU-handle import remain
deferred behind the same private output contract.

On Kotlin/JS and Kotlin/Wasm, `FFplaySurface` hosts an HTML canvas through Compose's
`HtmlElementView`. Software RGBA frames are copied into a reusable scratch canvas and scaled into
the display canvas, so browser fallback frames contain the decoded pixels rather than a blank
Compose bitmap. The per-player FFplay engine runs in the Emscripten worker and transfers scheduled
frames through a one-frame native mailbox, so a slow UI cannot create an unbounded callback queue.
Mounted input bytes remain owned by that player until stop/reprepare/close. When WebCodecs accepts
the stream configuration, FFmpeg continues to demux and schedule encoded packets while the browser
decoder produces real `VideoFrame` objects. Each frame is drawn directly to the same canvas and
closed immediately after presentation or discard. Unsupported configurations and decoder failures
fall back to the bounded Wasm software path. The public snapshot reports WebCodecs as hardware only
when the browser returns a decoder configuration that explicitly confirms hardware acceleration;
a requested preference alone is not treated as proof.

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

## Deferred audio and picture-in-picture

The engine keeps audio disabled in V1. Adding it later requires a per-player audio sink, an audio
clock that becomes the FFplay master clock, resampling, route/interruption handling, and A/V drift
tests; it does not require changing the public state model.

Android PiP can wrap the existing external surface with a media session and PiP action adapter once
audio is available. iOS PiP can reuse the current `AVSampleBufferDisplayLayer` view with
`AVPictureInPictureController.ContentSource`, plus an audio session and playback delegate. Both
integrations must keep surface detach/reattach independent from the prepared source and must route
remote play, pause, and seek commands through the existing `FFplayPlayer` methods.

```kotlin
val player = rememberFFplayPlayer()

LaunchedEffect(source) {
    player.prepare(FFplaySource(source))
    player.play()
}

FFplaySurface(player, Modifier.fillMaxSize())
```

Run the shared lifecycle, scheduling, capability, surface-churn, concurrency, and protected-content
tests on every supported target with:

```shell
./gradlew :library:ffplay:commonTestAllTargets
```
