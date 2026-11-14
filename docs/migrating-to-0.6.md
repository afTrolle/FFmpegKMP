# Migrating to 0.6

0.6 is the release a Compose video editor needs: several videos on the Compose
canvas, resized while they play, kept in step with one timeline, exported frame by
frame, with posters and a fallback chain of encoders. Building such an editor on
0.5 took workarounds in the app. This guide says what replaces each one, then
lists every breaking change with its migration, and what 0.6 does not do yet.

The full list of changes is in the [changelog](../CHANGELOG.md); the module
READMEs ([`codec`](../library/codec/README.md), [`ffplay`](../library/ffplay/README.md))
and the [architecture](architecture.md) explain how each part works.

## Replacing the workarounds

### Size "rungs", a settle delay, and reopening decoders to resize

On 0.5 a decoder's output size was fixed when it opened, and an HDR video
tone-mapped at a new size stalled while swscale rebuilt a table (about 400 ms on
a desktop host). An app therefore snapped sizes to a few rungs, waited for a
resize to settle, and opened another decoder for each rung.

None of that is needed now:

- **Playback on the Compose Canvas.** The player converts each frame as it is
  queued, at the size the picture is drawn at then. A resize while playing has no
  flush and no reopen: frames already queued (at most `frameQueueSize`) are drawn
  scaled by the GPU until frames converted at the new size replace them. A resize
  while paused converts the frame on screen again at the new size. The library
  already snaps the bitmap to one of 16 steps of the video's size, so a size that
  wobbles allocates nothing; the app passes the size it draws at and nothing else.
  On Android 14 and later, hardware-decoded BT.709, PQ and HLG frames are drawn from
  GPU memory, where a resize costs nothing.
- **A zoomed surface.** A `graphicsLayer` scale is invisible to the surface; pass
  the size the picture is rendered at as `FFplaySurface(..., resolution = …)`,
  as before.
- **`VideoDecoder` for scrubbing or export.** Keep the decoder and call
  `resize`. The next frame, at the same position too, is converted again from the
  decoded frame at the new size, without decoding or seeking, and is byte for byte
  what a decoder opened at that size returns:

  ```kotlin
  // Before: close the decoder and open another at the new rung.
  // After:
  decoder.resize(FrameSize(tile.width, tile.height))   // null: the source's size
  decoder.frameAt(position).use(image::update)
  ```

  The decoder frees the old size's frames, so resizing often holds one size's
  ring and the frames the app still has. `resize` throws for
  `VideoOutput.GpuBuffers`, whose frames the GPU scales as it draws them.
- **The settle delay.** The HDR gamut mapping is sampled once per pair of
  primaries and kept for the process, so the first frame at a new size costs
  about what any other frame costs (5.3 ms against 367 ms for 1080p on a desktop
  host). The per-frame cost on a phone has not been measured yet.

### Hand rotation of posters

`VideoDecoder.extractFrame` returns one frame as a poster or thumbnail: RGBA8
sRGB with HDR tone mapped, upright, with square pixels, never enlarged. The
display matrix is applied with libavfilter as FFmpeg's own tools autorotate,
mirrors included, and the frame reports rotation 0, so the app draws it as it
is. The decoder is resized before anything is converted, so a 4K HDR source is
tone mapped and scaled once, straight to the poster's size.

```kotlin
// Before: open a decoder, take a frame, convert it, turn and crop it by rotationDegrees in the app.
// After:
VideoDecoder.extractFrame(
    MediaSource(path),
    position = 1.seconds,
    size = FrameSize(320, 320),
    scale = FrameScale.CROP,      // cover the box and cut the centre; FIT keeps the whole picture
).use { poster -> image.update(poster) }
```

`timeout` bounds the call; under `CodecPreference.AUTO` a hardware attempt
that failed or timed out is retried once in software within the same timeout
(the first attempt gets half of it). On a timeout or cancellation the frame and the decoder are
closed.

### An encoder chain that cannot pin "H.264 hardware" or one component

`VideoEncoderConfig.encoder` takes a `CodecSelection`: a `CodecPreference` as
before, or a `NamedCodec`, FFmpeg's own vocabulary (`-c:v name` with AVOptions),
which opens exactly that encoder and never falls back. A MediaCodec component is
pinned with FFmpeg's `codec_name` option, and libaom is made realtime with its
own options, which the library never sets of its own.

The series of tries stays in the app, since it also varies the codec, the
dynamic range and anything else. `MediaWriter.canEncode` opens the encoder to
answer; an encoder can still fail later, and `finish()` now fails a track whose
encoder took frames and produced no packets, so the chain catches
`MediaWritingException` and moves on:

