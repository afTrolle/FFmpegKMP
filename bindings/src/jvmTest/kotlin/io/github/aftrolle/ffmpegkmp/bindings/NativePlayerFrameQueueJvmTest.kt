// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import okio.Buffer

/**
 * The player's frame queue: the session thread decodes ahead into it, and refresh shows its frames.
 * These tests refresh the player themselves, or not at all, so that the queue fills.
 */
class NativePlayerFrameQueueJvmTest {
    @Test
    fun neverMoreFramesOutstandingThanTheQueueHolds() {
        for (size in listOf(1, 3)) {
            val sink = TrackingSink()
            player(sink, size).use { bridge ->
                assertEquals(0, bridge.play())
                // Nothing refreshes: the decoder fills the queue and waits.
                sink.awaitOutstanding(size)
                Thread.sleep(100)
                assertEquals(size, sink.outstanding(), "frames outstanding with nothing shown, queue of $size")
                RefreshedPlayerBridge(bridge).use { refreshed ->
                    sink.awaitState(refreshed, NativePlayerState.ENDED)
                }
            }
            assertTrue(sink.maxOutstanding <= size, "${sink.maxOutstanding} frames outstanding in a queue of $size")
            sink.assertEachFrameFinishedOnce()
        }
    }

    @Test
    fun noFrameFromBeforeASeekIsShownAfterIt() {
        val sink = TrackingSink()
        player(sink, 3).use { bridge ->
            assertEquals(0, bridge.play())
            // The decoder waits on a full queue of frames from the start.
            sink.awaitOutstanding(3)
            var refreshed: RefreshedPlayerBridge? = null
            sink.assertSeekShowsNoEarlierFrame(bridge, 600_000) {
                refreshed = RefreshedPlayerBridge(bridge)
                sink.awaitPresentedPast(800_000, from = it)
            }
            // A seek while playing, with the refresh thread taking frames meanwhile.
            sink.assertSeekShowsNoEarlierFrame(bridge, 200_000) { sink.awaitPresentedPast(400_000, from = it) }
            refreshed?.close()
        }
        sink.assertEachFrameFinishedOnce()
    }

    /**
     * Seeks [bridge] to [positionUs], runs [then] with the count of frames presented before the seek,
     * and checks the frames presented since: whatever an in-flight refresh had taken before the seek
     * may still show, but from the seek's preview on, every frame is of the seek's serial and at or
     * after its position.
     */
    private fun TrackingSink.assertSeekShowsNoEarlierFrame(bridge: NativePlayerBridge, positionUs: Long, then: (Int) -> Unit) {
        val before = presented.size
        assertEquals(0, bridge.seek(positionUs))
        val serial = bridge.snapshot().queueSerial
        then(before)
        val since = presented.drop(before)
        val preview = since.indexOfFirst { it.queueSerial == serial }
        assertTrue(preview >= 0, "no frame of the seek: ${since.map { it.presentationTimeUs }}")
        assertEquals(positionUs, since[preview].presentationTimeUs, "the seek's preview")
        val after = since.drop(preview)
        assertTrue(
            after.all { it.queueSerial == serial && it.presentationTimeUs >= positionUs },
            "a frame from before the seek to $positionUs shown after it: ${after.map { it.queueSerial to it.presentationTimeUs }}",
        )
    }

    @Test
    fun controlsReturnWhileTheDecoderWaitsForRoom() {
        for (control in listOf("pause", "stop", "close")) {
            val sink = TrackingSink()
            val bridge = player(sink, 2)
            assertEquals(0, bridge.play())
            sink.awaitOutstanding(2)
            val took = measureTime {
                when (control) {
                    "pause" -> assertEquals(0, bridge.pause())
                    "stop" -> assertEquals(0, bridge.stop())
                    else -> bridge.close()
                }
            }
            assertTrue(took < LIVENESS, "$control took $took while the decoder waited for room")
            if (control != "close") bridge.close()
            sink.assertEachFrameFinishedOnce()
        }
    }

    @Test
    fun controlsReturnWhileARefreshPresents() {
        for (control in listOf("pause", "seek", "stop", "close")) {
            val sink = TrackingSink(presentMillis = 300)
            val bridge = player(sink, 3)
            assertEquals(0, bridge.play())
            sink.awaitOutstanding(3)
            val refresher = thread { while (bridge.refresh() != NATIVE_PLAYER_REFRESH_IDLE) Thread.sleep(1) }
            sink.awaitPresenting()
            val took = measureTime {
                when (control) {
                    "pause" -> assertEquals(0, bridge.pause())
                    // The seek plays on afterwards; the pause after it lets the refreshes end.
                    "seek" -> assertEquals(0, bridge.seek(100_000)).also { bridge.pause() }
                    "stop" -> assertEquals(0, bridge.stop())
                    else -> bridge.close()
                }
            }
            assertTrue(took < LIVENESS, "$control took $took while a refresh presented")
            refresher.join(LIVENESS.inWholeMilliseconds)
            assertTrue(!refresher.isAlive, "the refresh did not return after $control")
            if (control != "close") bridge.close()
            sink.assertEachFrameFinishedOnce()
        }
    }

