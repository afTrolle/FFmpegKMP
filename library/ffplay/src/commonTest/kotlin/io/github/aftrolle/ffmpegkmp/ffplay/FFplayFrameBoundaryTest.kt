// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerConfiguration
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerOutputCapabilities
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSnapshot
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSource
import io.github.aftrolle.ffmpegkmp.bindings.NativePlatformVideoFrame
import io.github.aftrolle.ffmpegkmp.bindings.NativePlatformVideoFrameKind
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class FFplayFrameBoundaryTest {
    @Test
    fun currentFramesReachOutputAndSeekStaleFramesAreDiscarded() = runTest {
        val harness = FrameBridgeHarness()
        val player = harness.player()
        val output = CountingOutput(acceptFrames = false)
        player.attachOutput(output)
        player.prepare(FFplaySource("movie.mp4"))
        val oldSerial = harness.snapshot().queueSerial

        player.seekTo(1.seconds)
        assertNotEquals(oldSerial, harness.snapshot().queueSerial)
        harness.emitFrame(oldSerial)
        assertEquals(0, output.submitCount)
        assertEquals(0, player.snapshot.value.droppedFrames)

        harness.emitFrame(harness.snapshot().queueSerial)
        assertEquals(1, output.submitCount)
        assertEquals(1, player.snapshot.value.droppedFrames)
        player.close()
    }

    @Test
    fun protectedFramesNeverCrossTheCpuReadableBoundary() = runTest {
        val harness = FrameBridgeHarness()
        val player = harness.player()
        val output = CountingOutput(
            acceptFrames = true,
            capabilities = FFplayOutputCapabilities(
                hardwareFrameImport = true,
                softwareFrameUpload = false,
                zeroCopy = true,
                protectedContent = true,
            ),
            kind = FFplayRendererKind.NATIVE_SURFACE,
        )
        player.attachOutput(output)
        player.prepare(
            FFplaySource(
                "protected.mpd",
                protection = FFplayContentProtection.REQUIRE_SECURE_PATH,
            ),
        )
        val fatal = async { player.events.first() }
        runCurrent()

        harness.emitFrame(harness.snapshot().queueSerial)

        assertEquals(0, output.submitCount)
        assertTrue(fatal.await() is FFplayEvent.Fatal)
        player.close()
    }

    @Test
    fun currentHardwareFramesReachThePlatformOutputAndStaleFramesAreRejected() = runTest {
        val harness = FrameBridgeHarness()
        val player = harness.player()
        val output = CountingOutput(
            acceptFrames = true,
            capabilities = FFplayOutputCapabilities(
                hardwareFrameImport = true,
                softwareFrameUpload = true,
                zeroCopy = true,
            ),
            kind = FFplayRendererKind.NATIVE_SURFACE,
        )
        player.attachOutput(output)
        player.prepare(FFplaySource("movie.mp4"))
        val staleSerial = harness.snapshot().queueSerial

        player.seekTo(1.seconds)

        assertEquals(false, harness.emitPlatformFrame(staleSerial))
        assertEquals(0, output.platformSubmitCount)
        assertEquals(true, harness.emitPlatformFrame(harness.snapshot().queueSerial))
        assertEquals(1, output.platformSubmitCount)
        player.close()
    }

    @Test
    fun protectedHardwareFramesRemainInsideThePlatformBoundary() = runTest {
        val harness = FrameBridgeHarness()
        val player = harness.player()
        val output = CountingOutput(
            acceptFrames = true,
            capabilities = FFplayOutputCapabilities(
                hardwareFrameImport = true,
                softwareFrameUpload = false,
                zeroCopy = true,
                protectedContent = true,
            ),
            kind = FFplayRendererKind.NATIVE_SURFACE,
        )
        player.attachOutput(output)
        player.prepare(
            FFplaySource(
                "protected.mpd",
                protection = FFplayContentProtection.REQUIRE_SECURE_PATH,
            ),
        )

        assertTrue(harness.emitPlatformFrame(harness.snapshot().queueSerial))
        assertEquals(1, output.platformSubmitCount)
        assertEquals(0, output.submitCount)
        player.close()
    }

    @Test
    fun rejectedHardwareFramesContributeToTheDroppedFrameCount() = runTest {
        val harness = FrameBridgeHarness()
        val player = harness.player()
        val output = CountingOutput(
            acceptFrames = false,
            capabilities = FFplayOutputCapabilities(hardwareFrameImport = true),
            kind = FFplayRendererKind.NATIVE_SURFACE,
        )
        player.attachOutput(output)
        player.prepare(FFplaySource("movie.mp4"))

        assertEquals(false, harness.emitPlatformFrame(harness.snapshot().queueSerial))
        assertEquals(1, player.snapshot.value.droppedFrames)
        player.close()
    }

    @Test
    fun droppedFrameCountResetsForANewSourceGeneration() = runTest {
        val harness = FrameBridgeHarness()
        val player = harness.player()
        val output = CountingOutput(acceptFrames = false)
        player.attachOutput(output)
        player.prepare(FFplaySource("first.mp4"))
        harness.emitFrame(harness.snapshot().queueSerial)
        assertEquals(1, player.snapshot.value.droppedFrames)

        player.prepare(FFplaySource("second.mp4"))

        assertEquals(0, player.snapshot.value.droppedFrames)
        player.close()
    }

    @Test
    fun rejectedOutputReplacementClearsThePreviousPlatformTarget() = runTest {
        val harness = FrameBridgeHarness()
        val player = harness.player()
        val firstTarget = Any()
        player.attachOutput(PlatformOutput(firstTarget))
        player.prepare(FFplaySource("movie.mp4"))
        assertEquals(firstTarget, harness.platformTarget())

        player.attachOutput(
            PlatformOutput(
                platformTarget = Any(),
                capabilities = FFplayOutputCapabilities(
                    hardwareFrameImport = false,
                    softwareFrameUpload = false,
                ),
            ),
        )

        assertEquals(FFplayState.FAILED, player.snapshot.value.state)
        assertNull(player.snapshot.value.output)
        assertNull(harness.platformTarget())
        player.close()
    }

    @Test
    fun failedPlatformAttachmentCannotRetainThePreviousTarget() = runTest {
        val harness = FrameBridgeHarness()
        val player = harness.player()
        val firstTarget = Any()
        val failingTarget = Any()
        player.attachOutput(PlatformOutput(firstTarget))
        player.prepare(FFplaySource("movie.mp4"))
        harness.rejectPlatformTarget(failingTarget)

        player.attachOutput(PlatformOutput(failingTarget))

        assertEquals(FFplayState.FAILED, player.snapshot.value.state)
        assertNull(player.snapshot.value.output)
        assertNull(harness.platformTarget())
        player.close()
    }

    @Test
    fun nativeOutputFailureRemainsFailedAfterCapabilitiesAreCleared() = runTest {
        val harness = FrameBridgeHarness()
        val player = harness.player()
        player.prepare(FFplaySource("movie.mp4"))
        harness.failNextOutputAttachment(-5)

        player.attachOutput(PlatformOutput(Any()))

        assertEquals(FFplayState.FAILED, player.snapshot.value.state)
        assertNull(player.snapshot.value.output)
        assertTrue(player.snapshot.value.failure?.message?.contains("error -5") == true)
        assertNull(harness.platformTarget())
        player.close()
    }
}

