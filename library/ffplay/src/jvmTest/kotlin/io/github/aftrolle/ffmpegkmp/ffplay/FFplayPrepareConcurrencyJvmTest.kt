// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSource
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

@OptIn(InternalFFmpegKmpApi::class)
class FFplayPrepareConcurrencyJvmTest {
    @Test
    fun controlsReturnImmediatelyWhileANativePrepareBlocks() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val player = FFplayPlayer(
            configuration = FFplayConfiguration(),
            engineFactory = { configuration, update, emit ->
                createFFplayEngineWithBridge(configuration, update, emit) { native, nativeUpdate, _, _ ->
                    val delegate = createInMemoryPlayerBridge(native, nativeUpdate)
                    object : NativePlayerBridge by delegate {
                        override fun prepare(source: NativePlayerSource): Int {
                            entered.countDown()
                            release.await(10, TimeUnit.SECONDS)
                            return delegate.prepare(source)
                        }
                    }
                }
            },
            audioOpener = { null },
        )
        val preparing = async(Dispatchers.Default) { player.prepare(FFplaySource("slow-network.mp4")) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val elapsed = measureTime {
            player.attachOutput(CanvasOutput())
            player.seekTo(2.seconds)
            player.play()
            player.pause()
            player.play()
        }

        assertTrue(elapsed < 1.seconds, "Controls blocked for $elapsed during a native prepare")
        release.countDown()
        preparing.await()
        assertEquals(FFplayState.PLAYING, player.snapshot.value.state)
        player.close()
    }

    private class CanvasOutput : FFplayVideoOutput {
        override val kind = FFplayRendererKind.COMPOSE_CANVAS
        override val capabilities = FFplayOutputCapabilities()
        override fun submit(frame: FFplayFrame): Boolean = true
        override fun discard() = Unit
    }
}
