# One open per source in the FFplay player

Status: phases 1, 1b, 2, 3 and 4 are built on `feature/ffplay-reopen`; phase
2's web packet reader reuse moved to phase 7. Phase 0's macOS native baselines
are measured (2b); its Android, JVM and browser measurements are not. Phases
5–8 are not built. The audio-cleanup and small-cleanups work this plan builds
on is merged into main as of f9f92c1.

`FFplayPlayer` opens and probes its input again on nearly every transition:
`prepare` opens it twice, a seek while playing opens it twice more, and play
after pause is a full re-open, codec and seek included. That is cheap for one
player and expensive for an image and video editor, which plays or scrubs many
sources at once. This plan keeps one demuxer per prepared source, keeps the
codec only while it is needed, shares one hardware-decoder budget between
players and `VideoDecoder`s, removes the copies that are not inherent, and
converts Canvas frames straight to the size they are drawn at.

Line numbers below were taken at e63b66d, before the two cleanups merged, and
have drifted; the function names are the stable references. "Verified" means
read in this repository's code; "inferred" means platform or FFmpeg behaviour
outside it.

## 0. Ordering

- Phase 0 adds new files only and can land at any time.
- Phase 1b is Kotlin only and depends only on the small cleanups' `FrameImage`
  Canvas outputs, so it can land before or alongside the native phases.
- Phases 1–7 run in order. Put the hardware lease inside
  `ffplaykmp_video_codec_open/close`, not at the end of `ffplaykmp_core.c`, and
  have phase 6's budget wait (if any) use the shared call-deadline guard in
  `ffmpegkmp_decoder.c`.

## 1. Current state

### 1a. Every open, probe, codec open, hardware-device create and seek (verified)

The engine has two primitives, both in `native-build/bridge/ffplaykmp_player.c`:

- `ffplaykmp_inspect_source` (497-518): `ffplaykmp_player_open_input` (500) → `ffplaykmp_open_input` in `ffplaykmp_core.c:334-375`, which does IO_OPEN on a mounted resource (350), allocates a 32 KB AVIO buffer (`FFPLAYKMP_IO_BUFFER_SIZE`, 319), `avformat_open_input` (370/372) and `avformat_find_stream_info` (374, the probe). Then `av_find_best_stream` (502), metadata into the snapshot, and `ffplaykmp_close_input` (516) which fires IO_CLOSE (core.c 329).
- `ffplaykmp_decode_frames` (357-490): open_input again (378), find stream (380), `ffplaykmp_video_codec_open` (404) which on Android allocates a MediaCodec context and `av_mediacodec_default_init` against the Surface (core.c 510-519) or ByteBuffer mode without one, and elsewhere `av_hwdevice_ctx_create` (core.c 522-523) then `avcodec_open2` (core.c 535); if `start_position_us > 0`, `avformat_seek_file` + `avcodec_flush_buffers` (429-437); decode until one frame (preview) or until abort/EOF (continuous); cleanup closes codec and input (483-484); **AUTO fallback** (485-488): if hardware was active, nothing decoded and not aborted, the whole function recurses in SOFTWARE, i.e. a third open+probe+codec open. The fallback is not remembered anywhere, so on a device where hardware fails every later call pays hardware-attempt + software again.

Who calls them, per transition:

| Transition | Calls | Opens/probes | Codec opens | Seeks |
|---|---|---|---|---|
| `prepare` (800-885) | stop_worker 814; inspect 832; if output attached and `can_decode`, preview `decode_frames(…, 0, 0)` 863 | 2 (3 with fallback) | 1 (2) | 0 |
| `set_output` (887-929) | stop_worker 897; preview at `position_us` 911; if `play_when_ready`, `start_worker` 924 | 1 + 1 | 1 + 1 | 1 + 1 |
| `seek` (990-1029) | stop_worker 999; preview 1012; if playing, `start_worker` 1024 | 2 (paused: 1) | 2 (1) | 2 (1) |
| `play` (945-974) | stop_worker 950; `start_worker` 969 → worker `decode_frames(…, position_us, 1)` 582 | 1 | 1 | 1 if position > 0 |
| `pause` (976-988) | stop_worker 980 only | 0 | 0 | 0 |
| `set_android_surface` (753-784) | stop_worker 774 (codec is implicitly gone; next decode reopens with the new surface) | 0 | 0 | 0 |
| `clear_output` (931-943), `stop` (1031-1050), `cancel` (1052), `reset_cancel` (1059), `destroy` (787) | stop_worker | 0 | 0 | 0 |

So play-after-pause is a full re-open (pause joins the worker at 980; play creates a new one at 969 whose `decode_frames` opens, opens the codec and seeks to `snapshot.position_us`, 580 and 429-437). A seek while playing is two full opens.

Web (`ffplaykmp_web_*`):
- `ffplaykmp_web_player_prepare_bytes` (1377-1420): `malloc`+`memcpy` of the bytes (1395-1398) — the second copy in the Wasm heap, because `bindings/src/webMain/resources/ffmpegkmp-worker.mjs` already did `_malloc` + `HEAPU8.set` (608-611) and frees after the call; then `ffplaykmp_player_prepare` → inspect (open 1).
- `ffplaykmp_web_player_open_packets` (1499-1547): `ffplaykmp_player_open_input(player, "ffmpegkmp:1")` (1518) — open 2 of the same bytes, kept in `callbacks->packet_reader`. Called from `configureWebCodecs` (mjs 359-368) on every `player-set-output`, and closed on 259/382/400/408.
- `openPacketReader` (mjs 711-760), used by the browser `VideoDecoder` (`openVideo`, mjs 1131-1134): creates a whole `ffplaykmp_web_player` (740), copies the bytes (743-746), `prepare_bytes` (747, open+probe 1) then `open_packets` (755, open+probe 2) only to demux.

Audio (verified): `ffmpegkmp_player.c` opens the same URL separately, through `ffplaykmp_open_input` since the audio cleanup. Kotlin opens it through `openAudioPlayer` (`library/ffplay/src/commonMain/kotlin/io/github/aftrolle/ffmpegkmp/ffplay/FFplayAudio.kt:210-216` → `AudioPlayer.open(path|fileHandle)`), so a player with audio always holds a second demuxer, a second probe, and for a path a second OS file handle; for a mounted `FileHandle` both engines share the Kotlin handle (serialised per resource by `MountTable`/`IoLock`).

By contrast `ffmpegkmp_decoder.c` keeps one `ffplaykmp_input` + `ffplaykmp_video_codec` per decoder (40-41), opened once in `ffmpegkmp_video_decoder_start` (705-707), seeks with `avformat_seek_file` + `avcodec_flush_buffers` (333-343), re-syncs after an interrupt (389-402) and closes at destroy (798-799). Its AUTO fallback reopens input and codec once (714-723) because the failed attempt consumed packets.

### 1b. Threads (verified)

- **Caller's thread runs every preview decode.** `prepare`'s preview (863) runs on the thread that called `ffplaykmp_player_prepare`: Kotlin's `prepareOnWorker` → `async(Dispatchers.Default)` under `prepareLock` (`FFplayPlayer.kt:236-250`). `set_output`'s preview (911) runs on whoever calls `attachOutput` → `command()` under `operationLock` (`FFplayPlayer.kt:200-201, 268-272`): on Android that is the main thread inside `AndroidExternalSurface.onSurface` (`PlatformFFplaySurface.android.kt:74-76`), elsewhere the `DisposableEffect` in `FFplaySurface.kt:79-80`; or, if a prepare is in flight, the prepare's Default thread in `finishPreparing` (252, 275-290). `seek`'s preview (1012) runs on the thread calling `seekTo`, typically the UI thread during a scrub, holding `operationLock`. All of these block for open + probe + codec open + seek + decode-to-target today.
- **The worker** is the pthread created in `ffplaykmp_start_worker` (619-628) running `ffplaykmp_playback_worker` (573-600), joined by `ffplaykmp_stop_worker` (602-617), which sets `worker_abort`. Interrupt: `ffplaykmp_interrupt` (61-64) = `cancelled || worker_abort`, installed as the AVFormatContext interrupt callback (core.c 343-344) and checked on every mounted read (core.c 266).
- Per playing player, software: 1 worker + `thread_count` FFmpeg frame/slice threads (`FF_THREAD_FRAME | FF_THREAD_SLICE`, 406; auto = cores+1 capped at 8, core.c 450-455) + audio engine thread(s) + Kotlin `audioScope` on Default. Canvas conversion happens on the worker (`FFplayEngine.acceptNativeFrame` → `submitNative` → `toImageBitmap`, `FFplayEngine.kt:383-420, 56-63`). Paused: 0 native player threads (worker joined). Web: `ffplaykmp_web_frame` lazily allocates a converter per player (1287-1288); converter worker pools start lazily on the first transfer-step job (core.c 1046-1073, 1077) with `min(cores, 8) - 1` threads (4 on the web, 608/612); each of the three `SwsContext`s gets swscale's own threads (`FFMPEGKMP_SCALER_THREADS`, 0 = automatic natively, 4 on the web, 1640-1647).
- `VideoDecoder`: one `DecoderThread` per decoder (`bindings/src/commonMain/kotlin/.../DecoderThread.kt`), a `Mutex` around calls and a watchdog (`library/codec/.../VideoDecoder.kt:190, 375-441`); converter threads bounded by the decoder's count (decoder.c 634-635); ring of 3 frames (636).

### 1c. I/O and mounts (verified)

