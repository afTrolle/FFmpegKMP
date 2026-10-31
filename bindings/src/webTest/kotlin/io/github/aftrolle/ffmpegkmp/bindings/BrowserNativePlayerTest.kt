// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer

/**
 * The real player in the browser: [createPlatformPlayerBridge]'s Web Worker running the wasm build of the native
 * player. Its decodes run on the player's session thread, one of the worker's pooled pthreads, while the worker's
 * own thread waits on it for each preview. Under a software output the frames are the RGBA copies the worker hands
 * the page; under WebCodecs they are `VideoFrame`s, drawn into a canvas to be read.
 *
 * cfr-30-h264-128.mp4 draws its frame number as a 12-bit code in its top rows, so each check sees which frame
 * arrived. Every wait has a timeout, in real time, so a hang fails the test, naming what it waited for and where the
 * player was.
 */
class BrowserNativePlayerTest {
    @Test
    fun softwarePlaysTheClipThroughTheLifecycle() = playerTest("software lifecycle") {
        Playing("software").use { playing -> playThroughTheLifecycle(playing) }
    }

    /** The WebCodecs packet path: FFmpeg demuxes in the worker, packet by packet, and WebCodecs decodes. */
    @Test
    fun webCodecsPlaysTheClipThroughTheLifecycle() = playerTest("WebCodecs lifecycle") {
        if (!browserSupportsWebCodecs()) {
            log("skipped: this browser has no VideoDecoder or EncodedVideoChunk")
            return@playerTest
        }
        Playing("WebCodecs", NativePlayerDecoderPreference.AUTO, webCodecs = true).use { playing ->
            playThroughTheLifecycle(playing)
            assertEquals(0, playing.softwareFrames, "frames copied through the software path")
        }
        assertEquals(0, browserPlayerVideoFrameRegistrySize(), "VideoFrames left in the registry")
    }

    /**
     * Eight workers, each with its own pthread pool and session thread, prepared, seeked and played at once: every
     * command is posted to all of them before any is awaited.
     */
    @Test
    fun eightPlayersPrepareSeekAndPlayAtOnce() = playerTest("eight players") {
        val starts = List(8) { it * 500_000L }
        val players = starts.indices.map { Playing("player $it", index = it) }
        try {
            val started = TimeSource.Monotonic.markNow()
            players.eachAtOnce { playing -> playing.prepareAndShow() }
            log("eight players prepared in ${started.elapsedNow()}")

            players.eachAtOnce { playing -> playing.seekWhilePaused(starts[playing.index]) }
            players.forEach { assertEquals(0, it.bridge.play()) }
            players.eachAtOnce { playing ->
                val end = starts[playing.index] + 1_000_000L
                playing.await("playback past ${end}us") { it.positionUs >= end }
            }
            players.forEach { assertEquals(0, it.bridge.pause()) }
            players.eachAtOnce { playing -> playing.await("paused") { it.state == NativePlayerState.PAUSED } }
            val positions = players.map { it.snapshot.value.positionUs }
            for (playing in players) {
                val start = starts[playing.index]
                val position = positions[playing.index]
                // Each moved on from its own start, about as far as the others, while they all played.
                assertTrue(position in start + 1_000_000L..start + 2_500_000L, "${playing.name} at ${position}us from ${start}us")
                val shown = playing.settled()
                assertTrue(shown.number in frameAt(start) + FPS..frameAt(position), "${playing.name} shows ${shown.number} at ${position}us")
            }
            val spread = positions.zip(starts) { position, start -> position - start }
            assertTrue(spread.max() - spread.min() < 1_000_000L, "the players advanced by $spread")

            // Seeks while playing, each to another player's start, then a stop and a prepare that restarts each
            // session thread.
            players.forEach { assertEquals(0, it.bridge.play()) }
            players.eachAtOnce { playing -> playing.seekWhilePlaying(starts[starts.size - 1 - playing.index]) }
            players.forEach { assertEquals(0, it.bridge.stop()) }
            players.eachAtOnce { playing -> playing.await("stopped") { it.state == NativePlayerState.STOPPED } }
            players.eachAtOnce { playing -> playing.prepareAndShow() }
            players.eachAtOnce { playing -> playing.seekWhilePaused(starts[playing.index] + 1_000_000L) }
            players.forEach(Playing::check)
            log("eight players: ${players.map { "${it.name} ${it.shown.value.count} frames" }} in ${started.elapsedNow()}")
        } finally {
            players.forEach(Playing::close)
        }
    }

