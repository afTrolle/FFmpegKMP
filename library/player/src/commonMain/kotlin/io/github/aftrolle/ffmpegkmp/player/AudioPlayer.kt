// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.player

import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

public enum class PlaybackState {
    /** Ready to play; the initial state, and the state after [AudioPlayer.pause]. */
    PAUSED,
    PLAYING,

    /** Reached the end of the input. [AudioPlayer.play] restarts from the beginning. */
    ENDED,

    /** Decoding or the audio device failed; see [AudioPlayer.failure]. Resources are released. */
    FAILED,
    CLOSED,
}

/**
 * Plays an input's audio through the platform's audio output (AudioTrack on Android, Java Sound
 * on desktop, AVAudioEngine on Apple platforms), decoded and mixed by FFmpeg via [AudioDecoder].
 *
 * Volume, mute and track controls apply live, without restarting playback:
 * - [setVolume]/[setMuted] (or [setLevel]) control the master output level;
 * - [setTrackVolume]/[setTrackMuted] (or [setTrackLevel]) control each track in the mix;
 * - [selectTrack] switches to one track (e.g. another language); [setTrackEnabled] adds or
 *   removes tracks from the mix, for example music plus a commentary track.
 *
 * Controls are safe from any thread, including the main thread; they never block. State is
 * published through the [StateFlow]s below. On iOS, configure the app's `AVAudioSession`
 * category (e.g. `.playback`) before playing; the player does not change it.
 */