Mounts are replaced per prepare: `GuardedPlayerBridge.prepare` (`bindings/src/commonMain/.../NativePlayerBridge.kt`, `engine.mount(source.mounts)` then `prepare`, unmount on failure), `stop` unmounts after `engine.stop()`, `close` unmounts then releases. `resetCancellation` is documented as "Joins the outgoing worker before a subsequent prepare reuses its mount ids" and is called under `operationLock` before each prepare (`FFplayPlayer.kt:241`). Mounted `Source`s are replayable (`MountedIo.kt:102-128`, `replayableSource = true` in `JavaCppPlayerBridge.kt:137`): the `ReplayableSource` cache grows to everything read, so each re-open re-reads the probe from that cache rather than the network, but a full play-through leaves the whole file in Kotlin memory. Each open does one IO_OPEN and one IO_CLOSE; file handles (FFmpeg's own `file` protocol or the Kotlin `FileHandle`) are open only inside a call today.

Web memory: `-sINITIAL_MEMORY=67108864`, `ALLOW_MEMORY_GROWTH=1`, `PTHREAD_POOL_SIZE=32` per worker (`native-build/wasm/build.gradle.kts:72-80`); one worker per player and per browser `VideoDecoder`/`MediaWriter` (`docs/architecture.md:237-239`). Input bytes exist in the page, in `readBytesForBrowserPlayer()`, transiently twice in the heap during `prepare_bytes`, and once retained.

### 1d. What happens when the hardware cap is hit today

Verified: `ffplaykmp_video_codec_open` returns the `avcodec_open2`/`av_mediacodec_default_init` error (core.c 513-517, 535) → `decode_frames` cleanup → under AUTO the silent software retry (485-488); under REQUIRE_HARDWARE the call fails and the player goes FAILED. The Kotlin `DecoderFallback` event fires only when `negotiated.hardwareFrameImport` (`FFplayEngine.kt:340-347`), so on desktop (hardware decode + software upload) a fallback is invisible except through `FFplayOutputInfo.decoder`. Because nothing is remembered, every subsequent seek/play re-attempts hardware and falls back again (two codec opens per transition).

Inferred (platform): on Android, `MediaCodec.configure` past the device's instance cap throws `CodecException` with `ERROR_INSUFFICIENT_RESOURCE` (1100) or later `ERROR_RECLAIMED` (1101) when the resource manager reclaims a codec from a lower-priority/background process; FFmpeg's NDK wrapper surfaces both as `AVERROR_EXTERNAL`. `getMaxSupportedInstances()` is a per-codec hint (often 16 or 32 for hardware decoders) but the effective cap at 4K is lower and resolution/secure-dependent and not queryable. A ByteBuffer-mode decoder counts the same as a Surface one. VideoToolbox creates its session on the first `get_format`/decode, not at `av_hwdevice_ctx_create`, so an over-budget VT decoder fails at the first frame with a VT error (the `decoded_frames == 0` fallback catches it); Apple documents no instance cap; practical limits are memory and the hardware decoder's throughput. If paused players keep their codec, both platforms reach these states sooner and the Android resource manager may reclaim a paused player's codec underneath it (reclaim arrives as a decode error on the next packet).

### 1e. Memory per open component

Verified or from the repo's own numbers: a software decoder with frame threads keeps about one frame in flight per thread, ~24 MB each for 4K 10-bit (`library/codec/README.md:251-253`): ~200 MB per open 4K software player codec at 8 threads, plus the DPB. A decoder's ring of three 4K RGBA8 frames is ~100 MB, F16 ~200 MB (`library/ffplay/README.md:270-273`). The converter's 16-bit intermediate is 6 B/px, ~50 MB at 4K, allocated on HDR routes only (bench prints it, `native-build/bench/video_decoder_bench.c:352`). A demuxer is small once probed (AVFormatContext, index, 32 KB AVIO buffer); the probe's cost is I/O, not residency. The player itself has no frame pool (frames go out as decoded, 117). Hardware frame pools are the platform's (MediaCodec's output buffers, VT's IOSurfaces) and are the dominant hardware cost; their counts are not configurable from FFmpeg.

Consequence for option (a): 16 kept-open 4K software codecs ≈ 3 GB. Pure keep-open per player is not viable on mobile for software decoders; the codec must be releasable, and hardware instances must be budgeted.

## 2. Measurement first (before any change)

1. **Bench, macOS (software and VideoToolbox, local file):** `native-build/bench/video_decoder_bench.c` already prints `open+first` per decoder (lines 52-54, `bench_steps`) in `steps` and `hardware` (AUTO) modes via `scripts/bench-video-decoder.sh`. Add a `reopen` mode: N × (create + start + frame_at(t) + destroy) versus create once + N × (seekTo(t) + frame_at), for SOFTWARE and AUTO, at 1080p and 4K, reporting ms per step, and the same through a custom `ffplaykmp_io_callback` that counts IO_OPEN/IO_READ/IO_SIZE/bytes and adds a configurable per-read latency (e.g. 2 ms per 32 KB) to stand in for a mounted/network input: the number of probe reads is what a network source pays. Add a small `native-build/bench/player_bench.c` that links `ffplaykmp_player.c` with a no-op state callback and a frame callback that only counts: prepare → set_output(SOFTWARE_FRAME_UPLOAD) → N seeks → N pause/play pairs, timing each call and counting opens through the I/O callback. This gives the per-transition baseline that the table in 1a predicts (2 opens per seek while playing, etc.).
2. **Android device:** a new gated `PlayerReopenBudgetDeviceTest` in `library/ffplay/src/androidDeviceTest/.../`, modelled on `ParallelDecoderBudgetDeviceTest` (threads via `/proc/self/task`, RSS via `/proc/self/status`, cool-down, instrumentation-argument gate) and `CompositeExportBudgetDeviceTest`: K ∈ {1, 4, 8, 16} `FFplayPlayer`s on `hevc-2160p-pq-4s.mp4` and the H.264 clip from `scripts/generate-budget-clip.sh`, each with its own `SurfaceView` (hardware) and again with Canvas (software); measure seek-to-frame latency (seekTo until the snapshot leaves SEEKING and a frame arrives), play-after-pause latency, peak threads/RSS, and how many players report `DecoderKind.HARDWARE` vs `SOFTWARE`; log `MediaCodecList` `getMaxSupportedInstances()` for the codec so the measured cap can be compared with the hint. Also run `VideoDecoder`s alongside players to see the shared cap.
3. **JVM/Apple:** the same K-player timing as a `src/jvmTest` and `macosArm64Test` production test (existing `FFplayProductionJvmTest`/`FFplayProductionNativeTest` are the pattern), on a local file and on a mounted `Source` with an injected delay.
4. **Browser:** a Kotlin/JS test timing prepare, set_output and seek on 1 and 4 players, sampling `WebAssembly.Memory` growth per worker; note the transient 2× heap during `prepare_bytes`.

Record the baseline numbers in the plan doc before phase 1 lands so each phase can show its delta.

## 2b. Baselines (phase 0, macOS native)

Measured on an M4 Pro (12 cores) at f9f92c1, against the JVM host build's
FFmpeg dylibs, with
[`scripts/bench-player.sh`](../../../scripts/bench-player.sh) and
`scripts/bench-video-decoder.sh reopen`, on the synthetic clips those scripts
generate. The input is served through a counting I/O callback
(`native-build/bench/bench_io.h`) with no added latency. The clips use
x264/x265's default GOP, so most seek targets decode from the clip's first
keyframe; that decode is the same before and after this plan and dominates the
seek times below.

### What one player's transitions cost

Per call; frames are converted into one reused RGBA8 bitmap at the source size,
as a Canvas output does. Opens are input opens, each with a full probe.

| Transition | 1080p H.264 SW | 4K H.264 SW | 4K H.264 AUTO (VT) | 4K HEVC PQ SW | 4K HEVC PQ AUTO (VT) |
|---|---|---|---|---|---|
| prepare, no output | 52 ms, 1 open | 111 ms, 1 open | 110 ms, 1 open | 2 ms, 1 open | 1 ms, 1 open |
| set_output (preview) | 80 ms, 1 open | 272 ms, 1 open | 266 ms, 1 open | 636 ms, 1 open | 565 ms, 1 open |
| seek while paused | 120 ms, 1 open, 2.4 MB | 480 ms, 1 open, 11.2 MB | 512 ms, 1 open, 9.6 MB | 862 ms, 1 open, 16.4 MB | 363 ms, 1 open, 15.5 MB |
| step forward 1/30 s, paused | 114 ms, 1 open | 382 ms, 1 open | 261 ms, 1 open | 577 ms, 1 open | 228 ms, 1 open |
| play after pause, to first frame | 106 ms | 442 ms | 539 ms | 892 ms | 345 ms |
| seek while playing | 118 ms, 1.8 opens | 535 ms, 1.8 opens | 568 ms, 1.8 opens | 906 ms, 1.8 opens | 398 ms, 1.8 opens |
| frame → RGBA8 bitmap | 0.20 ms | 0.54 ms | 1.29 ms | 46 ms (tone map) | 40 ms (tone map) |

A seek, then play, opens the input twice and reads about twice a seek's bytes.
A step forward while paused costs a whole seek, because every seek reopens and
decodes from the keyframe.

### What keeping the input and codec open saves (`VideoDecoder` as the model)

Ten scrub targets (0.5, 0.1, 0.9, … of the clip), each through a new decoder
(open, probe, codec open, seek, as the player does today) against one kept
decoder:

| Clip | Reopen per target | Kept per target | Saved |
|---|---|---|---|
| 1080p H.264 SW | 328 ms, 2.09 MB | 260 ms, 1.81 MB | 68 ms |
| 1080p H.264, GOP 30, SW | 240 ms, 1.54 MB | 160 ms, 1.13 MB | 80 ms |
| 4K H.264 SW | 770 ms, 4.59 MB | 505 ms, 3.37 MB | 265 ms |
| 4K H.264 AUTO (VT) | 307 ms | 147 ms | 160 ms |
| 4K HEVC PQ SW | 674 ms, 3.87 MB | 233 ms, 2.62 MB | 441 ms |
| 4K HEVC PQ AUTO (VT) | 552 ms | 114 ms | 438 ms |

### Many players at once (4K H.264, 10 s)

Each player prepares, attaches a Canvas output, plays for 2 s and pauses. The
footprint is the process's `phys_footprint` once all are paused; freed memory
stays in it, so it is close to the peak.

| Players | AUTO: frames/s each | AUTO: HW | AUTO: footprint | SW: frames/s each | SW: footprint |
|---|---|---|---|---|---|
| 1 | 22.5 | 1 | 83 MB | 21.5 | 314 MB |
| 4 | 22.4 | 4 | 350 MB | 21.4 | 1272 MB |
| 8 | 19.1 | 8 | 671 MB | 20.0 | 2177 MB |
| 16 | **0.5** | 16 | 1023 MB | 5.1 | 2461 MB |

Prepare time grows from 368 ms (1 player) to 829 ms (16, AUTO) and 1158 ms (16,
software).

### What the baselines say

- Every seek, step and play pays an input open and probe, plus a codec open: on
  4K that is 160–440 ms of each transition, the share phases 2 and 4 remove.
- VideoToolbox gives all 16 players a hardware session and then collapses to
  0.5 frames/s each, a tenth of what 16 software players manage. On this
  machine the hardware budget (phase 6) is needed for throughput, not only for
  memory; a limit of about 8 at 4K is where it still holds.
- The HDR tone map at the source size, about 40 ms a frame at 4K, is more than
  a 24 fps frame interval by itself; phase 1b's drawn-size conversion is the
  lever there.
- Not yet measured: Android (`PlayerReopenBudgetDeviceTest`), the JVM and
  browser players, and reads with added latency.

### Phase 1b results (54fb779, branch `claude/sad-haibt-07d9b9`)

Bitmap memory per Canvas player, three RGBA8 bitmaps:

| Drawn at | From 1080p | From 4K |
|---|---|---|
| Source size (before) | 24.9 MB | 99.5 MB |
| 1920×1080 | 24.9 MB | 24.9 MB |
| 960×540 tile | 6.2 MB | 6.2 MB |
| 256×144 thumbnail | 0.88 MB (360×203) | 1.56 MB (480×270) |

Sixteen paused 4K players drawn as tiles go from about 1.6 GB to about 100 MB.
A thumbnail is larger than 4h's table says because one ladder step of 4K is
240×135, so a 256-wide thumbnail gets two steps; question 12's 64 px rounding
would close that.

Converter time per frame, full size against 960×540 (three noisy runs):

| Route | Full size | 960×540 |
|---|---|---|
| 4K HEVC PQ tone map to RGBA8 | 40–64 ms | 3.4–7.8 ms |
| 4K PQ to linear F16 | 31 ms | 2.2–5.0 ms |
| 1080p SDR YUV→RGBA8 | 0.10–0.17 ms | 0.40–0.61 ms |
| 4K SDR YUV→RGBA8 | 0.30–1.10 ms | 0.87–1.58 ms |

The HDR routes get 6–10× faster. SDR conversion gets *slower* when it scales:
the same-size conversion takes swscale's unscaled fast path and the scaled one
does not. It stays under 2 ms, and the saving there is memory and a 16× smaller
GPU upload (question 15).

Two departures from 4h: `drawFrameImage` stores the canvas size and the
`ContentScale` instead of the placed size, and `updateIfOpen` places each frame
itself, so a paused player's first preview frame (often its only one) is
already converted at the drawn size. An RGBA F16 frame keeps its own size,
decided from its pixel layout, instead of a retry after `ENOSYS`. The two new
Android device tests have not run.

### Phase 2 results (macOS native, the same benches as 2b)

Every transition after prepare now opens the input 0 times (1–2 before), and
reads only what it decodes. Per call:

| Transition | 1080p SW before → after | 4K H.264 SW | 4K H.264 AUTO | 4K HEVC PQ SW | 4K HEVC PQ AUTO |
|---|---|---|---|---|---|
| set_output (preview) | 80 → 33 ms | 272 → 107 ms | 266 → 121 ms | 636 → 487 ms | 565 → 419 ms |
| seek while paused | 120 → 57 ms | 480 → 246 ms | 512 → 425 ms | 862 → 605 ms | 363 → 327 ms |
| step forward, paused | 114 → 54 ms | 382 → 176 ms | 261 → 181 ms | 577 → 407 ms | 228 → 200 ms |
| play to first frame | 106 → 48 ms | 442 → 216 ms | 539 → 449 ms | 892 → 620 ms | 345 → 299 ms |
| seek while playing | 118 → 56 ms | 535 → 270 ms | 568 → 477 ms | 906 → 650 ms | 398 → 333 ms |

What remains per transition is the codec open (phase 4), the decode from the
keyframe and, for HDR, the tone map at the source size (phase 1b covers Canvas
outputs). A JVM test (`NativePlayerBridgeJvmTest.theInputIsProbedOncePerPrepare`)
checks through a counting mount that previews, seeks, play and pause never
reopen the input and that a new prepare does.

### Phase 3 results

Every decode, previews included, now runs on one session thread per player,
started by the first job and ended at stop, reset, destroy and a failed prepare,
just before the input closes. A preview's caller blocks on its job as it blocked
on the decode before; play queues a job and returns. The player bench's times
match phase 2 within noise: the hand-off costs nothing measurable. Two JVM tests
check that previews and playback share one thread that is not the caller's, and
that seeks from two threads end when the player is cancelled and closed.

Checked before moving decodes off the caller's thread: no frame or state
callback takes a lock a caller holds while it waits (the Kotlin callbacks take
none, the Android Surface output never calls the player under its lock, the
Apple outputs dispatch to the main queue asynchronously), and the web callbacks
only fill the mailbox the page polls. On the web the session thread takes one
of the 32 pooled pthreads, as the play worker did.

### Phase 4 results

The codec is now opened by the first decode that needs it and kept, on the
session thread, until its key changes: whether the output wants a hardware
decoder, the Android Surface it renders to, and the input it decodes. A new
source, stop, reset and destroy end it with the session thread; `clear_output`
and `set_android_surface` close a codec that renders to a Surface, on the
session thread, before they return; a software or desktop codec survives output
churn. A run goes on from the frame on screen instead of seeking when the
target is ahead of it and the index has no keyframe between the next frame and
the target (with no index, at most 2 s ahead), keeping the frame it decoded but
did not present, the packet the decoder did not take, the frame on screen and,
after the end of the input, the decoder's remaining frames, for the next run. A
preview at a target the frame on screen covers presents that frame again, so
every seek still delivers a frame with the new queue serial; a MediaCodec frame
cannot be kept, so there the Surface keeps showing it. Under AUTO a hardware
decoder that will not open, or decodes nothing, gives way to software for the
rest of the source, and `ffplaykmp_snapshot.decoder_fallback` (NONE,
UNSUPPORTED, FAILED; phase 6 adds BUDGET as 3) reaches
`FFplayOutputInfo.decoderFallback` and `FFplayEvent.DecoderFallback.reason`,
fired whenever the output wanted hardware. A desktop hardware frame goes out as
decoded and is downloaded once, as it is converted, into one of two buffers of
a per-player `ffmpegkmp_frame_pool_alloc(2)` ring. `ffplaykmp_get_statistics`
(Kotlin `nativePlayerStatistics()`) counts codec opens, video packets and
downloads for the tests and the bench. Failed player calls throw
`NativePlayerException` with the native `errorCode`.

macOS player bench, phase 3 (1d8aaff, run the same day) against phase 4, per
call. After prepare, every transition opens 0 codecs (1 before):

| Transition | 1080p SW | 4K H.264 SW | 4K H.264 AUTO | 4K HEVC PQ SW | 4K HEVC PQ AUTO |
|---|---|---|---|---|---|
| set_output (preview) | 32 → 24 ms | 105 → 76 ms | 163 → 121 ms | 768 → 515 ms | 434 → 412 ms |
| seek while paused | 63 → 57 ms | 255 → 266 ms | 397 → 339 ms | 673 → 520 ms | 298 → 229 ms |
| step forward, paused | 62 → 1.3 ms | 170 → 16 ms | 166 → 16 ms | 418 → 43 ms | 214 → 29 ms |
| play to first frame | 50 → 3.2 ms | 242 → 17 ms | 457 → 12 ms | 624 → 33 ms | 318 → 26 ms |
| seek while playing | 57 → 56 ms | 316 → 306 ms | 462 → 424 ms | 656 → 464 ms | 347 → 256 ms |

A step forward reads one frame's packet after the first (whose target is
0.4 s past the scrub before it: the bench's 2.1 packets a step are those 12
plus 9 single ones), against the whole GOP before (40 reads at 1080p). Seek,
play and pause read half the bytes, since play goes on from the seek's frame.
A seek far into a long GOP still decodes from the keyframe: that is the decode
itself, not a reopen. Desktop hardware frames take 1.00 download each and 0.02
frame buffers allocated per frame over a run (the two ring buffers), against a
fresh buffer per frame before.