```kotlin
val base = VideoEncoderConfig(width, height, FrameRate(30))
val tries = listOf(
    base.copy(codec = VideoCodec.HEVC, encoder = CodecPreference.REQUIRE_HARDWARE),
    base.copy(codec = VideoCodec.H264, encoder = CodecPreference.REQUIRE_HARDWARE),
    // Android's own software AVC encoder, by its MediaCodec component.
    base.copy(codec = VideoCodec.H264, encoder = NamedCodec("h264_mediacodec", mapOf("codec_name" to "c2.android.avc.encoder"))),
    base.copy(codec = VideoCodec.AV1, encoder = NamedCodec("libaom-av1", mapOf("usage" to "realtime", "cpu-used" to "8"))),
)

suspend fun exportWithTheFirstThatWorks(output: MediaOutput): VideoEncoderConfig? {
    for (config in tries) {
        if (!MediaWriter.canEncode(config)) continue
        try {
            MediaWriter.open(output).use { writer ->
                val track = writer.addVideoTrack(config)
                writeFrames(track)
                writer.finish()
            }
            return config
        } catch (failure: MediaWritingException) {
            log("${config.codec} ${config.encoder} failed, trying the next: ${failure.message}")
        }
    }
    return null
}
```

`VideoTrack.encoderName` names the encoder that took the track, and `isHardware`
reports Android's own answer for a pinned MediaCodec component, so
`c2.android.avc.encoder` reports software. On Android 14 a named MediaCodec
encoder whose only option is `codec_name` still takes frames through its input
surface with no copy. This chain is a device test in `library/codec`
(`MediaWriterDeviceTest`), which passes on a Samsung S25.

An option overrides the field the writer set from the config (`b`, `g`,
`profile`, …); an option that changes the size, the pixel format, the colour or
the container's global header fails the track. An unknown encoder, one for
another codec, an unknown option or a bad value fails `addVideoTrack` with the
reason, and `canEncode` answers false for each.

### Several videos in step

On 0.5 each player paced itself, and keeping several in step meant seeking them
from the app. A player can now follow a clock the app keeps: at each display
frame it asks `FFplayClock.positionAt(frameTime)` and shows the frame whose span
contains that position, the frame `VideoDecoder.frameAt` returns for it. So a
preview and a frame-by-frame export of one position show the same frame.

Keep one timeline clock in the app and give each clip a clock that applies its
offset, trim and loop, so clips of different lengths loop independently:

```kotlin
class ClipClock(private val timeline: TimelineClock, private val clip: Clip) : FFplayClock {
    override fun positionAt(frameTime: Duration): Duration {
        val local = (timeline.positionAt(frameTime) - clip.start).coerceAtLeast(Duration.ZERO)
        val inClip = if (clip.loops) {
            (local.inWholeMicroseconds % clip.length.inWholeMicroseconds).microseconds
        } else {
            local
        }
        return clip.trimStart + inClip
    }
}

players.forEach { (player, clip) -> player.follow(ClipClock(timeline, clip)) }
// Back to the player's own clock (its audio, else the wall clock):
player.follow(null)
```

- The timeline clock is the transport: while it advances the players play,
  while it stands still they hold their frames, and a jump of the clock itself,
  back or more than 500 ms further than the frame time moved, seeks (a loop wrap
  is one such seek). The video is never paused per frame: it decodes ahead and
  waits on a full queue, so a timeline snapped to the video's frame rate on a
  faster display costs nothing. Positions before the start or past the end hold
  the first or last frame, silent. While a player follows,
  `play`, `pause` and `seekTo` throw `IllegalStateException`; move the timeline
  instead.
- `positionAt` is called once per display frame on the refreshing thread: make
  it fast and thread-safe, reading the timeline's state without locking the UI.
- `frameTime` is the Compose frame time on a Canvas, the same for every Canvas
  surface in a display frame, so the clips show one instant; on a native
  surface it is the player's monotonic clock.
- Audio plays while the position advances within 25 % of real time, judged over
  100 ms, and is silent otherwise (a scrub, another rate, a still clock, no
  frame asking for 200 ms). It seeks when the clock jumps and is sought back
  when it drifts more than 100 ms; it is not resampled. `follow(null)` stops
  following and pauses.
- A loop wrap costs a seek, as the app's own looping did; the first frames after
  it wait for that seek.

### Queue size and threads for several players