    /**
     * Stop ends the session thread, and with it the decoder's frame threads; the next prepare starts them again
     * from the worker's pool of 32, which never grows. Kept threads, up to nine a cycle, would use it up within these
     * six cycles.
     */
    @Test
    fun aPlayerPreparesAgainAfterEachStop() = playerTest("prepare, stop and prepare again") {
        Playing("cycling", decoderThreads = 8).use { playing ->
            val bridge = playing.bridge
            for (cycle in 0 until 6) {
                val started = TimeSource.Monotonic.markNow()
                playing.prepareAndShow()
                val target = cycle * 700_000L + 100_000L
                playing.seekWhilePaused(target)
                assertEquals(0, bridge.play())
                playing.await("playback past ${target + 300_000L}us") { it.positionUs >= target + 300_000L }
                val ending = when (cycle % 3) {
                    0 -> "while playing"
                    1 -> {
                        assertEquals(0, bridge.pause())
                        playing.await("paused") { it.state == NativePlayerState.PAUSED }
                        "while paused"
                    }
                    else -> {
                        assertEquals(0, bridge.seek(target / 2))
                        "right after a seek"
                    }
                }
                assertEquals(0, bridge.stop())
                playing.await("stopped $ending") { it.state == NativePlayerState.STOPPED }
                log("cycle $cycle, stopped $ending, took ${started.elapsedNow()}")
            }
            playing.check()
        }
    }

    /**
     * With no release delay the decoder goes, its frame threads back to the worker's pool, whenever the player is
     * idle: play after a pause starts again at the paused frame, decoded from the keyframe, where a kept decoder
     * goes on to the next one, and every seek still shows its exact frame.
     */
    @Test
    fun softwareReleasesItsDecoderWhenPausedAndPlaysOnFromThePausedFrame() = playerTest("release when paused") {
        Playing("released when idle", codecReleaseDelayUs = 0).use { playing ->
            val bridge = playing.bridge
            playing.prepareAndShow()
            playing.seekWhilePaused(1_550_000L)
            assertEquals(0, bridge.play())
            playing.await("playback past 2s") { it.positionUs >= 2_000_000L }
            assertEquals(0, bridge.pause())
            playing.await("paused") { it.state == NativePlayerState.PAUSED }
            val held = playing.settled()
            val paused = playing.snapshot.value.positionUs
            delay(300)
            assertEquals(held, playing.shown.value, "no frame arrives as the decoder goes")

            assertEquals(0, bridge.play())
            playing.await("playback past ${paused + 300_000L}us") { it.positionUs >= paused + 300_000L }
            val numbers = playing.numbersSince(held.count)
            assertEquals(held.number, numbers.first(), "the first frame played after the release, of $numbers")
            assertEquals(numbers.sorted().distinct(), numbers, "frames in order")
            assertEquals(0, bridge.pause())
            playing.await("paused again") { it.state == NativePlayerState.PAUSED }
            listOf(700_000L, 733_334L, 4_000_000L).forEach { playing.seekWhilePaused(it) }
            playing.check()
        }
    }

    /** Close ends the worker wherever playback is; the next player starts afresh in a worker of its own. */
    @Test
    fun closeWhilePlayingReturns() = playerTest("close while playing") {
        for (played in listOf(0L, 300_000L, 1_500_000L)) {
            val playing = Playing("closed after ${played}us")
            try {
                playing.prepareAndShow()
                assertEquals(0, playing.bridge.play())
                if (played > 0) playing.await("playback past ${played}us") { it.positionUs >= played }
                val started = TimeSource.Monotonic.markNow()
                playing.bridge.close()
                log("close while playing after ${played}us took ${started.elapsedNow()}")
                val shown = playing.shown.value
                delay(200)
                assertEquals(shown, playing.shown.value, "no frame after close")
            } finally {
                playing.close()
            }
        }
        Playing("after the closed ones").use { playing ->
            playing.prepareAndShow()
            assertEquals(0, playing.bridge.play())
            playing.await("playback") { it.positionUs >= 300_000L }
        }
    }

