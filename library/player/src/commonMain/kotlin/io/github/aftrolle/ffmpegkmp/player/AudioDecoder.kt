// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.aftrolle.ffmpegkmp.player

import io.github.aftrolle.ffmpegkmp.bindings.DecoderThread
import io.github.aftrolle.ffmpegkmp.bindings.NativeAudioDecoder
import io.github.aftrolle.ffmpegkmp.bindings.NativeAudioDecoderException
import io.github.aftrolle.ffmpegkmp.bindings.NativeBridgeUnavailableException
import io.github.aftrolle.ffmpegkmp.bindings.openPlatformAudioDecoder
import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import io.github.aftrolle.ffmpegkmp.core.FFmpegKmpException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.update
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileHandle

/** Interleaved 32-bit float PCM, the format [AudioDecoder] produces and [AudioPlayer] plays. */
public data class PcmFormat(
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
) {
    init {
        require(sampleRate in 8_000..384_000) { "Sample rate must be between 8 kHz and 384 kHz: $sampleRate" }
        require(channels in 1..8) { "Channel count must be between 1 and 8: $channels" }
    }

    public companion object {
        public val Default: PcmFormat = PcmFormat()
    }
}

/** One audio track of an input. [index] is audio-relative (`a:N`), matching `mapAudio(track = N)`. */
public data class AudioTrackInfo(
    val index: Int,
    val codec: String,
    val language: String?,
    val title: String?,
    val channels: Int,
    val sampleRate: Int,
    val isDefault: Boolean,
    /** False when this FFmpeg build has no decoder for [codec]; such tracks cannot be enabled. */
    val isDecodable: Boolean,
)

public class AudioDecodingException(message: String, cause: Throwable? = null) : FFmpegKmpException(message, cause)

/**
 * Decodes an input's audio tracks into interleaved float PCM, mixing every enabled track at its
 * own [AudioLevel] and then applying the master [level]. This is the engine behind
 * [AudioPlayer]; use it directly to feed your own audio pipeline, analyse audio, or render PCM.
 *
 * A mix sums tracks the same way the filters module's `FilterGraph.Builder.mixAudio` does with its
 * default `normalize = false`, and levels are the same [AudioLevel]s `FFmpegCommand.Builder.audioLevel`
 * takes, so a preview and an export sound the same. The mix is clipped to [-1, 1].
 *
 * It is built on FFmpeg's libraries directly, not the ffmpeg command line, so it runs
 * alongside `FFmpegClient`/`FFprobeClient` commands instead of queueing behind them.
 *
 * Threading: each decoder decodes on a thread of its own, and [read] and [seek] suspend while it
 * works, one at a time: calls from several coroutines queue in order. A cancelled call returns at
 * once and its native work finishes on the decoder's thread; a cancelled read's samples are
 * dropped and never reach its array. Level and track changes are safe from any thread and take
 * effect on the next [read].
 */
