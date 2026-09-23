// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okio.Buffer

@OptIn(InternalFFmpegKmpApi::class)
class FFplayProductionJvmTest {
    @Test
    fun publicPlayerDecodesARealMountedVideoThroughTheProductionEngine() = runBlocking {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/playback-color-patches-1s.mp4")) {
            "Missing shared playback fixture"
        }.use { it.readBytes() }
        val output = ProductionOutput()
        val player = FFplayPlayer(FFplayConfiguration(decoderPreference = FFplayDecoderPreference.SOFTWARE))
        player.attachOutput(output)

        try {
            player.prepare(
                FFplaySource(
                    input = "playback-color-patches-1s.mp4",
                    io = CommandIo {
                        input("playback-color-patches-1s.mp4", Buffer().write(bytes))
                    },
                ),
            )

            assertEquals(FFplayState.READY, player.snapshot.value.state)
            assertEquals(FFplayDecoderKind.SOFTWARE, player.snapshot.value.output?.decoder)
            assertNotNull(player.snapshot.value.video)
            assertTrue(output.framesReceived > 0)
        } finally {
            player.close()
        }
    }

    @Test
    fun closeCancelsAndWaitsForAnActivePrepareBeforeDestroyingTheEngine() = runBlocking {
        val enteredPrepare = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val prepareExited = CountDownLatch(1)
        val engine = BlockingPrepareEngine(enteredPrepare, cancelled, prepareExited)
        val player = FFplayPlayer(engineFactory = { _, _, _ -> engine })

        val preparation = async(Dispatchers.Default) {
            assertFailsWith<IllegalStateException> {
                player.prepare(FFplaySource("blocking.mp4"))
            }
        }
        assertTrue(enteredPrepare.await(2, TimeUnit.SECONDS), "prepare did not enter the engine")

        val closing = async(Dispatchers.Default) { player.close() }
        closing.await()
        preparation.await()

        assertTrue(cancelled.count == 0L)
        assertTrue(prepareExited.count == 0L)
        assertEquals(FFplayState.CLOSED, player.snapshot.value.state)
        assertEquals(1, engine.closeCount)
    }

    @Test
    fun closeRacingCancellationResetPreventsPrepareFromStarting() = runBlocking {
        val resetEntered = CountDownLatch(1)
        val releaseReset = CountDownLatch(1)
        val engine = BlockingResetEngine(resetEntered, releaseReset)
        val player = FFplayPlayer(engineFactory = { _, _, _ -> engine })

        val preparation = async(Dispatchers.Default) {
            assertFailsWith<IllegalStateException> {
                player.prepare(FFplaySource("never-opened.mp4"))
            }
        }
        assertTrue(resetEntered.await(2, TimeUnit.SECONDS), "cancellation reset did not start")

        val closing = async(Dispatchers.Default) { player.close() }
        assertTrue(engine.cancelled.await(2, TimeUnit.SECONDS), "close did not signal cancellation")
        releaseReset.countDown()
        closing.await()
        preparation.await()

        assertEquals(0, engine.prepareCount)
        assertEquals(FFplayState.CLOSED, player.snapshot.value.state)
    }
}

@OptIn(InternalFFmpegKmpApi::class)
private class ProductionOutput : FFplayVideoOutput {
    override val kind = FFplayRendererKind.COMPOSE_CANVAS
    override val frames = kotlinx.coroutines.flow.MutableStateFlow<FFplayFrame?>(null)
    override val capabilities = FFplayOutputCapabilities()
    var framesReceived = 0
        private set

    override fun submit(frame: FFplayFrame): Boolean = true

    override fun submitNative(frame: NativeVideoFrame, video: FFplayVideoInfo?): Boolean {
        framesReceived++
        return true
    }

    override fun discard() {
        frames.value = null
    }
}

private class BlockingPrepareEngine(
    private val enteredPrepare: CountDownLatch,
    private val cancelled: CountDownLatch,
    private val prepareExited: CountDownLatch,
) : FFplayEngine {
    @Volatile
    private var cancellationRequested = false
    var closeCount = 0
        private set

    override fun prepare(source: FFplaySource) {
        enteredPrepare.countDown()
        while (!cancellationRequested) Thread.onSpinWait()
        prepareExited.countDown()
    }

    override fun cancel() {
        cancellationRequested = true
        cancelled.countDown()
    }

    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(position: kotlin.time.Duration) = Unit
    override fun stop() = Unit
    override fun attachOutput(output: FFplayVideoOutput) = Unit
    override fun detachOutput(output: FFplayVideoOutput) = Unit
    override fun close() {
        check(prepareExited.count == 0L) { "Engine closed before prepare exited" }
        closeCount++
    }
}

private class BlockingResetEngine(
    private val resetEntered: CountDownLatch,
    private val releaseReset: CountDownLatch,
) : FFplayEngine {
    val cancelled = CountDownLatch(1)
    var prepareCount = 0
        private set

    override fun resetCancellation() {
        resetEntered.countDown()
        check(releaseReset.await(2, TimeUnit.SECONDS)) { "Timed out waiting to release reset" }
    }

    override fun prepare(source: FFplaySource) {
        prepareCount++
    }

    override fun cancel() {
        cancelled.countDown()
    }

    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(position: kotlin.time.Duration) = Unit
    override fun stop() = Unit
    override fun attachOutput(output: FFplayVideoOutput) = Unit
    override fun detachOutput(output: FFplayVideoOutput) = Unit
    override fun close() = Unit
}
