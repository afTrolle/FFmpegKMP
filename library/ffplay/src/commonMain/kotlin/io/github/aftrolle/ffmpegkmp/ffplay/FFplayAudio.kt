// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativeAudioMixer
import io.github.aftrolle.ffmpegkmp.bindings.NativeAudioTrackInfo
import io.github.aftrolle.ffmpegkmp.bindings.NativeInputHint
import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import io.github.aftrolle.ffmpegkmp.core.applyLatest
import io.github.aftrolle.ffmpegkmp.player.AudioMix
import io.github.aftrolle.ffmpegkmp.player.AudioMixControls
import io.github.aftrolle.ffmpegkmp.player.AudioPlayer
import io.github.aftrolle.ffmpegkmp.player.PlaybackState
import io.github.aftrolle.ffmpegkmp.player.setLevel
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The part of [AudioPlayer] FFplay drives; a seam for tests. */
internal interface FFplayAudioOutput : AudioMixControls, AutoCloseable {
    val state: StateFlow<PlaybackState>
    val position: StateFlow<Duration>
    fun play()
    fun pause()
    fun seekTo(position: Duration)
}

/** Opens the source's audio, or returns null when it has none worth playing. */
internal typealias FFplayAudioOpener = suspend (MediaSource) -> FFplayAudioOutput?

/**
 * Plays the prepared source's audio alongside FFplay's video. Audio follows the video engine's
 * state transitions (so play, seek, end, failure and a lost surface all apply to it), and its
 * audible position is reported back as the video master clock.
 */
internal class FFplayAudio(
    private val scope: CoroutineScope,
    private val warn: (String) -> Unit,
    private val open: FFplayAudioOpener,
) {
    /** Kept across sources, so a muted player stays muted. */
    private val masterLevel = MutableStateFlow(AudioLevel.Unchanged)
    private val silent: AudioMixControls = SourceMix(AudioMix(NoTracks))
    private val mutableMix = MutableStateFlow(silent)

    /** The current source's mix, or one without tracks while there is none. */
    val mix: StateFlow<AudioMixControls> = mutableMix.asStateFlow()

    private val current = AtomicReference<FFplayAudioOutput?>(null)
    private val lastEngineState = AtomicReference<FFplayState?>(null)
    private val pendingSeek = AtomicReference<PendingSeek?>(null)
    private var clockJob: Job? = null

    /** Opens audio for [source] without attaching it; failures become warnings. */
    suspend fun load(source: MediaSource): FFplayAudioOutput? {
        val opened = try {
            open(source)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            warn("Audio is unavailable: ${failure.message ?: failure::class.simpleName}")
            return null
        } ?: return null
        if (opened.tracks.isEmpty()) {
            opened.close()
            return null
        }
        return opened
    }

    /**
     * Starts using [audio] and brings it to the engine's current [snapshot] — e.g. playing, if
     * play was pressed while audio was still opening. [reportClock] receives audible positions.
     */
    fun attach(audio: FFplayAudioOutput, snapshot: FFplaySnapshot, reportClock: (Duration) -> Unit) {
        close()
        current.store(audio)
        applyLatest({ masterLevel.value }, audio::setLevel)
        mutableMix.value = SourceMix(audio)
        clockJob = scope.launch {
            combine(audio.state, audio.position) { state, position -> state to position }
                .collect { (state, position) -> if (state == PlaybackState.PLAYING) reportClock(position) }
        }
        lastEngineState.store(null)
        followEngine(snapshot)
    }

    /**
     * Mirrors video engine transitions onto the audio. Called from engine callbacks, possibly on
     * native threads, so it only issues the audio player's non-blocking commands.
     */
    fun followEngine(snapshot: FFplaySnapshot) {
        val audio = current.load() ?: return
        val previous = lastEngineState.exchange(snapshot.state)
        if (previous == snapshot.state) return
        when (snapshot.state) {
            FFplayState.SEEKING -> seekAudio(audio, snapshot.position)
            FFplayState.PLAYING -> {
                // Re-align on every start, covering replay from the end and a late output.
                if (previous != FFplayState.SEEKING) seekAudio(audio, snapshot.position)
                audio.play()
            }
            else -> audio.pause()
        }
    }

    /**
     * The master clock to report for an audible [position], or null for a stale one: positions
     * from before a seek can still arrive after it, so until one near the target shows up (or a
     * second passes), reports are ignored.
     */
    fun clockFor(position: Duration): Long? {
        pendingSeek.load()?.let { seek ->
            val nearTarget = position >= seek.target - SEEK_TOLERANCE && position <= seek.target + SEEK_WINDOW
            if (!nearTarget && seek.started.elapsedNow() < SEEK_TIMEOUT) return null
            pendingSeek.compareAndSet(seek, null)
        }
        return position.inWholeMicroseconds
    }

    /** Releases the source's audio; the master level carries over to the next source. */
    fun close() {
        clockJob?.cancel()
        clockJob = null
        current.exchange(null)?.close()
        pendingSeek.store(null)
        mutableMix.value = silent
    }

    private fun seekAudio(audio: FFplayAudioOutput, position: Duration) {
        pendingSeek.store(PendingSeek(position, TimeSource.Monotonic.markNow()))
        audio.seekTo(position)
    }

    /** [source]'s mix with the player's master level, which every source shares. */
    private inner class SourceMix(source: AudioMixControls) : AudioMixControls by source {
        override val level: StateFlow<AudioLevel> = masterLevel.asStateFlow()

        override fun updateLevel(transform: (AudioLevel) -> AudioLevel) {
            masterLevel.update(transform)
            applyLatest({ masterLevel.value }) { level -> current.load()?.setLevel(level) }
        }
    }

    private class PendingSeek(val target: Duration, val started: TimeMark)

    private companion object {
        val SEEK_TOLERANCE = 20.milliseconds

        /** Right after a seek the audio reports the target itself; much further away is stale. */
        val SEEK_WINDOW = 250.milliseconds
        val SEEK_TIMEOUT = 1_000.milliseconds
    }
}

/**
 * Plays [source]'s audio with the `player` engine, which reads the same input as the video: with
 * [hint], what the video's prepare found, without probing it again.
 */
internal suspend fun openAudioPlayer(source: MediaSource, hint: NativeInputHint? = null): FFplayAudioOutput {
    val player = AudioPlayer.open(source, hint)
    return object : FFplayAudioOutput, AudioMixControls by player, AutoCloseable by player {
        override val state get() = player.state
        override val position get() = player.position
        override fun play() = player.play()
        override fun pause() = player.pause()
        override fun seekTo(position: Duration) = player.seekTo(position)
    }
}

/** The mixer behind [FFplayAudio.mix] while there is no audio: only the master level changes. */
private object NoTracks : NativeAudioMixer {
    override val tracks: List<NativeAudioTrackInfo> = emptyList()
    override val durationMicros: Long = -1
    override fun isTrackEnabled(track: Int): Boolean = false
    override fun setTrackEnabled(track: Int, enabled: Boolean) = Unit
    override fun setTrackGain(track: Int, gain: Float) = Unit
    override fun setMasterGain(gain: Float) = Unit
}
