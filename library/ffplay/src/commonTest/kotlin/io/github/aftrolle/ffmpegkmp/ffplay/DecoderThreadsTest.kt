// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerConfiguration
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import io.github.aftrolle.ffmpegkmp.codec.DecoderThreads
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class DecoderThreadsTest {
    @Test
    fun thePlayerHandsItsThreadsToTheNativeBridge() = runTest {
        // 0 is the bridge's automatic count, which it caps.
        assertEquals(0, nativeConfiguration(FFplayConfiguration()).decoderThreads)
        assertEquals(1, nativeConfiguration(FFplayConfiguration(threads = DecoderThreads.Fixed(1))).decoderThreads)
        assertEquals(6, nativeConfiguration(FFplayConfiguration(threads = DecoderThreads.Fixed(6))).decoderThreads)
    }
}

private suspend fun nativeConfiguration(configuration: FFplayConfiguration): NativePlayerConfiguration {
    val created = mutableListOf<NativePlayerConfiguration>()
    val player = FFplayPlayer(
        configuration = configuration,
        engineFactory = { engineConfiguration, update, emit ->
            createFFplayEngineWithBridge(engineConfiguration, update, emit) { native, nativeUpdate, _, _ ->
                created += native
                createInMemoryPlayerBridge(native, nativeUpdate)
            }
        },
        audioOpener = { null },
    )
    player.prepare(FFplaySource("movie.mp4"))
    player.close()
    return created.single()
}