    /**
     * A seek blocks the worker's thread while the session thread decodes its frame. Stop, queued behind it, must
     * still end playback and the session thread; close ends the worker whatever it is doing.
     */
    @Test
    fun stopOrCloseDuringASeekReturns() = playerTest("stop or close during a seek") {
        val heads = listOf(0L, 0L, 2L, 5L, 20L)
        for (close in listOf(false, true)) {
            for ((attempt, head) in heads.withIndex()) {
                val what = "${if (close) "close" else "stop"} during a seek (attempt $attempt)"
                Playing(what).use { playing ->
                    val bridge = playing.bridge
                    playing.prepareAndShow()
                    if (attempt % 2 == 1) {
                        assertEquals(0, bridge.play())
                        playing.await("playback") { it.positionUs >= 300_000L }
                    }
                    // The last is past the end, so its seek drains the decoder for the last frame.
                    val target = (1 + attempt) * 1_000_000L + 500_000L
                    assertEquals(0, bridge.seek(target))
                    if (head > 0) delay(head)
                    val started = TimeSource.Monotonic.markNow()
                    if (close) {
                        bridge.close()
                    } else {
                        assertEquals(0, bridge.stop())
                        playing.await("stopped") { it.state == NativePlayerState.STOPPED }
                        // The session thread ended with the stop; a prepare starts another.
                        playing.prepareAndShow()
                        playing.seekWhilePaused(1_000_000L)
                    }
                    log("$what, ${head}ms after it, returned in ${started.elapsedNow()}")
                    playing.check()
                }
            }
        }
    }

    /**
     * Prepare and show the clip, then seek while paused, play, pause, seek while playing, seek past the end, and
     * stop.
     */
    private suspend fun playThroughTheLifecycle(playing: Playing) {
        val bridge = playing.bridge
        val started = TimeSource.Monotonic.markNow()
        playing.prepareAndShow()
        val prepared = playing.snapshot.value
        assertEquals(SIZE, prepared.videoWidth)
        assertEquals(SIZE, prepared.videoHeight)
        val duration = checkNotNull(prepared.durationUs) { "no duration in $prepared" }
        assertTrue(duration in 4_900_000L..5_100_000L, "duration ${duration}us")
        log("${playing.name} prepared in ${started.elapsedNow()} on ${prepared.activeDecoder}")
        playing.assertExpectedDecoder()

        val target = 1_550_000L
        playing.seekWhilePaused(target)

        val beforePlay = playing.shown.value.count
        assertEquals(0, bridge.play())
        playing.await("playing") { it.state == NativePlayerState.PLAYING }
        val later = target + 1_000_000L
        playing.await("playback past ${later}us") { it.positionUs >= later }
        playing.awaitShown("frames past ${frameAt(later)}") { it.number >= frameAt(later) }
        val played = playing.shown.value.count - beforePlay
        log("${playing.name}: $played frames in a second of playback")
        assertTrue(played >= FPS - 3, "$played frames shown in a second of playback")
        val numbers = playing.numbersSince(beforePlay)
        assertEquals(numbers.sorted().distinct(), numbers, "frames in order")

        assertEquals(0, bridge.pause())
        playing.await("paused") { it.state == NativePlayerState.PAUSED }
        val held = playing.settled()
        val paused = playing.snapshot.value.positionUs
        assertTrue(held.number in frameAt(paused) - 1..frameAt(paused), "showing ${held.number} paused at ${paused}us")
        delay(400)
        assertEquals(paused, playing.snapshot.value.positionUs, "the position holds while paused")
        assertEquals(held, playing.shown.value, "no frame arrives while paused")

        assertEquals(0, bridge.play())
        playing.await("playback past ${paused}us") { it.positionUs > paused + 200_000L }
        val back = 500_000L
        playing.seekWhilePlaying(back)
        playing.await("playback past ${back + 500_000L}us") { it.positionUs in back + 500_000L..back + 2_000_000L }
        playing.awaitShown("frames past ${frameAt(back + 500_000L)}") {
            it.number in frameAt(back + 500_000L) until frameAt(back) + 2 * FPS
        }

        // Past the end, while paused: the seek shows the last frame. The bridge keeps the position it was given;
        // FFplayEngine bounds a seek to the duration before it reaches the bridge.
        assertEquals(0, bridge.pause())
        playing.await("paused before the end") { it.state == NativePlayerState.PAUSED }
        val serial = playing.snapshot.value.queueSerial
        assertEquals(0, bridge.seek(10_000_000L))
        val end = playing.await("the seek past the end") {
            it.queueSerial != serial && (it.state == NativePlayerState.PAUSED || it.state == NativePlayerState.ENDED)
        }
        playing.awaitShown("the last frame") { it.number == 5 * FPS - 1 }
        log("${playing.name} past the end: ${end.state} at ${playing.snapshot.value.positionUs}us of ${duration}us")
        assertEquals(10_000_000L, playing.snapshot.value.positionUs, "the position past the end")

        assertEquals(0, bridge.stop())
        playing.await("stopped") { it.state == NativePlayerState.STOPPED }
        val stopped = playing.settled()
        delay(200)
        assertEquals(stopped, playing.shown.value, "no frame after stop")
        playing.check()
        log("${playing.name} lifecycle took ${started.elapsedNow()}, ${playing.shown.value.count} frames")
    }

