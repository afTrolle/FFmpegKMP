// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class FFplayPlayerTest {
    @Test
    fun prepareWaitsForOutputAndResumesPlayWhenAttached() = runTest {
        val player = testPlayer()
        player.prepare(FFplaySource("movie.mp4"))
        assertEquals(FFplayState.WAITING_FOR_OUTPUT, player.snapshot.value.state)

        player.play()
        assertEquals(FFplayState.WAITING_FOR_OUTPUT, player.snapshot.value.state)

        val output = FakeOutput()
        player.attachOutput(output)
        assertEquals(FFplayState.PLAYING, player.snapshot.value.state)
        assertEquals(FFplayRendererKind.COMPOSE_CANVAS, player.snapshot.value.output?.renderer)
        assertEquals(FFplayDecoderKind.SOFTWARE, player.snapshot.value.output?.decoder)

        player.detachOutput(output)
        assertEquals(FFplayState.WAITING_FOR_OUTPUT, player.snapshot.value.state)
        player.close()
        assertEquals(FFplayState.CLOSED, player.snapshot.value.state)
    }

    @Test
    fun seekIsBoundedAndRequiresPreparedSource() = runTest {
        val player = testPlayer()
        assertFailsWith<IllegalStateException> { player.seekTo(1.seconds) }
        assertFailsWith<IllegalArgumentException> { player.seekTo((-1).seconds) }

        player.prepare(FFplaySource("movie.mp4"))
        player.seekTo(4.seconds)
        assertEquals(4.seconds, player.snapshot.value.position)
        player.close()
    }

    @Test
    fun sourceAndVideoInfoValidateTheirInvariants() {
        assertFailsWith<IllegalArgumentException> { FFplaySource(" ") }
        assertFailsWith<IllegalArgumentException> { FFplaySource("bad\u0000path") }
        assertFailsWith<IllegalArgumentException> { FFplayVideoInfo(width = 0, height = 1080) }
    }

    @Test
    fun strictHardwarePreferenceFailsTruthfullyOnCanvas() = runTest {
        val player = testPlayer(
            FFplayConfiguration(decoderPreference = FFplayDecoderPreference.REQUIRE_HARDWARE),
        )
        player.prepare(FFplaySource("movie.mp4"))
        player.attachOutput(FakeOutput())

        assertEquals(FFplayState.FAILED, player.snapshot.value.state)
        assertEquals(null, player.snapshot.value.output)
        player.close()
    }

    @Test
    fun automaticCanvasSelectionEmitsOneRendererFallbackPerSource() = runTest {
        val player = testPlayer()
        player.prepare(FFplaySource("movie.mp4"))
        val firstFallback = async {
            player.events.filterIsInstance<FFplayEvent.RendererFallback>().first()
        }
        runCurrent()

        val first = FakeOutput()
        player.attachOutput(first)
        assertTrue(firstFallback.await().message.contains("Compose Canvas"))

        // Surface churn within one source generation must not spam consumers.
        val replacement = FakeOutput()
        player.attachOutput(replacement)
        assertEquals(FFplayState.READY, player.snapshot.value.state)

        // A newly prepared source is a new negotiation and may report its own fallback.
        player.prepare(FFplaySource("replacement.mp4"))
        val secondFallback = async {
            player.events.filterIsInstance<FFplayEvent.RendererFallback>().first()
        }
        runCurrent()
        player.attachOutput(FakeOutput())
        assertTrue(secondFallback.await().message.contains("Compose Canvas"))
        player.close()
    }

    @Test
    fun explicitCanvasPreferenceDoesNotReportCanvasAsAFallback() = runTest {
        val events = mutableListOf<FFplayEvent>()
        val player = FFplayPlayer(
            configuration = FFplayConfiguration(
                outputPreference = FFplayOutputPreference.COMPOSE_CANVAS,
            ),
            engineFactory = { configuration, update, emit ->
                createInMemoryFFplayEngine(configuration, update) { event ->
                    events += event
                    emit(event)
                }
            },
        )
        player.prepare(FFplaySource("movie.mp4"))
        player.attachOutput(FakeOutput())

        assertTrue(events.none { it is FFplayEvent.RendererFallback })
        player.close()
    }

    @Test
    fun protectedSourceNeverFallsBackToCanvas() = runTest {
        val player = testPlayer()
        player.prepare(
            FFplaySource(
                input = "protected.mpd",
                protection = FFplayContentProtection.REQUIRE_SECURE_PATH,
            ),
        )
        player.attachOutput(FakeOutput())

        assertEquals(FFplayState.FAILED, player.snapshot.value.state)
        assertFalse(player.snapshot.value.output?.securePath ?: false)
        assertTrue(player.secureOutputRequired.value)
        player.stop()
        assertFalse(player.secureOutputRequired.value)
        player.close()
    }

    @Test
    fun protectedSourceAcceptsOnlyVerifiedSecureNativeOutput() = runTest {
        val player = testPlayer(
            FFplayConfiguration(decoderPreference = FFplayDecoderPreference.REQUIRE_HARDWARE),
        )
        player.prepare(
            FFplaySource(
                input = "protected.m3u8",
                protection = FFplayContentProtection.REQUIRE_SECURE_PATH,
            ),
        )
        player.attachOutput(SecureFakeOutput())

        assertEquals(FFplayState.READY, player.snapshot.value.state)
        assertTrue(player.snapshot.value.output?.securePath == true)
        assertEquals(FFplayRendererKind.NATIVE_SURFACE, player.snapshot.value.output?.renderer)
        assertEquals(FFplayDecoderKind.HARDWARE, player.snapshot.value.output?.decoder)
        player.close()
    }

    @Test
    fun multiplePlayersKeepIndependentStateAndPositions() = runTest {
        val first = testPlayer()
        val second = testPlayer()
        first.attachOutput(FakeOutput())
        second.attachOutput(FakeOutput())
        first.prepare(FFplaySource("first.mp4"))
        second.prepare(FFplaySource("second.mp4"))

        first.play()
        second.seekTo(7.seconds)
        second.pause()

        assertEquals(FFplayState.PLAYING, first.snapshot.value.state)
        assertEquals(FFplayState.PAUSED, second.snapshot.value.state)
        assertNotEquals(first.snapshot.value.position, second.snapshot.value.position)
        first.close()
        second.close()
    }

    @Test
    fun surfaceChurnPreservesPlaybackIntentAndDiscardsFrames() = runTest {
        val player = testPlayer()
        val first = FakeOutput()
        val replacement = FakeOutput()
        player.attachOutput(first)
        player.prepare(FFplaySource("movie.mp4"))
        player.play()

        player.attachOutput(replacement)
        assertEquals(1, first.discardCount)
        assertEquals(FFplayState.PLAYING, player.snapshot.value.state)

        player.detachOutput(replacement)
        assertEquals(1, replacement.discardCount)
        assertEquals(FFplayState.WAITING_FOR_OUTPUT, player.snapshot.value.state)

        val recreated = FakeOutput()
        player.attachOutput(recreated)
        assertEquals(FFplayState.PLAYING, player.snapshot.value.state)
        player.close()
        assertEquals(1, recreated.discardCount)
    }

    @Test
    fun closeIsIdempotentAndRejectsFurtherCommands() {
        val player = testPlayer()
        player.close()
        player.close()

        assertEquals(FFplayState.CLOSED, player.snapshot.value.state)
        assertFailsWith<IllegalStateException> { player.play() }
    }

    @Test
    fun failedProtectedPrepareDoesNotRetainOrReviveTheSource() = runTest {
        val player = testPlayer()
        val insecureOutput = FakeOutput()
        player.attachOutput(insecureOutput)

        assertFailsWith<IllegalStateException> {
            player.prepare(
                FFplaySource(
                    input = "protected.mpd",
                    protection = FFplayContentProtection.REQUIRE_SECURE_PATH,
                ),
            )
        }

        assertEquals(FFplayState.FAILED, player.snapshot.value.state)
        assertFalse(player.secureOutputRequired.value)
        assertFailsWith<IllegalStateException> { player.play() }

        player.attachOutput(SecureFakeOutput())
        assertFailsWith<IllegalStateException> { player.play() }
        player.close()
    }

    @Test
    fun staleSurfaceDestructionCannotDetachItsReplacement() = runTest {
        val player = testPlayer()
        val stale = FakeOutput()
        val active = FakeOutput()
        player.attachOutput(stale)
        player.prepare(FFplaySource("movie.mp4"))
        player.play()
        player.attachOutput(active)

        player.detachOutput(stale)

        assertEquals(FFplayState.PLAYING, player.snapshot.value.state)
        assertEquals(0, active.discardCount)
        player.close()
        assertEquals(1, active.discardCount)
    }

    @Test
    fun repeatedPrepareStopCyclesResetStateAndPlaybackIntent() = runTest {
        val player = testPlayer()
        val output = FakeOutput()
        player.attachOutput(output)

        repeat(25) { index ->
            player.prepare(FFplaySource("movie-$index.mp4"))
            player.seekTo((index + 1).seconds)
            player.play()
            assertEquals(FFplayState.PLAYING, player.snapshot.value.state)
            player.stop()
            assertEquals(FFplayState.STOPPED, player.snapshot.value.state)
            assertEquals(0.seconds, player.snapshot.value.position)
            assertFailsWith<IllegalStateException> { player.play() }
        }

        player.close()
        assertEquals(26, output.discardCount)
    }

    @Test
    fun stopDiscardsTheRetainedOutputFrame() = runTest {
        val player = testPlayer()
        val output = FakeOutput()
        player.attachOutput(output)
        player.prepare(FFplaySource("movie.mp4"))

        player.stop()

        assertEquals(1, output.discardCount)
        player.close()
        assertEquals(2, output.discardCount)
    }
}

private fun testPlayer(
    configuration: FFplayConfiguration = FFplayConfiguration(),
): FFplayPlayer = FFplayPlayer(configuration, useInMemoryEngine = true)

private class FakeOutput : FFplayVideoOutput {
    override val kind: FFplayRendererKind = FFplayRendererKind.COMPOSE_CANVAS
    override val frames = MutableStateFlow<FFplayFrame?>(null)
    override val capabilities = FFplayOutputCapabilities()
    var discardCount = 0
        private set
    override fun submit(frame: FFplayFrame): Boolean = true
    override fun discard() {
        discardCount++
        frames.value = null
    }
}

private class SecureFakeOutput : FFplayVideoOutput {
    override val kind = FFplayRendererKind.NATIVE_SURFACE
    override val frames = MutableStateFlow<FFplayFrame?>(null)
    override val capabilities = FFplayOutputCapabilities(
        hardwareFrameImport = true,
        softwareFrameUpload = false,
        zeroCopy = true,
        protectedContent = true,
    )
    override fun submit(frame: FFplayFrame): Boolean = false
    override fun discard() = Unit
}
