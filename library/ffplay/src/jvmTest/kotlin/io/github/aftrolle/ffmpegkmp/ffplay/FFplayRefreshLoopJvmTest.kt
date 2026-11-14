// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NATIVE_PLAYER_REFRESH_IDLE
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSnapshot
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The engine's refresh loop: who it runs for, how it waits, and what a failing refresh does. */
class FFplayRefreshLoopJvmTest {
    @Test
    fun aBridgeThatPacesItsOwnFramesIsNeverRefreshed() {
        val refreshes = AtomicInteger()
        val engine = engine(refreshes = false) {
            refreshes.incrementAndGet()
            NATIVE_PLAYER_REFRESH_IDLE
        }
        engine.prepare(MediaSource("movie.mp4"), FFplayContentProtection.CLEAR_OR_AUTO_DETECT)
        engine.attachOutput(CanvasOutput())
        engine.play()
        Thread.sleep(200)
        assertEquals(0, refreshes.get())
        engine.close()
    }

    /** After IDLE the loop waits for the player's next snapshot instead of asking again on a timer. */
    @Test
    fun anIdleRefreshWaitsForTheNextSnapshot() {
        val refreshes = AtomicInteger()
        lateinit var publish: (NativePlayerSnapshot) -> Unit
        lateinit var last: NativePlayerSnapshot
        val engine = engine(refreshes = true, onUpdate = { last = it }, publisher = { publish = it }) {
            refreshes.incrementAndGet()
            NATIVE_PLAYER_REFRESH_IDLE
        }
        engine.prepare(MediaSource("movie.mp4"), FFplayContentProtection.CLEAR_OR_AUTO_DETECT)
        engine.attachOutput(CanvasOutput())
        engine.play()
        awaitCondition { refreshes.get() >= 1 }
        Thread.sleep(200)
        val idle = refreshes.get()
        // A 10 ms poll would have refreshed about 20 times by now.
        assertTrue(idle <= 3, "refreshed $idle times while idle")
        publish(last)
        awaitCondition { refreshes.get() > idle }
        engine.close()
    }

    @Test
    fun aRefreshThatThrowsFailsThePlayer() {
        val snapshots = CopyOnWriteArrayList<FFplaySnapshot>()
        val events = CopyOnWriteArrayList<FFplayEvent>()
        val engine = engine(refreshes = true, update = snapshots::add, emit = events::add) {
            throw IllegalStateException("refresh broke")
        }
        engine.prepare(MediaSource("movie.mp4"), FFplayContentProtection.CLEAR_OR_AUTO_DETECT)
        engine.attachOutput(CanvasOutput())
        engine.play()
        awaitCondition { snapshots.lastOrNull()?.state == FFplayState.FAILED }
        val failure = snapshots.last().failure
        assertTrue(failure?.cause is IllegalStateException, "failure: $failure")
        assertEquals(1, events.filterIsInstance<FFplayEvent.Fatal>().size, "events: $events")
        engine.close()
    }

    /** The in-memory bridge, refreshed by [refresh] when [refreshes]; [publisher] gets its snapshot callback. */
    private fun engine(
        refreshes: Boolean,
        update: (FFplaySnapshot) -> Unit = {},
        emit: (FFplayEvent) -> Unit = {},
        onUpdate: (NativePlayerSnapshot) -> Unit = {},
        publisher: ((NativePlayerSnapshot) -> Unit) -> Unit = {},
        refresh: () -> Long,
    ): FFplayEngine = createFFplayEngineWithBridge(
        FFplayConfiguration(audio = false),
        update,
        emit,
    ) { configuration, nativeUpdate, _, _ ->
        publisher(nativeUpdate)
        val delegate = createInMemoryPlayerBridge(configuration) { native ->
            onUpdate(native)
            nativeUpdate(native)
        }
        object : NativePlayerBridge by delegate {
            override val refreshes: Boolean get() = refreshes
            override fun refresh(nowUs: Long, leadUs: Long): Long = refresh()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "timed out")
            Thread.sleep(2)
        }
    }

    private class CanvasOutput : FFplayVideoOutput {
        override val kind = FFplayRendererKind.COMPOSE_CANVAS
        override val capabilities = FFplayOutputCapabilities()

        override fun submitNative(frame: VideoFrame, video: VideoInfo?): Boolean {
            frame.close()
            return true
        }

        override fun discard() = Unit
    }
}