    /** What reached the page: how many frames, and the latest one's number, size, time and queue serial. */
    private data class Shown(
        val count: Int = 0,
        val number: Int = -1,
        val width: Int = 0,
        val height: Int = 0,
        val presentationTimeUs: Long = -1,
        val queueSerial: UInt = 0u,
    )

    /**
     * One player on a worker of its own, with a software output, or, with [webCodecs], an output that also imports
     * WebCodecs `VideoFrame`s.
     */
    private class Playing(
        val name: String,
        preference: NativePlayerDecoderPreference = NativePlayerDecoderPreference.SOFTWARE,
        decoderThreads: Int = 0,
        val webCodecs: Boolean = false,
        val index: Int = 0,
        codecReleaseDelayUs: Long = 2_000_000,
    ) {
        val snapshot = MutableStateFlow(NativePlayerSnapshot())
        val shown = MutableStateFlow(Shown())
        private val numbers = mutableListOf<Int>()
        private val failures = mutableListOf<NativePlayerSnapshot>()
        private var outputSet = false
        var softwareFrames = 0
            private set
        val bridge: NativePlayerBridge = createPlatformPlayerBridge(
            configuration = NativePlayerConfiguration(preference, decoderThreads, codecReleaseDelayUs),
            update = { update ->
                if (update.state == NativePlayerState.FAILED) failures += update
                snapshot.value = update
            },
            frame = { frame ->
                softwareFrames++
                show(frame.number(), frame.width, frame.height, frame.presentationTimeUs, frame.queueSerial)
                frame.frame.close()
            },
            platformFrame = { frame ->
                show(browserVideoFrameNumber(frame.handle as Int), frame.width, frame.height, frame.presentationTimeUs, frame.queueSerial)
                // Declined, so the binding closes the VideoFrame at once.
                false
            },
        )

        private fun show(number: Int, width: Int, height: Int, presentationTimeUs: Long, queueSerial: UInt) {
            numbers += number
            shown.update { Shown(it.count + 1, number, width, height, presentationTimeUs, queueSerial) }
        }

        fun numbersSince(count: Int): List<Int> = numbers.drop(count)

        /** Prepares the clip, sets the output the first time once the stream is known, and waits for the preview of its first frame. */
        suspend fun prepareAndShow() {
            val before = shown.value.count
            assertEquals(0, bridge.prepare(source()))
            val prepared = within("${name}'s preparation") { bridge.awaitPreparation() }
            assertEquals(0, prepared, "$name prepared: ${snapshot.value}")
            // Once set, the bridge sends the output again after each prepare.
            if (!outputSet) {
                assertEquals(
                    0,
                    bridge.setOutput(NativePlayerOutputCapabilities(hardwareFrameImport = webCodecs, softwareFrameUpload = true)),
                )
                outputSet = true
            }
            await("ready") { it.state == NativePlayerState.READY }
            val preview = awaitShown("the preview") { it.count > before && it.number == 0 }
            assertEquals(SIZE to SIZE, preview.width to preview.height, "the preview's size")
            assertEquals(0L, preview.presentationTimeUs)
        }

        /** Seeks while paused or ready, and waits for the frame on screen at [target]. */
        suspend fun seekWhilePaused(target: Long) {
            val serial = snapshot.value.queueSerial
            assertEquals(0, bridge.seek(target))
            await("the seek to ${target}us") {
                it.queueSerial != serial && it.state == NativePlayerState.PAUSED && it.positionUs == target
            }
            awaitShown("frame ${frameAt(target)}") { it.queueSerial != serial && it.number == frameAt(target) }
        }

        /** Seeks while playing, and waits for frames from the one at [target] on. */
        suspend fun seekWhilePlaying(target: Long) {
            val serial = snapshot.value.queueSerial
            assertEquals(0, bridge.seek(target))
            await("playing after the seek to ${target}us") {
                it.queueSerial != serial && it.state == NativePlayerState.PLAYING
            }
            awaitShown("frames from ${frameAt(target)}") {
                it.queueSerial != serial && it.number in frameAt(target) until frameAt(target) + FPS
            }
        }

        fun assertExpectedDecoder() {
            val expected = if (webCodecs) NativePlayerDecoderKind.UNKNOWN else NativePlayerDecoderKind.SOFTWARE
            assertEquals(expected, snapshot.value.activeDecoder, "$name's decoder")
            if (webCodecs) assertEquals(0, softwareFrames, "$name's frames through the software path")
        }

        suspend fun await(
            what: String,
            timeout: Duration = 10.seconds,
            predicate: (NativePlayerSnapshot) -> Boolean,
        ): NativePlayerSnapshot = withTimeoutOrNull(timeout) {
            snapshot.first { update ->
                if (update.state == NativePlayerState.FAILED) fail("$name failed waiting for $what: $update")
                predicate(update)
            }
        } ?: fail("Timed out after $timeout waiting for $what on $name; it is at ${snapshot.value}, showing ${shown.value}")

        suspend fun awaitShown(what: String, timeout: Duration = 10.seconds, predicate: (Shown) -> Boolean): Shown =
            withTimeoutOrNull(timeout) { shown.first(predicate) }
                ?: fail("Timed out after $timeout waiting for $what on $name; it shows ${shown.value} at ${snapshot.value}")

        /** What the page shows once the frames already on their way have arrived. */
        suspend fun settled(): Shown {
            delay(150)
            return shown.value
        }

        /** Fails on a failed state the player reported. */
        fun check() {
            assertEquals(emptyList(), failures, "$name's failures")
        }

        fun close() = bridge.close()

        inline fun <R> use(block: (Playing) -> R): R = try {
            block(this)
        } finally {
            close()
        }
    }

