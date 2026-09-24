// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerHdrType
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerOutputCapabilities
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerVideoInfo
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FFplayHdrPolicyTest {
    @Test
    fun preserveKeepsTheHdrCapableHardwarePath() = runTest {
        val (player, negotiated) = hdrPlayer(FFplayConfiguration())
        player.attachOutput(HdrSurface())

        assertTrue(negotiated.last().hardwareFrameImport)
        assertEquals(FFplayHdrResult.PRESERVED, player.snapshot.value.output?.hdrResult)
        player.close()
    }

    @Test
    fun forceSdrKeepsHdrOffTheDirectPathAndToneMapsInstead() = runTest {
        val (player, negotiated) = hdrPlayer(FFplayConfiguration(hdrPolicy = FFplayHdrPolicy.FORCE_SDR))
        player.attachOutput(HdrSurface())

        assertFalse(negotiated.last().hardwareFrameImport)
        assertFalse(negotiated.last().zeroCopy)
        assertTrue(negotiated.last().toneMapHdrToSdr)
        assertEquals(FFplayHdrResult.TONE_MAPPED, player.snapshot.value.output?.hdrResult)
        assertEquals("sRGB", player.snapshot.value.output?.outputColorSpace)
        player.close()
    }

    @Test
    fun forceSdrWithRequiredHardwareFailsInsteadOfShowingHdr() = runTest {
        val (player, _) = hdrPlayer(
            FFplayConfiguration(
                hdrPolicy = FFplayHdrPolicy.FORCE_SDR,
                decoderPreference = FFplayDecoderPreference.REQUIRE_HARDWARE,
            ),
        )
        player.attachOutput(HdrSurface())

        assertEquals(FFplayState.FAILED, player.snapshot.value.state)
        assertTrue(player.snapshot.value.failure?.message.orEmpty().contains("FORCE_SDR"))
        player.close()
    }
}

/** A prepared player whose source is HDR10, recording every negotiated output. */
private suspend fun hdrPlayer(
    configuration: FFplayConfiguration,
): Pair<FFplayPlayer, List<NativePlayerOutputCapabilities>> {
    val negotiated = mutableListOf<NativePlayerOutputCapabilities>()
    val player = FFplayPlayer(
        configuration = configuration,
        engineFactory = { engineConfiguration, update, emit ->
            createFFplayEngineWithBridge(engineConfiguration, update, emit) { native, nativeUpdate, _, _ ->
                val bridge = createInMemoryPlayerBridge(native) { snapshot ->
                    nativeUpdate(snapshot.copy(videoInfo = HDR10))
                }
                object : NativePlayerBridge by bridge {
                    override fun setPlatformOutputTarget(target: Any?, secure: Boolean): Int = 0

                    override fun setOutput(capabilities: NativePlayerOutputCapabilities): Int {
                        negotiated += capabilities
                        return bridge.setOutput(capabilities)
                    }
                }
            }
        },
        audioOpener = { null },
    )
    player.prepare(FFplaySource("hdr10.mp4"))
    return player to negotiated
}

private val HDR10 = NativePlayerVideoInfo(
    width = 3840,
    height = 2160,
    colorPrimaries = 9,
    colorTransfer = 16,
    colorSpace = 9,
    hdrType = NativePlayerHdrType.HDR10,
)

/** An Android/iOS-style direct surface on an HDR display. */
private class HdrSurface : FFplayVideoOutput {
    override val kind = FFplayRendererKind.NATIVE_SURFACE
    override val platformTarget: Any = Any()
    override val capabilities = FFplayOutputCapabilities(
        hardwareFrameImport = true,
        softwareFrameUpload = true,
        zeroCopy = true,
        hdrTransfers = setOf("PQ", "HLG"),
        colorSpaces = setOf("sRGB", "BT.2020"),
        toneMapHdrToSdr = true,
    )
    override fun submit(frame: FFplayFrame): Boolean = true
    override fun discard() = Unit
}
