// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerConfiguration
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderPreference
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.DecoderThreadType
import io.github.aftrolle.ffmpegkmp.codec.DecoderThreads
import io.github.aftrolle.ffmpegkmp.codec.NamedCodec
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class DecoderThreadsTest {
    @Test
    fun autoLeavesTheThreadsToFFmpegAndThePlayerDefaultsToTwoFrameThreads() = runTest {
        // 0 and 0: FFmpeg's automatic count and its default thread types.
        val auto = nativeConfiguration(FFplayConfiguration(threads = DecoderThreads.Auto))
        assertEquals(0, auto.decoderThreads)
        assertEquals(0, auto.decoderThreadType)
        val default = nativeConfiguration(FFplayConfiguration())
        assertEquals(2, default.decoderThreads)
        assertEquals(FRAME, default.decoderThreadType)
        assertEquals(500_000, default.catchUpLagUs)
        assertEquals(-1, nativeConfiguration(FFplayConfiguration(catchUpLag = kotlin.time.Duration.INFINITE)).catchUpLagUs)
    }

    @Test
    fun fixedThreadsPassThroughWithTheirTypes() = runTest {
        val both = nativeConfiguration(FFplayConfiguration(threads = DecoderThreads.Fixed(6)))
        assertEquals(6, both.decoderThreads)
        assertEquals(FRAME or SLICE, both.decoderThreadType)
        val slice = nativeConfiguration(
            FFplayConfiguration(threads = DecoderThreads.Fixed(4, setOf(DecoderThreadType.SLICE))),
        )
        assertEquals(4, slice.decoderThreads)
        assertEquals(SLICE, slice.decoderThreadType)
        val frame = nativeConfiguration(
            FFplayConfiguration(threads = DecoderThreads.Fixed(1, setOf(DecoderThreadType.FRAME))),
        )
        assertEquals(1, frame.decoderThreads)
        assertEquals(FRAME, frame.decoderThreadType)
    }

    @Test
    fun aNamedDecoderReachesTheBridgeWithItsOptions() = runTest {
        val named = nativeConfiguration(
            FFplayConfiguration(decoder = NamedCodec("h264", mapOf("skip_loop_filter" to "all"))),
        )
        assertEquals("h264", named.decoderName)
        assertEquals(mapOf("skip_loop_filter" to "all"), named.decoderOptions)
        assertEquals(NativePlayerDecoderPreference.AUTO, named.decoderPreference)

        val chosen = nativeConfiguration(FFplayConfiguration(decoder = CodecPreference.SOFTWARE))
        assertNull(chosen.decoderName)
        assertEquals(NativePlayerDecoderPreference.SOFTWARE, chosen.decoderPreference)
    }

    private companion object {
        const val FRAME = 1
        const val SLICE = 2
    }
}

internal suspend fun nativeConfiguration(configuration: FFplayConfiguration): NativePlayerConfiguration {
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
    player.prepare(MediaSource("movie.mp4"))
    player.close()
    return created.single()
}