    private companion object {
        /** 128x128 H.264 at 30 fps, five seconds, with keyframes every 12 frames: the frame code over cfr-30.mp4. */
        const val CLIP = "cfr-30-h264-128.mp4"
        const val SIZE = 128
        const val FPS = 30

        private var clip: ByteArray? = null

        suspend fun clipBytes(): ByteArray =
            clip ?: loadBrowserPlayerTestResource("/base/kotlin/video-decoder/$CLIP").also { clip = it }

        suspend fun source(): NativePlayerSource {
            val bytes = clipBytes()
            return NativePlayerSource(
                input = CLIP,
                mounts = listOf(NativeMountedIo(path = CLIP, resource = NativeSourceResource(Buffer().write(bytes)))),
            )
        }
    }
}

/**
 * Runs [block] in real time, where a timeout is a wall-clock one: the test scheduler would skip the waits for the
 * worker's messages straight to their timeouts.
 */
private fun playerTest(name: String, block: suspend CoroutineScope.() -> Unit) = runTest(timeout = 100.seconds) {
    configureBrowserPlayerTestRuntime()
    val started = TimeSource.Monotonic.markNow()
    withContext(Dispatchers.Default) { block() }
    log("$name took ${started.elapsedNow()}")
}

/** Runs [action] on every player at once, failing on the first that fails. */
private suspend fun <T> List<T>.eachAtOnce(action: suspend (T) -> Unit) = coroutineScope {
    map { async { action(it) } }.awaitAll()
}

private suspend fun <T> within(what: String, timeout: Duration = 15.seconds, block: suspend () -> T): T =
    withTimeoutOrNull(timeout) { block() } ?: fail("$what did not finish within $timeout")

/** The frame on screen at [timeUs]; a frame's own start time, in whole microseconds, may fall just short of it. */
private fun frameAt(timeUs: Long): Int = floor(timeUs * 30 / 1e6 + 1e-3).toInt()

/** The number cfr-30-h264-128.mp4 draws in a frame: 12 cells of 16x16, the green channel lit for a set bit. */
private fun NativeVideoFrame.number(): Int = frame.usePlanes { planes ->
    val plane = planes.single()
    val bytes = plane.memory as ByteArray
    (0 until 12).sumOf { bit ->
        val x = (bit % 6) * 16 + 8
        val y = (bit / 6) * 16 + 8
        if ((bytes[plane.offset + y * plane.rowBytes + x * 4 + 1].toInt() and 0xff) > 128) 1 shl bit else 0
    }
}

private fun log(message: String) = println("BrowserNativePlayerTest: $message")

/** Reads the frame number from the WebCodecs `VideoFrame` registered as [frameId], or -1 if there is none. */
internal expect fun browserVideoFrameNumber(frameId: Int): Int
