// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package io.github.aftrolle.ffmpegkmp.player

import io.github.aftrolle.ffmpegkmp.bindings.NativeAudioDecoder
import io.github.aftrolle.ffmpegkmp.bindings.NativeAudioTrackInfo
import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class AudioPlayerTest {
    @Test
    fun playsToTheEndAndDrains() = runTest {
        val (player, decoder, output) = player(totalFrames = 10)

        assertEquals(PlaybackState.PAUSED, player.state.value)
        player.play()
        advanceUntilIdle()

        assertEquals(PlaybackState.ENDED, player.state.value)
        assertEquals(10, output.writtenFrames)
        assertEquals(1, output.drains)
        assertEquals(decoder.positionMicros, player.position.value.inWholeMicroseconds)
        player.close()
    }

    @Test
    fun playAfterEndRestartsFromTheBeginning() = runTest {
        val (player, decoder, output) = player(totalFrames = 8)
        player.play()
        advanceUntilIdle()
        player.play()
        advanceUntilIdle()

        assertEquals(listOf(0L), decoder.seeks)
        assertEquals(16, output.writtenFrames)
        player.close()
    }

    @Test
    fun pauseStopsTheOutputAndSeekFlushesIt() = runTest {
        val (player, decoder, output) = player(totalFrames = 48_000)
        player.pause()
        player.seekTo(250.milliseconds)
        advanceUntilIdle()

        assertEquals(PlaybackState.PAUSED, player.state.value)
        assertEquals(0, output.writtenFrames)
        assertEquals(listOf(250_000L), decoder.seeks)
        assertEquals(1, output.flushes)
        assertEquals(250.milliseconds, player.position.value)
        player.close()
    }

    @Test
    fun seekPastTheEndClampsToTheDuration() = runTest {
        val (player, decoder, _) = player(totalFrames = 4_800)
        player.seekTo(5.milliseconds * 1_000)
        advanceUntilIdle()

        assertEquals(listOf(100_000L), decoder.seeks)
        player.close()
    }

    @Test
    fun levelsAndTrackSelectionReachTheDecoderLive() = runTest {
        val (player, decoder, _) = player(totalFrames = 4)

        player.setVolume(0.5)
        player.setMuted(true)
        assertEquals(AudioLevel(volume = 0.5, muted = true), player.level.value)
        assertEquals(0f, decoder.master)
        player.setMuted(false)
        assertEquals(0.5f, decoder.master)

        player.setTrackVolume(1, 0.25)
        player.setTrackMuted(0, true)
        assertEquals(listOf(AudioLevel.Muted, AudioLevel(volume = 0.25)), player.trackLevels.value)
        assertEquals(listOf(0f, 0.25f), decoder.gains)

        player.setTrackEnabled(1, true)
        assertEquals(setOf(0, 1), player.enabledTracks.value)
        player.selectTrack(1)
        assertEquals(setOf(1), player.enabledTracks.value)
        assertEquals(listOf(false, true), decoder.enabled)
        assertFailsWith<IllegalArgumentException> { player.selectTrack(2) }
        player.close()
    }

    @Test
    fun closeReleasesTheDecoderAndOutput() = runTest {
        val (player, decoder, output) = player(totalFrames = 4)
        player.close()
        advanceUntilIdle()

        assertEquals(PlaybackState.CLOSED, player.state.value)
        assertTrue(decoder.closed)
        assertTrue(output.closed)
    }

    @Test
    fun aDecodeFailureStaysFailedAndReleasesResources() = runTest {
        val (player, decoder, output) = player(totalFrames = 100)
        decoder.failAt = 6
        player.play()
        advanceUntilIdle()

        assertEquals(PlaybackState.FAILED, player.state.value)
        assertEquals("decoder exploded", player.failure?.message)
        assertTrue(decoder.closed)
        assertTrue(output.closed)
    }

    @Test
    fun closeWhilePlayingEndsClosedEvenWhenTheAbortSurfacesAsAReadError() = runTest {
        // A playing loop never idles, so this runs on a real dispatcher and waits in real time.
        val decoder = FakeDecoder(totalFrames = Int.MAX_VALUE)
        val output = FakeOutput()
        val player = AudioPlayer(AudioDecoder(decoder), output, Dispatchers.Default, chunkFrames = 3)
        player.play()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { player.state.first { it == PlaybackState.PLAYING } }
            player.close()
            withTimeout(5_000) { player.state.first { it == PlaybackState.CLOSED || it == PlaybackState.FAILED } }
        }

        assertTrue(decoder.aborted)
        assertEquals(PlaybackState.CLOSED, player.state.value)
        assertEquals(null, player.failure)
        assertTrue(decoder.closed && output.closed)
    }

    @Test
    fun controlsAfterCloseDoNotThrow() = runTest {
        val (player, _, _) = player(totalFrames = 4)
        player.close()
        advanceUntilIdle()

        player.setVolume(0.3)
        player.setMuted(true)
        player.setTrackVolume(0, 0.5)
        player.setTrackEnabled(1, true)
        player.play()
        player.seekTo(10.milliseconds)
        assertEquals(PlaybackState.CLOSED, player.state.value)
    }

    @Test
    fun aCancelledOpenClosesWhatItBuiltInsteadOfLeakingIt() = runTest {
        val decoder = FakeDecoder(totalFrames = 4)
        val output = FakeOutput()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opening = launch {
            AudioPlayer.openWith(
                openDecoder = {
                    // The caller gives up while the (uninterruptible) native open is in progress.
                    coroutineContext.cancel()
                    AudioDecoder(decoder)
                },
                openOutput = { output },
                dispatcher = dispatcher,
            )
        }
        advanceUntilIdle()

        assertTrue(opening.isCancelled)
        assertTrue(decoder.closed, "the decoder opened for a cancelled caller must be released")
        assertTrue(output.closed)
    }

    private fun TestScope.player(totalFrames: Int): Triple<AudioPlayer, FakeDecoder, FakeOutput> {
        val decoder = FakeDecoder(totalFrames)
        val output = FakeOutput()
        val player = AudioPlayer(AudioDecoder(decoder), output, StandardTestDispatcher(testScheduler), chunkFrames = 3)
        return Triple(player, decoder, output)
    }
}