Each player's memory is now three numbers the app sets or can see: its frame
threads × one decoded picture, its `frameQueueSize` × one drawable frame, and the
decoder's own reference pictures. Two settings control the first two:

```kotlin
val tile = FFplayConfiguration(
    // How many frames are decoded ahead (default 3, ffplay's; at least 1). On the software Canvas
    // each costs a bitmap at the drawn size, width × height × 4 bytes, and the Canvas keeps this many + 2.
    frameQueueSize = 2,
    // The player's default; Fixed sets FFmpeg's thread_count and thread_type, Auto leaves both to FFmpeg.
    threads = DecoderThreads.Fixed(2, setOf(DecoderThreadType.FRAME)),
)
val player = rememberFFplayPlayer(tile)
```

- `frameQueueSize = 1` costs the three bitmaps a player held on 0.5; the default
  of 3 holds five. A larger queue absorbs frames that are slow to decode; a
  smaller one saves memory per tile.
- `canvasFrames` sets the Canvas's bitmaps (or GPU buffers, for hardware frames)
  directly, down to 1; the default is `frameQueueSize` + 2. At 2 the frame shown
  before is reused at once; at 1 each frame is converted as it is shown, on the
  drawing thread, over the one on screen, which can show a partly written
  picture.
- Each frame thread holds one decoded picture (about 24 MB for 4K 10-bit) and
  reads one packet further ahead. `DecoderThreads.Fixed(n, setOf(DecoderThreadType.SLICE))`
  keeps one frame in progress whatever `n` is.
- Hardware decoders ignore the threads. On a Surface a MediaCodec decoder queues
  as many of its output buffers as it gives, up to `frameQueueSize`.
- On an S25 at 120 Hz, four 13 MP players at once on the Canvas dropped no frames
  once the Canvas converted ahead (18 drops before), measured before the player's
  thread default became two frame threads. Memory and frame rate with that default
  have not been measured on a phone.

## Breaking changes

Before 1.0 a minor release may break the API; these are 0.6's. Several parameter
types widened, so code compiled against 0.5 needs recompiling even where its
source is unchanged.

- **`VideoEncoderConfig.encoder` is a `CodecSelection`** instead of a
  `CodecPreference`; the default is still `CodecPreference.AUTO`. Code that passes a
  `CodecPreference` compiles unchanged. Code that reads `config.encoder` as a
  `CodecPreference` needs a `when`:

  ```kotlin
  when (val encoder = config.encoder) {
      is CodecPreference -> …
      is NamedCodec -> …
  }
  ```
- **`FFplayConfiguration.decoder` is a `CodecSelection`.** Passing a
  `CodecPreference` compiles unchanged; reading it back needs the same `when`.
  `VideoDecoder.open(decoder = …)` and `extractFrame(decoder = …)` take a
  `CodecSelection` too, with the same source compatibility.