Many players (4K H.264, 10 s, the same day):

| Players | AUTO fps each | AUTO paused footprint | SW fps each | SW paused footprint, threads |
|---|---|---|---|---|
| 1 | 23.5 → 24.0 | 94 → 93 MB | 23.5 → 24.0 | 314 → 320 MB, 15 → 23 |
| 4 | 23.6 → 24.0 | 340 → 352 MB | 23.0 → 24.4 | 1259 → 1269 MB, 53 → 86 |
| 8 | 23.4 → 23.1 | 627 → 693 MB | 22.3 → 24.4 | 2186 → 2532 MB, 45 → 121 |
| 16 | 0.6 → 2.0 | 1191 → 1384 MB | 16.9 → 23.9 | 3279 → 5044 MB, 65 → 193 |

Kept codecs are the cost: sixteen paused software players hold 16 decoders'
frame threads and their frames, about 315 MB each. That is design option (a)'s
footprint, which phase 5's release on pause exists to remove; until it lands a
paused player holds its codec. VideoToolbox at 16 players stays collapsed
(phase 6).

Verified: JVM bindings 41, codec 78, ffplay 90; macOS bindings 8, ffplay 80;
iOS simulator 8 and 80; js and wasm bindings 23 each (worker relinked), ffplay
54 each; Samsung SM-S938B ffplay device suite with `hdrGpuCheck=true` 94 tests,
93 pass, `CompositeExportBudgetDeviceTest.measure` gated. New checks: the codec
is kept across seeks, pause, play and Canvas output churn (JVM, and on the
device across the lifecycle and one codec per new Surface under MediaCodec,
none in software); a forward scrub reads at most one packet a step and a seek
inside the frame on screen presents it again (JVM); one download per desktop
hardware frame and at most the ring's two buffers (JVM, macOS); FAILED for the
MPEG-2 stream VideoToolbox advertises and fails, with no hardware retry on its
seeks (JVM bridge and `FFplayPlayer`); the reason and one event per source over
a fake bridge, and `NativePlayerException.errorCode` (common); MediaCodec then
the canvas then MediaCodec on one Surface reports FAILED back at Fit; a Surface
detached while playing or paused and a new one attached later (device).

Departures from section 4 and decisions it left open:
- AUTO's fallback is sticky for the source except that a new Android Surface
  tries hardware again, since a MediaCodec failure can belong to the Surface
  (one the canvas has drawn on) or to a codec reclaimed meanwhile.
- `clear_output` and `set_android_surface` release only a codec that renders
  to a Surface; a change of the hardware/software decision at `set_output` is
  found by the key at the next run, on the session thread.
- A replacing prepare ends the session thread, which closes the old source's
  codec, rather than only stopping playback; the key also carries the input's
  generation, so a codec never outlives its input.
- The session keeps a reference to the frame on screen (not for MediaCodec), so
  a covered preview and a new output's preview present it without decoding:
  one more decoder frame held per player.
- `VideoDecoder`'s as-decoded hardware frames are still downloaded at once, so
  the hardware surface goes back to the decoder (VAAPI and D3D11 pools are
  fixed), but into the decoder's ring while it has room; its formatted output's
  temporary download takes a ring buffer too. Section 4g's "already uses the
  lazy handle download" held only for handles converted later.
- "No `av_frame_alloc` per frame" holds for pixel memory: each frame handle
  still allocates its `AVFrame` header, as software frames' do.
