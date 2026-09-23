// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativeAudioProgress
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerAudio
import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import io.github.aftrolle.ffmpegkmp.player.AudioTrackInfo
import io.github.aftrolle.ffmpegkmp.player.PlaybackState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Audio the player bridge plays itself, presented as FFplay's audio output with AudioPlayer's states. */
internal class BridgeAudioOutput(
    private val native: NativePlayerAudio,
    private val warn: (String) -> Unit,
) : FFplayAudioOutput {
    override val tracks: List<AudioTrackInfo> = native.tracks.map { track ->
        AudioTrackInfo(
            index = track.index,
            codec = track.codec,
            language = track.language,
            title = track.title,
            channels = track.channels,
            sampleRate = track.sampleRate,
            isDefault = track.isDefault,
            isDecodable = track.isDecodable,
        )
    }

    private val mutableState = MutableStateFlow(PlaybackState.PAUSED)
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private val mutablePosition = MutableStateFlow(Duration.ZERO)
    override val position: StateFlow<Duration> = mutablePosition.asStateFlow()

    private val mutableEnabledTracks = MutableStateFlow(enabledNow())
    override val enabledTracks: StateFlow<Set<Int>> = mutableEnabledTracks.asStateFlow()

    private val mutableTrackLevels = MutableStateFlow(List(tracks.size) { AudioLevel.Unchanged })
    override val trackLevels: StateFlow<List<AudioLevel>> = mutableTrackLevels.asStateFlow()

    private var autoplayWarned = false

    init {
        native.setProgressListener(::accept)
    }

    override fun play() {
        if (mutableState.value !in setOf(PlaybackState.PAUSED, PlaybackState.ENDED)) return
        native.play()
        mutableState.value = PlaybackState.PLAYING
    }

    override fun pause() {
        if (mutableState.value != PlaybackState.PLAYING) return
        native.pause()
        mutableState.value = PlaybackState.PAUSED
    }

    override fun seekTo(position: Duration) {
        require(!position.isNegative()) { "Seek position must not be negative" }
        if (mutableState.value in setOf(PlaybackState.FAILED, PlaybackState.CLOSED)) return
        native.seek(position.inWholeMicroseconds)
        if (mutableState.value == PlaybackState.ENDED) mutableState.value = PlaybackState.PAUSED
    }

    override fun setLevel(level: AudioLevel) {
        native.setMasterGain(level.effectiveVolume.toFloat())
    }

    override fun setTrackLevel(track: Int, level: AudioLevel) {
        requireTrack(track)
        native.setTrackGain(track, level.effectiveVolume.toFloat())
        mutableTrackLevels.value = mutableTrackLevels.value.toMutableList().also { it[track] = level }
    }

    override fun setTrackEnabled(track: Int, enabled: Boolean) {
        if (enabled) requireDecodable(track) else requireTrack(track)
        native.setTrackEnabled(track, enabled)
        mutableEnabledTracks.value = enabledNow()
    }

    override fun selectTracks(selected: Set<Int>) {
        selected.forEach(::requireDecodable)
        tracks.indices.forEach { track -> native.setTrackEnabled(track, track in selected) }
        mutableEnabledTracks.value = enabledNow()
    }

    override fun close() {
        if (mutableState.value == PlaybackState.CLOSED) return
        native.close()
        if (mutableState.value != PlaybackState.FAILED) mutableState.value = PlaybackState.CLOSED
    }

    private fun accept(progress: NativeAudioProgress) {
        if (mutableState.value in setOf(PlaybackState.FAILED, PlaybackState.CLOSED)) return
        progress.failure?.let { message ->
            warn("Audio failed: $message")
            mutableState.value = PlaybackState.FAILED
            return
        }
        if (progress.blockedByAutoplay && !autoplayWarned) {
            autoplayWarned = true
            warn("The browser holds audio back until the page receives a user gesture")
        }
        mutablePosition.value = progress.positionMicros.microseconds
        if (progress.ended && mutableState.value == PlaybackState.PLAYING) mutableState.value = PlaybackState.ENDED
    }

    private fun enabledNow(): Set<Int> = tracks.indices.filterTo(mutableSetOf(), native::isTrackEnabled)

    private fun requireTrack(track: Int) = require(track in tracks.indices) { "No audio track $track" }

    private fun requireDecodable(track: Int) {
        requireTrack(track)
        require(tracks[track].isDecodable) { "No decoder for audio track $track (${tracks[track].codec})" }
    }
}
