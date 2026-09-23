// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_player
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer

@InternalFFmpegKmpApi
public actual fun openPlatformAudioDecoder(url: String, sampleRate: Int, channels: Int): NativeAudioDecoder {
    JavaCppBridgeLoader.load()
    val error = IntArray(1)
    val player = bridge.ffmpegkmp_player_open(url, sampleRate, channels, error)
        ?.takeUnless(ffmpegkmp_player::isNull)
        ?: throw NativeAudioDecoderException("Could not open audio input '$url' (error ${error[0]})", error[0])
    return JavaCppAudioDecoder(player, sampleRate, channels)
}

private class JavaCppAudioDecoder(
    private val player: ffmpegkmp_player,
    override val sampleRate: Int,
    override val channels: Int,
) : NativeAudioDecoder {
    @Volatile
    private var closed = false

    /** Reused across reads so steady-state decoding allocates nothing off-heap. */
    private var pcm = FloatPointer(DEFAULT_BUFFER_FRAMES.toLong() * channels)

    override val tracks: List<NativeAudioTrackInfo> = List(bridge.ffmpegkmp_player_track_count(player)) { index ->
        NativeAudioTrackInfo(
            index = index,
            streamIndex = bridge.ffmpegkmp_player_track_stream_index(player, index),
            codec = bridge.ffmpegkmp_player_track_codec(player, index).text().orEmpty(),
            language = bridge.ffmpegkmp_player_track_language(player, index).text(),
            title = bridge.ffmpegkmp_player_track_title(player, index).text(),
            channels = bridge.ffmpegkmp_player_track_channels(player, index),
            sampleRate = bridge.ffmpegkmp_player_track_sample_rate(player, index),
            isDefault = bridge.ffmpegkmp_player_track_is_default(player, index) != 0,
            isDecodable = bridge.ffmpegkmp_player_track_is_decodable(player, index) != 0,
        )
    }

    override val durationMicros: Long get() = ensureOpen().let { bridge.ffmpegkmp_player_duration_us(player) }
    override val positionMicros: Long get() = ensureOpen().let { bridge.ffmpegkmp_player_position_us(player) }

    override fun isTrackEnabled(track: Int): Boolean =
        ensureOpen().let { bridge.ffmpegkmp_player_track_enabled(player, track) != 0 }

    override fun setTrackEnabled(track: Int, enabled: Boolean) {
        ensureOpen()
        requireSuccess(bridge.ffmpegkmp_player_set_track_enabled(player, track, if (enabled) 1 else 0), "enable track $track")
    }

    override fun setTrackGain(track: Int, gain: Float) {
        ensureOpen()
        requireSuccess(bridge.ffmpegkmp_player_set_track_gain(player, track, gain), "set gain of track $track")
    }

    override fun setMasterGain(gain: Float) {
        ensureOpen()
        requireSuccess(bridge.ffmpegkmp_player_set_master_gain(player, gain), "set master gain")
    }

    override fun seek(positionMicros: Long) {
        ensureOpen()
        requireSuccess(bridge.ffmpegkmp_player_seek(player, positionMicros), "seek to ${positionMicros}us")
    }

    override fun read(destination: FloatArray, offset: Int, frames: Int): Int {
        ensureOpen()
        requireReadArguments(destination, offset, frames, channels)
        if (frames == 0) return 0
        val samples = frames.toLong() * channels
        if (pcm.capacity() < samples) {
            pcm.close()
            pcm = FloatPointer(samples)
        }
        val count = bridge.ffmpegkmp_player_read(player, pcm, frames)
        requireSuccess(count, "decode audio")
        pcm.position(0L).get(destination, offset, count * channels)
        return count
    }

    override fun abort() {
        if (!closed) bridge.ffmpegkmp_player_abort(player)
    }

    override fun close() {
        if (closed) return
        closed = true
        bridge.ffmpegkmp_player_close(player)
        pcm.close()
    }

    private fun ensureOpen() = check(!closed) { "The audio decoder is closed" }
}

private fun requireSuccess(result: Int, action: String) {
    if (result < 0) throw NativeAudioDecoderException("Could not $action (error $result)", result)
}

private fun BytePointer?.text(): String? = this?.takeUnless(BytePointer::isNull)?.string?.takeIf(String::isNotEmpty)

private const val DEFAULT_BUFFER_FRAMES = 4_096
