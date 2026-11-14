// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.core.ExperimentalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import io.github.aftrolle.ffmpegkmp.player.AudioMixControls
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * State-driven owner for one FFplay engine instance.
 *
 * The native engine is accessed through [FFplayEngine], which keeps native frame handles out of
 * the public API and lets a Compose surface attach after media preparation.
 *
 * Controls never wait for a [prepare] that is opening its input: calls made meanwhile are applied
 * in order once it returns, and playback calls are dropped if it fails.
 */
@OptIn(ExperimentalAtomicApi::class, io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)
public class FFplayPlayer internal constructor(
    public val configuration: FFplayConfiguration = FFplayConfiguration(),
    engineFactory: FFplayEngineFactory,
    audioOpener: FFplayAudioOpener? = null,
) : AutoCloseable {
    public constructor(
        configuration: FFplayConfiguration = FFplayConfiguration(),
    ) : this(configuration, ::createPlatformFFplayEngine)

    private val closed = AtomicBoolean(false)
    private val closeCompleted = AtomicBoolean(false)
    private val prepareMutex = Mutex()
    private val operationLock = FFplayOperationLock()

    /**
     * Held for a blocking native prepare, which may also present its preview frame on the attached
     * output. Only close and releasing a native surface wait for it; other commands are queued.
     * Always taken before [operationLock].
     */
    private val prepareLock = FFplayOperationLock()

    /** Guarded by [operationLock]. */
    private var preparing = false

    /** The clock the player follows; guarded by [operationLock]. */
    private var following: FFplayClock? = null

    /**
     * Bumped by every [follow] and by close: a sample is applied, and a seek for it made, only under
     * the generation it was taken in, so one from a refresh still running when the clock changed never
     * reaches the next clock's follower.
     */
    private val followGeneration = AtomicInt(0)
    private var followJob: Job? = null
    private var seekJob: Job? = null

    /** The followed clock's latest position, as the refresh hands it over: applied as it comes, older ones skipped. */
    private val clockSamples = MutableStateFlow<TaggedSample?>(null)

    /** The seek the follower wants next, a newer one replacing it; null when none. */
    private val followSeeks = MutableStateFlow<FollowSeek?>(null)

    /** Set when the follower asks for a seek and cleared once it is done, so it is asked for once. */
    private val followSeeking = AtomicBoolean(false)

    /**
     * Whether the last seek for the followed clock left the run that decodes ahead on. The snapshot
     * says so too, but a refresh's publish taken before the run started can land after it.
     */
    private val followRunOn = AtomicBoolean(false)

    /** Counts the followed clock's samples the follower took, applied or not: tests wait for it. */
    internal val followSamplesTaken = AtomicInt(0)

    /** How long the audio plays on without a followed clock's sample; tests that pace their samples slowly lengthen it. */
    internal var followWatchdog: Duration = FOLLOW_AUDIO_WATCHDOG
    private val pending = mutableListOf<PendingCommand>()
    private val mutableSnapshot = MutableStateFlow(FFplaySnapshot())
    private val mutableSecureOutputRequired = MutableStateFlow(false)
    private val mutableEvents = MutableSharedFlow<FFplayEvent>(extraBufferCapacity = 16)

    // Before the engine: its callbacks reach audio as soon as it exists.
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioPlayback = FFplayAudio(
        audioScope,
        ::warnAudio,
        audioOpener ?: { source -> engine.openAudio(::warnAudio) ?: openAudioPlayer(source, engine.inputHint()) },
    )

    /** Bumped by every prepare, stop and close; audio that finishes opening afterwards is stale. */
    private var audioGeneration = 0

    private val engine: FFplayEngine = engineFactory(
        configuration,
        ::acceptEngineUpdate,
        ::emit,
    )

    public val snapshot: StateFlow<FFplaySnapshot> = mutableSnapshot.asStateFlow()
    public val events: Flow<FFplayEvent> = mutableEvents.asSharedFlow()

    /**
     * The prepared source's audio mix, whose levels and track selection apply live. With no audio
     * to play (no source, no decodable track, audio off or a protected source) it has no tracks.
     * Its master [AudioMixControls.level] belongs to the player and carries over to later sources,
     * so a muted player stays muted; it can be set before any source is prepared.
     */
    public val audio: StateFlow<AudioMixControls> = audioPlayback.mix
    internal val secureOutputRequired = mutableSecureOutputRequired.asStateFlow()

    public suspend fun prepare(
        source: MediaSource,
        protection: FFplayContentProtection = FFplayContentProtection.CLEAR_OR_AUTO_DETECT,
    ) {
        prepareMutex.withLock {
            val generation = operationLock.withLock {
                checkOpen()
                mutableSecureOutputRequired.value = protection == FFplayContentProtection.REQUIRE_SECURE_PATH
                mutableSnapshot.value = FFplaySnapshot(state = FFplayState.PREPARING)
                audioPlayback.close()
                ++audioGeneration
            }
            try {
                prepareOnWorker(source, protection)
                if (configuration.audio && protection != FFplayContentProtection.REQUIRE_SECURE_PATH) attachAudio(source, generation)
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
    // While a source is being prepared, these are queued and applied in order once it is.
    /** Plays from the position shown. Rejected while the player [follow]s a clock, which plays it. */
    public fun play(): Unit = transport("play") { engine.play() }

    /** Pauses on the frame shown. Rejected while the player [follow]s a clock, which pauses it. */
    public fun pause(): Unit = transport("pause") { engine.pause() }

    /**
     * Seeks to [position]. The frame shown is the one on screen there, as `VideoDecoder.frameAt`
     * picks it, also while paused, and the audio resumes at the same sample. Rejected while the
     * player [follow]s a clock, which moves it.
     */
    public fun seekTo(position: Duration) {
        require(!position.isNegative()) { "Seek position must not be negative" }
        transport("seekTo") { engine.seekTo(position) }
    }

    /**
     * Follows [clock], a position the app gives each display frame, instead of the player's own
     * clock. While it follows, each display frame shows the frame `VideoDecoder.frameAt` returns for
     * the clock's position, and the clock is the player's transport, so [play], [pause] and [seekTo]
     * throw [IllegalStateException]:
     * - the player decodes ahead of the frame shown, as far as its frame queue holds, whether the
     *   clock advances or not: a position that advances takes the frames decoded ahead, one that
     *   stands still holds its frame, and a decoder that falls behind skips forward to a keyframe;
     * - a position that goes back, or moves forward well past what the frame time did, seeks
     *   (a loop that wraps), and so does the first position after [follow];
     * - the audio plays while the position advances at about real time and is silent otherwise, as
     *   [FFplayClock] says.
     *
     * While it follows the player's state is [FFplayState.PLAYING] once it decodes for the clock,
     * [FFplayState.ENDED] once the clock passed its last frame, and [FFplayState.PAUSED] on its last
     * frame while the clock is past the end. Null stops following and pauses on the frame shown.
     * Throws [UnsupportedOperationException] where the player paces its frames itself (the browser).
     */
    @ExperimentalFFmpegKmpApi
    public fun follow(clock: FFplayClock?): Unit = operationLock.withLock {
        checkOpen()
        val generation = followGeneration.incrementAndFetch()
        clockSamples.value = null
        followSeeks.value = null
        followRunOn.store(false)
        engine.follow(clock) { sample -> clockSamples.value = TaggedSample(generation, sample) }
        val previous = following
        following = clock
        if (clock == null) {
            followJob?.cancel()
            seekJob?.cancel()
            followJob = null
            seekJob = null
            followSeeking.store(false)
            if (previous != null) {
                if (!preparing && mutableSnapshot.value.state == FFplayState.PLAYING) {
                    runCatching { engine.pause() }
                        .onFailure { emit(FFplayEvent.Warning("Unable to pause after following: ${it.message}")) }
                }
                audioPlayback.stopFollowing(mutableSnapshot.value)
            }
            return@withLock
        }
        audioPlayback.startFollowing()
        if (followJob == null) followJob = audioScope.launch { followSamples() }
        if (seekJob == null) seekJob = audioScope.launch { followSeeks.collect { seek -> if (seek != null) seekFor(seek) } }
    }

    public fun stop(): Unit = command(needsSource = false) {
        audioGeneration++
        audioPlayback.close()
        engine.stop()
        mutableSecureOutputRequired.value = false
    }

    internal fun attachOutput(output: FFplayVideoOutput): Unit =
        command(needsSource = false) { engine.attachOutput(output) }

    internal fun detachOutput(output: FFplayVideoOutput) {
        if (output.platformTarget == null) {
            // Frames reach a software output through the engine, which drops them once detached.
            operationLock.withLock {
                when {
                    closed.load() -> Unit
                    preparing -> pending += PendingCommand(needsSource = false) { engine.detachOutput(output) }
                    else -> engine.detachOutput(output)
                }
            }
            return
        }
        // The caller releases this native surface next, and a prepare may be presenting on it.
        prepareLock.withLock {
            operationLock.withLock { if (!closed.load()) engine.detachOutput(output) }
        }
    }

    internal fun requestClose() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        // Cancellation is the one operation intentionally allowed to race an active native call.
        engine.cancel()
    }

    override fun close() {
        requestClose()
        // The locks prevent destruction until an active native call has observed cancellation.
        // Multiple callers may wait here, but only one destroys the engine.
        prepareLock.withLock { closeEngine() }
    }

    private fun closeEngine() = operationLock.withLock {
        if (!closeCompleted.compareAndSet(expectedValue = false, newValue = true)) {
            return@withLock
        }
        // Audio reads the same mounted input; release it before the engine closes that input.
        audioGeneration++
        audioPlayback.close()
        followGeneration.incrementAndFetch()
        audioScope.cancel()
        following = null
        followJob = null
        seekJob = null
        engine.close()
        pending.clear()
        mutableSecureOutputRequired.value = false
        mutableSnapshot.value = mutableSnapshot.value.copy(state = FFplayState.CLOSED)
    }

    private suspend fun prepareOnWorker(source: MediaSource, protection: FFplayContentProtection): Unit = coroutineScope {
        val preparation = async(Dispatchers.Default) {
            prepareLock.withLock {
                operationLock.withLock {
                    checkOpen()
                    engine.resetCancellation()
                    // close() may have raced the reset. Checking again guarantees its cancellation
                    // cannot be cleared immediately before entering a blocking native prepare.
                    checkOpen()
                    preparing = true
                }
                var prepared = false
                try {
                    engine.prepare(source, protection)
                    prepared = true
                } finally {
                    operationLock.withLock { finishPreparing(prepared) }
                }
                operationLock.withLock { checkOpen() }
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

    /** Runs [action] now, or queues it while a native prepare is in flight. */
    private fun command(needsSource: Boolean, action: () -> Unit): Unit = operationLock.withLock {
        checkOpen()
        if (preparing) pending += PendingCommand(needsSource, action) else action()
    }

    /** Applies what was queued during the prepare; after a failed one, only output changes and stop. */
    private fun finishPreparing(prepared: Boolean) {
        preparing = false
        val queued = pending.toList()
        pending.clear()
        if (closed.load()) return
        for (command in queued) {
            if (!prepared && command.needsSource) continue
            try {
                command.action()
            } catch (failure: Throwable) {
                emit(FFplayEvent.Warning("A queued command failed: ${failure.message ?: failure::class.simpleName}"))
            }
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
    private suspend fun attachAudio(source: MediaSource, generation: Int) {
        val audio = audioPlayback.load(source) ?: return
        val attached = operationLock.withLock {
            if (closed.load() || generation != audioGeneration) return@withLock false
            audioPlayback.attach(audio, mutableSnapshot.value, ::reportAudibleClock)
            true
        }
        if (!attached) audio.close()
    }

    /**
     * Filtering and forwarding share the lock with seekTo, so a pre-seek report can't land after it.
     * A followed clock is the master clock instead: the audio follows it, not the other way round.
     */
    private fun reportAudibleClock(position: Duration) = operationLock.withLock {
        if (closed.load()) return@withLock
        val clock = audioPlayback.clockFor(position)
        if (following == null) clock?.let(engine::setMasterClock)
    }

    /**
     * Applies the followed clock's samples as they come, older ones skipped, on one coroutine: seeks
     * for a jump, starts the run that decodes ahead when there is none, and moves the audio. No
     * sample for [FOLLOW_AUDIO_WATCHDOG] silences the audio.
     */
    private suspend fun followSamples() {
        var seen: TaggedSample? = null
        while (true) {
            val tagged = withTimeoutOrNull(followWatchdog) {
                clockSamples.first { it != null && it !== seen }
            }
            if (tagged == null) {
                audioPlayback.followLost()
                continue
            }
            seen = tagged
            try {
                followSample(tagged)
            } finally {
                followSamplesTaken.incrementAndFetch()
            }
        }
    }

    /** Applies one of the followed clock's samples, unless it was taken for an earlier clock. */
    private fun followSample(tagged: TaggedSample) {
        if (tagged.generation != followGeneration.load()) return
        if (tagged.generation != followedGeneration) {
            // A new clock: nothing known of its motion, and its first position is sought.
            followedGeneration = tagged.generation
            previous = null
            motion.reset()
        }
        val sample = tagged.sample
        val snapshot = mutableSnapshot.value
        if (snapshot.state !in FOLLOWABLE && snapshot.state != FFplayState.SEEKING) {
            // Nothing prepared to show: the clock's first position once there is is sought.
            previous = null
            motion.reset()
            audioPlayback.follow(sample, audible = false, jumped = false)
            return
        }
        val jumped = previous?.let { followJumped(it, sample) } ?: true
        previous = sample
        motion.add(sample, jumped)
        val duration = snapshot.duration
        val inClip = !sample.position.isNegative() && (duration == null || sample.position < duration)
        // The run that decodes ahead is not on: a seek from the end paused it, or a prepare left the player ready.
        val runStopped = !followRunOn.load() && (snapshot.state == FFplayState.READY || snapshot.state == FFplayState.PAUSED)
        if (jumped || (runStopped && !followSeeking.load() && sample.position < (duration ?: Duration.INFINITE))) {
            requestSeek(tagged.generation, sample.position, duration)
        }
        audioPlayback.follow(sample, audible = if (inClip) motion.atRealTime else false, jumped = jumped)
    }

    /** What the follower knows of the clock it follows, touched only by the coroutine that follows. */
    private val motion = FollowedMotion()
    private var followedGeneration = -1
    private var previous: FFplayClockSample? = null

    /** Asks for a seek to [position], within the clip; a newer one replaces it, and one to where a seek already goes is not asked for again. */
    private fun requestSeek(generation: Int, position: Duration, duration: Duration?) {
        val target = position.coerceIn(Duration.ZERO, duration ?: Duration.INFINITE)
        val pending = followSeeks.value
        if (pending != null && pending.generation == generation && (pending.target - target).absoluteValue < FOLLOW_SAME_SEEK) return
        followSeeking.store(true)
        followSeeks.value = FollowSeek(generation, target)
    }

    /**
     * Seeks for the followed clock, off the follower so samples keep coming meanwhile, then starts the
     * run that decodes ahead unless the position is at the end, where the last frame is held.
     */
    private fun seekFor(seek: FollowSeek) = operationLock.withLock {
        try {
            if (closed.load() || preparing || seek.generation != followGeneration.load()) return@withLock
            if (mutableSnapshot.value.state !in FOLLOWABLE) return@withLock
            followRunOn.store(false)
            engine.seekTo(seek.target)
            val now = mutableSnapshot.value
            val atEnd = now.duration?.let { seek.target >= it } ?: false
            if (!atEnd && (now.state == FFplayState.READY || now.state == FFplayState.PAUSED)) engine.play()
            followRunOn.store(!atEnd)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            emit(FFplayEvent.Warning("Unable to follow the clock: ${failure.message ?: failure::class.simpleName}"))
        } finally {
            // A newer seek asked for meanwhile is still to come.
            if (followSeeks.compareAndSet(seek, null)) followSeeking.store(false)
        }
    }

    /** A playback command, rejected while a followed clock is the transport. */
    private fun transport(operation: String, action: () -> Unit): Unit = operationLock.withLock {
        checkOpen()
        check(following == null) { "$operation is the followed clock's while the player follows one; call follow(null) first" }
        if (preparing) pending += PendingCommand(needsSource = true, action) else action()
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

private class PendingCommand(val needsSource: Boolean, val action: () -> Unit)

/** A followed clock's sample, with the [FFplayPlayer.follow] generation it was taken in. */
private class TaggedSample(val generation: Int, val sample: FFplayClockSample)

/** A seek the follower asked for. */
private class FollowSeek(val generation: Int, val target: Duration)

/** The states a followed clock moves the player in; in the others it has nothing prepared to show. */
private val FOLLOWABLE = setOf(FFplayState.READY, FFplayState.PLAYING, FFplayState.PAUSED, FFplayState.ENDED)

/** How close two seek targets are to be the same seek: well within a frame. */
private val FOLLOW_SAME_SEEK = 1.milliseconds
