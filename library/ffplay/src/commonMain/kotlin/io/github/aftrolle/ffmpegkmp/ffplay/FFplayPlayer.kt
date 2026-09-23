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
 * The native engine is accessed through [FFplayEngine], which keeps native frame handles out of
 * the public API and lets a Compose surface attach after media preparation.
 */
@OptIn(ExperimentalAtomicApi::class)
public class FFplayPlayer internal constructor(
    public val configuration: FFplayConfiguration = FFplayConfiguration(),
    engineFactory: FFplayEngineFactory,
    audioOpener: FFplayAudioOpener? = null,
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
        // In-memory inputs are not real media: never try to open their audio.
        audioOpener = if (useInMemoryEngine) ({ null }) else null,
    )

    private val closed = AtomicBoolean(false)
    private val closeCompleted = AtomicBoolean(false)
    private val prepareMutex = Mutex()
    private val operationLock = FFplayOperationLock()
    private val mutableSnapshot = MutableStateFlow(FFplaySnapshot())
    private val mutableSecureOutputRequired = MutableStateFlow(false)
    private val mutableEvents = MutableSharedFlow<FFplayEvent>(extraBufferCapacity = 16)

    // Before the engine: its callbacks reach audio as soon as it exists.
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioPlayback = audioOpener
        ?.let { FFplayAudio(audioScope, ::warnAudio, it) }
        ?: FFplayAudio(audioScope, ::warnAudio)

    /** Bumped by every prepare, stop and close; audio that finishes opening afterwards is stale. */
    private var audioGeneration = 0

    private val engine: FFplayEngine = engineFactory(
        configuration,
        ::acceptEngineUpdate,
        ::emit,
    )

    public val snapshot = mutableSnapshot.asStateFlow()
    public val events: Flow<FFplayEvent> = mutableEvents.asSharedFlow()


    /** The prepared source's audio tracks and levels; see [setVolume] and [selectAudioTrack]. */
    public val audio: StateFlow<FFplayAudioState> = audioPlayback.state
    internal val secureOutputRequired = mutableSecureOutputRequired.asStateFlow()

    public suspend fun prepare(source: FFplaySource) {
        prepareMutex.withLock {
            val generation = operationLock.withLock {
                checkOpen()
                mutableSecureOutputRequired.value =
                    source.protection == FFplayContentProtection.REQUIRE_SECURE_PATH
                mutableSnapshot.value = FFplaySnapshot(state = FFplayState.PREPARING)
                audioPlayback.close()
                ++audioGeneration
            }
            try {
                prepareOnWorker(source)
                if (configuration.audio) attachAudio(source, generation)
            } catch (cancellation: CancellationException) {
                operationLock.withLock {
                    audioPlayback.close()
                    if (!closed.load()) {
                        // Don't leave the source loaded behind an IDLE snapshot.
                        runCatching { engine.stop() }
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

    // Audio follows the engine's state transitions (see acceptEngineUpdate), not these calls.
    public fun play(): Unit = operationLock.withLock {
        checkOpen()
        engine.play()
    }

    public fun pause(): Unit = operationLock.withLock {
        checkOpen()
        engine.pause()
    }

    public fun seekTo(position: Duration): Unit = operationLock.withLock {
        checkOpen()
        require(!position.isNegative()) { "Seek position must not be negative" }
        engine.seekTo(position)
    }

    public fun stop(): Unit = operationLock.withLock {
        checkOpen()
        audioGeneration++
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
            audioGeneration++
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
        if (closed.load()) return
        mutableSnapshot.value = snapshot
        audioPlayback.followEngine(snapshot)
    }

    /**
     * Opens the source's audio (blocking, so outside the lock), then attaches it only if no stop,
     * close or newer prepare happened meanwhile; otherwise it is closed rather than leaked.
     */
    private suspend fun attachAudio(source: FFplaySource, generation: Int) {
        val audio = audioPlayback.load(source) ?: return
        val attached = operationLock.withLock {
            if (closed.load() || generation != audioGeneration) return@withLock false
            audioPlayback.attach(audio, mutableSnapshot.value, ::reportAudibleClock)
            true
        }
        if (!attached) audio.close()
    }

    /** Filtering and forwarding share the lock with seekTo, so a pre-seek report can't land after it. */
    private fun reportAudibleClock(position: Duration) = operationLock.withLock {
        if (!closed.load()) audioPlayback.clockFor(position)?.let(engine::setMasterClock)
    }

    private fun warnAudio(message: String) = emit(FFplayEvent.Warning(message))

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