    /**
     * ffplay's video_refresh with a display clock that jumps ahead: the first refresh after the
     * jump re-anchors on the next frame, and once the master clock (here the clock that runs on from
     * the first frame) is ahead, each refresh drops the older due frames, counts them, and shows the
     * newest, until playback has caught up and ends.
     */
    @Test
    fun aRefreshShowsTheNewestDueFrameAndDropsTheOlder() {
        val sink = TrackingSink()
        player(sink, 3).use { bridge ->
            sink.presented.clear()
            assertEquals(0, bridge.play())
            sink.awaitOutstanding(3)
            val start = 1_000_000_000L
            // The preview at 0 was shown last: the first queued frame is due a frame later.
            assertTrue(bridge.refresh(start, 0) in 1..40_000)
            assertTrue(sink.presented.isEmpty())
            bridge.refresh(start + 40_000, 0)
            assertEquals(listOf(40_000L), sink.presented.map { it.presentationTimeUs })

            sink.awaitOutstanding(3)
            val jump = start + 40_000 + 1_000_000
            bridge.refresh(jump, 0)
            assertEquals(80_000L, sink.presented.last().presentationTimeUs, "the frame the jump re-anchors on")
            assertEquals(0, bridge.snapshot().droppedFrames)

            sink.awaitOutstanding(3)
            val queued = sink.preparedPositions()
            // The frame timer runs a frame duration per frame from the jump: 200 ms on, all three are due.
            bridge.refresh(jump + 200_000, 0)
            assertEquals(queued.max(), sink.presented.last().presentationTimeUs, "the newest of $queued")
            assertEquals(queued.size - 1L, bridge.snapshot().droppedFrames, "the older of $queued")
            assertEquals(queued.max(), bridge.snapshot().positionUs)

            var now = jump + 200_000
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (bridge.snapshot().state == NativePlayerState.PLAYING) {
                assertTrue(System.nanoTime() < deadline, "playback did not recover: ${sink.presented.map { it.presentationTimeUs }}")
                sink.awaitOutstanding(3, orEnd = bridge)
                now += 40_000
                bridge.refresh(now, 0)
            }
            assertEquals(NativePlayerState.ENDED, bridge.snapshot().state)
            val positions = sink.presented.map { it.presentationTimeUs }
            assertEquals(positions.sorted(), positions)
            assertEquals(960_000L, positions.last())
        }
        sink.assertEachFrameFinishedOnce()
    }

    /**
     * A presented frame says when it is due on screen, on the refresh caller's clock, which is what a
     * MediaCodec buffer is rendered at (on the monotonic clock): the frame timer's time for it,
     * between the refresh's now and the time it chose frames for, not that time itself; now for a
     * late frame; and -1 for a preview, shown at once.
     */
    @Test
    fun aPresentedFrameIsDueAtItsFrameTimeOnTheCallersClock() {
        val sink = TrackingSink()
        player(sink, 3).use { bridge ->
            assertEquals(listOf(-1L), sink.displayTimes.toList(), "the preview's")
            assertEquals(0, bridge.play())
            sink.awaitOutstanding(3)
            val start = 1_000_000_000L
            val lead = 16_000L
            // The frame timer starts at start + lead; the first queued frame is due a frame (40 ms) later.
            assertTrue(bridge.refresh(start, lead) in 1..40_000)
            assertEquals(1, sink.presented.size, "only the preview")
            bridge.refresh(start + 45_000, lead)
            assertEquals(40_000L, sink.presented.last().presentationTimeUs)
            assertEquals(start + lead + 40_000, sink.displayTimes.last(), "due on the frame timer, within the lead")

            sink.awaitOutstanding(3)
            // Late by more than ffplay's sync threshold: the frame timer resets to the refresh, and the frame is due now.
            val late = start + 200_000
            bridge.refresh(late, 0)
            assertEquals(80_000L, sink.presented.last().presentationTimeUs)
            assertEquals(late, sink.displayTimes.last(), "a late frame is due now")
        }
        sink.assertEachFrameFinishedOnce()
    }

