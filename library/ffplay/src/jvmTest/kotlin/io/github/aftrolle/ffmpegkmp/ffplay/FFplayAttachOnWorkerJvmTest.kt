// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerOutputCapabilities
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class FFplayAttachOnWorkerJvmTest {
    @Test
    fun theNativeAttachRunsOffTheCallingThread() = runBlocking {
        val attachThreads = CopyOnWriteArrayList<Thread>()
        val player = recordingPlayer(attachThreads)
        player.prepare(MediaSource("movie.mp4"))

        player.attachOutputOnWorker(WorkerOutput())

        assertNotEquals(Thread.currentThread(), attachThreads.single())
        assertEquals(FFplayState.READY, player.snapshot.value.state)
        player.close()
    }

    @Test
    fun aCancelledAttachFinishesBeforeItsCallerGoesOn() = runBlocking {
        val attachThreads = CopyOnWriteArrayList<Thread>()
        val attaching = CountDownLatch(1)
        val release = CountDownLatch(1)
        val player = recordingPlayer(attachThreads) {
            attaching.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        player.prepare(MediaSource("movie.mp4"))
        val attach = launch(Dispatchers.Default) { player.attachOutputOnWorker(WorkerOutput()) }
        assertTrue(attaching.await(5, TimeUnit.SECONDS))

        // A surface detaches its output once the attach returns: never before the native attach is done.
        attach.cancel()
        delay(100)
        assertFalse(attach.isCompleted)
        release.countDown()
        attach.join()

        assertEquals(1, attachThreads.size)
        assertEquals(FFplayState.READY, player.snapshot.value.state)
        player.close()
    }

    @Test
    fun aCommandDuringAnAttachIsQueuedRatherThanWaitingForIt() = runBlocking {
        val attaching = CountDownLatch(1)
        val release = CountDownLatch(1)
        val player = recordingPlayer(CopyOnWriteArrayList()) {
            attaching.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        player.prepare(MediaSource("movie.mp4"))
        val attach = launch(Dispatchers.Default) { player.attachOutputOnWorker(WorkerOutput()) }
        assertTrue(attaching.await(5, TimeUnit.SECONDS))

        val waited = measureTime { player.play() }
        assertTrue(waited < 1.seconds, "play waited $waited for the attach")
        assertNotEquals(FFplayState.PLAYING, player.snapshot.value.state)
        release.countDown()
        attach.join()

        assertEquals(FFplayState.PLAYING, player.snapshot.value.state)
        player.close()
    }
}

private fun recordingPlayer(attachThreads: MutableList<Thread>, duringAttach: () -> Unit = {}): FFplayPlayer = FFplayPlayer(
    configuration = FFplayConfiguration(),
    engineFactory = { configuration, update, emit ->
        createFFplayEngineWithBridge(configuration, update, emit) { native, nativeUpdate, _, _ ->
            val delegate = createInMemoryPlayerBridge(native, nativeUpdate)
            object : NativePlayerBridge by delegate {
                override fun setOutput(capabilities: NativePlayerOutputCapabilities): Int {
                    attachThreads += Thread.currentThread()
                    duringAttach()
                    return delegate.setOutput(capabilities)
                }
            }
        }
    },
    audioOpener = { null },
)

private class WorkerOutput : FFplayVideoOutput {
    override val kind = FFplayRendererKind.COMPOSE_CANVAS
    override val capabilities = FFplayOutputCapabilities()
    override fun submitNative(frame: VideoFrame, video: VideoInfo?): Boolean {
        frame.close()
        return true
    }
    override fun discard() = Unit
}