- **`MediaWriter.finish()` fails a track whose encoder took frames and produced no
  packets**, with a `MediaWritingException` ("encoder X took N frames and produced
  no packets"), instead of writing an output with an empty track. Treat it as the
  encoder failing and try the next one (see the chain above). A track never given a
  frame finishes as before, and MP4 leaves it out. Checked on JVM, Android and
  Apple, not in the browser.
- **Behaviour: a player decodes on two frame threads by default**
  (`threads = DecoderThreads.Fixed(2, setOf(DecoderThreadType.FRAME))`) instead
  of up to eight threads, which bounds memory when several videos play at once.
  `DecoderThreads.Auto` is FFmpeg's own count and types everywhere.
- **Behaviour: `VideoDecoder`'s default `DecoderThreads.Auto` now uses frame
  threads**, FFmpeg's count of them: faster, and one decoded picture per thread
  (up to 17). An export that decodes several large videos at once should pass
  `DecoderThreads.Fixed(n, setOf(DecoderThreadType.SLICE))` to keep the memory
  of 0.5.
- **Renames:** `FFplayConfiguration(decoderPreference = …)` → `decoder = …`;
  `FrameScale.FILL` → `FrameScale.CROP`; `FFplayOutputInfo.sourceColorSpace` /
  `outputColorSpace` → `sourcePrimaries` / `outputPrimaries`;
  `FFplaySurface(resolution = …)` → `targetSize = …`;
  `releaseDecoderWhenPaused = null` → `decoderIdleRelease = Duration.INFINITE`
  (other values carry over under the new name).
- **Opt-in:** `FFplayPlayer.follow`, `FFplayClock` and `VideoDecoder.extractFrame`
  need `@OptIn(ExperimentalFFmpegKmpApi::class)` (a warning without it).
- **Removed from the public API:** `FFplayRendererKind.GPU_TEXTURE`, the ffprobe
  `*Serializer` objects (use `MediaInformation.serializer()`), and
  `CommandLineTokenizer`.
- **New knobs, defaults as before:** `VideoDecoder.open(ringSize = 3)`,
  `FFplayConfiguration.catchUpLag = 500.milliseconds` (`Duration.INFINITE` never
  skips; a skip emits `FFplayEvent.CaughtUp`), and frame conversion uses as many
  threads as `threads` says.
- **Behaviour: `DecoderThreads.Fixed(n)` allows frame and slice threads** in
  `VideoDecoder`, as `ffmpeg` does; it used to decode on slice threads only. To keep
  one frame in progress and the memory of before, pass
  `DecoderThreads.Fixed(n, setOf(DecoderThreadType.SLICE))`.

Changes in behaviour that need no code change, but that an app may notice:

- The size given to `VideoOutput.Memory(size = …)` is no longer fixed for the
  decoder's life: call `decoder.resize(newSize)` instead of opening another
  decoder.
- Under `CodecPreference.AUTO`, a hardware decoder that fails to open falls back
  to software at once and reports `DecoderFallbackReason.FAILED`; before, only a
  failure in the first decode did. The player opens its decoder by the same rule.
- The player shows frames with a port of ffplay's `video_refresh`: a decoder that
  falls behind shows the newest due frame and drops older ones, and one more than
  500 ms behind skips forward to the next keyframe. Both count in
  `FFplaySnapshot.droppedFrames`.
- On Android, AV1 decodes in hardware where the device has a MediaCodec decoder
  for it, in `VideoDecoder` and in the player: the decoder is now found by codec
  id. Not yet run on a phone.
- `VideoOutput.GpuBuffers` frames of Display P3 or BT.2020 SDR sources are drawn
  in those primaries instead of as sRGB.

## Known limits

### Per platform

Android is where 0.6 was built and measured. Other platforms compile and keep
their tests, and get what comes from the shared C player and common Kotlin; nothing
was built specifically for them.

| | Android | JVM desktop | Apple | Browser |
|---|---|---|---|---|
| `VideoDecoder.resize`, `extractFrame` | yes | yes | yes | `UnsupportedOperationException` |
| `NamedCodec` (encoders, decoders, player) | yes | yes | yes | fails as unsupported |
| `FFplayPlayer.follow` | yes | yes | yes | `UnsupportedOperationException` |
| `frameQueueSize` | yes | yes | yes | ignored (the worker keeps ffplay's 3) |
| `DecoderThreads` types | yes | yes | yes | ignored |
| `finish()` fails an empty track | yes | yes | yes | not checked |
| Canvas from GPU memory | Android 14+ | — | — | — |

- Outside Android the Canvas's frame clock is not on the player's clock, so a
  player chooses its frame, and asks a followed clock, at the time its refresh
  runs rather than at the display frame's time.
- iOS keeps its `AVSampleBufferDisplayLayer`, refreshed by the engine's timer.

### The Canvas on Android

- SDR in Display P3 or BT.2020 primaries is converted in software on the Canvas,
  also on Android 14: Android's mapping of such a GPU buffer into an sRGB canvas
  differs from the conversion by up to 16 8-bit codes, so it is not used.
  `FFplayOutputInfo` shows it as a software decoder.
- The GPU tone map of PQ and HLG takes the clip's peak from the stream's metadata,
  not from each frame, and assumes an sRGB canvas.
- With one software decoder thread, a 13 MP video now converts on that thread and
  falls behind (27.7 fps on an S25, frames up to 513 ms late). Give such a player
  more threads.
- Catching up by keyframe can still show frames late on a very slow decoder: 8K on
  one software thread plays through (13.6 fps shown on an S25) where it used to time
  out, with frames up to 1.46 s late.

### Not yet measured on a phone

These are built and tested on the host (JVM), as the commits record, but have not
been run or measured on a device:

- following a clock: several players' sync on the Canvas, audio drift, the hitch
  of a loop wrap;
- the bounded MediaCodec waits and Surface buffers handed over ahead with their
  due time;
- memory and frame rate with the player's new thread default;
- named encoders and the encoder chain above (device tests written, not run);
- named decoders and AV1 in hardware (device test written, not run);
- `extractFrame` (on the host only the hardware branch of its retry test ran);
- the tone map's per-frame cost on a phone;
- tearing with `canvasFrames` below the default (the device test checks frame
  order and drops, not partly written pictures).
