// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativeAudioProgress
import io.github.aftrolle.ffmpegkmp.bindings.NativeAudioTrackInfo
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerAudio
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import io.github.aftrolle.ffmpegkmp.player.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class FFplayBridgeAudioTest {
    @Test
    fun theOutputFollowsTheBridgesProgressAndLevels() {
        val native = FakeBridgeAudio()
        val warnings = mutableListOf<String>()
        val output = BridgeAudioOutput(native, warnings::add)

        output.play()
        native.report(NativeAudioProgress(positionMicros = 250_000))
        assertEquals(PlaybackState.PLAYING, output.state.value)
        assertEquals(250.milliseconds, output.position.value)

        output.setLevel(AudioLevel(volume = 0.5, muted = true))
        assertEquals(0f, native.lastMasterGain)
        output.setTrackLevel(1, AudioLevel(volume = 0.25))
        assertEquals(0.25f, native.trackGains[1])
        assertEquals(AudioLevel(volume = 0.25), output.trackLevels.value[1])

        output.selectTracks(setOf(1))
        assertEquals(setOf(1), output.enabledTracks.value)
        assertFailsWith<IllegalArgumentException> { output.setTrackEnabled(2, true) }

        native.report(NativeAudioProgress(positionMicros = 1_000_000, ended = true))
        assertEquals(PlaybackState.ENDED, output.state.value)
        output.seekTo(0.seconds)
        assertEquals(PlaybackState.PAUSED, output.state.value)
        assertEquals(listOf(0L), native.seeks)

        output.close()
        assertEquals(PlaybackState.CLOSED, output.state.value)
        assertTrue(native.closed)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun autoplayBlockingWarnsOnceAndAFailureStopsTheAudio() {
        val native = FakeBridgeAudio()
        val warnings = mutableListOf<String>()
        val output = BridgeAudioOutput(native, warnings::add)
        output.play()

        native.report(NativeAudioProgress(positionMicros = 0, blockedByAutoplay = true))
        native.report(NativeAudioProgress(positionMicros = 0, blockedByAutoplay = true))
        assertEquals(1, warnings.size)

        native.report(NativeAudioProgress(positionMicros = 0, failure = "decoder died"))
        assertEquals(PlaybackState.FAILED, output.state.value)
        assertTrue(warnings.last().contains("decoder died"))
    }

    @Test
    fun aPlayerUsesTheBridgesAudioAndReportsItAsTheMasterClock() = runTest {
        val native = FakeBridgeAudio()
        val clock = mutableListOf<Long>()
        val player = FFplayPlayer(
            configuration = FFplayConfiguration(),
            engineFactory = { configuration, update, emit ->
                createFFplayEngineWithBridge(configuration, update, emit) { nativeConfiguration, nativeUpdate, _, _ ->
                    val delegate = createInMemoryPlayerBridge(nativeConfiguration, nativeUpdate)
                    object : NativePlayerBridge by delegate {
                        override suspend fun openAudio(): NativePlayerAudio = native
                        override fun setMasterClock(mediaTimeUs: Long) {
                            clock += mediaTimeUs
                        }
                    }
                }
            },
        )
        player.attachOutput(AudioTestCanvas())
        player.prepare(FFplaySource("movie.mp4"))
        assertEquals(3, player.audio.value.tracks.size)

        player.play()
        assertTrue(native.playing)
        native.report(NativeAudioProgress(positionMicros = 40_000))
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { while (40_000L !in clock) kotlinx.coroutines.delay(5) }
        }

        player.stop()
        assertTrue(native.closed)
        player.close()
    }
}

private class FakeBridgeAudio : NativePlayerAudio {
    override val tracks = listOf(
        track(0, isDefault = true),
        track(1),
        track(2, isDecodable = false),
    )
    override val durationMicros: Long = 1_000_000
    private val enabled = mutableSetOf(0)
    private var listener: (NativeAudioProgress) -> Unit = {}
    val trackGains = mutableMapOf<Int, Float>()
    val seeks = mutableListOf<Long>()
    var lastMasterGain = 1f
    var playing = false
    var closed = false

    fun report(progress: NativeAudioProgress) = listener(progress)

    override fun isTrackEnabled(track: Int) = track in enabled
    override fun setTrackEnabled(track: Int, enabled: Boolean) {
        if (enabled) this.enabled += track else this.enabled -= track
    }
    override fun setTrackGain(track: Int, gain: Float) {
        trackGains[track] = gain
    }
    override fun setMasterGain(gain: Float) {
        lastMasterGain = gain
    }
    override fun play() {
        playing = true
    }
    override fun pause() {
        playing = false
    }
    override fun seek(positionMicros: Long) {
        seeks += positionMicros
    }
    override fun setProgressListener(listener: (NativeAudioProgress) -> Unit) {
        this.listener = listener
    }
    override fun close() {
        closed = true
    }
}

private fun track(index: Int, isDefault: Boolean = false, isDecodable: Boolean = true) = NativeAudioTrackInfo(
    index = index,
    codec = "aac",
    language = null,
    title = null,
    channels = 2,
    sampleRate = 48_000,
    isDefault = isDefault,
    isDecodable = isDecodable,
)

private class AudioTestCanvas : FFplayVideoOutput {
    override val kind = FFplayRendererKind.COMPOSE_CANVAS
    override val capabilities = FFplayOutputCapabilities()
    override fun submit(frame: FFplayFrame): Boolean = true
    override fun discard() = Unit
}
