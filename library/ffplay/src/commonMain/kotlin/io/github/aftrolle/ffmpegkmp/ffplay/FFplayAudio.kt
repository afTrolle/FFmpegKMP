// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativeFileResource
import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import io.github.aftrolle.ffmpegkmp.core.toNativeMounts
import io.github.aftrolle.ffmpegkmp.player.AudioPlayer
import io.github.aftrolle.ffmpegkmp.player.AudioTrackInfo
import io.github.aftrolle.ffmpegkmp.player.PlaybackState
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

/** Audio of the prepared source: its tracks and the levels the listener chose. */
public data class FFplayAudioState(
    /** False when the source has no playable audio, or audio is unavailable on this platform. */
    val available: Boolean = false,
    val tracks: List<AudioTrackInfo> = emptyList(),
    val enabledTracks: Set<Int> = emptySet(),
    /** Master level; kept across sources, so a muted player stays muted. */
    val level: AudioLevel = AudioLevel.Unchanged,
    val trackLevels: List<AudioLevel> = emptyList(),
)

/** The part of [AudioPlayer] FFplay drives; a seam for tests. */
internal interface FFplayAudioOutput : AutoCloseable {
    val tracks: List<AudioTrackInfo>
    val state: StateFlow<PlaybackState>
    val position: StateFlow<Duration>
    val enabledTracks: StateFlow<Set<Int>>
    val trackLevels: StateFlow<List<AudioLevel>>
    fun play()
    fun pause()
    fun seekTo(position: Duration)
    fun setLevel(level: AudioLevel)
    fun setTrackLevel(track: Int, level: AudioLevel)
    fun setTrackEnabled(track: Int, enabled: Boolean)
    fun selectTracks(selected: Set<Int>)
}

/** Opens the source's audio, or returns null when it has none worth playing. */
internal typealias FFplayAudioOpener = suspend (FFplaySource) -> FFplayAudioOutput?

/**
 * Plays the prepared source's audio alongside FFplay's video. Audio follows the video engine's
 * state transitions (so play, seek, end, failure and a lost surface all apply to it), and its
 * audible position is reported back as the video master clock.
 */
internal class FFplayAudio(
    private val scope: CoroutineScope,
    private val warn: (String) -> Unit,
    private val open: FFplayAudioOpener = ::openAudioPlayer,
) {
    private val mutableState = MutableStateFlow(FFplayAudioState())
    val state: StateFlow<FFplayAudioState> = mutableState.asStateFlow()

    private val current = AtomicReference<FFplayAudioOutput?>(null)
    private val lastEngineState = AtomicReference<FFplayState?>(null)
    private val pendingSeek = AtomicReference<PendingSeek?>(null)
    private var clockJob: Job? = null

    /** Opens audio for [source] without attaching it; failures become warnings. */
    suspend fun load(source: FFplaySource): FFplayAudioOutput? {
        if (source.protection == FFplayContentProtection.REQUIRE_SECURE_PATH) return null
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
        audio.setLevel(mutableState.value.level)
        mutableState.value = FFplayAudioState(
            available = true,
            tracks = audio.tracks,
            enabledTracks = audio.enabledTracks.value,
            level = mutableState.value.level,
            trackLevels = audio.trackLevels.value,
        )
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

    fun setLevel(level: AudioLevel) {
        current.load()?.setLevel(level)
        mutableState.update { it.copy(level = level) }
    }

    fun setTrackLevel(track: Int, level: AudioLevel) {
        val audio = current.load() ?: return
        audio.setTrackLevel(track, level)
        mutableState.update { it.copy(trackLevels = audio.trackLevels.value) }
    }

    fun setTrackEnabled(track: Int, enabled: Boolean) {
        val audio = current.load() ?: return
        audio.setTrackEnabled(track, enabled)
        mutableState.update { it.copy(enabledTracks = audio.enabledTracks.value) }
    }

    fun selectTracks(selected: Set<Int>) {
        val audio = current.load() ?: return
        audio.selectTracks(selected)
        mutableState.update { it.copy(enabledTracks = audio.enabledTracks.value) }
    }

    /** Releases the source's audio; the master level carries over to the next source. */
    fun close() {
        clockJob?.cancel()
        clockJob = null
        current.exchange(null)?.close()
        pendingSeek.store(null)
        mutableState.update { FFplayAudioState(level = it.level) }
    }

    private fun seekAudio(audio: FFplayAudioOutput, position: Duration) {
        pendingSeek.store(PendingSeek(position, TimeSource.Monotonic.markNow()))
        audio.seekTo(position)
    }

    private class PendingSeek(val target: Duration, val started: TimeMark)

    private companion object {
        val SEEK_TOLERANCE = 20.milliseconds

        /** Right after a seek the audio reports the target itself; much further away is stale. */
        val SEEK_WINDOW = 250.milliseconds
        val SEEK_TIMEOUT = 1_000.milliseconds
    }
}

/** Plays a path/URL input, or a mounted file handle; other mounts can't be re-read for audio. */
private suspend fun openAudioPlayer(source: FFplaySource): FFplayAudioOutput? {
    val mount = source.io.toNativeMounts().firstOrNull { it.path == source.input }
    val player = when (val resource = mount?.resource) {
        null -> AudioPlayer.open(source.input)
        is NativeFileResource -> AudioPlayer.open(resource.fileHandle)
        else -> throw IllegalArgumentException("Audio needs a seekable input; mount ${source.input} as a FileHandle")
    }
    return object : FFplayAudioOutput, AutoCloseable by player {
        override val tracks get() = player.tracks
        override val state get() = player.state
        override val position get() = player.position
        override val enabledTracks get() = player.enabledTracks
        override val trackLevels get() = player.trackLevels
        override fun play() = player.play()
        override fun pause() = player.pause()
        override fun seekTo(position: Duration) = player.seekTo(position)
        override fun setLevel(level: AudioLevel) = player.setLevel(level)
        override fun setTrackLevel(track: Int, level: AudioLevel) = player.setTrackLevel(track, level)
        override fun setTrackEnabled(track: Int, enabled: Boolean) = player.setTrackEnabled(track, enabled)
        override fun selectTracks(selected: Set<Int>) = player.selectTracks(selected)
    }
}
