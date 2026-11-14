// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Buffer

/**
 * Catch-up: a decoder slower than real time skips forward to a keyframe once a frame it decodes is
 * more than 500 ms behind the master clock (here the external clock, as there is no audio). The
 * decoder is slowed by the sink: its prepare, on the session thread, sleeps.
 */
class NativePlayerCatchUpJvmTest {
    /**
     * At half real time a frame falls 500 ms behind within a second; every skip lands on a keyframe
     * at or after the clock, so what is shown stays within 500 ms and a GOP of it, moves only
     * forward, keeps coming, and ends on the last frame. Every frame after the preview is shown or
     * counted as dropped: those passed over are counted from their timestamps.
     */
    @Test
    fun aDecoderSlowerThanRealTimeSkipsForwardToAKeyframe() {
        val sink = SlowSink(prepareMillis = 2 * FRAME_MS)
        player(sink, GOP_FIXTURE).use { bridge ->
            sink.clear()
            assertEquals(0, bridge.play())
            awaitEnded(bridge)
            val presented = sink.presented.map { it.first.presentationTimeUs }
            assertEquals(presented.distinct().sorted(), presented, "shown out of order")
            assertEquals(LAST_FRAME_US, presented.last())
            val dropped = bridge.snapshot().droppedFrames
            assertTrue(dropped > 0, "nothing dropped")
            assertEquals(FRAMES_AFTER_FIRST, presented.size + dropped, "shown ${presented.size}, dropped $dropped")
            val behind = sink.behindTheClock()
            assertTrue(behind.all { it <= CATCH_UP_US + GOP_US }, "shown behind the clock by (us): $behind")
            // Keeps showing: no second-long hole between frames shown.
            val gaps = sink.presented.zipWithNext { a, b -> (b.second - a.second) / 1_000_000 }
            assertTrue(gaps.all { it < 1_000 }, "gaps between frames shown (ms): $gaps")
        }
        sink.assertFinished()
    }

    /** With no keyframe after the first, nothing is skipped: every frame is decoded and prepared, late. */
    @Test
    fun aSourceWithNoLaterKeyframeIsNotSkipped() {
        val sink = SlowSink(prepareMillis = 3 * FRAME_MS / 2)
        player(sink, ONE_GOP_FIXTURE).use { bridge ->
            assertEquals(0, bridge.seek(START_US))
            sink.awaitPresented { it.presentationTimeUs == START_US }
            sink.clear()
            assertEquals(0, bridge.play())
            awaitEnded(bridge)
            val prepared = sink.prepared.map { it.presentationTimeUs }
            assertEquals((START_US + FRAME_US..LAST_FRAME_US step FRAME_US).toList(), prepared)
            assertTrue(sink.behindTheClock().max() > CATCH_UP_US, "the decoder never fell behind")
            assertEquals(prepared.size.toLong(), sink.presented.size + bridge.snapshot().droppedFrames)
        }
        sink.assertFinished()
    }

    /** A seek while the player catches up wins: the frames after it are the target's, in order. */
    @Test
    fun aSeekDuringCatchUpWins() {
        val sink = SlowSink(prepareMillis = 2 * FRAME_MS)
        player(sink, GOP_FIXTURE).use { bridge ->
            assertEquals(0, bridge.play())
            // A skip shows as a jump in what is decoded.
            awaitCondition("a skip") {
                sink.prepared.zipWithNext().any { (a, b) -> b.presentationTimeUs - a.presentationTimeUs > FRAME_US }
            }
            val before = sink.presented.size
            assertEquals(0, bridge.seek(SEEK_US))
            val serial = bridge.snapshot().queueSerial
            sink.awaitPresented { it.queueSerial == serial && it.presentationTimeUs > SEEK_US }
            val after = sink.presented.drop(before).map { it.first }
            val firstNew = after.indexOfFirst { it.queueSerial == serial }
            val shown = after.drop(firstNew)
            assertTrue(shown.all { it.queueSerial == serial }, "a frame from before the seek after it: $after")
            assertEquals(SEEK_US, shown.first().presentationTimeUs, "the seek's frame")
            assertEquals(shown.map { it.presentationTimeUs }.sorted(), shown.map { it.presentationTimeUs })
            assertEquals(0, bridge.pause())
        }
        sink.assertFinished()
    }

