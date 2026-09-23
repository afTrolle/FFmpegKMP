// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

@InternalFFmpegKmpApi
public data class NativeAudioTrackInfo(
    /** Audio-relative index (`a:N`), the index every other decoder call takes. */
    val index: Int,
    val streamIndex: Int,
    val codec: String,
    val language: String?,
    val title: String?,
    val channels: Int,
    val sampleRate: Int,
    val isDefault: Boolean,
    val isDecodable: Boolean,
)

/**
 * The native audio decode/mix engine (`ffmpegkmp_player.c`). [read], [seek] and [close] must
 * not overlap; the gain, track-enable and [abort] calls are safe from any thread.
 */
@InternalFFmpegKmpApi
public interface NativeAudioDecoder : AutoCloseable {
    public val sampleRate: Int
    public val channels: Int
    public val tracks: List<NativeAudioTrackInfo>

    /** Negative when the input does not report a duration. */
    public val durationMicros: Long
    public val positionMicros: Long

    public fun isTrackEnabled(track: Int): Boolean
    public fun setTrackEnabled(track: Int, enabled: Boolean)
    public fun setTrackGain(track: Int, gain: Float)
    public fun setMasterGain(gain: Float)
    public fun seek(positionMicros: Long)

    /**
     * Decodes up to [frames] interleaved frames into [destination] starting at [offset];
     * returns the frame count, or 0 at the end of the input.
     */
    public fun read(destination: FloatArray, offset: Int, frames: Int): Int

    /** Unblocks an in-flight [read] on a stalled network input. */
    public fun abort()
}

@InternalFFmpegKmpApi
public class NativeAudioDecoderException(
    message: String,
    public val errorCode: Int,
) : IllegalStateException(message)

/** Opens [url] (a path or any URL this build's FFmpeg protocols accept) for audio decoding. */
@InternalFFmpegKmpApi
public expect fun openPlatformAudioDecoder(url: String, sampleRate: Int, channels: Int): NativeAudioDecoder

internal fun requireReadArguments(destination: FloatArray, offset: Int, frames: Int, channels: Int) {
    require(frames >= 0) { "Frame count must not be negative" }
    require(offset >= 0 && offset.toLong() + frames.toLong() * channels <= destination.size) {
        "Destination holds ${destination.size} samples; $frames frames of $channels channels at $offset do not fit"
    }
}
