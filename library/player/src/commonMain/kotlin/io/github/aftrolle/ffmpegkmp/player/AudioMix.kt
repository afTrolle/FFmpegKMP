// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.player

import io.github.aftrolle.ffmpegkmp.bindings.NativeAudioMixer
import io.github.aftrolle.ffmpegkmp.bindings.NativeAudioTrackInfo
import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.core.applyLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live levels and track selection for the mix of an input's audio tracks: each enabled track
 * plays at its entry in [trackLevels], and the mix then at [level]. [AudioDecoder], [AudioPlayer]
 * and `FFplayPlayer.audio` all expose it.
 *
 * Calls are safe from any thread, including the main thread, and never block; the state flows
 * hold the change by the time a call returns. Use [setVolume], [setMuted], [setTrackVolume],
 * [selectTrack] and the other extensions for single changes.
 */
public interface AudioMixControls {
    public val tracks: List<AudioTrackInfo>

    /** Master level, applied to the mix of every enabled track. */
    public val level: StateFlow<AudioLevel>

    /** One level per entry of [tracks]. */
    public val trackLevels: StateFlow<List<AudioLevel>>

    /** Tracks currently decoded and mixed. Opening enables FFmpeg's default track only. */
    public val enabledTracks: StateFlow<Set<Int>>

    /**
     * Atomic, so a volume and a mute change from different threads never lose one another.
     * [transform] may run more than once when another update races it, so keep it pure.
     */
    public fun updateLevel(transform: (AudioLevel) -> AudioLevel)

    /** Atomic, like [updateLevel], for one track's level; [transform] may likewise run more than once. */
    public fun updateTrackLevel(track: Int, transform: (AudioLevel) -> AudioLevel)

    /**
     * Adds [track] to, or removes it from, the mix. Enabling a track mid-playback joins it at the
     * current position; disabling one stops decoding it altogether. A track muted with
     * [setTrackMuted] is still decoded, so un-muting is instant.
     */
    public fun setTrackEnabled(track: Int, enabled: Boolean)

    /** Mixes exactly [selected], e.g. `selectTracks(setOf(2))` to switch language. */
    public fun selectTracks(selected: Set<Int>)
}

public fun AudioMixControls.setLevel(level: AudioLevel): Unit = updateLevel { level }

public fun AudioMixControls.setVolume(volume: Double): Unit = updateLevel { it.copy(volume = volume) }

public fun AudioMixControls.setMuted(muted: Boolean): Unit = updateLevel { it.copy(muted = muted) }

public fun AudioMixControls.setTrackLevel(track: Int, level: AudioLevel): Unit = updateTrackLevel(track) { level }

public fun AudioMixControls.setTrackVolume(track: Int, volume: Double): Unit =
    updateTrackLevel(track) { it.copy(volume = volume) }

public fun AudioMixControls.setTrackMuted(track: Int, muted: Boolean): Unit =
    updateTrackLevel(track) { it.copy(muted = muted) }

/** Plays [track] alone, replacing the current selection. */
public fun AudioMixControls.selectTrack(track: Int): Unit = selectTracks(setOf(track))

/**
 * [AudioMixControls] over the engine's native mixer, for [AudioDecoder] and for audio a player
 * bridge plays itself. Gains go out through [applyLatest], so when updates race, the newest level
 * is the one the engine is left with.
 */
@InternalFFmpegKmpApi
public class AudioMix(private val native: NativeAudioMixer) : AudioMixControls {
    override val tracks: List<AudioTrackInfo> = native.tracks.map { it.toPublic() }

    private val mutableLevel = MutableStateFlow(AudioLevel.Unchanged)
    override val level: StateFlow<AudioLevel> = mutableLevel.asStateFlow()

    private val mutableTrackLevels = MutableStateFlow(List(tracks.size) { AudioLevel.Unchanged })
    override val trackLevels: StateFlow<List<AudioLevel>> = mutableTrackLevels.asStateFlow()

    private val mutableEnabledTracks = MutableStateFlow(enabledNow())
    override val enabledTracks: StateFlow<Set<Int>> = mutableEnabledTracks.asStateFlow()

    override fun updateLevel(transform: (AudioLevel) -> AudioLevel) {
        mutableLevel.update(transform)
        applyLatest({ mutableLevel.value }) { native.setMasterGain(it.effectiveVolume.toFloat()) }
    }

    override fun updateTrackLevel(track: Int, transform: (AudioLevel) -> AudioLevel) {
        requireTrack(track)
        mutableTrackLevels.update { current -> current.toMutableList().also { it[track] = transform(it[track]) } }
        applyLatest({ mutableTrackLevels.value[track] }) { native.setTrackGain(track, it.effectiveVolume.toFloat()) }
    }

    override fun setTrackEnabled(track: Int, enabled: Boolean) {
        if (enabled) requireDecodable(track) else requireTrack(track)
        decoding("enable audio track $track") { native.setTrackEnabled(track, enabled) }
        publishEnabledTracks()
    }

    override fun selectTracks(selected: Set<Int>) {
        selected.forEach(::requireDecodable)
        decoding("select audio tracks $selected") {
            tracks.indices.forEach { track -> native.setTrackEnabled(track, track in selected) }
        }
        publishEnabledTracks()
    }

    /** Re-reads after publishing, so a change another thread made meanwhile is not left unpublished. */
    private fun publishEnabledTracks() = applyLatest(::enabledNow) { mutableEnabledTracks.value = it }

    private fun enabledNow(): Set<Int> = tracks.indices.filterTo(mutableSetOf(), native::isTrackEnabled)

    private fun requireDecodable(track: Int) {
        requireTrack(track)
        require(tracks[track].isDecodable) { "No decoder for audio track $track (${tracks[track].codec})" }
    }

    private fun requireTrack(track: Int) =
        require(track in tracks.indices) { "No audio track $track; the input has ${tracks.size}" }
}

private fun NativeAudioTrackInfo.toPublic() = AudioTrackInfo(
    index = index,
    codec = codec,
    language = language,
    title = title,
    channels = channels,
    sampleRate = sampleRate,
    isDefault = isDefault,
    isDecodable = isDecodable,
)