- The reason enum has NONE, UNSUPPORTED and FAILED; BUDGET (3) comes with phase
  6. The browser's snapshot JSON does not carry it: the wasm player decodes in
  software only.
- `requireNative` is public (internal API) so that `FFplayEngine` can use it;
  `NativePlayerException` lives in `bindings` with the other call exceptions.

## 3. Design options (behaviour at 1 / 4 / 16 concurrent sources)

**(a) Keep-open per player** (demuxer + codec from prepare to stop). 1: ideal, seek = flush + seek + decode-to-target; 4: fine on desktop; 16: software 4K ≈ 3 GB of frame-thread frames, 16 × 8 decoder threads, 16 hardware instances (over any Android cap; paused players still hold them). Simple, but the user's concern is exactly the resource held open. Not recommended alone.

**(b) Keep the demuxer open, release the codec when paused/idle, reacquire lazily.** Demuxer cost is small (index + 32 KB buffer + one file handle/mount open), so 16 is fine for demuxers; codecs exist only for playing players and for a configurable grace after pause (scrub-then-pause stays warm). 1: same as (a) while playing; 4: same; 16 paused + 1 playing: 1 codec. Cost: play-after-grace pays a codec open (not a probe), and a hardware codec open is the expensive part on Android (MediaCodec configure ~tens of ms). This is the main lever for memory and threads.

**(c) Process-wide hardware budget/lease, shared by `FFplayPlayer` and `VideoDecoder`**, in `ffplaykmp_core.c` where `ffplaykmp_video_codec_open` already decides hardware vs software: a counted lease per hardware codec, software fallback (AUTO) or `UNSUPPORTED`/new error (REQUIRE_HARDWARE) when full, with priority and eviction of idle holders. 1: no effect; 4: no effect below the limit; 16: at most `limit` hardware decoders, the rest software (and reported so), no platform error paths, no resource-manager reclaim surprises. Needs (b) so leases are returned on pause; otherwise 16 paused players keep the budget full.

**(d) One demux shared by video and audio.** Removes one probe and one file handle per player and halves demux reads on network inputs, but needs a single demux thread with per-stream packet queues (ffplay's model) and lock-step seeks between two consumers on two threads (the audio engine pulls on the audio thread, `ffmpegkmp_player_read` at `ffmpegkmp_player.c:634`); it rewrites the audio engine the audio cleanup just moved onto `ffplaykmp_open_input`. At 16 it saves 16 demuxers and probes (small memory, real I/O on network). Defer to a later phase; a cheap intermediate is passing the already-detected format name and a small `probesize`/`analyzeduration` to the second open, which cuts the probe without sharing a context.

Recommended: (b) + (c), with (d) deferred and coordinated.

## 4. Recommended design

### Session struct and ownership (C)

Replace the per-call locals of `ffplaykmp_decode_frames` with a session owned by the player, guarded by a new `session_mutex` separate from `mutex` (which stays a short snapshot lock):

```c
/* Open from prepare until stop, destroy, reset_cancel or the next prepare. */
ffplaykmp_input input; int video_stream; int64_t media_start_us;
/* Open from the first decode until released; keyed by how it was opened. */
ffplaykmp_video_codec codec; int codec_open; uint32_t codec_key; /* hardware wanted, surface identity, preference */
int hardware_failed;   /* AUTO: sticky for this source after a failed hardware attempt */
AVPacket *packet; AVFrame *frame;
int64_t decoded_until_us; int needs_flush;
```

`decode_frames` becomes `ffplaykmp_run_job(player, job)` where a job is {start_position_us, continuous}: ensure input (already open), ensure codec for the current key (open if missing, release+reopen if the key changed), seek+flush only when the target is not the next frame in order (as `ffmpegkmp_position` decides, decoder.c 382-409: seeking backward, or when the index has a keyframe after the lookahead), then the existing packet loop. The AUTO fallback becomes: on codec-open failure or zero frames with hardware, set `hardware_failed`, release the codec, seek the demuxer back to the job start (replayable mounts give `AVIO` seek, `MountedIo.kt:53`), open software, run again — one input open, and never retried for this source.

**Threading: one session thread per prepared source (recommended over caller-thread previews).** Keep the public shapes (`seekTo`/`play`/`pause` synchronous; `prepare` suspend), but run every decode, preview included, on the player's worker and let the caller wait for the preview on a condvar. Reasons: (1) `library/codec/README.md:89` and `DecoderThread` document that MediaCodec sessions are bound to one thread, and a kept codec would otherwise be touched alternately by the UI thread (preview) and the worker; (2) cancellation gets one mechanism; (3) it stops blocking the UI thread in a later phase for free (seek could return once the job is queued). Mechanics: `start_worker` once per prepare; the worker loops on a mailbox {job, generation} under `session_mutex`; `seek` posts a preview job and waits for "presented or failed" (bounded by the same abort flags); `play` posts a continuous job; `pause` posts "idle" and waits for the job to unwind (replaces today's join at 980); `stop`, `reset_cancel`, `destroy` and prepare-replace post "exit" and join. The existing `stop_worker` join sites become "wait idle" except where the input must close.

**Cancellation, interrupt, deadlines.** `cancelled` keeps meaning "abort everything, the next prepare resets it"; `worker_abort` becomes "abort the current job" (checked in the packet loop and by the interrupt callback as now, 61-64, core.c 266/343). A preview waiter wakes on job end or abort. Mount-id safety: today the input is never open across Kotlin calls, so IO_CLOSE always reaches the right mount table. With a kept input, `GuardedPlayerBridge.prepare` remounts *before* calling native prepare, so the old input's IO_CLOSE would hit the new table. Fix in the same place the comment already lives: `ffplaykmp_player_reset_cancel` (called under `operationLock` before every prepare, `FFplayPlayer.kt:241`, and before any remount) joins the worker and closes codec and input; `stop` closes them before `GuardedPlayerBridge.stop` unmounts; `close` must call `engine.release()` before `mount(emptyList())` (today it is the other order, harmless only because nothing is open). The browser bridge has no mounts to reuse; its `resetCancellation` restarts the worker.

**Android Surface and codec lifetime.** The codec key includes the surface's identity. `set_android_surface` (753-784) and `clear_output` release the codec (a MediaCodec configured on a destroyed Surface cannot be reused; FFmpeg's wrapper exposes no `setOutputSurface`), keep the input, and the next job reopens against the new surface; `set_output` with a different hardware/software decision does the same. On the Canvas path (software on Android, `can_decode` 649-654) the codec survives output churn. The attach sequence in `FFplayEngine.attachOutput` (248-312: clear output + null surface, set surface, set output) therefore costs one codec open per surface, as today, and never a probe.

**AUTO hardware→software fallback.** Sticky per source (`hardware_failed`), applied at codec open, and reported: add `int32_t decoder_fallback_reason` to `ffplaykmp_snapshot` (NONE, UNSUPPORTED, FAILED, BUDGET) so Kotlin's `DecoderFallback` event and `FFplayOutputInfo` can say why; fire the event whenever hardware was wanted, not only with `hardwareFrameImport` (closes the desktop gap at `FFplayEngine.kt:340-347`).

**Release on pause/idle.** `pause` posts idle; the worker releases the codec after `codec_release_delay_us` (configuration; 0 = at once, negative = keep) unless a new job arrives, and always releases a *hardware* codec when the budget has a waiter. Software codecs free their frame-thread frames on release, which is the memory lever at 16.

**Budget/lease in core.** `ffplaykmp_video_codec_open` gains a lease step: a process-wide table `{mutex, limit, in_use, holders[]}` in `ffplaykmp_core.c`; a hardware open takes a lease or, when full, returns `FFPLAYKMP_ERROR_BUSY` (new, -1009) which the caller maps to software (AUTO) or failure (REQUIRE_HARDWARE); `ffplaykmp_video_codec_close` returns it. `ffmpegkmp_decoder.c` gets the same behaviour through the shared function (its AUTO path at 710-723 already handles "hardware failed before the first frame"). Priorities and eviction, second step: each holder registers {priority, reclaim flag}; an opener above the lowest idle holder's priority sets that holder's flag and waits up to a short bound (e.g. 100 ms) for its session thread to release at its next idle point, else falls back to software. Keep v1 to counting + release-on-pause; add reclaim only if measurements show paused-but-warm holders starving visible players.

**Web.** One `ffplaykmp_input` per player, opened by `prepare_bytes` through the inspect and kept; `open_packets` seeks it to the start and uses it (no second open), after verifying no software job is running (the two output modes are exclusive; take `session_mutex`); `close_packets` just parks it. `openPacketReader` becomes one `ffplaykmp_web_player_prepare_packets` (inspect + reader on the same open, snapshot delivered) so the browser `VideoDecoder` opens once. Drop a copy by letting `prepare_bytes` take ownership of the pointer the worker `_malloc`ed (the mjs stops freeing it; document in the header), removing the transient 2× heap. The player's worker pthread is one of the pool of 32 as today.

### Threading with FrameImage Canvas outputs
Version 1 said "Canvas conversion happens on the worker via `toImageBitmap`". After `ae1e339` conversion still happens on the player's native thread (the worker today, the session thread in phase 3), but it now writes into one of the output's three reused bitmaps under `FrameImage`'s lock, and `updateIfOpen` drops a frame that arrives after the output closed. Consequences:
- The preview path's blocking caller (UI thread on a seek) waits for a conversion into a reused bitmap, no allocation: cheaper and bounded. Drawing never takes `FrameImage`'s lock (the shown bitmap is not written), so a UI thread blocked in `seekTo` under `operationLock` while the session thread converts cannot deadlock with Compose drawing.
- Memory accounting gains a fixed line per Canvas player: three bitmaps at the video size (4K RGBA8 ≈ 3 × 33 MB, F16 double), kept while the output is composed, paused or not (`clear()` keeps them). 16 paused 4K Canvas players on desktop hold ≈ 1.6 GB in bitmaps regardless of anything in C. Release-on-pause (phase 5) does not touch this; note it in the budget doc as a Kotlin-side cost and leave it to the output's own lifetime.
- Android's Surface software fallback converts and draws under `AndroidSurfaceOutput.lock`; `detachSurface` closes the bitmap under the same lock, so a session-thread frame after a surface loss is safely rejected. No change to the Surface/codec rules in 4c.
- `submitNative` is now the one entry; the fallback-reason reporting in phase 4 touches `acceptNativeSnapshot` only, not the frame path.

### 4d. Budget/lease in core
`ffplaykmp_video_codec_open` takes a lease from a process-wide `{mutex, limit, in_use}` in `ffplaykmp_core.c` when opening hardware; full → `FFPLAYKMP_ERROR_BUSY` (new, -1009), which callers map to software (AUTO) or failure (REQUIRE_HARDWARE); `ffplaykmp_video_codec_close` returns it. `ffmpegkmp_decoder.c` inherits it through the shared function. Priorities/eviction only if phase-0/5 numbers show warm idle holders starving visible players.