public class AudioDecoder internal constructor(
    private val native: NativeAudioDecoder,
    /** Null runs the native calls on the caller's thread, as the tests' fakes need. */
    private val thread: DecoderThread?,
) : AutoCloseable {
    public val format: PcmFormat = PcmFormat(native.sampleRate, native.channels)

    public val tracks: List<AudioTrackInfo> = native.tracks.map { track ->
        AudioTrackInfo(
            index = track.index,
            codec = track.codec,
            language = track.language,
            title = track.title,
            channels = track.channels,
            sampleRate = track.sampleRate,
            isDefault = track.isDefault,
            isDecodable = track.isDecodable,
        )
    }

    private val masterLevel = AtomicReference(AudioLevel.Unchanged)
    private val levels = AtomicReference(List(tracks.size) { AudioLevel.Unchanged })

    /** Null when the input does not report a duration (live streams, some raw formats). */
    public val duration: Duration? = native.durationMicros.takeIf { it >= 0 }?.microseconds

    /** Media time of the next frame [read] returns, once the calls before have returned. */
    public val position: Duration get() = native.positionMicros.microseconds

    private val calls = Mutex()
    private val closed = AtomicBoolean(false)

    /** The decoder's thread decodes here and [read] copies out, so a cancelled read never writes into the caller's array. */
    private var decoded = FloatArray(0)

    /** Master level, applied to the mix of every enabled track. */
    public var level: AudioLevel
        get() = masterLevel.load()
        set(value) {
            masterLevel.store(value)
            native.setMasterGain(value.effectiveVolume.toFloat())
        }

    public val trackLevels: List<AudioLevel> get() = levels.load()

    public fun trackLevel(track: Int): AudioLevel = levels.load()[requireTrack(track)]

    public fun setTrackLevel(track: Int, level: AudioLevel) {
        requireTrack(track)
        levels.update { current -> current.toMutableList().also { it[track] = level } }
        native.setTrackGain(track, level.effectiveVolume.toFloat())
    }

    /** Tracks currently decoded and mixed. Opening enables FFmpeg's default track only. */
    public val enabledTracks: Set<Int> get() = tracks.indices.filterTo(mutableSetOf(), native::isTrackEnabled)

    /**
     * Adds or removes [track] from the mix. Enabling a track mid-playback joins it at the current
     * position; disabling one stops decoding it altogether. A track muted with [setTrackLevel]
     * is still decoded, so un-muting is instant.
     */
    public fun setTrackEnabled(track: Int, enabled: Boolean) {
        if (enabled) requireDecodable(track) else requireTrack(track)
        decoding("enable audio track $track") { native.setTrackEnabled(track, enabled) }
    }

    /** Mixes exactly [selected], e.g. `selectTracks(setOf(2))` to switch language. */
    public fun selectTracks(selected: Set<Int>) {
        selected.forEach(::requireDecodable)
        decoding("select audio tracks $selected") {
            tracks.indices.forEach { track -> native.setTrackEnabled(track, track in selected) }
        }
    }

    /** Seeks so the next [read] starts at [position], sample-accurately. */
    public suspend fun seek(position: Duration) {
        require(!position.isNegative()) { "Seek position must not be negative" }
        call("seek to $position") { native.seek(position.inWholeMicroseconds) }
    }

    /**
     * Decodes up to [frames] frames of interleaved PCM into [destination] from [offset], suspending
     * while the decoder's thread works. Returns the number of frames written (each
     * [PcmFormat.channels] samples), or 0 once the input has ended. Silence is returned while every
     * track is disabled.
     */
    public suspend fun read(
        destination: FloatArray,
        offset: Int = 0,
        frames: Int = (destination.size - offset) / format.channels,
    ): Int {
        val samples = frames * format.channels
        require(offset >= 0 && frames >= 0 && offset + samples <= destination.size) {
            "$frames frames from $offset do not fit an array of ${destination.size} samples"
        }
        if (thread == null) return call("decode audio") { native.read(destination, offset, frames) }
        return calls.withLock {
            val buffer = decoded.takeIf { it.size >= samples } ?: FloatArray(samples).also { decoded = it }
            val count = onThread("decode audio") { native.read(buffer, 0, frames) }
            buffer.copyInto(destination, offset, 0, count * format.channels)
            count
        }
    }

    /** Closes the decoder once a call still running has returned, without waiting on one that is stuck. */
    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        if (thread == null) return native.close()
        native.abort()
        thread.finish(CLOSE_BOUND) { native.close() }
    }

    internal fun abort() {
        native.abort()
    }

    private suspend fun <T> call(action: String, block: () -> T): T {
        if (thread == null) return decoding(action, block)
        return calls.withLock { onThread(action, block) }
    }

    private suspend fun <T> onThread(action: String, block: () -> T): T {
        check(!closed.load()) { "The audio decoder is closed" }
        return decoding(action) { checkNotNull(thread).submit(block).await() }
    }

    private fun requireDecodable(track: Int) {
        requireTrack(track)
        require(tracks[track].isDecodable) { "No decoder for audio track $track (${tracks[track].codec})" }
    }

    private fun requireTrack(track: Int): Int {
        require(track in tracks.indices) { "No audio track $track; the input has ${tracks.size}" }
        return track
    }

    public companion object {
        /**
         * Opens [url] — a file path or any URL the FFmpeg build's protocols accept — for audio
         * decoding, probing the input on the decoder's thread.
         */
        public suspend fun open(url: String, format: PcmFormat = PcmFormat.Default): AudioDecoder {
            require(url.isNotBlank()) { "Audio URL must not be blank" }
            return open("open '$url'") { openPlatformAudioDecoder(url, format.sampleRate, format.channels) }
        }

        /**
         * Opens a random-access [fileHandle] — for example an Android content URI, or media
         * already in memory. The decoder reads it on demand and does not close it.
         */
        public suspend fun open(fileHandle: FileHandle, format: PcmFormat = PcmFormat.Default): AudioDecoder =
            open("open the file handle") { openPlatformAudioDecoder(fileHandle, format.sampleRate, format.channels) }

        /**
         * Probing cannot be interrupted, so it runs to completion; if the caller was cancelled
         * meanwhile, the decoder it opened is closed instead of leaked.
         */
        private suspend fun open(action: String, create: () -> NativeAudioDecoder): AudioDecoder {
            val thread = DecoderThread("FFmpegKMP AudioDecoder")
            val native = try {
                withContext(NonCancellable) { thread.submit { decoding(action, create) }.await() }
            } catch (failure: Throwable) {
                thread.finish(Duration.ZERO) {}
                throw failure
            }
            val decoder = AudioDecoder(native, thread)
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                decoder.close()
                throw cancelled
            }
            return decoder
        }
    }
}

/** How long [AudioDecoder.close] waits for the native close queued behind a stuck call. */
private val CLOSE_BOUND = 1.seconds

private inline fun <T> decoding(action: String, block: () -> T): T = try {
    block()
} catch (failure: NativeAudioDecoderException) {
    throw AudioDecodingException("Could not $action: ${failure.message}", failure)
} catch (failure: NativeBridgeUnavailableException) {
    throw AudioDecodingException(failure.message ?: "The FFmpegKMP native runtime is unavailable", failure)
}