private class FakeDecoder(private val totalFrames: Int) : NativeAudioDecoder {
    override val sampleRate = 48_000
    override val channels = 2
    override val tracks = List(2) { index ->
        NativeAudioTrackInfo(index, "pcm_f32le", null, null, 2, 48_000, index == 0, true)
    }
    override val durationMicros = totalFrames * 1_000_000L / sampleRate
    override val positionMicros get() = frame * 1_000_000L / sampleRate
    private var frame = 0L
    val seeks = mutableListOf<Long>()
    val enabled = mutableListOf(true, false)
    val gains = mutableListOf(1f, 1f)
    var master = 1f
    var closed = false

    override fun isTrackEnabled(track: Int) = enabled[track]
    override fun setTrackEnabled(track: Int, enabled: Boolean) {
        this.enabled[track] = enabled
    }
    override fun setTrackGain(track: Int, gain: Float) {
        gains[track] = gain
    }
    override fun setMasterGain(gain: Float) {
        master = gain
    }
    override fun seek(positionMicros: Long) {
        seeks += positionMicros
        frame = positionMicros * sampleRate / 1_000_000L
    }
    var failAt = -1L

    override fun read(destination: FloatArray, offset: Int, frames: Int): Int {
        if (failAt in 0..frame) throw IllegalStateException("decoder exploded")
        val count = minOf(frames.toLong(), totalFrames - frame).toInt().coerceAtLeast(0)
        frame += count
        return count
    }
    var aborted = false

    override fun abort() {
        // Like the native engine: once aborted, an in-flight or later read fails.
        aborted = true
        failAt = 0
    }
    override fun close() {
        closed = true
    }
}

private class FakeOutput : PlatformAudioOutput {
    override val latencyFrames = 0
    var writtenFrames = 0
    var flushes = 0
    var drains = 0
    var closed = false

    override fun start() = Unit
    override fun pause() = Unit
    override fun flush() {
        flushes++
    }
    override fun write(samples: FloatArray, frames: Int) {
        writtenFrames += frames
    }
    override fun drain() {
        drains++
    }
    override fun close() {
        closed = true
    }
}