private class FrameBridgeHarness {
    private lateinit var bridge: ControllablePlayerBridge

    fun player(): FFplayPlayer = FFplayPlayer(
        configuration = FFplayConfiguration(),
        engineFactory = { configuration, update, emit ->
            createFFplayEngineWithBridge(configuration, update, emit) {
                    nativeConfiguration,
                    nativeUpdate,
                    nativeFrame,
                    platformFrame,
                ->
                ControllablePlayerBridge(
                    nativeConfiguration,
                    nativeUpdate,
                    nativeFrame,
                    platformFrame,
                )
                    .also { bridge = it }
            }
        },
    )

    fun snapshot(): NativePlayerSnapshot = bridge.snapshot()

    fun emitFrame(serial: UInt) = bridge.emitFrame(serial)

    fun emitPlatformFrame(serial: UInt): Boolean = bridge.emitPlatformFrame(serial)

    fun platformTarget(): Any? = bridge.platformTarget

    fun rejectPlatformTarget(target: Any) {
        bridge.rejectedPlatformTarget = target
    }

    fun failNextOutputAttachment(errorCode: Int) {
        bridge.nextOutputFailure = errorCode
    }

}

private class ControllablePlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    private val frame: (NativeVideoFrame) -> Unit,
    private val platformFrame: (NativePlatformVideoFrame) -> Boolean,
) : NativePlayerBridge {
    private val delegate = createInMemoryPlayerBridge(configuration, update)
    var platformTarget: Any? = null
        private set
    var rejectedPlatformTarget: Any? = null
    var nextOutputFailure: Int? = null

    fun emitFrame(serial: UInt) {
        frame(
            NativeVideoFrame(
                rgba = ByteArray(16).also { bytes ->
                    for (alphaIndex in 3 until bytes.size step 4) bytes[alphaIndex] = -1
                },
                width = 2,
                height = 2,
                stride = 8,
                presentationTimeUs = 0,
                queueSerial = serial,
            ),
        )
    }

    fun emitPlatformFrame(serial: UInt): Boolean = platformFrame(
        NativePlatformVideoFrame(
            kind = NativePlatformVideoFrameKind.CV_PIXEL_BUFFER,
            handle = Any(),
            width = 1920,
            height = 1080,
            presentationTimeUs = 0,
            queueSerial = serial,
        ),
    )

    override fun prepare(source: NativePlayerSource): Int = delegate.prepare(source)
    override fun setOutput(capabilities: NativePlayerOutputCapabilities): Int {
        val failure = nextOutputFailure
        if (failure != null) {
            nextOutputFailure = null
            return failure
        }
        return delegate.setOutput(capabilities)
    }
    override fun setPlatformOutputTarget(target: Any?, secure: Boolean): Int {
        if (rejectedPlatformTarget != null && target === rejectedPlatformTarget) return -95
        platformTarget = target
        return 0
    }
    override fun clearOutput() = delegate.clearOutput()
    override fun play(): Int = delegate.play()
    override fun pause(): Int = delegate.pause()
    override fun seek(positionUs: Long): Int = delegate.seek(positionUs)
    override fun stop(): Int = delegate.stop()
    override fun cancel() = delegate.cancel()
    override fun snapshot(): NativePlayerSnapshot = delegate.snapshot()
    override fun close() = delegate.close()
}