### 4e. Kotlin async and threading decisions

Procedure applied per site: name the owner of cancellation/completion/failure; keep any suspend API that already gives the caller those (no added scope, launch or deferred); keep synchronous setters synchronous; model durable facts as state and advisory one-shots as events with their loss contract stated.

1. **`prepareMutex` / `operationLock` / `prepareLock` (`FFplayPlayer.kt:48-56`).** `prepareMutex` is a coroutine `Mutex` serialising `suspend prepare` — caller-owned cancellation, keep. `operationLock` is the reentrant blocking lock behind the synchronous setters (`command()`); keep: setters stay synchronous and the C call under it stays a blocking boundary. `prepareLock` is held for the whole blocking native prepare on a Default thread and is what `close()` and a native-surface `detachOutput` wait on — a required blocking edge (native calls cannot be freed underneath), keep. No new lock: the session-thread preview wait lives in C (`ffplaykmp_player_seek` blocks on its condvar), so from Kotlin a seek is the same synchronous call under `operationLock` as today, and two threads' seeks are serialised by `operationLock` before the C side sees one waiter.
2. **`prepareOnWorker`'s `async(Dispatchers.Default)` (236-266).** It is structured: a child of the caller's `coroutineScope`, awaited, and on cancellation `engine.cancel()` then a `NonCancellable` join so the native call has observed cancellation before the caller unwinds. The owner is the caller of `prepare`; failure propagates through `await`. `runInterruptible` does not apply (the native call is interrupted by `ffplaykmp_player_cancel`, not `Thread.interrupt`). Keep as is. One open question, not a change: it runs a blocking native call on `Dispatchers.Default`, while `docs/architecture.md:196-197` says the command bridge makes blocking native calls on `Dispatchers.IO`; a long network probe holds a Default thread. With the kept demuxer the prepare is the only remaining probe, so the cost shrinks; decide IO vs Default by the phase-0 numbers.
3. **`FFplayAudio.scope` and `clockJob` (`FFplayAudio.kt:60-120`; the scope is `FFplayPlayer.audioScope`, a stored `CoroutineScope(SupervisorJob() + Dispatchers.Default)` created in a field initializer and cancelled in `closeEngine`).** Against the skill this is a stored scope on a non-UI class; it is tolerable because `FFplayPlayer` is an `AutoCloseable` lifecycle owner with an explicit cancel site and the launch site is `attach`, reached only from `suspend prepare`. The work (the audible-clock collector) must outlive `prepare`, so it cannot become part of the caller's suspension. Per the user's constraint: no new scope, no change here. Optional later cleanup, outside this plan: give `FFplayAudioOutput` a synchronous progress listener (the browser's `NativePlayerAudio.setProgressListener` already has that shape), which removes `clockJob` and the stored scope entirely. The `audioGeneration` guard against stale opens stays.
4. **Waiting on the session thread's preview.** Blocking, in C, on the calling thread, under `operationLock` — exactly today's shape. Cancellation of a waiting `seekTo`: only `close()` can cancel it (`requestClose` → `engine.cancel()` → `cancelled`), the job aborts, the C call returns `AVERROR_EXIT`, and the synchronous setter throws through `requireNativeSuccess`, as a post-close seek does today. Nothing new is needed. If the user later wants a non-blocking scrub: do **not** add `suspend fun seekTo` or a launch; add mailbox coalescing in C (a newer preview replaces a queued one) behind `FFplayConfiguration(previewSeeks = ASYNC)` where `seekTo` returns after posting and completion is observable as the `snapshot` `StateFlow` going SEEKING → PAUSED/PLAYING (state, not an event). Keep BLOCKING as the default; this is open question 2.
5. **Cancellation of `prepare`.** Stays `suspend`, caller-owned. The existing `CancellationException` handler (`FFplayPlayer.kt:104-115`) calls `engine.stop()`, which in the new design closes the kept input and codec and joins the session thread — no change needed, and the broad `catch (failure: Throwable)` already comes after the cancellation catch, as the skill requires. With the session thread, `ffplaykmp_player_prepare` runs the inspect on the calling (Default) thread as now and posts the preview as the first job; `cancelled` aborts both.
6. **`VideoDecoder`: `DecoderThread`, `calls` Mutex, watchdog (`VideoDecoder.kt:190, 375-441`; `openOnDecoderThread` from `e88b137`).** `DecoderThread` is a required thread-affinity adapter with an explicit owner (`close` → `thread.release` → `finish(bound)`), so it is not detached work; `calls` serialises suspend calls; `within` re-throws `CancellationException` after interrupting the native call and abandons the result to `discard` — correct per the skill. `openOnDecoderThread`'s `NonCancellable` create is justified by releasing on failure. No change. The budget lease plugs into `native.start()` at the C level, so no new Kotlin async.
7. **Exposing the hardware budget lease.** Synchronous and non-blocking in v1: `DecoderBudget` in `codec` is a plain object with a synchronous `hardwareDecoders` setter backed by `ffplaykmp_decoder_budget_set`, read synchronously; a full budget yields software or `BUSY` immediately. Reporting is **state**: `VideoDecoder.decoderFallback: DecoderFallbackReason?` (a `val` fixed at open, like `decoderKind`) and `FFplayOutputInfo.decoderFallback` in `snapshot` (synchronous `.value` needed by `attachAudio` and the queued commands, so the eager `MutableStateFlow` is right). If waiting for a slot is wanted later (REQUIRE_HARDWARE callers willing to queue): it must not be a new suspend entry or a scope. For `VideoDecoder`, put the wait inside the already-suspend `open` via a parameter (`hardware = HardwareDecoding.WAIT`): owner and cancellation are `open`'s caller, failure is `VideoDecodingException`, and the wait is a C condvar with a deadline inside `ffmpegkmp_video_decoder_start`, bounded by the existing native deadline so the current watchdog and `native.interrupt()` on cancellation already cover it (this is the hook that must use the deadline guard small-cleanups may extract). For `FFplayPlayer`, only inside `suspend prepare` (never in synchronous `play`: a full budget there takes software or fails per preference). Decision for the user: ship v1 without waiting.
8. **State vs events.** `snapshot: StateFlow<FFplaySnapshot>` (`FFplayPlayer.kt:82`) carries every durable fact: state, position, `output.decoder`, and now the fallback reason. `events` (83) is a `MutableSharedFlow(extraBufferCapacity = 16)` emitted with `tryEmit` (320): broadcast to active collectors, no replay, dropped when no collector or when 16 are buffered. That contract is acceptable because every event this plan adds (`DecoderFallback` with a reason, a `Warning` for a budget-forced software decode) is advisory and mirrored in state, as `Fatal` is mirrored by `snapshot.failure`. Do not add a `Channel`; nothing here needs exactly-once delivery. Also adopt small-cleanups' `requireNative` with a `NativePlayerException : NativeCallException` in `FFplayEngine.requireNativeSuccess` (497-499) so `errorCode` (`BUSY`, `UNSUPPORTED`) is inspectable instead of a message-only `IllegalStateException`.

### 4g. Copy inventory: every copy on the touched paths, where, and its fate

Legend: **stays** = inherent to the platform/FFmpeg or a GPU upload; **P n** = removed or reduced by phase n.

#### Input bytes (native targets: JVM, Android, Apple)
| # | Copy | Where (verified) | Fate |
|---|---|---|---|
| I1 | OS file → Kotlin `ByteArray` scratch | `MountedIo.read` (`bindings/src/commonMain/.../MountedIo.kt:58-59`, `fileHandle.read(offset, into…)`), scratch reused (`JavaCppExecutionBridge.kt:160, 187-190`; `NativeExecutionBridge.native.kt:126, 139`) | **stays** while mounts are served from Kotlin: Okio `FileHandle` reads into arrays. Could go only by giving FFmpeg a file descriptor (`MediaSource` path through FFmpeg's `file` protocol already has no Kotlin copy); out of scope. |
| I2 | scratch → AVIO buffer | JVM `data.put(bytes, 0, count)` (`JavaCppExecutionBridge.kt:169`); native `memcpy` (`NativeExecutionBridge.native.kt:144`); audio-cleanup's `MountTable.dispatch` `copyOut` keeps the same two steps | **stays** (JNI/cinterop boundary; a direct-buffer read would need Okio to read into native memory). Note: the AVIO buffer is 32 KB (`ffplaykmp_core.c:319`), so each probe and each seek costs ⌈bytes/32 KB⌉ round trips; P2 removes the probe's share of these per transition (counted by the phase-0 I/O callback). |
| I3 | `ReplayableSource`: `Source` → `cache` segments, then `cache.copyTo(Buffer())` + `readExactly(into)` | `MountedIo.kt:109-114` (two copies per read: 110 into the cache, 113 out of it) | P2 reduces the *number* of reads (no re-probe); the two copies per read **stay** for a `Source` (it is a non-seekable stream served from a cache). Follow-up (not in this plan): `cache.copyTo(into, offset, size)` directly from the `Buffer` to the array would remove the intermediate `Buffer()` at 113. |
| I4 | AVIO buffer → `AVPacket` | FFmpeg's demuxer (`av_get_packet` in the pinned `mov.c:11882/11884`, upstream) | **stays** (FFmpeg owns the packet buffer). |
| I5 | Probe reads | `avformat_find_stream_info` (`ffplaykmp_core.c:374`) reads up to `probesize` and rewinds; today paid 2-3× per prepare and 1-2× per seek/play | P2 removes all but one per source; the audio engine's probe stays until phase 8 (or the `probesize`/format-name hint). |

#### Input bytes (web)
| # | Copy | Where (verified) | Fate |
|---|---|---|---|
| W1 | mount → Kotlin `ByteArray` (whole input) | `readBytesForBrowser` (`BrowserPlayerBridge.kt:326-335` in main; moved to `BrowserBuffers.kt` in small-cleanups): `FileHandle.read` into a `Buffer` then `readByteArray()` (two copies), or `ReplayableSource.readAll()` → `cache.snapshot().toByteArray()` (`MountedIo.kt:118-123`, two) | small-cleanups' uncommitted change removes whole-buffer copies in the bridges; verify on rebase whether one copy remains (file → array). What remains **stays**: the worker needs the bytes in a transferable buffer. |
| W2 | Kotlin array → transferable `Uint8Array` | Kotlin/JS: array is the `Int8Array` (no copy, `BrowserWorker.js.kt` "A Kotlin/JS ByteArray is an Int8Array"); Kotlin/Wasm: `toInt8Array()` out of linear memory then `unsignedCopy(...).slice()` (`BrowserWorker.wasm.kt:255-257`: **two** copies) | Wasm: one copy is inherent (linear memory → JS buffer); the second (`slice()`) exists because `toInt8Array()` "does not guarantee a buffer of its own" (comment 252-253). P7: use `unsignedView` (316-320) which slices only when the view does not own its buffer, as `copyToJsUint8Array(bytes, offset, size)` already does. |
| W3 | page → worker | `postMessage(…, request.mounts.map(m => m.bytes.buffer))` (`BrowserWorker.js.kt:280-283`, `.wasm.kt:305-308`): transferred, **no copy** | already borrowed/moved. |
| W4 | worker JS → Wasm heap | `_malloc` + `HEAPU8.set(mount.bytes, inputPointer)` (`ffmpegkmp-worker.mjs:608-611`), freed at 617 | **stays** (JS heap → Wasm linear memory is a copy by construction) but becomes the *only* heap copy. |
| W5 | heap → heap | `ffplaykmp_web_player_prepare_bytes` `malloc`+`memcpy` (`ffplaykmp_player.c:1395-1398`) | **P7 removes**: `prepare_bytes` takes ownership of the pointer (documented in the header), the mjs stops freeing it; `ffplaykmp_web_player_destroy` frees it (1373 already `free(callbacks->input)`). Removes the transient 2× heap peak. |
| W6 | heap bytes → AVIO buffer | `ffplaykmp_web_io` `memcpy(data, callbacks->input + offset, count)` (1192) | **stays** as long as the input goes through an `AVIOContext` (FFmpeg reads into its own buffer). Could be avoided with a custom `AVIOContext` whose buffer *is* the input (`avio_alloc_context(input_bytes, size, …)` with a read callback that returns EOF and a seek that moves the buffer pointer); FFmpeg refills by copying anyway, so the gain is small and it depends on `AVIOContext` internals. Document as not worth it. |
| W7 | `openPacketReader` second player | mjs 740-747 copies the bytes again (W4+W5 twice) | **P7 removes** (single open, one player per decoder worker). |

#### Packets: demuxer → codec
| # | Copy | Where | Fate |
|---|---|---|---|
| K1 | `av_read_frame` → `avcodec_send_packet` | `ffplaykmp_player.c:438-440`: the packet is referenced, not copied (FFmpeg `av_packet_ref` on refcounted data) | none; **P1** keeps the packet and frame allocated once per session instead of per `decode_frames` call (423-424, freed 481-482). |
| K2 | packet → MediaCodec input buffer | FFmpeg `mediacodecdec_common.c:970` `memcpy(data, pkt->data + offset, size)` (upstream) | **stays** (MediaCodec owns its input buffers). |
| K3 | packet → VideoToolbox bitstream | FFmpeg `videotoolbox.c:87-115` `av_fast_realloc` + `memcpy` (upstream; Annex-B conversion) | **stays**. |
| K4 | packet → software decoder | referenced; frame threads copy nothing | none. |
| K5 | packets decoded and dropped before the preview target | `ffplaykmp_present_decoded_frame` returns 0 for `presentation_time_us < start_position_us` (246-247) | not a copy but the dominant per-seek cost ("bytes moved per seek" = packet bytes from the keyframe to the target); **P4** adds the forward-seek rule (no re-seek when the target is ahead of the decoder within the GOP, as `ffmpegkmp_seek_is_shorter`, decoder.c 362-373), so a scrub forward decodes each frame once. |

#### Packets: reader → WebCodecs
| # | Copy | Where | Fate |
|---|---|---|---|
| WK1 | heap packet → JS | `module.HEAPU8.slice(bytes, bytes + size)` in the player (`mjs:557`) and the decoder reader (`mjs:736`) | **P7 removes**: pass `HEAPU8.subarray(bytes, bytes + size)` straight to `new EncodedVideoChunk({data})` (mjs 564, 977). Inferred and to verify in Chrome/Safari: the WebCodecs spec copies `data` during the constructor and types it `AllowSharedBufferSource`, so a view over the pthread `SharedArrayBuffer` heap is accepted and the packet is valid only for the callback's duration (as the header already says, `ffplaykmp_player.h:327-329`). If a browser rejects shared views, keep one `slice` — then WK2 is the inherent one. |
| WK2 | JS → `EncodedVideoChunk` | constructor copy (`mjs:564`, 977) | **stays** (spec). |
| WK3 | decoder reader's `video.packet` retained between `readPacket` and `decode` | `mjs:961-977` holds the sliced copy | with WK1 removed the chunk must be built inside the packet callback; P7 restructures `openVideo`'s `onPacket` to construct the chunk immediately (the callback already runs synchronously, 1134-1136). |

#### Decoded frames
| # | Copy | Where | Fate |
|---|---|---|---|
| F1 | software frame → Kotlin `VideoFrame` | `ffmpegkmp_frame_from_av` (`ffplaykmp_player.c:117`) = one `av_frame_ref`; the JVM callback takes `ffmpegkmp_frame_ref` (`JavaCppPlayerBridge.kt:65-67`) | none (handle, no pixels copied). |
| F2 | `VideoFrame` → Canvas `FrameImage` | after small-cleanups: `image.updateIfOpen(frame)` → `FrameBitmap.write` → Skiko converts straight into `peekPixels().addr` (`VideoFrameImage.skiko.kt:77-80`; README 133-136), Android into `AndroidBitmap_lockPixels` (`VideoFrameImage.android.kt:185-186` → `convertIntoBitmap`) | one conversion into the destination's memory, no allocation per frame (three reused bitmaps). **Stays** as the single conversion. |
| F3 | bitmap → GPU | Skia draws a mutable bitmap from a copy of its pixels and uploads (README 191-196); Android HWUI uploads by generation | **stays** (renderer). |
| F4 | desktop hardware frame → software download | `ffplaykmp_emit_downloaded_video_frame` (`ffplaykmp_player.c:163-185`): `av_frame_alloc` + `ffplaykmp_download_frame` (core.c 1763-1766, `av_hwframe_transfer_data`) **allocates a new frame per decoded frame**, then F2 converts it: two full-frame passes and one allocation per frame | **P4 changes**: emit the hardware frame's handle as-is (the handle downloads lazily, once, on first map or conversion: `ffmpegkmp_frame.c:332-365, 1067-1074`, `ffmpegkmp_frame.h` "download, made on its first map or conversion"), and give the download a buffer from a per-player `ffmpegkmp_frame_pool_alloc(2)` ring instead of `av_frame_alloc` (the handle's `download` is a bare `av_frame_alloc` at `ffmpegkmp_frame.c:355`; add a pool parameter or a `ffmpegkmp_frame_set_download_pool`). Result: one download into a reused buffer + one conversion into the bitmap; zero per-frame allocations. Same for the JVM `VideoDecoder` path, which already uses the lazy handle download. |
| F5 | Apple VideoToolbox frame → display | `ffplaykmp_emit_platform_video_frame` (133-160) borrows the `CVPixelBuffer`; Canvas fallback locks it in place (`ffmpegkmp_frame.c:312`) | none. |
| F6 | Android MediaCodec → Surface | `av_mediacodec_render_buffer_at_time` (281) | none (zero copy). Android Canvas fallback: software decode → F2 into one reused `FrameBitmap` (small-cleanups) → `canvas.drawBitmap` upload (**stays**). |
| F7 | converter intermediate | HDR routes convert through a 16-bit planar RGB intermediate (core.c 1466-1492; ~50 MB at 4K); SDR routes are one swscale pass into the destination (1715-1719) | **stays** (colour management needs the float pass); the intermediate is reused per converter, allocated once. |
| F8 | `ffmpegkmp_copy_props` / `av_frame_copy_props` | core.c 1407-1424, 1766 | metadata and side data only (bytes, not pixels); **stays**. |
| F9 | web software path: decoded → RGBA | `ffplaykmp_web_frame` converts into `callbacks->converted` (1293-1294, buffer reused by `ffplaykmp_rgb_output`) then `memcpy` into `callbacks->rgba` under the mailbox mutex (1309) | **P7 removes the memcpy**: double-buffer the mailbox — two converted frames owned by the callbacks struct, the converter writes the one not being read, and `ffplaykmp_web_player_poll` hands out a pointer under the mutex (the page-side callback at mjs 519-531 copies synchronously, so the swap is safe). |
| F10 | web: heap RGBA → JS | `module.HEAPU8.slice(rgba, …)` (mjs 520) then `postMessage(…, [copied.buffer])` (transfer, no copy) | the one `slice` **stays**: the heap is a `SharedArrayBuffer` that cannot be transferred, so one copy out of it is inherent; nothing else. |
| F11 | web: JS buffer → Kotlin | `jsBufferToByteArray`: a view on Kotlin/JS (`BrowserWorker.js.kt` "A Kotlin/JS ByteArray is an Int8Array"), a **copy into linear memory on Kotlin/Wasm** (`BrowserWorker.wasm.kt:314`, `BrowserBuffers.kt:18`) | Kotlin/JS: none. Kotlin/Wasm: **stays** while `VideoFrame`'s web implementation is a `ByteArray` (`NativeFrame.web.kt:15-16`), because Kotlin/Wasm cannot alias a JS buffer. This is the one path where the architecture rule ("never into a Kotlin array") is broken by the toolchain, not the design; a `BrowserFrame` over a `JsAny` buffer with `installPixels` from a JS view would remove it but needs Skiko-web interop — recorded as a follow-up, not a phase. |
| F12 | web: Kotlin bytes → Skia heap | `installPixels` (`VideoFrameImage.web.kt:24`; README 137), plus `copyOfRange` when the plane is not the whole array (23) | one copy **stays** (separate heaps); the `copyOfRange` is avoided when the frame's array is exactly the plane, which `BrowserFrame.rgba` guarantees for player frames (`NativeFrame.web.kt:65-66`). |
| F13 | web WebCodecs path: `VideoFrame.copyTo(new ArrayBuffer)` → transfer → F11 → F12 | `mjs:1111-1112`, `1169` (transferred) | the `copyTo` **stays** (WebCodecs' only read API; `allocationSize` buffer per frame could be pooled: P7 keeps two `ArrayBuffer`s and transfers them in rotation only if the page returns them — otherwise a transferred buffer is gone, so allocation per frame stays; note as a follow-up). |

Summary of what the phases change on the copy count: per seek, P2 removes the probe reads and P4 the redundant backward seek; per frame on desktop hardware, P4 removes one allocation and one full-frame pass (F4); on the web, P7 removes W5, W7, WK1, WK3 and F9 (three heap copies and one JS copy per packet/frame) and leaves W4, W6, WK2, F10 and (Wasm only) F11/F12 as the inherent ones.

### 4g, continued: what drawn-size conversion changes in the inventory
Input bytes (I1-I5, W1-W7), packets (K1-K5, WK1-WK3) and frames (F1-F13) as above, with these changes once 4h is in:
- **F2** (frame → Canvas bitmap, the single conversion into the bitmap's memory via `SkiaFrameBitmap.write` → `fillBitmap` → `peekPixels()` and `AndroidFrameBitmap.write` → `convertIntoBitmap` → `AndroidBitmap_lockPixels`): still one pass, but it writes `target` pixels instead of source pixels; swscale's output stage and the HDR float pass (`ffmpegkmp_convert_transfer`, core.c 1596-1601: the intermediate is made at the destination's size and the source is scaled into it first) run at the drawn size. The input stage still reads the whole source.
- **F3** (bitmap → GPU upload): bytes uploaded per frame drop by the same area ratio.
- **F7** (HDR intermediate, 6 B/px): allocated at the destination size (core.c 1473-1481), so ~50 MB at 4K becomes ~12 MB at 1080p and ~3 MB for a 960×540 tile.
- **F9-F12** (web: convert → mailbox → `slice` → page → Skia/ImageData): with the worker converting at the drawn size (4h, web), every one of these moves `target` bytes instead of source bytes; the copies that remain stay the same in number.
- **K5** (packets decoded to reach a preview target): unchanged in bytes, but a paused player no longer re-decodes on a resize (decision 4h.4).

### 4h. Canvas frames converted straight to the drawn size

**Verified starting point.** `FrameImage.updateIfOpen` (`FrameImage.kt`) allocates with `frame.toFrameBitmap(format)` at `frame.width × frame.height` and reuses a bitmap only if `holds(frame, format)` matches that size (203-204). `SkiaFrameBitmap` builds its `ImageInfo(width, height, …)` from the frame (`VideoFrameImage.skiko.kt:60-63`) and `write` converts into the bitmap's own pixels (77-80); `AndroidFrameBitmap` creates the `Bitmap` at the frame size (`VideoFrameImage.android.kt:159-171`) and `write` goes through `ffmpegkmp_convert_with_bitmap`, which wraps the bitmap's pixels at the bitmap's own size from `AndroidBitmap_getInfo` (`ffmpegkmp_android_bitmap.c:51-57` → `ffmpegkmp_frame_wrap`, `ffmpegkmp_frame.c:1102-1122`). Both therefore already convert into whatever size the bitmap has; `VideoFrame.convertInto` documents the scaling (`VideoFrame.kt:95-102`) and the converter scales in the same pass on the `SCALE`, `SCALE_MAPPED`, `TONE_MAP` and YUV→F16 `TRANSFER` routes (core.c 1310-1323, 1594-1601). The one refusal is a packed RGBA F16 (or RGBA/BGRA) *source* into a direct destination of another size (`direct` && sizes differ → `AVERROR(ENOSYS)`, core.c 1602-1604). The Canvas surfaces draw by stretching `shown.source` into the placed rectangle (`drawUpright`, 243-258), so a bitmap of any size draws identically. `Shown.displaySize` is computed from `source` (the bitmap rect) (219-221) — that is the one thing that must change.

**1. Target size, fed synchronously from drawing.** Add to `FrameImage`:
```kotlin
/** Pixels the next frames are converted to, capped at the source size; null for the source size. */
public var targetSize: IntSize?   // synchronous setter; stored in an AtomicReference, read under the update lock
```
`DrawScope.drawFrameImage(image, contentScale)` (`FFplaySurface.kt:117-120`), which `ComposeCanvasFFplaySurface` (95-98) and the iOS overlay (`PlatformFFplaySurface.ios.kt:122-123`) both call, already computes `destination = contentScale.place(image.displaySize, size)`; it writes `image.drawnSize = destination.size` there. That is a plain atomic field, not snapshot state, so writing it during draw neither invalidates nor loops; it is in device pixels because `DrawScope.size` is. The next `updateIfOpen` reads it under the lock, turns it back into coded orientation (swap for a quarter turn, `Rect.beforeTurn` already does this, 290-294), buckets it (2), caps it at the frame's coded size (never upscale), and allocates or reuses bitmaps at that size. No `onSizeChanged`, no scope, no launch: the drawn size reaches the converter on the player's native thread through one atomic read. Before the first draw (`drawnSize` unset) bitmaps are at the source size, as today; a surface that is never drawn keeps that.

**2. Buckets with hysteresis.** `targetSize` is not used raw: the bitmap size is `source × s` with `s` from a 16-step ladder (`1/16 … 16/16`, `s = ceil(needed × 16) / 16`), which keeps the aspect exact and makes a resize animation cross at most a few steps. Growth takes effect at the next update (quality first); shrinking only when the needed step is two or more below the current one (so a wobble around a step boundary never reallocates). A step change replaces the three bitmaps one per update through the existing `holds` path (the oldest that does not hold the new size is closed and reallocated, 127-134): three allocations per step crossed, never one per pixel or per frame.

**3. The bitmap is smaller than the video; layout is not.** `Shown` gets the frame's coded size next to the bitmap's `source` rect, and `displaySize` is computed from the frame (`displaySize(frame.width, frame.height, sar, rotation)`), not from the bitmap. `FrameImage.displaySize` (102) therefore stays the video's display size whatever the bitmaps hold, `ContentScale.place` keeps placing the video, and there is no feedback between the drawn size and the layout. `drawUpright` stretches the smaller `source` into the same rectangle, so a bitmap at the ladder step is drawn 1:1 or very slightly resampled.

**4. A paused player whose drawn size grows: keep the small bitmap, scaled up, until the next frame (chosen).** Alternatives: (A) re-decode the current frame through the kept session (a preview job at the current position); (C) retain the last decoded `VideoFrame` per `FrameImage` and re-convert it at the new size. (A) couples the Canvas output to the engine (today `ComposeCanvasOutput` has no handle on the player), costs a flush + keyframe seek + decode-to-target on *every* paused player when a window resizes (16 tiles → 16 decodes, software 4K tens to hundreds of ms each), and needs the session thread (phase 3) to be safe. (C) needs no engine, but holds a decoder frame per paused player (4K 8-bit 4:2:0 ≈ 12 MB, 10-bit ≈ 25 MB; 16 players ≈ 200-400 MB), which is the memory this feature exists to remove. (B) costs nothing: the GPU already stretches `source` into the rectangle, the interim picture is one frame at the old step, and the editor's next action on an enlarged player (scrub, play, seek) delivers a frame at the new size through the normal path; a seek while paused also does, since it presents a preview. Choose (B); (C) stays as an opt-in open question if the one-frame blur is unacceptable for a hero preview.

**5. Paths.**
- *Compose Canvas (JVM, macOS, iOS overlay, web Skiko)*: as above; `ComposeCanvasOutput`/`IOSSampleBufferOutput` (`playerFrameImage()`) need no change beyond the draw-site write.
- *Android Surface software fallback*: `AndroidSurfaceOutput.draw` (small-cleanups `PlatformFFplaySurface.android.kt`, the `draw(frame)` added in `ae1e339`) already computes `display` and the placed `destination` from the surface's `width/height` before converting into its one reused `FrameBitmap`; give `toFrameBitmap(format, size)` the bucketed destination size and change its `takeIf { width == frame.width … }` to the target size. `canvas.drawBitmap(pixels, null, destRect)` then draws 1:1. One bitmap stays enough (it draws before taking the next frame).
- *Skiko vs Android `FrameBitmap.write`*: unchanged bodies; only the constructors take a size (`SkiaFrameBitmap(frame, format, size)` builds `ImageInfo(size)`; `AndroidFrameBitmap` creates the `Bitmap` at `size`). `VideoFrame.toFrameBitmap(format)` becomes `toFrameBitmap(format, size: IntSize)` (internal expect).
- *HDR `RgbaF16` bitmaps*: a YUV PQ/HLG frame into an F16 canvas takes the `TRANSFER` route through the intermediate at the destination's size (core.c 1594-1601), so the float transfer step runs at the drawn size — the biggest saving (16 B/px bitmaps and the 6 B/px intermediate both shrink). The player's Canvas outputs advertise no HDR transfers (`hdrTransfers = emptySet()`, `FFplaySurface.kt:101`), so their frames tone map into RGBA8 via `TONE_MAP`, also scaled first. An RGBA F16 *source* frame (a `VideoOutput.Memory(RgbaF16)` decoder feeding a user's `FrameImage`, or a `ComposeFrameRenderer` frame) is the exception: the direct route refuses another size (core.c 1602-1604), so `FrameImage` pins that format at the source size (the first `ENOSYS` from `write` sets a per-format "no scaling" flag and retries at the source size) and documents it, as `convertInto` does.
- *Web Canvas*: the browser's `convertInto` copies same-size RGBA8 only (`NativeFrame.web.kt:49-57`), and `WebCanvasOutput` draws RGBA8 `ImageData` at the frame's size (`PlatformFFplaySurface.web.kt:78-93`), so the size must be applied where the pixels are made: in the worker. Add `ffplaykmp_web_player_set_frame_size(player, width, height)` (0,0 = source) which `ffplaykmp_web_frame` applies through `ffplaykmp_rgb_output` (core.c 1735, give it a size) so `converted` is made at the drawn size; a `player-frame-size` message from `BrowserPlayerBridge` (new `NativePlayerBridge.setFrameSize(w, h)`, default no-op on other bindings; the Canvas output forwards `drawnSize` through the engine as it forwards `drawnSize` to `FrameImage` — synchronous, from the same draw-site write, coalesced to the bucket step so it posts only on a step change). The frame then arrives already small: `BrowserFrame.rgba` is at that size, the Skiko-web `FrameImage` allocates at the frame size (its `targetSize` is a no-op there because the frame is already ≤ target), and `WebCanvasOutput.drawRgba` draws it into the HTML canvas as now. Display aspect survives because `ffmpegkmp_scaled_aspect` sets the converted frame's SAR (core.c 1724-1733, used at 1700); the display *size* for layout on the web comes from `VideoInfo` (`snapshot.video`), which the web surface already uses for platform frames (`video.sampleAspectRatioValue()`, 96-98) and must now use for `submitNative` too (`it.width/it.height` at 83-84 are the converted size).
- *`displaySize`*: see 3; on every path layout uses the video's size from the frame's coded size (native) or `VideoInfo` (web), never the bitmap's.

**6. Memory table (paused-player floor), 4K source, RGBA8, three bitmaps**: source size 3 × 33 MB ≈ 100 MB; drawn at 1920×1080 ≈ 25 MB; 960×540 tile ≈ 6 MB; 256×144 thumbnail ≈ 0.45 MB. Sixteen paused players: ≈ 1.6 GB today → ≈ 400 MB if all are full-HD, ≈ 100 MB as tiles, ≈ 7 MB as thumbnails. F16 doubles each figure; the Android Surface fallback is one bitmap, a third of these. A HiDPI full-screen player drawing 4K pixels keeps the full size, which is correct. The per-player floor after this phase is the bitmaps at the drawn step plus the kept demuxer (phase 2); codecs are released on pause (phase 5).

**7. API and threading.** `FrameImage.targetSize` is a public synchronous property (useful to `ComposeFrameRenderer` tiles too, the same mechanism `VideoOutput.Memory(format, FrameSize)` gives decoders); `drawnSize` is internal and written only by `drawFrameImage`. No new scope, launch, flow or suspend function; the player's native thread reads one atomic value under the lock it already holds. `update`/`updateIfOpen` keep their signatures. Conversions per frame stay at one.

## 5. Limits and policy to expose (keep shapes; pre-1.0)

- `codec`: `object DecoderBudget { var hardwareDecoders: Int }` (0 = unlimited; defaults: Android 4, iOS 4, desktop 8, browser not applicable, to be set from the phase-0 numbers) applied to both `VideoDecoder.open` and `FFplayPlayer`; `enum DecoderPriority { VISIBLE, BACKGROUND, EXPORT }` parameter on `VideoDecoder.open` and `FFplayConfiguration`.
- `FFplayConfiguration(releaseDecoderWhenPaused: Duration? = 2.seconds)` (null = keep).
- Reporting: `FFplayOutputInfo.decoder` already says HARDWARE/SOFTWARE; add `decoderFallback: DecoderFallbackReason?` and put the reason in `FFplayEvent.DecoderFallback`; `VideoDecoder.decoderKind` likewise plus a `fallbackReason`. `FFplaySnapshot.state` stays; a scrub still blocks `seekTo` for the preview in phase 3 (as today), with an opt-in asynchronous preview later.

Added for drawn-size Canvas frames: `FrameImage.targetSize` as in 4h; the size
ladder is an internal constant, not configuration.

## 6. Phases, one commit each, with performance checks

Instrumentation added in phase 0 and kept:

1. A counting `ffplaykmp_io_callback` in the bench and in a JVM test mount:
   IO_OPEN, reads and bytes per prepare and per seek.
2. `ffmpegkmp_frame_get_statistics` sampled before and after N frames, giving
   allocations per frame (`src/jvmTest` already asserts flat native frame counts
   over 300 frames).
3. Debug counters in `ffplaykmp_player.c` for `av_frame_alloc`,
   `av_hwframe_transfer_data` and `memcpy` on the frame paths, read by the bench.
4. On the web, `WebAssembly.Memory.buffer.byteLength` and a count of
   `HEAPU8.slice` calls per frame in the test harness.
5. `FrameImage.allocations`/`conversions` and the bitmaps' byte total per image.

0. **Measure.** Bench `reopen` mode in `native-build/bench/video_decoder_bench.c`:
   N × (create + start + frame_at + destroy) against one create and N × (seek +
   frame_at), SOFTWARE and AUTO, 1080p and 4K, and through the counting I/O
   callback with injected per-read latency. A new `player_bench.c` linking
   `ffplaykmp_player.c`: prepare, set_output, N seeks, N pause/play, per-call
   time and opens counted. Android: a gated `PlayerReopenBudgetDeviceTest`
   modelled on `ParallelDecoderBudgetDeviceTest`, K ∈ {1, 4, 8, 16} players,
   Surface and Canvas, seek-to-frame and play-after-pause latency, HARDWARE and
   SOFTWARE counts, `getMaxSupportedInstances()` logged, plus concurrent
   `VideoDecoder`s. JVM and macOS K-player timing; browser timing with heap
   growth. Baselines also record bitmap bytes per Canvas player at the source
   size and converter time at 960×540. The numbers go in this document before
   phase 1.
1. **Refactor `decode_frames`** into open-input, open-codec, seek, decode-loop
   and close helpers on a session struct, still opened and closed per call.
   Packet and frame are allocated once per session. Check: the same numbers as
   phase 0, two fewer allocations per seek. Verify:
   `:library:ffplay:commonTestAllTargets`, `jvmTest`, `macosArm64Test`,
   `connectedAndroidDeviceTest`, bench.
1b. **Canvas bitmaps at the drawn size** (4h). Verify in `FrameImageTest`:
   bitmap sizes equal the ladder step for 1080p, 960×540 and 256×144 targets
   with 4K frames; 3 allocations after a step change and none across 100
   updates within a step; a 60-frame shrink and grow sweep allocates at most
   3 × the steps crossed; `displaySize` equals the video's for every target,
   rotated and anamorphic frames included; an F16 source keeps its size;
   `FrameImageDeviceTest` for the Android Surface fallback. Checks: bitmap bytes
   per player match the memory table; one conversion per frame; converter time
   at tile size against full size; paused RSS for 16 Canvas players.
2. **Keep the demuxer open per prepared source.** The inspect opens and keeps
   it; previews and the worker reuse it; `reset_cancel`, `stop`, `destroy` and
   a replacing prepare close it; `GuardedPlayerBridge.close` releases before it
   unmounts. (The web packet reader still opens its own input: `ffplaykmp_input`
   points into itself, so handing the kept one over needs care; done in phase 7.)
   Verify: a JVM test with a counting
   mount (one IO_OPEN per prepare, one IO_CLOSE at stop, close or replace, no
   read after unmount); `FFplayPrepareConcurrencyJvmTest`. Check: probe bytes
   paid once; seek latency is phase 0's minus the probe; the `ReplayableSource`
   cache does not grow further.
3. **Session thread with a job mailbox.** Previews run on it and callers block
   on a condvar as they block today; `pause` waits for the job to unwind.
   Verify: cancel during prepare, close during seek, a two-thread seek storm
   with cancel, MediaCodec used from the session thread only on Android, a seek
   preview after `ComposeCanvasOutput.close()` dropped by `updateIfOpen`, a seek
   preview after a resize converted at the new step. Check: the UI thread blocks
   no longer per seek than in phase 2; no allocation per job.
4. **Keep the codec; sticky AUTO fallback; fallback reason; forward-seek rule;
   lazy pooled download.** The codec is released on a key change (surface,
   output, preference). `FFplayEngine` adopts `requireNative` with a
   `NativePlayerException`. Verify: Android surface attach and detach while
   paused and playing; fallback-reason assertions. Check: no codec open per
   seek, pause or play on an unchanged key; one frame's packets demuxed per
   forward scrub step; on desktop hardware no `av_frame_alloc` per frame and one
   download plus one conversion per presented frame; play-after-pause without a
   codec open.
5. **Release on pause or idle, with a grace period.** Check: play after 0 s and
   after the grace; RSS with 16 paused players against the phase 1b floor; one
   codec open on play after the grace.
6. **Hardware budget in core, with Kotlin `DecoderBudget`, priority and
   reporting**, shared by players and `ffmpegkmp_decoder.c`. Verify: limit 2
   with 4 players gives exactly 2 HARDWARE and BUDGET reasons; players and
   decoders share one budget; a software fallback adds no copies.
7. **Web: one open, owned bytes, and frames at the drawn size.** `prepare_bytes`
   takes the worker's buffer; `openPacketReader` opens once; packets reach
   `EncodedVideoChunk` without a `slice`; the software mailbox is double
   buffered; `ffplaykmp_web_player_set_frame_size` and a `player-frame-size`
   message convert at the drawn size. Verify: Kotlin/JS player and decoder
   tests. Check: heap growth per prepare is 1× the input; no `slice` per packet
   (or one, documented, if a browser rejects shared views); one heap copy per
   software frame; bytes per frame equal the drawn size × 4; the HTML-canvas and
   Skiko-web outputs draw the display aspect from `VideoInfo`.
8. **Later: a shared demuxer for audio**, or the cheaper format-name and
   `probesize` hint for the audio open. Check: probes per player go from 2 to 1.

Stress tests run across phases with the counters logged: JVM with 16 players
and 4 decoders and flat frame statistics, a macOS native test against the
VideoToolbox budget, the gated Android device tests, and the browser with 4
workers seeking and memory sampled.

## 7. Open questions

Defaults in bold are what the phases assume until decided otherwise.

1. Default `hardwareDecoders` per platform. **0 (unlimited) until phase 0
   gives numbers.**
2. Seek previews: **blocking, as today**, or asynchronous through C-side
   coalescing behind `FFplayConfiguration(previewSeeks = ASYNC)`, observed as
   `snapshot` going SEEKING → PAUSED or PLAYING.
3. Release-on-pause grace (**2 s**), and whether a VISIBLE paused player may be
   evicted.
4. MediaCodec thread affinity is asserted by the project's docs, not verified;
   phase 3 comes before phase 4 on that assumption. If a device test shows
   cross-thread use is fine, phase 3 can be simplified.
5. VideoToolbox limits are inferred; Apple numbers from phase 0 decide whether
   the budget applies there by default.
6. Kept software codecs keep their frame-thread frames; release on pause is the
   only lever. Confirm that is acceptable against the reopen cost on
   software-only platforms.
7. Budget waiting: **ship without it**, or add `HardwareDecoding.WAIT` inside
   the already-suspend `VideoDecoder.open` and `FFplayPlayer.prepare`, bounded by
   the native deadline.
8. Run the native prepare on `Dispatchers.IO` instead of `Dispatchers.Default`;
   **decide from phase 0's numbers.**
9. Shared-buffer `EncodedVideoChunk` data: confirm on the target browsers;
   the fallback is one `slice`.
10. The Kotlin/Wasm frame copy into linear memory: **accept it for now**, or
    schedule a `BrowserFrame` over a JS buffer with Skiko-web `installPixels`.
11. A paused player whose drawn size grows: **show the small bitmap scaled up
    until the next frame**, or offer `FrameImage.keepLastFrame` to re-convert.
12. Size ladder: **16 steps of the source size** (exact aspect), or rounding
    each dimension to 64 px.
13. `ContentScale.Crop`: **keep the whole picture, capped at the source**, or
    allocate only the visible crop.
14. **Make `FrameImage.targetSize` public now**, or keep it internal until an
    export user asks.
15. Scaled SDR conversion is slower than same-size (swscale's unscaled fast
    path): **accept it** (under 2 ms, and the bitmaps and upload shrink), or
    convert at the source size and scale in a second, cheaper pass when the
    drawn size is close to it.
