// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_io_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_player
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge
import okio.FileHandle
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.Pointer

@InternalFFmpegKmpApi
public actual fun openPlatformAudioDecoder(url: String, sampleRate: Int, channels: Int): NativeAudioDecoder {
    JavaCppBridgeLoader.load()
    val error = IntArray(1)
    val player = bridge.ffmpegkmp_player_open(url, sampleRate, channels, error)
        ?.takeUnless(ffmpegkmp_player::isNull)
        ?: throw NativeAudioDecoderException("Could not open audio input '$url' (error ${error[0]})", error[0])
    return GuardedAudioDecoder(JavaCppAudioEngine(player, null, channels), sampleRate, channels)
}

@InternalFFmpegKmpApi
public actual fun openPlatformAudioDecoder(fileHandle: FileHandle, sampleRate: Int, channels: Int): NativeAudioDecoder {
    JavaCppBridgeLoader.load()
    val reader = FileHandleReader(fileHandle)
    val callback = object : ffmpegkmp_io_callback() {
        override fun call(
            opaque: Pointer?,
            resourceId: Long,
            operation: Int,
            offset: Long,
            data: BytePointer?,
            size: Long,
        ): Long = when (operation) {
            AUDIO_IO_READ -> if (data == null || size !in 0..Int.MAX_VALUE) -1L else {
                reader.read(offset, size.toInt()) { bytes, count -> data.put(bytes, 0, count) }
            }
            AUDIO_IO_SIZE -> reader.size()
            else -> -1L
        }
    }
    val error = IntArray(1)
    val player = bridge.ffmpegkmp_player_open_io(callback, null, 0L, sampleRate, channels, error)
        ?.takeUnless(ffmpegkmp_player::isNull)
    if (player == null) {
        callback.close()
        throw NativeAudioDecoderException("Could not open the audio input (error ${error[0]})", error[0])
    }
    return GuardedAudioDecoder(JavaCppAudioEngine(player, callback, channels), sampleRate, channels)
}

private class JavaCppAudioEngine(
    private val player: ffmpegkmp_player,
    /** Kept reachable for the player's lifetime: native code calls back into it. */
    private val ioCallback: ffmpegkmp_io_callback?,
    private val channels: Int,
) : AudioEngineCalls {
    /** Reused across reads so steady-state decoding allocates nothing off-heap. */
    private var pcm = FloatPointer(DEFAULT_BUFFER_FRAMES.toLong() * channels)

    override fun trackCount() = bridge.ffmpegkmp_player_track_count(player)
    override fun trackCodec(track: Int) = bridge.ffmpegkmp_player_track_codec(player, track).text()
    override fun trackLanguage(track: Int) = bridge.ffmpegkmp_player_track_language(player, track).text()
    override fun trackTitle(track: Int) = bridge.ffmpegkmp_player_track_title(player, track).text()
    override fun trackChannels(track: Int) = bridge.ffmpegkmp_player_track_channels(player, track)
    override fun trackSampleRate(track: Int) = bridge.ffmpegkmp_player_track_sample_rate(player, track)
    override fun trackIsDefault(track: Int) = bridge.ffmpegkmp_player_track_is_default(player, track) != 0
    override fun trackIsDecodable(track: Int) = bridge.ffmpegkmp_player_track_is_decodable(player, track) != 0
    override fun trackEnabled(track: Int) = bridge.ffmpegkmp_player_track_enabled(player, track) != 0
    override fun setTrackEnabled(track: Int, enabled: Boolean) =
        bridge.ffmpegkmp_player_set_track_enabled(player, track, if (enabled) 1 else 0)
    override fun setTrackGain(track: Int, gain: Float) = bridge.ffmpegkmp_player_set_track_gain(player, track, gain)
    override fun setMasterGain(gain: Float) = bridge.ffmpegkmp_player_set_master_gain(player, gain)
    override fun duration() = bridge.ffmpegkmp_player_duration_us(player)
    override fun position() = bridge.ffmpegkmp_player_position_us(player)
    override fun seek(positionMicros: Long) = bridge.ffmpegkmp_player_seek(player, positionMicros)

    override fun read(destination: FloatArray, offset: Int, frames: Int): Int {
        val samples = frames.toLong() * channels
        if (pcm.capacity() < samples) {
            pcm.close()
            pcm = FloatPointer(samples)
        }
        val count = bridge.ffmpegkmp_player_read(player, pcm, frames)
        if (count > 0) pcm.position(0L).get(destination, offset, count * channels)
        return count
    }

    override fun abort() = bridge.ffmpegkmp_player_abort(player)

    override fun release() {
        bridge.ffmpegkmp_player_close(player)
        pcm.close()
        ioCallback?.close()
    }
}

private fun BytePointer?.text(): String? = this?.takeUnless(BytePointer::isNull)?.string?.takeIf(String::isNotEmpty)

private const val DEFAULT_BUFFER_FRAMES = 4_096
