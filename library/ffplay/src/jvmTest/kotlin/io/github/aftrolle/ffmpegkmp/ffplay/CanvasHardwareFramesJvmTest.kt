// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativeGpuBuffer
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerOutputCapabilities
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerVideoInfo
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest

/** How the Compose Canvas output negotiates hardware frames with the player, and what it does with them. */
class CanvasHardwareFramesJvmTest {
    @Test
    fun framesComeInMemoryUntilTheStreamIsKnown() = runTest {
        val hardware = FakeHardwareFrames()
        val session = canvasSession(FFplayConfiguration(), SDR, hardware, prepare = false)

        session.player.attachOutput(session.output)

        val first = session.negotiated.single()
        assertFalse(first.prepareHardwareFrames)
        assertFalse(first.hardwareFrameImport)
        assertNull(session.targets.last())
        session.close()
    }

    @Test
    fun anSdrStreamTakesHardwareFramesIntoTheOutputsSurface() = runTest {
        val hardware = FakeHardwareFrames()
        val session = canvasSession(FFplayConfiguration(), SDR, hardware)

        assertTrue(session.output.negotiatesFor(session.player.snapshot.value.video))
        session.player.attachOutput(session.output)

        val last = session.negotiated.last()
        assertTrue(last.prepareHardwareFrames)
        assertTrue(last.hardwareFrameImport)
        assertTrue(last.zeroCopy)
        assertSame(hardware.surface, session.targets.last())
        // The same stream again changes nothing, so the player is not asked again.
        assertFalse(session.output.negotiatesFor(session.player.snapshot.value.video))
        session.close()
    }

    @Test
    fun anHdrStreamTheHardwareDoesNotTakeStaysInMemory() = runTest {
        val hardware = FakeHardwareFrames(takesHdr = false)
        val session = canvasSession(FFplayConfiguration(), HDR10, hardware)

        assertFalse(session.output.negotiatesFor(session.player.snapshot.value.video))
        session.player.attachOutput(session.output)

        assertFalse(session.negotiated.last().prepareHardwareFrames)
        assertNull(session.targets.last())
        session.close()
    }

    @Test
    fun requiredHardwareIsOfferedBeforeTheStreamIsKnown() = runTest {
        val hardware = FakeHardwareFrames()
        val configuration = FFplayConfiguration(decoder = CodecPreference.REQUIRE_HARDWARE)
        val session = canvasSession(configuration, SDR, hardware, prepare = false)

        session.player.attachOutput(session.output)

        assertTrue(session.negotiated.single().prepareHardwareFrames)
        assertSame(hardware.surface, session.targets.last())
        session.close()
    }

    @Test
    fun aPreparedHardwareFrameIsDrawnFromItsBufferWhichReleaseHandsBack() = runTest {
        val hardware = FakeHardwareFrames()
        val session = canvasSession(FFplayConfiguration(), SDR, hardware)
        session.output.negotiatesFor(session.player.snapshot.value.video)

        val frames = (0 until 3).map { index -> assertNotNull(session.output.prepareNative(decoded(index), null)) }

        assertEquals(3, hardware.taken)
        assertEquals(0, hardware.released)
        frames.forEach(FFplayPreparedFrame::release)
        assertEquals(3, hardware.released, "Each buffer goes back with the frame that held it")
        session.close()
    }

    @Test
    fun closingTheOutputClosesItsHardware() = runTest {
        val hardware = FakeHardwareFrames()
        val session = canvasSession(FFplayConfiguration(), SDR, hardware)

        session.close()

        assertTrue(hardware.closed)
    }
}

private class CanvasSession(
    val player: FFplayPlayer,
    val output: ComposeCanvasOutput,
    val negotiated: List<NativePlayerOutputCapabilities>,
    val targets: List<Any?>,
) {
    fun close() {
        player.detachOutput(output)
        output.close()
        player.close()
    }
}

/** A Canvas output with [hardware] on a player whose source has [video], recording every negotiation. */
private suspend fun canvasSession(
    configuration: FFplayConfiguration,
    video: NativePlayerVideoInfo,
    hardware: CanvasHardwareFrames,
    prepare: Boolean = true,
): CanvasSession {
    val negotiated = mutableListOf<NativePlayerOutputCapabilities>()
    val targets = mutableListOf<Any?>()
    val player = FFplayPlayer(
        configuration = configuration,
        engineFactory = { engineConfiguration, update, emit ->
            createFFplayEngineWithBridge(engineConfiguration, update, emit) { native, nativeUpdate, _, _ ->
                val bridge = createInMemoryPlayerBridge(native) { snapshot -> nativeUpdate(snapshot.copy(videoInfo = video)) }
                object : NativePlayerBridge by bridge {
                    override fun setPlatformOutputTarget(target: Any?, secure: Boolean): Int {
                        targets += target
                        return 0
                    }

                    override fun setOutput(capabilities: NativePlayerOutputCapabilities): Int {
                        negotiated += capabilities
                        return bridge.setOutput(capabilities)
                    }
                }
            }
        },
        audioOpener = { null },
    )
    if (prepare) player.prepare(MediaSource("clip.mp4"))
    return CanvasSession(player, ComposeCanvasOutput(player, playerClockNanos = null, hardware = hardware), negotiated, targets)
}

/** A hardware decoder's frame, as the player hands it over: no CPU-visible pixels of its own here. */
private fun decoded(index: Int): VideoFrame =
    VideoFrame.of(NativeGpuBuffer(Any(), -1L - index, 0, 0, 64, 48, 1) {}, (index * 33).milliseconds, 33.milliseconds, 64, 48)

/** Hands out a fresh GPU buffer for every frame, counting them out and back. */
private class FakeHardwareFrames(private val takesHdr: Boolean = true) : CanvasHardwareFrames {
    override val surface: Any = Any()
    var taken = 0
    var released = 0
    var closed = false

    override fun takes(video: VideoInfo): Boolean = takesHdr || !video.color.isHdr

    override fun inGpuMemory(frame: VideoFrame, video: VideoInfo?): VideoFrame {
        frame.close()
        taken++
        val buffer = NativeGpuBuffer(Any(), taken.toLong(), 0, 0, frame.width, frame.height, 1) { released++ }
        return VideoFrame.of(buffer, frame.pts, frame.duration, frame.width, frame.height)
    }

    override fun close() {
        closed = true
    }
}

private val SDR = NativePlayerVideoInfo(width = 64, height = 48, colorPrimaries = 1, colorTransfer = 1, colorSpace = 1)

private val HDR10 = NativePlayerVideoInfo(width = 64, height = 48, colorPrimaries = 9, colorTransfer = 16, colorSpace = 9)
