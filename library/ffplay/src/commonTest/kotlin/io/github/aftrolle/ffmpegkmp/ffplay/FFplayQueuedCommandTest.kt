// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerError
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSource
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

class FFplayQueuedCommandTest {
    @Test
    fun commandsDuringPrepareApplyInOrderOnceItReturns() = runTest {
        val calls = mutableListOf<String>()
        val bridge = RecordingPrepareBridge(calls)
        val player = bridge.player()
        bridge.duringPrepare = {
            player.attachOutput(QueuedOutput())
            player.seekTo(3.seconds)
            player.play()
            // Nothing may reach the engine while the native prepare is still running.
            assertEquals(listOf("prepare"), calls)
        }

        player.prepare(FFplaySource("movie.mp4"))

        assertEquals(listOf("prepare", "setOutput", "seek", "play"), calls)
        assertEquals(FFplayState.PLAYING, player.snapshot.value.state)
        player.close()
    }

    @Test
    fun aFailedPrepareDropsPlaybackCommandsButKeepsOutputChanges() = runTest {
        val calls = mutableListOf<String>()
        val bridge = RecordingPrepareBridge(calls, prepareResult = NativePlayerError.IO)
        val player = bridge.player()
        bridge.duringPrepare = {
            player.play()
            player.attachOutput(QueuedOutput())
        }

        assertFailsWith<IllegalStateException> { player.prepare(FFplaySource("missing.mp4")) }

        assertEquals(listOf("prepare", "setOutput"), calls)
        player.close()
    }

    @Test
    fun commandsQueuedBeforeCloseAreDropped() = runTest {
        val calls = mutableListOf<String>()
        val bridge = RecordingPrepareBridge(calls)
        val player = bridge.player()
        bridge.duringPrepare = {
            player.play()
            player.requestClose()
        }

        runCatching { player.prepare(FFplaySource("movie.mp4")) }
        player.close()

        assertEquals(listOf("prepare"), calls)
    }
}

/** Runs [duringPrepare] from inside the native prepare call, as a UI thread would race it. */
private class RecordingPrepareBridge(
    private val calls: MutableList<String>,
    private val prepareResult: Int = 0,
) {
    var duringPrepare: () -> Unit = {}

    fun player(): FFplayPlayer = FFplayPlayer(
        configuration = FFplayConfiguration(),
        engineFactory = { configuration, update, emit ->
            createFFplayEngineWithBridge(configuration, update, emit) { native, nativeUpdate, _, _ ->
                val delegate = createInMemoryPlayerBridge(native, nativeUpdate)
                object : NativePlayerBridge by delegate {
                    override fun prepare(source: NativePlayerSource): Int {
                        calls += "prepare"
                        duringPrepare()
                        return if (prepareResult < 0) prepareResult else delegate.prepare(source)
                    }

                    override fun setOutput(
                        capabilities: io.github.aftrolle.ffmpegkmp.bindings.NativePlayerOutputCapabilities,
                    ): Int = delegate.setOutput(capabilities).also { calls += "setOutput" }

                    override fun seek(positionUs: Long): Int = delegate.seek(positionUs).also { calls += "seek" }

                    override fun play(): Int = delegate.play().also { calls += "play" }
                }
            }
        },
        audioOpener = { null },
    )
}

private class QueuedOutput : FFplayVideoOutput {
    override val kind = FFplayRendererKind.COMPOSE_CANVAS
    override val capabilities = FFplayOutputCapabilities()
    override fun submit(frame: FFplayFrame): Boolean = true
    override fun discard() = Unit
}