    @Test
    fun everyPreparedFrameIsPresentedOrReleasedOnce() {
        val sink = TrackingSink()
        player(sink, 3).let(::RefreshedPlayerBridge).use { bridge ->
            assertEquals(0, bridge.play())
            sink.awaitPresentedPast(200_000)
            assertEquals(0, bridge.seek(500_000))
            sink.awaitPresentedPast(600_000)
            assertEquals(0, bridge.pause())
            assertEquals(0, bridge.seek(100_000))
            bridge.clearOutput()
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(0, bridge.play())
            sink.awaitPresentedPast(300_000, from = sink.presented.size)
            assertEquals(0, bridge.stop())
            assertEquals(0, bridge.prepare(videoSource()))
            assertEquals(0, bridge.play())
            sink.awaitOutstanding(1)
            // Closed while playing, frames queued.
        }
        sink.assertEachFrameFinishedOnce()
        assertTrue(sink.released > 0, "no frame was flushed")
    }

    private fun player(sink: TrackingSink, queue: Int): NativePlayerBridge =
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE, frameQueueSize = queue),
            update = {},
            frame = sink,
        ).also { bridge ->
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(0, bridge.prepare(videoSource()))
        }

    private fun videoSource(): NativePlayerSource {
        // 25 fps, keyframes at 0, 0.48 and 0.96 s.
        val bytes = checkNotNull(javaClass.getResourceAsStream("/playback-color-patches-1s.mp4")).use { it.readBytes() }
        return NativePlayerSource(
            input = "playback-color-patches-1s.mp4",
            mounts = listOf(NativeMountedIo("playback-color-patches-1s.mp4", NativeSourceResource(Buffer().write(bytes)))),
        )
    }
}

/** Generous: these bound liveness, not speed. */
private val LIVENESS: Duration = 3.seconds

/**
 * Follows each frame from its prepare to its present or release, and fails on a token presented or
 * released twice, or never prepared.
 */
private class TrackingSink(private val presentMillis: Long = 0) : NativeVideoFrameSink {
    private val lock = Any()
    private val outstanding = LinkedHashMap<Long, NativeVideoFrame>()
    private val finished = HashSet<Long>()
    private val errors = CopyOnWriteArrayList<String>()
    val presented = CopyOnWriteArrayList<NativeVideoFrame>()

    /** Each presented frame's display time, as [presented] lists them. */
    val displayTimes = CopyOnWriteArrayList<Long>()

    @Volatile
    var maxOutstanding = 0
        private set

    @Volatile
    var released = 0
        private set

    @Volatile
    private var presenting = false

    override fun prepare(frame: NativeVideoFrame): Boolean {
        frame.frame.close()
        synchronized(lock) {
            if (frame.token == 0L || frame.token in finished || frame.token in outstanding) errors += "token ${frame.token} prepared again"
            outstanding[frame.token] = frame
            maxOutstanding = maxOf(maxOutstanding, outstanding.size)
        }
        return true
    }

    override fun present(token: Long, displayTimeUs: Long) {
        displayTimes += displayTimeUs
        present(token)
    }

    override fun present(token: Long) {
        val frame = finish(token, "presented") ?: return
        presenting = true
        if (presentMillis > 0) Thread.sleep(presentMillis)
        presented += frame
        presenting = false
    }

    override fun release(token: Long) {
        if (finish(token, "released") != null) released++
    }

    private fun finish(token: Long, how: String): NativeVideoFrame? = synchronized(lock) {
        val frame = outstanding.remove(token)
        if (frame == null) errors += "token $token $how but ${if (token in finished) "already finished" else "never prepared"}"
        finished += token
        frame
    }

    fun outstanding(): Int = synchronized(lock) { outstanding.size }

    /** The positions of the frames prepared and not yet presented or released. */
    fun preparedPositions(): List<Long> = synchronized(lock) { outstanding.values.map { it.presentationTimeUs } }

    fun assertEachFrameFinishedOnce() {
        assertEquals(emptyList(), errors.toList())
        assertEquals(0, outstanding(), "frames neither presented nor released: ${preparedPositions()}")
    }

    /** Waits until [count] frames are outstanding, or, given [orEnd], that player's input has no more. */
    fun awaitOutstanding(count: Int, orEnd: NativePlayerBridge? = null) = await("$count frames outstanding") {
        outstanding() >= count || (orEnd != null && outstanding() > 0 && orEnd.snapshot().positionUs >= 840_000)
            || (orEnd != null && orEnd.snapshot().state != NativePlayerState.PLAYING)
    }

    fun awaitPresentedPast(positionUs: Long, from: Int = 0) = await("a frame past ${positionUs}us") {
        presented.drop(from).any { it.presentationTimeUs > positionUs }
    }

    fun awaitPresenting() = await("a frame being presented") { presenting }

    fun awaitState(bridge: NativePlayerBridge, state: NativePlayerState) = await("$state") { bridge.snapshot().state == state }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "no $what within 5 s")
            Thread.sleep(2)
        }
    }
}
