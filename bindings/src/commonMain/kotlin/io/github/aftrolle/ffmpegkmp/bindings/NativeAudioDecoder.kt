// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import okio.FileHandle

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

/** Opens a random-access [fileHandle], read on demand; the decoder does not close it. */
@InternalFFmpegKmpApi
public expect fun openPlatformAudioDecoder(fileHandle: FileHandle, sampleRate: Int, channels: Int): NativeAudioDecoder

/** Serves the engine's READ/SIZE callbacks from an Okio file handle, reusing one buffer. */
internal class FileHandleReader(private val fileHandle: FileHandle) {
    private var buffer = ByteArray(0)

    /** Returns the byte count handed to [copyOut], 0 at end of file, or -1 on failure. */
    fun read(offset: Long, size: Int, copyOut: (ByteArray, Int) -> Unit): Long = try {
        if (buffer.size < size) buffer = ByteArray(size)
        val count = fileHandle.read(offset, buffer, 0, size)
        if (count > 0) copyOut(buffer, count)
        if (count < 0) 0L else count.toLong()
    } catch (_: Throwable) {
        -1L
    }

    fun size(): Long = try {
        fileHandle.size()
    } catch (_: Throwable) {
        -1L
    }
}

internal const val AUDIO_IO_READ: Int = 1
internal const val AUDIO_IO_SIZE: Int = 3

internal fun requireReadArguments(destination: FloatArray, offset: Int, frames: Int, channels: Int) {
    require(frames >= 0) { "Frame count must not be negative" }
    require(offset >= 0 && offset.toLong() + frames.toLong() * channels <= destination.size) {
        "Destination holds ${destination.size} samples; $frames frames of $channels channels at $offset do not fit"
    }
}
