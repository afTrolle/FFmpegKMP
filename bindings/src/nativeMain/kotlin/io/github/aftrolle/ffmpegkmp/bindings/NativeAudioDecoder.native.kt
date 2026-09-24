// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import cnames.structs.ffmpegkmp_player
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_abort
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_close
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_duration_us
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_open
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_player_open_io
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
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.FileHandle
import platform.posix.memcpy

@InternalFFmpegKmpApi
public actual fun openPlatformAudioDecoder(url: String, sampleRate: Int, channels: Int): NativeAudioDecoder =
    memScoped {
        val error = alloc<IntVar>()
        val player = ffmpegkmp_player_open(url, sampleRate, channels, error.ptr)
            ?: throw NativeAudioDecoderException("Could not open audio input '$url' (error ${error.value})", error.value)
        GuardedAudioDecoder(CInteropAudioEngine(player, null), sampleRate, channels)
    }

@InternalFFmpegKmpApi
public actual fun openPlatformAudioDecoder(fileHandle: FileHandle, sampleRate: Int, channels: Int): NativeAudioDecoder =
    memScoped {
        val error = alloc<IntVar>()
        val reader = StableRef.create(FileHandleReader(fileHandle))
        val player = ffmpegkmp_player_open_io(
            staticCFunction(::receiveAudioIo),
            reader.asCPointer(),
            0L,
            sampleRate,
            channels,
            error.ptr,
        ) ?: run {
            reader.dispose()
            throw NativeAudioDecoderException("Could not open the audio input (error ${error.value})", error.value)
        }
        GuardedAudioDecoder(CInteropAudioEngine(player, reader), sampleRate, channels)
    }

private fun receiveAudioIo(
    opaque: COpaquePointer?,
    resourceId: Long,
    operation: Int,
    offset: Long,
    data: CPointer<UByteVar>?,
    size: ULong,
): Long {
    val reader = opaque?.asStableRef<FileHandleReader>()?.get() ?: return -1L
    return when (operation) {
        AUDIO_IO_READ -> if (data == null || size > Int.MAX_VALUE.toULong()) -1L else {
            reader.read(offset, size.toInt()) { bytes, count ->
                bytes.usePinned { pinned -> memcpy(data, pinned.addressOf(0), count.convert()) }
            }
        }
        AUDIO_IO_SIZE -> reader.size()
        else -> -1L
    }
}

private class CInteropAudioEngine(
    private val player: CPointer<ffmpegkmp_player>,
    private val reader: StableRef<FileHandleReader>?,
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
        reader?.dispose()
    }
}

private fun CPointer<ByteVar>?.text(): String? = this?.toKString()?.takeIf(String::isNotEmpty)
