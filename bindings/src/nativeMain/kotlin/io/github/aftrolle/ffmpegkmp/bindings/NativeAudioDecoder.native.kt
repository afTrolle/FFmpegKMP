// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.UnsafeNumber::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import cnames.structs.ffmpegkmp_player
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_abort
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_close
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_duration_us
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_open_hinted
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_input_hint
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_input_hint_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_position_us
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_read
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_seek
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_set_master_gain
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_set_track_enabled
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_set_track_gain
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_track_channels
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_track_codec
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_track_count
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_track_enabled
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_track_is_decodable
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_track_is_default
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_track_language
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_track_sample_rate
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_track_title
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

@InternalFFmpegKmpApi
public actual fun openPlatformAudioDecoder(
    source: NativePlayerSource,
    sampleRate: Int,
    channels: Int,
    hint: NativeInputHint?,
): NativeAudioDecoder =
    memScoped {
        val error = alloc<IntVar>()
        val nativeHint = hint?.let { known ->
            alloc<ffplaykmp_input_hint>().apply {
                ffplaykmp_input_hint_init(ptr)
                // A name too long for the field is cut short, and the engine then probes.
                val name = known.formatName.encodeToByteArray()
                for (index in 0 until minOf(name.size, FORMAT_NAME_CAPACITY - 1)) format_name[index] = name[index]
                start_us = known.startUs
                duration_us = known.durationUs
                audio_streams = known.audioStreams
            }
        }
        val mounts = StableRef.create(MountTable(source.mounts, replayableSources = true))
        val player = ffmpegkmp_player_open_hinted(
            source.protocolInput(),
            staticCFunction(::receiveMountedIo),
            mounts.asCPointer(),
            sampleRate,
            channels,
            nativeHint?.ptr,
            error.ptr,
        ) ?: run {
            mounts.dispose()
            throw NativeAudioDecoderException(
                "Could not open audio input '${source.input}' (error ${error.value})",
                error.value,
            )
        }
        GuardedAudioDecoder(CInteropAudioEngine(player, mounts), sampleRate, channels)
    }

private class CInteropAudioEngine(
    private val player: CPointer<ffmpegkmp_player>,
    private val mounts: StableRef<MountTable>,
) : AudioEngineCalls {
    override fun trackCount() = ffmpegkmp_player_track_count(player)
    override fun trackCodec(track: Int) = ffmpegkmp_player_track_codec(player, track).text()
    override fun trackLanguage(track: Int) = ffmpegkmp_player_track_language(player, track).text()
    override fun trackTitle(track: Int) = ffmpegkmp_player_track_title(player, track).text()
    override fun trackChannels(track: Int) = ffmpegkmp_player_track_channels(player, track)
    override fun trackSampleRate(track: Int) = ffmpegkmp_player_track_sample_rate(player, track)
    override fun trackIsDefault(track: Int) = ffmpegkmp_player_track_is_default(player, track) != 0
    override fun trackIsDecodable(track: Int) = ffmpegkmp_player_track_is_decodable(player, track) != 0
    override fun trackEnabled(track: Int) = ffmpegkmp_player_track_enabled(player, track) != 0
    override fun setTrackEnabled(track: Int, enabled: Boolean) =
        ffmpegkmp_player_set_track_enabled(player, track, if (enabled) 1 else 0)
    override fun setTrackGain(track: Int, gain: Float) = ffmpegkmp_player_set_track_gain(player, track, gain)
    override fun setMasterGain(gain: Float) = ffmpegkmp_player_set_master_gain(player, gain)
    override fun duration() = ffmpegkmp_player_duration_us(player)
    override fun position() = ffmpegkmp_player_position_us(player)
    override fun seek(positionMicros: Long) = ffmpegkmp_player_seek(player, positionMicros)

    // Decode straight into the caller's array: no intermediate native buffer or copy.
    override fun read(destination: FloatArray, offset: Int, frames: Int): Int =
        destination.usePinned { pinned -> ffmpegkmp_player_read(player, pinned.addressOf(offset), frames) }

    override fun abort() = ffmpegkmp_player_abort(player)

    override fun release() {
        ffmpegkmp_player_close(player)
        mounts.dispose()
    }
}

private fun CPointer<ByteVar>?.text(): String? = this?.toKString()?.takeIf(String::isNotEmpty)

/** `ffplaykmp_input_hint.format_name`'s size, its terminating zero included. */
private const val FORMAT_NAME_CAPACITY = 64
