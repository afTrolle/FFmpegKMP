// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativeFileResource
import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import io.github.aftrolle.ffmpegkmp.core.toNativeMounts
import io.github.aftrolle.ffmpegkmp.player.AudioPlayer
import io.github.aftrolle.ffmpegkmp.player.AudioTrackInfo
import io.github.aftrolle.ffmpegkmp.player.PlaybackState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

/**
 * Plays the prepared source's audio alongside FFplay's video and makes it the master clock: the
 * audible position is reported to the video scheduler, so frames are shown when their sound is
 * heard. Audio uses its own demuxer over the same input (a path/URL, or a mounted file handle).
 */
internal class FFplayAudio(
    private val scope: CoroutineScope,
    private val setMasterClock: (mediaTimeUs: Long) -> Unit,
    private val warn: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(FFplayAudioState())
    val state: StateFlow<FFplayAudioState> = mutableState.asStateFlow()

    private var player: AudioPlayer? = null
    private var clockJob: Job? = null

    /** Positions from before a seek can still arrive after it; accept only ones near the target. */
    @kotlin.concurrent.Volatile
    private var pendingSeekTarget: Duration? = null

    suspend fun open(source: FFplaySource) {
        close()
        if (source.protection == FFplayContentProtection.REQUIRE_SECURE_PATH) return
        val opened = try {
            val mount = source.io.toNativeMounts().firstOrNull { it.path == source.input }
            when (val resource = mount?.resource) {
                null -> AudioPlayer.open(source.input)
                is NativeFileResource -> AudioPlayer.open(resource.fileHandle)
                else -> {
                    warn("Audio needs a seekable input; mount ${source.input} as a FileHandle to hear it")
                    return
                }
            }
        } catch (failure: Throwable) {
            warn("Audio is unavailable: ${failure.message ?: failure::class.simpleName}")
            return
        }
        if (opened.tracks.isEmpty()) {
            opened.close()
            return
        }
        player = opened
        opened.setLevel(mutableState.value.level)
        mutableState.value = FFplayAudioState(
            available = true,
            tracks = opened.tracks,
            enabledTracks = opened.enabledTracks.value,
            level = mutableState.value.level,
            trackLevels = opened.trackLevels.value,
        )
        clockJob = scope.launch {
            combine(opened.state, opened.position) { state, position -> state to position }
                .collect { (state, position) -> if (state == PlaybackState.PLAYING) report(position) }
        }
    }

    fun play(fromStart: Boolean) {
        val audio = player ?: return
        if (fromStart) seekTo(Duration.ZERO)
        audio.play()
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(position: Duration) {
        val audio = player ?: return
        pendingSeekTarget = position
        audio.seekTo(position)
    }

    fun setLevel(level: AudioLevel) {
        player?.setLevel(level)
        mutableState.update { it.copy(level = level) }
    }

    fun setTrackLevel(track: Int, level: AudioLevel) {
        val audio = player ?: return
        audio.setTrackLevel(track, level)
        mutableState.update { it.copy(trackLevels = audio.trackLevels.value) }
    }

    fun setTrackEnabled(track: Int, enabled: Boolean) {
        val audio = player ?: return
        audio.setTrackEnabled(track, enabled)
        mutableState.update { it.copy(enabledTracks = audio.enabledTracks.value) }
    }

    fun selectTracks(selected: Set<Int>) {
        val audio = player ?: return
        audio.selectTracks(selected)
        mutableState.update { it.copy(enabledTracks = audio.enabledTracks.value) }
    }

    /** Releases the source's audio; the master level carries over to the next source. */
    fun close() {
        clockJob?.cancel()
        clockJob = null
        player?.close()
        player = null
        pendingSeekTarget = null
        mutableState.update { FFplayAudioState(level = it.level) }
    }

    private fun report(position: Duration) {
        pendingSeekTarget?.let { target ->
            if (position < target - SEEK_TOLERANCE || position > target + SEEK_WINDOW) return
            pendingSeekTarget = null
        }
        setMasterClock(position.inWholeMicroseconds)
    }

    private companion object {
        val SEEK_TOLERANCE = 20.milliseconds

        /**
         * Right after a seek the audio reports the target itself (it holds the position until the
         * device has buffered), so anything further away is a stale pre-seek report — e.g. the
         * ~1 s tail of a clip that just ended, arriving after a replay's seek to zero.
         */
        val SEEK_WINDOW = 250.milliseconds
    }
}
