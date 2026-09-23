// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import cnames.structs.ffmpegkmp_player
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_abort
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_close
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_duration_us
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_open
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
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_track_stream_index
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_track_title
import kotlin.concurrent.atomics.AtomicBoolean
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

@InternalFFmpegKmpApi
public actual fun openPlatformAudioDecoder(url: String, sampleRate: Int, channels: Int): NativeAudioDecoder =
    memScoped {
        val error = alloc<IntVar>()
        val player = ffmpegkmp_player_open(url, sampleRate, channels, error.ptr)
            ?: throw NativeAudioDecoderException(
                "Could not open audio input '$url' (error ${error.value})",
                error.value,
            )
        CInteropAudioDecoder(player, sampleRate, channels)
    }

private class CInteropAudioDecoder(
    private val player: CPointer<ffmpegkmp_player>,
    override val sampleRate: Int,
    override val channels: Int,
) : NativeAudioDecoder {
    private val closed = AtomicBoolean(false)

    override val tracks: List<NativeAudioTrackInfo> = List(ffmpegkmp_player_track_count(player)) { index ->
        NativeAudioTrackInfo(
            index = index,
            streamIndex = ffmpegkmp_player_track_stream_index(player, index),
            codec = ffmpegkmp_player_track_codec(player, index).text().orEmpty(),
            language = ffmpegkmp_player_track_language(player, index).text(),
            title = ffmpegkmp_player_track_title(player, index).text(),
            channels = ffmpegkmp_player_track_channels(player, index),
            sampleRate = ffmpegkmp_player_track_sample_rate(player, index),
            isDefault = ffmpegkmp_player_track_is_default(player, index) != 0,
            isDecodable = ffmpegkmp_player_track_is_decodable(player, index) != 0,
        )
    }

    override val durationMicros: Long get() = ensureOpen().let { ffmpegkmp_player_duration_us(player) }
    override val positionMicros: Long get() = ensureOpen().let { ffmpegkmp_player_position_us(player) }

    override fun isTrackEnabled(track: Int): Boolean =
        ensureOpen().let { ffmpegkmp_player_track_enabled(player, track) != 0 }

    override fun setTrackEnabled(track: Int, enabled: Boolean) {
        ensureOpen()
        requireSuccess(ffmpegkmp_player_set_track_enabled(player, track, if (enabled) 1 else 0), "enable track $track")
    }

    override fun setTrackGain(track: Int, gain: Float) {
        ensureOpen()
        requireSuccess(ffmpegkmp_player_set_track_gain(player, track, gain), "set gain of track $track")
    }

    override fun setMasterGain(gain: Float) {
        ensureOpen()
        requireSuccess(ffmpegkmp_player_set_master_gain(player, gain), "set master gain")
    }

    override fun seek(positionMicros: Long) {
        ensureOpen()
        requireSuccess(ffmpegkmp_player_seek(player, positionMicros), "seek to ${positionMicros}us")
    }

    override fun read(destination: FloatArray, offset: Int, frames: Int): Int {
        ensureOpen()
        requireReadArguments(destination, offset, frames, channels)
        if (frames == 0) return 0
        // Decode straight into the caller's array: no intermediate native buffer or copy.
        val count = destination.usePinned { pinned ->
            ffmpegkmp_player_read(player, pinned.addressOf(offset), frames)
        }
        requireSuccess(count, "decode audio")
        return count
    }

    override fun abort() {
        if (!closed.load()) ffmpegkmp_player_abort(player)
    }

    override fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) ffmpegkmp_player_close(player)
    }

    private fun ensureOpen() = check(!closed.load()) { "The audio decoder is closed" }
}

private fun requireSuccess(result: Int, action: String) {
    if (result < 0) throw NativeAudioDecoderException("Could not $action (error $result)", result)
}

private fun CPointer<ByteVar>?.text(): String? = this?.toKString()?.takeIf(String::isNotEmpty)
