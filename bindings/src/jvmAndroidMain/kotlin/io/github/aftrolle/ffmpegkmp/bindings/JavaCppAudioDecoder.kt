// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_player
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_input_hint
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_io_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer

@InternalFFmpegKmpApi
public actual fun openPlatformAudioDecoder(
    source: NativePlayerSource,
    sampleRate: Int,
    channels: Int,
    hint: NativeInputHint?,
): NativeAudioDecoder {
    JavaCppBridgeLoader.load()
    val mounts = MountTable(source.mounts, replayableSources = true)
    val ioCallback = mountedIoCallback { mounts }
    val error = IntArray(1)
    val nativeHint = hint?.toNative()
    val player = try {
        bridge.ffmpegkmp_player_open_hinted(source.protocolInput(), ioCallback, null, sampleRate, channels, nativeHint, error)
            ?.takeUnless(ffmpegkmp_player::isNull)
    } finally {
        nativeHint?.close()
    }
    if (player == null) {
        ioCallback.close()
        throw NativeAudioDecoderException("Could not open audio input '${source.input}' (error ${error[0]})", error[0])
    }
    return GuardedAudioDecoder(JavaCppAudioEngine(player, ioCallback, channels), sampleRate, channels)
}

private class JavaCppAudioEngine(
    private val player: ffmpegkmp_player,
    /** Kept reachable for the player's lifetime: native code calls back into it. */
    private val ioCallback: ffplaykmp_io_callback,
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
        ioCallback.close()
    }
}

private fun BytePointer?.text(): String? = this?.takeUnless(BytePointer::isNull)?.string?.takeIf(String::isNotEmpty)

private const val DEFAULT_BUFFER_FRAMES = 4_096

/** An `ffplaykmp_input_hint` holding [this]; a name too long for it is cut short, and the engine then probes. */
private fun NativeInputHint.toNative(): ffplaykmp_input_hint {
    val hint = ffplaykmp_input_hint()
    bridge.ffplaykmp_input_hint_init(hint)
    val name = formatName.encodeToByteArray()
    for (index in 0 until minOf(name.size, FORMAT_NAME_CAPACITY - 1)) hint.format_name(index, name[index])
    return hint.start_us(startUs).duration_us(durationUs).audio_streams(audioStreams)
}

/** `ffplaykmp_input_hint.format_name`'s size, its terminating zero included. */
private const val FORMAT_NAME_CAPACITY = 64