    private fun player(sink: SlowSink, fixture: String): RefreshedPlayerBridge {
        val bridge = createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
            update = {},
            frame = sink,
        )
        val bytes = checkNotNull(javaClass.getResourceAsStream("/$fixture")).use { it.readBytes() }
        assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
        assertEquals(0, bridge.prepare(NativePlayerSource(fixture, listOf(NativeMountedIo(fixture, NativeSourceResource(Buffer().write(bytes)))))))
        return RefreshedPlayerBridge(bridge)
    }

    private fun awaitEnded(bridge: NativePlayerBridge) =
        awaitCondition("the end") { bridge.snapshot().state == NativePlayerState.ENDED }

    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + LIVENESS_NS
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "no $what within ${LIVENESS_NS / 1_000_000_000} s")
            Thread.sleep(2)
        }
    }
}

/**
 * Takes every frame, sleeping [prepareMillis] in each prepare so that decoding is slower than real
 * time, and records what it prepared and presented, with System.nanoTime() of each present.
 */
private class SlowSink(private val prepareMillis: Long) : NativeVideoFrameSink {
    private val lock = Any()
    private val outstanding = HashMap<Long, NativeVideoFrame>()
    val prepared = CopyOnWriteArrayList<NativeVideoFrame>()
    val presented = CopyOnWriteArrayList<Pair<NativeVideoFrame, Long>>()

    override fun prepare(frame: NativeVideoFrame): Boolean {
        Thread.sleep(prepareMillis)
        frame.frame.close()
        prepared += frame
        synchronized(lock) { outstanding[frame.token] = frame }
        return true
    }

    override fun present(token: Long) {
        val at = System.nanoTime()
        synchronized(lock) { outstanding.remove(token) }?.let { presented += it to at }
    }

    override fun release(token: Long) {
        synchronized(lock) { outstanding.remove(token) }
    }

    fun clear() {
        prepared.clear()
        presented.clear()
    }

    /**
     * How far behind the external clock each frame was shown: that clock runs on from the first
     * frame shown, as it does unless it is more than 10 s from the video.
     */
    fun behindTheClock(): List<Long> {
        val (first, startedAt) = presented.first()
        return presented.map { (frame, at) -> first.presentationTimeUs + (at - startedAt) / 1_000 - frame.presentationTimeUs }
    }

    fun awaitPresented(condition: (NativeVideoFrame) -> Boolean) {
        val deadline = System.nanoTime() + LIVENESS_NS
        while (presented.none { condition(it.first) }) {
            assertTrue(System.nanoTime() < deadline, "no such frame shown")
            Thread.sleep(2)
        }
    }

    fun assertFinished() {
        assertEquals(emptyList(), synchronized(lock) { outstanding.values.map { it.presentationTimeUs } })
    }
}

/** 6 s of 25 fps, a keyframe every 12 frames (0.48 s). */
private const val GOP_FIXTURE = "playback-gop-6s.mp4"

/** 6 s of 25 fps, its only keyframe the first frame. */
private const val ONE_GOP_FIXTURE = "playback-one-gop-6s.mp4"

private const val FRAME_MS = 40L
private const val FRAME_US = 40_000L
private const val GOP_US = 480_000L
private const val LAST_FRAME_US = 5_960_000L

/** The frames after the first, which prepare shows. */
private const val FRAMES_AFTER_FIRST = 149L

/** The player's catch-up point. */
private const val CATCH_UP_US = 500_000L

private const val START_US = 3_000_000L
private const val SEEK_US = 4_000_000L

/** Liveness, not speed: playing at half real time takes about 6 s with the skips, 12 s without. */
private const val LIVENESS_NS = 20_000_000_000L
