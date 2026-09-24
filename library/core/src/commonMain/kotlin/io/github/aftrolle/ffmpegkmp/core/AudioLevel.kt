// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

/**
 * Loudness applied to one audio track or to a whole mix.
 *
 * The same model drives encoding (the `volume` filter FFmpeg applies while transcoding) and
 * playback (the gain the player applies live), so a level chosen in a preview renders
 * identically in an export. [volume] is a linear amplitude factor: `1.0` leaves samples
 * unchanged, `0.5` is roughly -6 dB and values above `1.0` amplify and may clip. [muted]
 * silences the track without forgetting its volume, so un-muting restores it.
 */
public data class AudioLevel(
    val volume: Double = 1.0,
    val muted: Boolean = false,
) {
    init {
        require(volume.isFinite() && volume >= 0.0) { "Audio volume must be a finite, non-negative factor: $volume" }
    }

    /** The factor actually applied to samples: [volume], or `0.0` while [muted]. */
    val effectiveVolume: Double get() = if (muted) 0.0 else volume

    public companion object {
        public val Unchanged: AudioLevel = AudioLevel()
        public val Muted: AudioLevel = AudioLevel(muted = true)
    }
}