public class AudioPlayer internal constructor(
    private val decoder: AudioDecoder,
    private val output: PlatformAudioOutput,
    dispatcher: CoroutineDispatcher,
    private val chunkFrames: Int = DEFAULT_CHUNK_FRAMES,
) : AutoCloseable {
    public val format: PcmFormat get() = decoder.format
    public val tracks: List<AudioTrackInfo> get() = decoder.tracks
    public val duration: Duration? get() = decoder.duration

    private val mutableState = MutableStateFlow(PlaybackState.PAUSED)
    public val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private val mutablePosition = MutableStateFlow(Duration.ZERO)

    /** Playback position, compensated for the audio device's buffering. */
    public val position: StateFlow<Duration> = mutablePosition.asStateFlow()

    private val mutableLevel = MutableStateFlow(AudioLevel.Unchanged)
    public val level: StateFlow<AudioLevel> = mutableLevel.asStateFlow()

    private val mutableTrackLevels = MutableStateFlow(decoder.trackLevels)
    public val trackLevels: StateFlow<List<AudioLevel>> = mutableTrackLevels.asStateFlow()

    private val mutableEnabledTracks = MutableStateFlow(decoder.enabledTracks)
    public val enabledTracks: StateFlow<Set<Int>> = mutableEnabledTracks.asStateFlow()

    /** The error that moved the player to [PlaybackState.FAILED], if any. */
    public var failure: Throwable? = null
        private set

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        // ATOMIC: even a close() that lands before the loop first runs must reach its finally,
        // which is what releases the decoder and the audio device.
        scope.launch(start = CoroutineStart.ATOMIC) { runPlayback() }
    }

    public fun play() {
        commands.trySend(Command.Play)
    }

    public fun pause() {
        commands.trySend(Command.Pause)
    }

    /** Seeks to [position]; playback continues from there if it was playing. */
    public fun seekTo(position: Duration) {
        require(!position.isNegative()) { "Seek position must not be negative" }
        commands.trySend(Command.Seek(position))
    }

    public fun setLevel(level: AudioLevel) {
        decoder.level = level
        mutableLevel.value = level
    }

    public fun setVolume(volume: Double) {
        setLevel(mutableLevel.value.copy(volume = volume))
    }

    public fun setMuted(muted: Boolean) {
        setLevel(mutableLevel.value.copy(muted = muted))
    }

    public fun setTrackLevel(track: Int, level: AudioLevel) {
        decoder.setTrackLevel(track, level)
        mutableTrackLevels.value = decoder.trackLevels
    }

    public fun setTrackVolume(track: Int, volume: Double) {
        setTrackLevel(track, decoder.trackLevel(track).copy(volume = volume))
    }

    public fun setTrackMuted(track: Int, muted: Boolean) {
        setTrackLevel(track, decoder.trackLevel(track).copy(muted = muted))
    }

    /** Adds [track] to, or removes it from, the mix. */
    public fun setTrackEnabled(track: Int, enabled: Boolean) {
        decoder.setTrackEnabled(track, enabled)
        mutableEnabledTracks.update { current -> if (enabled) current + track else current - track }
    }

    /** Plays [track] alone, replacing the current selection. */
    public fun selectTrack(track: Int) {
        selectTracks(setOf(track))
    }

    /** Mixes exactly [selected]. */
    public fun selectTracks(selected: Set<Int>) {
        decoder.selectTracks(selected)
        mutableEnabledTracks.value = selected.toSet()
    }

    /** Stops playback and releases the decoder and audio device. The player cannot be reused. */
    override fun close() {
        if (mutableState.value == PlaybackState.CLOSED) return
        commands.close()
        // Unblock a network read so the loop observes cancellation promptly; the loop itself
        // releases the decoder and output, on its own thread, once it has stopped using them.
        decoder.abort()
        scope.cancel()
    }

    private suspend fun runPlayback() {
        val buffer = FloatArray(chunkFrames * decoder.format.channels)
        var playing = false
        var floor = Duration.ZERO
        try {
            while (scope.isActive) {
                val command = if (playing) commands.tryReceive().getOrNull() else commands.receiveCatching().getOrNull()
                if (command == null && !playing && commands.isClosedForReceive) break
                when (command) {
                    Command.Play -> if (!playing) {
                        if (mutableState.value == PlaybackState.ENDED) {
                            floor = restart(Duration.ZERO)
                        }
                        output.start()
                        playing = true
                        mutableState.value = PlaybackState.PLAYING
                    }
                    Command.Pause -> if (playing) {
                        output.pause()
                        playing = false
                        mutableState.value = PlaybackState.PAUSED
                    }
                    is Command.Seek -> {
                        floor = restart(command.position)
                        if (mutableState.value == PlaybackState.ENDED) mutableState.value = PlaybackState.PAUSED
                    }
                    null -> Unit
                }
                if (!playing) continue

                val frames = decoder.read(buffer, 0, chunkFrames)
                if (frames == 0) {
                    output.drain()
                    playing = false
                    mutablePosition.value = decoder.position
                    mutableState.value = PlaybackState.ENDED
                    continue
                }
                output.write(buffer, frames)
                val buffered = (output.latencyFrames * 1_000_000L / decoder.format.sampleRate).microseconds
                mutablePosition.value = maxOf(floor, decoder.position - buffered)
                // The write above blocks for the device; this lets cancellation and other work in.
                yield()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // close() aborts an in-flight read, which surfaces here as a decode error: that is a
            // requested shutdown, not a playback failure.
            if (scope.isActive) {
                failure = error
                mutableState.value = PlaybackState.FAILED
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { output.close() }
                runCatching { decoder.close() }
                // A failed player stays FAILED (with its failure) rather than looking closed.
                if (mutableState.value != PlaybackState.FAILED) mutableState.value = PlaybackState.CLOSED
            }
        }
    }

    /** Repositions decoding and drops audio already queued on the device. */
    private fun restart(position: Duration): Duration {
        val target = decoder.duration?.let { minOf(position, it) } ?: position
        output.flush()
        decoder.seek(target)
        mutablePosition.value = target
        return target
    }

    private sealed interface Command {
        data object Play : Command
        data object Pause : Command
        data class Seek(val position: Duration) : Command
    }

    public companion object {
        /**
         * Opens [url] (a file path or any URL the FFmpeg build's protocols accept) and prepares an
         * audio device for [format]. The player starts [PlaybackState.PAUSED]; call [play].
         */
        public suspend fun open(url: String, format: PcmFormat = PcmFormat.Default): AudioPlayer =
            withContext(playbackDispatcher) {
                val decoder = AudioDecoder.open(url, format)
                val output = try {
                    createPlatformAudioOutput(format)
                } catch (failure: Throwable) {
                    decoder.close()
                    throw AudioDecodingException("Could not open the audio output: ${failure.message}", failure)
                }
                AudioPlayer(decoder, output, playbackDispatcher)
            }
    }
}

/** About 21 ms at 48 kHz: small enough for responsive controls, large enough to be cheap. */
private const val DEFAULT_CHUNK_FRAMES = 1_024