private class PlatformOutput(
    override val platformTarget: Any,
    override val capabilities: FFplayOutputCapabilities = FFplayOutputCapabilities(),
) : FFplayVideoOutput {
    override val kind = FFplayRendererKind.NATIVE_SURFACE
    override val frames = kotlinx.coroutines.flow.MutableStateFlow<FFplayFrame?>(null)
    override fun submit(frame: FFplayFrame): Boolean = true
    override fun discard() {
        frames.value = null
    }
}

private class CountingOutput(
    private val acceptFrames: Boolean,
    override val capabilities: FFplayOutputCapabilities = FFplayOutputCapabilities(),
    override val kind: FFplayRendererKind = FFplayRendererKind.COMPOSE_CANVAS,
) : FFplayVideoOutput {
    override val frames = kotlinx.coroutines.flow.MutableStateFlow<FFplayFrame?>(null)
    var submitCount = 0
        private set
    var platformSubmitCount = 0
        private set

    override fun submit(frame: FFplayFrame): Boolean {
        submitCount++
        if (acceptFrames) frames.value = frame
        return acceptFrames
    }

    override fun submitNative(frame: NativeVideoFrame, video: FFplayVideoInfo?): Boolean {
        submitCount++
        return acceptFrames
    }

    override fun submitPlatform(frame: NativePlatformVideoFrame, video: FFplayVideoInfo?): Boolean {
        platformSubmitCount++
        return acceptFrames
    }

    override fun discard() {
        frames.value = null
    }
}
