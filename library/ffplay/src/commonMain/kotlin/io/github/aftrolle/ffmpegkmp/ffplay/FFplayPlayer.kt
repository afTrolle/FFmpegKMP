// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * State-driven owner for one FFplay engine instance.
 *
 * The native engine is intentionally accessed through [FFplayEngine]. This keeps SDL and native
 * frame handles out of the public API and lets a Compose surface attach after media preparation.
 */
@OptIn(ExperimentalAtomicApi::class)
public class FFplayPlayer internal constructor(
    public val configuration: FFplayConfiguration = FFplayConfiguration(),
    engineFactory: FFplayEngineFactory,
) : AutoCloseable {
    public constructor(
        configuration: FFplayConfiguration = FFplayConfiguration(),
    ) : this(configuration, ::createPlatformFFplayEngine)

    internal constructor(
        configuration: FFplayConfiguration,
        useInMemoryEngine: Boolean,
    ) : this(
        configuration,
        if (useInMemoryEngine) ::createInMemoryFFplayEngine else ::createPlatformFFplayEngine,
    )

    private val closed = AtomicBoolean(false)
    private val closeCompleted = AtomicBoolean(false)
    private val prepareMutex = Mutex()
    private val operationLock = FFplayOperationLock()
    private val mutableSnapshot = MutableStateFlow(FFplaySnapshot())
    private val mutableSecureOutputRequired = MutableStateFlow(false)
    private val mutableEvents = MutableSharedFlow<FFplayEvent>(extraBufferCapacity = 16)
    private val engine: FFplayEngine = engineFactory(
        configuration,
        ::acceptEngineUpdate,
        ::emit,
    )

    public val snapshot = mutableSnapshot.asStateFlow()
    public val events: Flow<FFplayEvent> = mutableEvents.asSharedFlow()

    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioPlayback = FFplayAudio(
        scope = audioScope,
        setMasterClock = { mediaTimeUs ->
            operationLock.withLock { if (!closed.load()) engine.setMasterClock(mediaTimeUs) }
        },
        warn = { message -> emit(FFplayEvent.Warning(message)) },
    )

    /** The prepared source's audio tracks and levels; see [setVolume] and [selectAudioTrack]. */
    public val audio: StateFlow<FFplayAudioState> = audioPlayback.state
    internal val secureOutputRequired = mutableSecureOutputRequired.asStateFlow()

    public suspend fun prepare(source: FFplaySource) {
        prepareMutex.withLock {
            operationLock.withLock {
                checkOpen()
                mutableSecureOutputRequired.value =
                    source.protection == FFplayContentProtection.REQUIRE_SECURE_PATH
                mutableSnapshot.value = FFplaySnapshot(state = FFplayState.PREPARING)
                audioPlayback.close()
            }
            try {
                prepareOnWorker(source)
                if (configuration.audio) audioPlayback.open(source)
            } catch (cancellation: CancellationException) {
                audioPlayback.close()
                operationLock.withLock {
                    if (!closed.load()) {
                        mutableSecureOutputRequired.value = false
                        mutableSnapshot.value = FFplaySnapshot(state = FFplayState.IDLE)
                    }
                }
                throw cancellation
            } catch (failure: Throwable) {
                operationLock.withLock {
                    if (!closed.load()) {
                        mutableSecureOutputRequired.value = false
                        fail(
                            "Unable to prepare ${source.input}: " +
                                (failure.message ?: failure::class.simpleName),
                            failure,
                        )
                    }
                }
                audioPlayback.close()
                throw failure
            }
        }
    }

    public fun play(): Unit = operationLock.withLock {
        checkOpen()
        // Replaying after the end restarts the video from zero; the audio must follow it there.
        val fromStart = mutableSnapshot.value.state == FFplayState.ENDED
        engine.play()
        audioPlayback.play(fromStart)
    }

    public fun pause(): Unit = operationLock.withLock {
        checkOpen()
        engine.pause()
        audioPlayback.pause()
    }

    public fun seekTo(position: Duration): Unit = operationLock.withLock {
        checkOpen()
        require(!position.isNegative()) { "Seek position must not be negative" }
        engine.seekTo(position)
        audioPlayback.seekTo(position)
    }

    public fun stop(): Unit = operationLock.withLock {
        checkOpen()
        audioPlayback.close()
        engine.stop()
        mutableSecureOutputRequired.value = false
    }

    /** Sets the master audio level; it carries over to later sources. */
    public fun setAudioLevel(level: AudioLevel) {
        audioPlayback.setLevel(level)
    }

    public fun setVolume(volume: Double) {
        setAudioLevel(audio.value.level.copy(volume = volume))
    }

    public fun setMuted(muted: Boolean) {
        setAudioLevel(audio.value.level.copy(muted = muted))
    }

    /** Sets one audio track's level in the mix (audio-relative index, as in [FFplayAudioState.tracks]). */
    public fun setAudioTrackLevel(track: Int, level: AudioLevel) {
        audioPlayback.setTrackLevel(track, level)
    }

    public fun setAudioTrackVolume(track: Int, volume: Double) {
        val current = audio.value.trackLevels.getOrNull(track) ?: return
        setAudioTrackLevel(track, current.copy(volume = volume))
    }

    public fun setAudioTrackMuted(track: Int, muted: Boolean) {
        val current = audio.value.trackLevels.getOrNull(track) ?: return
        setAudioTrackLevel(track, current.copy(muted = muted))
    }

    /** Plays [track] alone, e.g. to switch language. */
    public fun selectAudioTrack(track: Int) {
        audioPlayback.selectTracks(setOf(track))
    }

    /** Adds [track] to, or removes it from, the mix — e.g. commentary over the main audio. */
    public fun setAudioTrackEnabled(track: Int, enabled: Boolean) {
        audioPlayback.setTrackEnabled(track, enabled)
    }

    internal fun attachOutput(output: FFplayVideoOutput): Unit = operationLock.withLock {
        checkOpen()
        engine.attachOutput(output)
    }

    internal fun detachOutput(output: FFplayVideoOutput): Unit = operationLock.withLock {
        if (!closed.load()) engine.detachOutput(output)
    }

    internal fun requestClose() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        // Cancellation is the one operation intentionally allowed to race an active native call.
        engine.cancel()
    }

    override fun close() {
        requestClose()
        // The operation lock prevents destruction until an active native call has observed
        // cancellation. Multiple callers may wait here, but only one destroys the engine.
        operationLock.withLock {
            if (!closeCompleted.compareAndSet(expectedValue = false, newValue = true)) {
                return@withLock
            }
            // Audio reads the same mounted input; release it before the engine closes that input.
            audioPlayback.close()
            audioScope.cancel()
            engine.close()
            mutableSecureOutputRequired.value = false
            mutableSnapshot.value = mutableSnapshot.value.copy(state = FFplayState.CLOSED)
        }
    }

    private suspend fun prepareOnWorker(source: FFplaySource): Unit = coroutineScope {
        val preparation = async(Dispatchers.Default) {
            operationLock.withLock {
                checkOpen()
                engine.resetCancellation()
                // close() may have raced the reset. Checking again guarantees its cancellation
                // cannot be cleared immediately before entering a blocking native prepare.
                checkOpen()
                engine.prepare(source)
                checkOpen()
            }
            engine.awaitPreparation()
            operationLock.withLock { checkOpen() }
        }
        try {
            preparation.await()
        } catch (cancellation: CancellationException) {
            engine.cancel()
            withContext(NonCancellable) { preparation.join() }
            throw cancellation
        }
    }

    private fun acceptEngineUpdate(snapshot: FFplaySnapshot) {
        if (!closed.load()) mutableSnapshot.value = snapshot
    }

    private fun emit(event: FFplayEvent) {
        if (!closed.load()) mutableEvents.tryEmit(event)
    }

    private fun fail(message: String, cause: Throwable) {
        val failure = FFplayFailure(message, cause)
        mutableSnapshot.value = mutableSnapshot.value.copy(state = FFplayState.FAILED, failure = failure)
        emit(FFplayEvent.Fatal(message, cause))
    }

    private fun checkOpen() {
        check(!closed.load()) { "FFplayPlayer is closed" }
    }
}
