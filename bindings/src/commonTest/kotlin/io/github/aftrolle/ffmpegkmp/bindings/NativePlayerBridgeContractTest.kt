// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class NativePlayerBridgeContractTest {
    @Test
    fun lifecyclePreservesPlayIntentAcrossOutputLoss() {
        val updates = mutableListOf<NativePlayerSnapshot>()
        val bridge = createInMemoryPlayerBridge(NativePlayerConfiguration(), updates::add)

        assertEquals(0, bridge.prepare(NativePlayerSource("movie.mp4")))
        assertEquals(NativePlayerState.WAITING_FOR_OUTPUT, bridge.snapshot().state)
        assertEquals(0, bridge.play())
        assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
        assertEquals(NativePlayerState.PLAYING, bridge.snapshot().state)

        bridge.clearOutput()
        assertEquals(NativePlayerState.WAITING_FOR_OUTPUT, bridge.snapshot().state)
        assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
        assertEquals(NativePlayerState.PLAYING, bridge.snapshot().state)
        assertEquals(0, bridge.stop())
        bridge.close()
    }

    @Test
    fun seekInvalidatesQueuedFramesAndPlayersRemainIndependent() {
        val first = createInMemoryPlayerBridge(NativePlayerConfiguration()) {}
        val second = createInMemoryPlayerBridge(NativePlayerConfiguration()) {}
        first.prepare(NativePlayerSource("first.mp4"))
        second.prepare(NativePlayerSource("second.mp4"))
        val firstSerial = first.snapshot().queueSerial

        first.seek(4_000_000)

        assertNotEquals(firstSerial, first.snapshot().queueSerial)
        assertEquals(4_000_000, first.snapshot().positionUs)
        assertEquals(0, second.snapshot().positionUs)
        first.close()
        second.close()
    }

    @Test
    fun protectedContentCannotNegotiateAnInsecureOrCanvasLikeOutput() {
        val bridge = createInMemoryPlayerBridge(NativePlayerConfiguration()) {}
        bridge.prepare(NativePlayerSource("protected.mpd", requireSecurePath = true))

        assertEquals(-13, bridge.setOutput(NativePlayerOutputCapabilities()))
        assertEquals(NativePlayerState.FAILED, bridge.snapshot().state)
        assertEquals(-13, bridge.snapshot().errorCode)

        assertEquals(
            0,
            bridge.setOutput(
                NativePlayerOutputCapabilities(
                    hardwareFrameImport = true,
                    softwareFrameUpload = false,
                    zeroCopy = true,
                    protectedContent = true,
                ),
            ),
        )
        assertEquals(NativePlayerState.READY, bridge.snapshot().state)
        bridge.close()
    }

    @Test
    fun requireHardwareFailsInsteadOfMisreportingSoftwareFallback() {
        val bridge = createInMemoryPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.REQUIRE_HARDWARE),
        ) {}
        bridge.prepare(NativePlayerSource("movie.mp4"))

        assertEquals(-95, bridge.setOutput(NativePlayerOutputCapabilities()))
        assertEquals(NativePlayerState.FAILED, bridge.snapshot().state)
        bridge.close()
    }

    @Test
    fun failedPrepareDropsSourceAndCannotBeRevivedByANewOutput() {
        val bridge = createInMemoryPlayerBridge(NativePlayerConfiguration()) {}
        bridge.setOutput(NativePlayerOutputCapabilities())

        assertEquals(
            -13,
            bridge.prepare(NativePlayerSource("protected.mpd", requireSecurePath = true)),
        )
        assertEquals(NativePlayerState.FAILED, bridge.snapshot().state)

        assertEquals(
            0,
            bridge.setOutput(
                NativePlayerOutputCapabilities(
                    hardwareFrameImport = true,
                    softwareFrameUpload = false,
                    zeroCopy = true,
                    protectedContent = true,
                ),
            ),
        )
        assertFailsWith<IllegalStateException> { bridge.play() }
        bridge.close()
    }

    @Test
    fun repeatedPrepareSeekStopCyclesInvalidateEverySourceGeneration() {
        val bridge = createInMemoryPlayerBridge(NativePlayerConfiguration()) {}
        bridge.setOutput(NativePlayerOutputCapabilities())
        var lastSerial = bridge.snapshot().queueSerial

        repeat(25) { index ->
            assertEquals(0, bridge.prepare(NativePlayerSource("movie-$index.mp4")))
            assertNotEquals(lastSerial, bridge.snapshot().queueSerial)
            lastSerial = bridge.snapshot().queueSerial
            assertEquals(0, bridge.seek((index + 1L) * 1_000_000L))
            assertNotEquals(lastSerial, bridge.snapshot().queueSerial)
            lastSerial = bridge.snapshot().queueSerial
            assertEquals(0, bridge.stop())
            assertNotEquals(lastSerial, bridge.snapshot().queueSerial)
            lastSerial = bridge.snapshot().queueSerial
            assertEquals(0, bridge.snapshot().positionUs)
            assertFailsWith<IllegalStateException> { bridge.play() }
        }

        bridge.close()
    }
}
