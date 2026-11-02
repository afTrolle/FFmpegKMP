// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerError
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerException
import io.github.aftrolle.ffmpegkmp.bindings.nativePlayerStatistics
import io.github.aftrolle.ffmpegkmp.codec.DecoderBudget
import io.github.aftrolle.ffmpegkmp.codec.DecoderFallbackReason
import io.github.aftrolle.ffmpegkmp.codec.DecoderKind
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer

@OptIn(InternalFFmpegKmpApi::class)
class FFplayProductionJvmTest {
    @Test
    fun publicPlayerDecodesARealMountedVideoThroughTheProductionEngine() = runBlocking {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/playback-color-patches-1s.mp4")) {
            "Missing shared playback fixture"
        }.use { it.readBytes() }
        val output = ProductionOutput()
        val player = FFplayPlayer(FFplayConfiguration(decoderPreference = CodecPreference.SOFTWARE))
        player.attachOutput(output)

        try {
            player.prepare(
                MediaSource(
                    input = "playback-color-patches-1s.mp4",
                    io = CommandIo {
                        input("playback-color-patches-1s.mp4", Buffer().write(bytes))
                    },
                ),
            )

            assertEquals(FFplayState.READY, player.snapshot.value.state)
            assertEquals(DecoderKind.SOFTWARE, player.snapshot.value.output?.decoder)
            // Software was asked for, so nothing fell back.
            assertNull(player.snapshot.value.output?.decoderFallback)
            assertNotNull(player.snapshot.value.video)
            assertTrue(output.framesReceived > 0)
        } finally {
            player.close()
        }
    }

    /**
     * A Canvas output on the desktop wants a hardware decoder under AUTO, so a fallback is reported
     * there too, with its reason: VideoToolbox advertises this MPEG-2 stream on macOS and fails it.
     */
    @Test
    fun aDesktopCanvasOutputReportsWhyItDecodesInSoftware() = runBlocking {
        if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) return@runBlocking
        val player = FFplayPlayer(FFplayConfiguration(decoderPreference = CodecPreference.AUTO, audio = false))
        val fallback = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            player.events.filterIsInstance<FFplayEvent.DecoderFallback>().first()
        }
        try {
            player.attachOutput(ProductionOutput())
            player.prepare(fixtureSource("playback-color-patches-1s.mp4"))

            val output = assertNotNull(player.snapshot.value.output)
            assertEquals(DecoderKind.SOFTWARE, output.decoder)
            assertEquals(DecoderFallbackReason.FAILED, output.decoderFallback)
            val event = withTimeout(2_000) { fallback.await() }
            assertEquals(DecoderFallbackReason.FAILED, event.reason)
            // The source stays in software, and says why, through its seeks.
            player.seekTo(500.milliseconds)
            assertEquals(DecoderFallbackReason.FAILED, player.snapshot.value.output?.decoderFallback)
        } finally {
            fallback.cancel()
            player.close()
        }
    }

    /**
     * A paused player releases its decoder after the configured delay and keeps showing its frame;
     * play then opens one decoder and starts at the paused frame.
     */
    @Test
    fun aPausedPlayerReleasesItsDecoderAndPlaysOnFromThePausedFrame() = runBlocking {
        val output = ProductionOutput()
        val player = FFplayPlayer(
            FFplayConfiguration(
                decoderPreference = CodecPreference.SOFTWARE,
                audio = false,
                releaseDecoderWhenPaused = 300.milliseconds,
            ),
        )
        player.attachOutput(output)
        try {
            player.prepare(fixtureSource("playback-color-patches-1s.mp4"))
            player.play()
            withTimeout(3_000) { player.snapshot.first { it.position >= 300.milliseconds } }
            player.pause()
            val paused = player.snapshot.value.position
            val opens = nativePlayerStatistics().codecOpens
            val closes = nativePlayerStatistics().codecCloses
            val shown = output.presented.size
            withTimeout(3_000) { while (nativePlayerStatistics().codecCloses == closes) delay(10) }
            assertEquals(shown, output.presented.size, "frames presented while paused")
            assertEquals(FFplayState.PAUSED, player.snapshot.value.state)

            player.play()
            withTimeout(3_000) { player.snapshot.first { it.position > paused } }
            player.pause()
            assertEquals(paused, output.presented[shown], "the first frame played after the release")
            assertEquals(opens + 1, nativePlayerStatistics().codecOpens)
            assertEquals(DecoderKind.SOFTWARE, player.snapshot.value.output?.decoder)
        } finally {
            player.close()
        }
    }

    /**
     * With room for two hardware decoders, two of four players decode in VideoToolbox and two in
     * software, and say why.
     */
    @Test
    fun aBudgetOfTwoGivesTwoOfFourPlayersHardware() = withBudget(extra = 2) {
        val players = (1..4).map { budgetPlayer() }
        val fallbacks = players.map { player ->
            async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                player.events.filterIsInstance<FFplayEvent.DecoderFallback>().first()
            }
        }
        try {
            players.forEach { it.prepare(fixtureSource("hardware-h264.mp4")) }
            val outputs = players.map { assertNotNull(it.snapshot.value.output) }
            assertEquals(2, outputs.count { it.decoder == DecoderKind.HARDWARE && it.decoderFallback == null })
            assertEquals(2, outputs.count { it.decoder == DecoderKind.SOFTWARE && it.decoderFallback == DecoderFallbackReason.BUDGET })
            val reasons = fallbacks.filterIndexed { index, _ -> outputs[index].decoderFallback != null }
                .map { withTimeout(2_000) { it.await() }.reason }
            assertEquals(listOf(DecoderFallbackReason.BUDGET, DecoderFallbackReason.BUDGET), reasons)
        } finally {
            fallbacks.forEach { it.cancel() }
            players.forEach { it.close() }
        }
    }

    /** Players and video decoders take places in one budget, and a closed decoder's place goes to the next player. */
    @Test
    fun playersAndVideoDecodersShareOneBudget() = withBudget(extra = 2) {
        val players = mutableListOf<FFplayPlayer>()
        val decoder = VideoDecoder.open(fixtureSource("hardware-h264.mp4"), decoder = CodecPreference.AUTO)
        try {
            assertEquals(DecoderKind.HARDWARE, decoder.decoderKind)
            assertNull(decoder.decoderFallback)
            players += budgetPlayer().apply { prepare(fixtureSource("hardware-h264.mp4")) }
            assertEquals(DecoderKind.HARDWARE, players[0].snapshot.value.output?.decoder)

            // The budget is full: the next player and the next decoder decode in software.
            players += budgetPlayer().apply { prepare(fixtureSource("hardware-h264.mp4")) }
            assertEquals(DecoderFallbackReason.BUDGET, players[1].snapshot.value.output?.decoderFallback)
            VideoDecoder.open(fixtureSource("hardware-h264.mp4"), decoder = CodecPreference.AUTO).use {
                assertEquals(DecoderKind.SOFTWARE, it.decoderKind)
                assertEquals(DecoderFallbackReason.BUDGET, it.decoderFallback)
            }

            decoder.close()
            players += budgetPlayer().apply { prepare(fixtureSource("hardware-h264.mp4")) }
            assertEquals(DecoderKind.HARDWARE, players[2].snapshot.value.output?.decoder)
            assertNull(players[2].snapshot.value.output?.decoderFallback)
        } finally {
            decoder.close()
            players.forEach { it.close() }
        }
    }

    /**
     * Under REQUIRE_HARDWARE a full budget is a failure with BUSY: prepare throws it, an output
     * attached later fails the player with it, and so does a seek that needs a decoder again after
     * the idle release.
     */
    @Test
    fun requireHardwareWithAFullBudgetFailsWithBusy() = withBudget(extra = 1) {
        val held = DecoderBudget.hardwareDecodersInUse
        val releasing = budgetPlayer(CodecPreference.REQUIRE_HARDWARE, releaseDecoder = 300.milliseconds)
        val holder = budgetPlayer()
        val late = budgetPlayer(CodecPreference.REQUIRE_HARDWARE)
        val unattached = FFplayPlayer(FFplayConfiguration(decoderPreference = CodecPreference.REQUIRE_HARDWARE, audio = false))
        try {
            releasing.prepare(fixtureSource("hardware-h264.mp4"))
            assertEquals(DecoderKind.HARDWARE, releasing.snapshot.value.output?.decoder)
            withTimeout(3_000) { while (DecoderBudget.hardwareDecodersInUse > held) delay(10) }
            holder.prepare(fixtureSource("hardware-h264.mp4"))
            assertEquals(DecoderKind.HARDWARE, holder.snapshot.value.output?.decoder)

            val failure = assertFailsWith<NativePlayerException> { releasing.seekTo(500.milliseconds) }
            assertEquals(NativePlayerError.BUSY, failure.errorCode)

            val prepareFailure = assertFailsWith<NativePlayerException> { late.prepare(fixtureSource("hardware-h264.mp4")) }
            assertEquals(NativePlayerError.BUSY, prepareFailure.errorCode)

            unattached.prepare(fixtureSource("hardware-h264.mp4"))
            unattached.attachOutput(budgetOutput(CodecPreference.REQUIRE_HARDWARE))
            assertEquals(FFplayState.FAILED, unattached.snapshot.value.state)
            assertEquals(NativePlayerError.BUSY, (unattached.snapshot.value.failure?.cause as? NativePlayerException)?.errorCode)
        } finally {
            releasing.close()
            holder.close()
            late.close()
            unattached.close()
        }
    }

    /**
     * A player that keeps its decoder, so it keeps its place in the budget, with a Canvas output, or
     * under REQUIRE_HARDWARE one that takes hardware frames, as REQUIRE_HARDWARE needs.
     */
    private fun budgetPlayer(
        preference: CodecPreference = CodecPreference.AUTO,
        releaseDecoder: Duration? = null,
    ): FFplayPlayer =
        FFplayPlayer(FFplayConfiguration(decoderPreference = preference, audio = false, releaseDecoderWhenPaused = releaseDecoder))
            .apply { attachOutput(budgetOutput(preference)) }

    private fun budgetOutput(preference: CodecPreference): ProductionOutput =
        if (preference == CodecPreference.REQUIRE_HARDWARE) {
            ProductionOutput(FFplayOutputCapabilities(hardwareFrameImport = true), FFplayRendererKind.GPU_TEXTURE)
        } else {
            ProductionOutput()
        }

    /** Runs [block] on macOS with room for [extra] hardware decoders more than are open, then puts the limit back and checks every place came back. */
    private fun withBudget(extra: Int, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) return@runBlocking
        val limit = DecoderBudget.hardwareDecoders
        val held = DecoderBudget.hardwareDecodersInUse
        DecoderBudget.hardwareDecoders = held + extra
        try {
            block()
        } finally {
            DecoderBudget.hardwareDecoders = limit
        }
        assertEquals(held, DecoderBudget.hardwareDecodersInUse, "places still held")
    }

    private fun fixtureSource(name: String): MediaSource {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/$name")) { "Missing fixture $name" }.use { it.readBytes() }
        return MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) })
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
                player.prepare(MediaSource("blocking.mp4"))
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
                player.prepare(MediaSource("never-opened.mp4"))
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
private class ProductionOutput(
    override val capabilities: FFplayOutputCapabilities = FFplayOutputCapabilities(),
    override val kind: FFplayRendererKind = FFplayRendererKind.COMPOSE_CANVAS,
) : FFplayVideoOutput {
    var framesReceived = 0
        private set

    /** Each frame's presentation time, in order. */
    val presented: MutableList<Duration> = java.util.concurrent.CopyOnWriteArrayList()

    override fun submitNative(frame: VideoFrame, video: VideoInfo?): Boolean {
        presented += frame.pts
        frame.close()
        framesReceived++
        return true
    }

    override fun discard() = Unit
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

    override fun prepare(source: MediaSource, protection: FFplayContentProtection) {
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

    override fun prepare(source: MediaSource, protection: FFplayContentProtection) {
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
