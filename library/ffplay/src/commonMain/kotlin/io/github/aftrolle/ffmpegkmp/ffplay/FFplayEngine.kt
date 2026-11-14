// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)
package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativeInputHint
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerError
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerConfiguration
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderKind
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerException
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerOutputCapabilities
import io.github.aftrolle.ffmpegkmp.bindings.NativePlatformVideoFrame
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSnapshot
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSource
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerState
import io.github.aftrolle.ffmpegkmp.bindings.NATIVE_PLAYER_REFRESH_IDLE
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrameSink
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.createPlatformPlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.requireNative
import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.DecoderFallbackReason
import io.github.aftrolle.ffmpegkmp.codec.DecoderThreads
import io.github.aftrolle.ffmpegkmp.codec.NamedCodec
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import io.github.aftrolle.ffmpegkmp.codec.toNative
import io.github.aftrolle.ffmpegkmp.codec.toNativeThreadType
import io.github.aftrolle.ffmpegkmp.codec.toPublic
import io.github.aftrolle.ffmpegkmp.codec.toPublicVideoInfo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import io.github.aftrolle.ffmpegkmp.core.toNativeMounts
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal interface FFplayVideoOutput {
    val kind: FFplayRendererKind
    val capabilities: FFplayOutputCapabilities
    /** Private platform object (for example android.view.Surface) consumed by the native bridge. */
    val platformTarget: Any? get() = null
    val securePlatformTarget: Boolean get() = false

    /**
     * Shows a decoded frame and takes its reference, as the player presents it: on the engine's
     * refresh thread, or for a preview on the player's native thread. Returns false when it cannot
     * be presented. Used for outputs that do not [preparesFrames].
     */
    fun submitNative(frame: VideoFrame, video: VideoInfo?): Boolean

    /**
     * Whether the output makes software frames drawable ahead of their time: the engine then hands
     * each to [prepareNative] as the player queues it, on the player's session thread, and showing
     * it is [FFplayPreparedFrame.present], a flip. Otherwise frames wait in the queue as decoded and
     * go to [submitNative] when shown.
     */
    val preparesFrames: Boolean get() = false

    /**
     * Makes [frame] drawable and takes it, for an output that [preparesFrames]: the Canvas converts it
     * into a free bitmap of its ring. Returns null when it cannot take it, having closed it.
     */
    fun prepareNative(frame: VideoFrame, video: VideoInfo?): FFplayPreparedFrame? {
        frame.close()
        return null
    }

    /**
     * Whether the output refreshes the player itself, from a frame clock of its own (the Compose
     * Canvas's, once per display frame), through the driver [bindRefresh] gives it. The engine's
     * timer refreshes a player whose output does not.
     */
    val drivesRefresh: Boolean get() = false

    /**
     * Gives an output that [drivesRefresh] the player's refresh while it is attached to a player
     * that is refreshed ([NativePlayerBridge.refreshes]); null once it is detached.
     */
    fun bindRefresh(driver: FFplayRefreshDriver?) = Unit

    /** Accepts a borrowed platform hardware frame synchronously, as the player presents it. */
    fun submitPlatform(frame: NativePlatformVideoFrame, video: VideoInfo?): Boolean = false

    /** Discards the currently retained frame, for example after a seek or target loss. */
    fun discard()

    /**
     * Gives the output, while attached, where to tell the size it draws software frames at, in the
     * frame's own orientation, for a bridge that converts frames to it (the browser's,
     * [NativePlayerBridge.setFrameSize]); null once detached. Outputs whose bitmaps scale the frames
     * themselves ignore it.
     */
    fun bindFrameSize(sink: ((width: Int, height: Int) -> Unit)?) = Unit
}

/** A frame an output prepared; exactly one of [present] or [release] follows. */
internal interface FFplayPreparedFrame {
    /** Shows it, on the refresh's thread or, for a preview, the session thread; false when it cannot be shown. */
    fun present(): Boolean

    /** Gives it back unshown: dropped as late, or flushed. */
    fun release()
}

/** The player's refresh, for an output that drives it from its own frame clock. */
internal interface FFplayRefreshDriver {
    /**
     * Suspends until a refresh can show something: while the player plays, and after a refresh that
     * answered [NATIVE_PLAYER_REFRESH_IDLE], until the player's next snapshot.
     */
    suspend fun awaitFrames()

    /**
     * [NativePlayerBridge.refresh] at [nowUs] (negative reads the player's clock), the frame shown
     * becoming visible [leadUs] after it. [frameTime] is the display frame's time on the output's
     * own frame clock, which a followed [FFplayClock] is asked about, so that every player drawn in
     * one display frame asks about the same time; null asks about the player's clock now. A refresh
     * that throws fails the player and answers IDLE, as does one after the output was detached.
     */
    fun refresh(nowUs: Long, leadUs: Long, frameTime: Duration? = null): Long
}

internal data class FFplayOutputCapabilities(
    val hardwareFrameImport: Boolean = false,
    val softwareFrameUpload: Boolean = true,
    val zeroCopy: Boolean = false,
    val hdrTransfers: Set<ColorTransfer> = emptySet(),
    /** The primaries the output presents as they are; sRGB's are BT.709's. */
    val colorSpaces: Set<ColorPrimaries> = setOf(ColorPrimaries.BT709),
    /** This output can receive a deterministic, bounded SDR conversion for HDR source frames. */
    val toneMapHdrToSdr: Boolean = false,
    /** True only for a platform output backed by a secure decoder and protected surface. */
    val protectedContent: Boolean = false,
    /**
     * Hardware frames reach [FFplayVideoOutput.prepareNative] like software ones, rendered into the
     * output's own [FFplayVideoOutput.platformTarget] as they are queued: Android's hardware Canvas.
     */
    val prepareHardwareFrames: Boolean = false,
    /** With [prepareHardwareFrames], the most hardware frames queued at once, each holding one of the output's buffers; 0 for the frame queue's size. */
    val hardwareFrameLimit: Int = 0,
)

internal interface FFplayEngine : AutoCloseable {
    fun resetCancellation() = Unit
    fun prepare(source: MediaSource, protection: FFplayContentProtection)
    suspend fun awaitPreparation() = Unit
    /** May be called concurrently to interrupt an active blocking operation. */
    fun cancel()
    fun play()
    fun pause()
    fun seekTo(position: Duration)

    /** Makes the audible audio position the video master clock; negative clears it. */
    fun setMasterClock(mediaTimeUs: Long) = Unit

    /**
     * Shows, at each refresh, the frame for the position [clock] gives the display frame, instead
     * of the frame the player's own clocks make due, and tells [onSample] each position, on the
     * refreshing thread; null returns to the player's clocks. Starting the play run that decodes
     * ahead, and seeking, stay the caller's. Throws [UnsupportedOperationException] where the engine
     * paces its frames itself.
     */
    fun follow(clock: FFplayClock?, onSample: (FFplayClockSample) -> Unit) {
        if (clock != null) throw UnsupportedOperationException("This player paces its own frames and cannot follow a clock")
    }

    /** The prepared source's audio when the engine plays it itself (the browser), else null. */
    suspend fun openAudio(warn: (String) -> Unit): FFplayAudioOutput? = null

    /** What preparing the source found when it probed it, for the audio to open it without a probe; null if unknown. */
    fun inputHint(): NativeInputHint? = null
    fun stop()
    fun attachOutput(output: FFplayVideoOutput)
    fun detachOutput(output: FFplayVideoOutput)
}

internal typealias FFplayEngineFactory = (
    configuration: FFplayConfiguration,
    update: (FFplaySnapshot) -> Unit,
    emit: (FFplayEvent) -> Unit,
) -> FFplayEngine

internal typealias NativePlayerBridgeFactory = (
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: NativeVideoFrameSink,
    platformFrame: (NativePlatformVideoFrame) -> Boolean,
) -> NativePlayerBridge

internal fun createPlatformFFplayEngine(
    configuration: FFplayConfiguration,
    update: (FFplaySnapshot) -> Unit,
    emit: (FFplayEvent) -> Unit,
): FFplayEngine = FFplayBridgeEngine(configuration, update, emit)

/** Keeps common lifecycle tests independent of whether a native runtime is installed. */
internal fun createInMemoryFFplayEngine(
    configuration: FFplayConfiguration,
    update: (FFplaySnapshot) -> Unit,
    emit: (FFplayEvent) -> Unit,
): FFplayEngine = FFplayBridgeEngine(
    configuration = configuration,
    update = update,
    emit = emit,
    bridgeFactory = { nativeConfiguration, nativeUpdate, _, _ ->
        createInMemoryPlayerBridge(nativeConfiguration, nativeUpdate)
    },
)

/** Test seam for exercising the coordinator with scheduled frames on every Kotlin target. */
internal fun createFFplayEngineWithBridge(
    configuration: FFplayConfiguration,
    update: (FFplaySnapshot) -> Unit,
    emit: (FFplayEvent) -> Unit,
    bridgeFactory: NativePlayerBridgeFactory,
): FFplayEngine = FFplayBridgeEngine(configuration, update, emit, bridgeFactory)

/**
 * Common coordinator around one per-player bridge. Native-capable targets use the opaque C player
 * handle, while browser targets use the worker-backed WebAssembly bridge.
 */
private class FFplayBridgeEngine(
    private val configuration: FFplayConfiguration,
    private val update: (FFplaySnapshot) -> Unit,
    private val emit: (FFplayEvent) -> Unit,
    bridgeFactory: NativePlayerBridgeFactory? = null,
) : FFplayEngine {
    /**
     * Guarded by [snapshotLock], with the counters it is made from: the player publishes from its
     * session thread and from the refresh, and outputs drop frames on the refresh, so each change is
     * made and reported in one step, in the order it was made.
     */
    private var snapshot = FFplaySnapshot()
    private val snapshotLock = FFplayOperationLock()
    private var source: MediaSource? = null
    private var protection = FFplayContentProtection.CLEAR_OR_AUTO_DETECT
    private val secureSource: Boolean get() = source != null && protection == FFplayContentProtection.REQUIRE_SECURE_PATH
    private var output: FFplayVideoOutput? = null
    /** What [output] was attached with, after [FFplayConfiguration.hdrPolicy] is applied. */
    private var negotiated = FFplayOutputCapabilities()
    private var nativeQueueSerial = 0u
    private var nativeDroppedFrames = 0L
    private var nativeCatchUps = 0L
    private var outputDroppedFrames = 0L
    private var decoderFallbackEmitted = false
    private var rendererFallbackEmitted = false
    private var closed = false

    /**
     * The frames the player prepared and has not presented or released yet, by token. Guarded by
     * [preparedLock]: the player prepares on its session thread and presents on the refresh thread.
     */
    private val prepared = HashMap<Long, PreparedFrame>()
    private val preparedLock = FFplayOperationLock()
    private val frames = object : NativeVideoFrameSink {
        override fun prepare(frame: NativeVideoFrame): Boolean = prepareNativeFrame(frame)
        override fun present(token: Long) = presentNativeFrame(token)
        override fun release(token: Long) {
            preparedLock.withLock { prepared.remove(token) }?.release()
        }
    }
    private val bridge: NativePlayerBridge = bridgeFactory?.invoke(
        configuration.toNative(),
        ::acceptNativeSnapshot,
        frames,
        ::acceptPlatformFrame,
    ) ?: createPlatformPlayerBridge(
        configuration.toNative(),
        ::acceptNativeSnapshot,
        frames,
        ::acceptPlatformFrame,
    )

    /**
     * Drives [NativePlayerBridge.refresh] while the player plays, for a bridge that [refreshes]
     * (NativePlayerBridge.refreshes): a timer for every output without a frame clock of its own
     * ([FFplayVideoOutput.drivesRefresh]), which waits as long as the last refresh said and
     * refreshes again. Frames are shown when it runs, and their visibility is whatever the output's
     * drawing adds to that. A refresh that answers IDLE waits for the player's next snapshot, which
     * it publishes when frames start to be shown, and while the player is not playing the loop waits
     * for it to play. A refresh that throws fails the player.
     */
    private val playing = MutableStateFlow(false)

    /** Counts the player's snapshots, for refreshes that answered IDLE to wait for the next one. */
    private val snapshotCount = MutableStateFlow(0L)

    /** False while the output refreshes the player from its own frame clock. */
    private val timerRefreshes = MutableStateFlow(true)

    /** The clock the refreshes follow, and who hears its positions; null for the player's own clocks. */
    private val followed = MutableStateFlow<Followed?>(null)

    /**
     * Held while a refresh shows the frame for a followed clock's position and reports it, and while
     * [follow] changes the clock, so neither happens for a clock after [follow] replaced it. Never
     * held while the clock is asked.
     */
    private val followLock = FFplayOperationLock()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        if (bridge.refreshes) {
            refreshScope.launch {
                try {
                    var idleAt: Long? = null
                    while (isActive) {
                        timerRefreshes.first { it }
                        awaitFrames(idleAt)
                        // An output that drives the refresh may have been attached while this waited: a timer
                        // refresh now would ask a followed clock at a time on another clock than the output's.
                        if (!timerRefreshes.value) continue
                        val seen = snapshotCount.value
                        val following = followed.value != null
                        val remainingUs = refreshBridge(-1, 0, null)
                        // A followed clock is asked again whether or not the player plays: it may start it.
                        idleAt = seen.takeIf { remainingUs == NATIVE_PLAYER_REFRESH_IDLE && !following }
                        when {
                            following -> delay(
                                (
                                    remainingUs.takeIf { it != NATIVE_PLAYER_REFRESH_IDLE }
                                        ?.coerceAtMost(FOLLOW_INTERVAL_US) ?: FOLLOW_INTERVAL_US
                                    ).microseconds,
                            )
                            idleAt == null -> delay(remainingUs.microseconds)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    refreshFailed(failure)
                }
            }
        }
    }

    /**
     * Suspends while the player is not playing, and after an IDLE refresh at snapshot [idleAt], until
     * the next; never while it follows a clock, which is asked every display frame.
     */
    private suspend fun awaitFrames(idleAt: Long?) {
        // The common case, once a display frame, without a flow.
        if (followed.value != null || (playing.value && idleAt == null)) return
        combine(playing, snapshotCount, followed) { isPlaying, count, clock ->
            clock != null || (isPlaying && (idleAt == null || count != idleAt))
        }.first { it }
    }

    /**
     * [NativePlayerBridge.refresh], or while a clock is followed, the frame for its position at
     * [frameTime] (the refresh clock's now when null), telling who follows it. A position before the
     * start shows the first frame.
     */
    private fun refreshBridge(nowUs: Long, leadUs: Long, frameTime: Duration?): Long {
        val following = followed.value ?: return bridge.refresh(nowUs, leadUs)
        val asked = frameTime ?: (if (nowUs >= 0) nowUs else bridge.clockNowUs()).microseconds
        val position = following.clock.positionAt(asked)
        // Not after follow changed the clock while this one was asked: its position is no longer the player's.
        return followLock.withLock {
            if (followed.value !== following) return@withLock FOLLOW_INTERVAL_US
            bridge.refreshAt(nowUs, leadUs, position.inWholeNanoseconds.coerceAtLeast(0))
                .also { following.onSample(FFplayClockSample(asked, position)) }
        }
    }

    override fun follow(clock: FFplayClock?, onSample: (FFplayClockSample) -> Unit) {
        checkOpen()
        if (clock != null && !bridge.refreshes) {
            throw UnsupportedOperationException("This player paces its own frames and cannot follow a clock")
        }
        followLock.withLock { followed.value = clock?.let { Followed(it, onSample) } }
    }

    private class Followed(val clock: FFplayClock, val onSample: (FFplayClockSample) -> Unit)

    /** [output]'s refresh while it is attached: it calls it from its own frame clock, on one coroutine. */
    private inner class OutputRefresh(private val output: FFplayVideoOutput) : FFplayRefreshDriver {
        private var idleAt: Long? = null

        override suspend fun awaitFrames() {
            awaitFrames(idleAt)
            idleAt = null
        }

        override fun refresh(nowUs: Long, leadUs: Long, frameTime: Duration?): Long {
            if (closed || this@FFplayBridgeEngine.output !== output) return NATIVE_PLAYER_REFRESH_IDLE
            val seen = snapshotCount.value
            val remainingUs = try {
                refreshBridge(nowUs, leadUs, frameTime)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                refreshFailed(failure)
                NATIVE_PLAYER_REFRESH_IDLE
            }
            idleAt = seen.takeIf { remainingUs == NATIVE_PLAYER_REFRESH_IDLE && followed.value == null }
            return remainingUs
        }
    }

    private fun refreshFailed(cause: Throwable) {
        if (closed) return
        val message = "Unable to show the next video frame: " +
            "${cause::class.simpleName}${cause.message?.let { ": $it" }.orEmpty()}"
        publish { it.copy(state = FFplayState.FAILED, failure = FFplayFailure(message, cause)) }
        emit(FFplayEvent.Fatal(message, cause))
    }

    override fun prepare(source: MediaSource, protection: FFplayContentProtection) {
        checkOpen()
        this.source = source
        this.protection = protection
        decoderFallbackEmitted = false
        rendererFallbackEmitted = false
        snapshotLock.withLock {
            nativeDroppedFrames = 0
            nativeCatchUps = 0
            outputDroppedFrames = 0
            snapshot = FFplaySnapshot(state = FFplayState.PREPARING)
        }
        val result = bridge.prepare(
            NativePlayerSource(
                input = source.input,
                mounts = source.io.toNativeMounts(),
                requireSecurePath = protection == FFplayContentProtection.REQUIRE_SECURE_PATH,
            ),
        )
        if (result < 0) this.source = null
        requireNativeSuccess("prepare", result)
    }

    override fun resetCancellation() {
        bridge.resetCancellation()
    }

    override suspend fun awaitPreparation() {
        val result = bridge.awaitPreparation()
        if (result < 0) source = null
        requireNativeSuccess("prepare", result)
    }

    override fun cancel() {
        bridge.cancel()
    }

    override fun play() {
        checkPrepared()
        requireNativeSuccess("play", bridge.play())
    }

    override fun pause() {
        checkPrepared()
        requireNativeSuccess("pause", bridge.pause())
    }

    override fun seekTo(position: Duration) {
        checkPrepared()
        val duration = snapshot.duration
        val bounded = if (duration == null) position else minOf(position, duration)
        requireNativeSuccess("seek", bridge.seek(bounded.inWholeMicroseconds))
    }

    override fun setMasterClock(mediaTimeUs: Long) {
        if (!closed) bridge.setMasterClock(mediaTimeUs)
    }

    override suspend fun openAudio(warn: (String) -> Unit): FFplayAudioOutput? =
        if (closed) null else bridge.openAudio()?.let { BridgeAudioOutput(it, warn) }

    override fun inputHint(): NativeInputHint? = if (closed) null else bridge.inputHint()

    override fun stop() {
        checkOpen()
        snapshotLock.withLock {
            nativeDroppedFrames = 0
            nativeCatchUps = 0
            outputDroppedFrames = 0
        }
        output?.discard()
        requireNativeSuccess("stop", bridge.stop())
        source = null
        // Stopping released them; a frame can only be left by a bridge that does not release its own.
        releasePreparedFrames()
    }

    override fun attachOutput(output: FFplayVideoOutput) {
        checkOpen()
        val previous = this.output
        if (previous !== output) previous?.discard()
        previous?.bindFrameSize(null)
        previous?.bindRefresh(null)
        timerRefreshes.value = true
        // Frames at their own size until the new output says what it draws them at.
        bridge.setFrameSize(0, 0)
        // Output replacement is transactional. Remove both the negotiated capabilities and the
        // platform object first so a rejected or failed replacement can never leave an old native
        // surface reachable behind a snapshot that reports no active output.
        clearBridgeOutput()
        val capabilities = output.capabilities.applyHdrPolicy(snapshot.video)
        val negotiationFailure = output.negotiationFailure(capabilities)
        if (negotiationFailure != null) {
            this.output = null
            val failure = FFplayFailure(negotiationFailure)
            publish { it.copy(state = FFplayState.FAILED, failure = failure, output = null) }
            if (secureSource) {
                emit(FFplayEvent.ProtectionRequired(negotiationFailure))
            } else {
                emit(FFplayEvent.Fatal(negotiationFailure))
            }
            return
        }
        this.output = output
        negotiated = capabilities
        val targetResult = bridge.setPlatformOutputTarget(
            output.platformTarget,
            output.securePlatformTarget,
        )
        if (targetResult < 0) {
            this.output = null
            clearBridgeOutput()
            val message = nativeError("attach platform output", targetResult)
            publish { it.copy(state = FFplayState.FAILED, failure = FFplayFailure(message), output = null) }
            emit(FFplayEvent.Fatal(message))
            return
        }
        val result = bridge.setOutput(capabilities.toNative())
        if (result < 0) {
            this.output = null
            clearBridgeOutput()
            val message = nativeError("attach output", result)
            // The cause carries the native code, BUSY for a full DecoderBudget under REQUIRE_HARDWARE.
            val cause = NativePlayerException(message, result)
            publish { it.copy(state = FFplayState.FAILED, failure = FFplayFailure(message, cause), output = null) }
            emit(FFplayEvent.Fatal(message, cause))
        } else {
            output.bindFrameSize { width, height ->
                if (!closed && this.output === output) bridge.setFrameSize(width, height)
            }
            if (bridge.refreshes && output.drivesRefresh) {
                timerRefreshes.value = false
                output.bindRefresh(OutputRefresh(output))
            }
        }
        if (result >= 0 && !rendererFallbackEmitted &&
            configuration.outputPreference == FFplayOutputPreference.AUTO &&
            output.kind == FFplayRendererKind.COMPOSE_CANVAS
        ) {
            rendererFallbackEmitted = true
            emit(
                FFplayEvent.RendererFallback(
                    "A native video surface was unavailable; using the Compose Canvas renderer",
                ),
            )
        }
    }

    override fun detachOutput(output: FFplayVideoOutput) {
        if (this.output !== output) return
        output.discard()
        output.bindFrameSize(null)
        output.bindRefresh(null)
        timerRefreshes.value = true
        bridge.setFrameSize(0, 0)
        this.output = null
        clearBridgeOutput()
        emit(FFplayEvent.SurfaceLost("The video output was detached"))
    }

    override fun close() {
        output?.discard()
        output?.bindFrameSize(null)
        output?.bindRefresh(null)
        closed = true
        refreshScope.cancel()
        bridge.cancel()
        bridge.setPlatformOutputTarget(null, false)
        bridge.close()
        releasePreparedFrames()
        source = null
        output = null
    }

    private fun releasePreparedFrames() {
        val frames = preparedLock.withLock { prepared.values.toList().also { prepared.clear() } }
        frames.forEach { it.release() }
    }

    private fun clearBridgeOutput() {
        // The player releases the frames it prepared for the output that goes.
        bridge.clearOutput()
        bridge.setPlatformOutputTarget(null, false)
    }

    /** Changes the snapshot and reports it, under [snapshotLock]. */
    private fun publish(change: (FFplaySnapshot) -> FFplaySnapshot) = snapshotLock.withLock {
        snapshot = change(snapshot)
        update(snapshot)
    }

    private fun acceptNativeSnapshot(native: NativePlayerSnapshot) = snapshotLock.withLock {
        nativeQueueSerial = native.queueSerial
        playing.value = native.state == NativePlayerState.PLAYING
        snapshotCount.value++
        nativeDroppedFrames = native.droppedFrames
        if (native.catchUps > nativeCatchUps) {
            val position = native.positionUs.microseconds
            emit(FFplayEvent.CaughtUp("The decoder fell behind; playback skipped forward to about $position", position))
        }
        nativeCatchUps = native.catchUps
        // The native player reports a reason whenever its output wanted a hardware decoder it did not get.
        val fallback = native.decoderFallback.toPublic()
        if (fallback != null && !decoderFallbackEmitted && output != null) {
            decoderFallbackEmitted = true
            emit(FFplayEvent.DecoderFallback(fallback.message(), fallback))
        }
        val video = native.videoInfo?.toPublicVideoInfo() ?: if (
            native.videoWidth > 0 && native.videoHeight > 0
        ) {
            VideoInfo(
                width = native.videoWidth,
                height = native.videoHeight,
                pixelFormat = "rgba8888-preview",
            )
        } else if (native.state !in setOf(
                NativePlayerState.IDLE,
                NativePlayerState.PREPARING,
                NativePlayerState.STOPPED,
            )
        ) {
            snapshot.video
        } else {
            null
        }
        snapshot = snapshot.copy(
            state = native.state.toPublic(),
            position = native.positionUs.microseconds,
            duration = native.durationUs?.microseconds,
            seekable = native.durationUs != null,
            video = video,
            output = native.outputCapabilities?.let {
                output?.outputInfo(native.activeDecoder, fallback, video)
            },
            droppedFrames = nativeDroppedFrames + outputDroppedFrames,
            failure = native.errorCode.takeIf { it < 0 }?.let { code ->
                FFplayFailure(nativeError("player operation", code, native.errorMessage))
            },
        )
        update(snapshot)
    }

    /** Keeps a frame the player decoded ahead until it is presented or released; on the session thread. */
    private fun prepareNativeFrame(native: NativeVideoFrame): Boolean {
        val target = output
        if (closed || target == null || !negotiated.softwareFrameUpload || secureSource) {
            native.frame.close()
            if (secureSource) emit(FFplayEvent.Fatal("A protected source attempted to cross the CPU-readable frame boundary"))
            return false
        }
        val made = if (target.preparesFrames) prepareOnOutput(target, native) ?: return false else null
        preparedLock.withLock { prepared[native.token] = PreparedFrame(native, target, made) }
        return true
    }

    /** [native] made drawable by [target] now, or null, counted as dropped, when it could not be. */
    private fun prepareOnOutput(target: FFplayVideoOutput, native: NativeVideoFrame): FFplayPreparedFrame? {
        val video = snapshot.video
        val frame = native.toVideoFrame(video)
        val made = try {
            target.prepareNative(frame, video)
        } catch (failure: Throwable) {
            frame.close()
            emit(
                FFplayEvent.Warning(
                    "Unable to prepare the video frame: " +
                        "${failure::class.simpleName}${failure.message?.let { ": $it" }.orEmpty()}",
                ),
            )
            null
        }
        if (made == null) countOutputDrop()
        return made
    }

    /** Shows a prepared frame on the output it was prepared for, if it is still attached. */
    private fun presentNativeFrame(token: Long) {
        val entry = preparedLock.withLock { prepared.remove(token) } ?: return
        if (entry.output !== output) return entry.release()
        val made = entry.made ?: return acceptNativeFrame(entry.native)
        if (closed || entry.native.queueSerial != nativeQueueSerial || secureSource) return made.release()
        val accepted = try {
            made.present()
        } catch (failure: Throwable) {
            made.release()
            emit(
                FFplayEvent.Warning(
                    "Unable to show the video frame: " +
                        "${failure::class.simpleName}${failure.message?.let { ": $it" }.orEmpty()}",
                ),
            )
            false
        }
        if (!accepted) countOutputDrop()
    }

    private fun countOutputDrop() = publish {
        outputDroppedFrames++
        it.copy(droppedFrames = nativeDroppedFrames + outputDroppedFrames)
    }

    private fun NativeVideoFrame.toVideoFrame(video: VideoInfo?): VideoFrame = VideoFrame.of(
        native = frame,
        pts = presentationTimeUs.microseconds,
        duration = Duration.ZERO,
        width = width,
        height = height,
        rotationDegrees = video?.rotationDegrees ?: 0.0,
        sampleAspectRatio = video.sampleAspectRatioValue(),
    )

    private fun acceptNativeFrame(native: NativeVideoFrame) {
        val video = snapshot.video
        val frame = native.toVideoFrame(video)
        if (closed || native.queueSerial != nativeQueueSerial) return frame.close()
        if (secureSource) {
            frame.close()
            emit(FFplayEvent.Fatal("A protected source attempted to cross the CPU-readable frame boundary"))
            return
        }
        val target = output ?: return frame.close()
        if (!negotiated.softwareFrameUpload) return frame.close()
        val accepted = try {
            target.submitNative(frame, video)
        } catch (failure: Throwable) {
            // The output took the reference; closing again is a no-op, and releases it if the output did not.
            frame.close()
            emit(
                FFplayEvent.Warning(
                    "Unable to submit the preview frame: " +
                        "${failure::class.simpleName}${failure.message?.let { ": $it" }.orEmpty()}",
                ),
            )
            false
        }
        if (!accepted) {
            publish {
                outputDroppedFrames++
                it.copy(droppedFrames = nativeDroppedFrames + outputDroppedFrames)
            }
        }
    }

    private fun acceptPlatformFrame(native: NativePlatformVideoFrame): Boolean {
        if (closed || native.queueSerial != nativeQueueSerial) return false
        val target = output ?: return false
        if (!negotiated.hardwareFrameImport) return false
        val accepted = try {
            target.submitPlatform(native, snapshot.video)
        } catch (failure: Throwable) {
            emit(
                FFplayEvent.Warning(
                    "Unable to submit the hardware video frame: " +
                        "${failure::class.simpleName}${failure.message?.let { ": $it" }.orEmpty()}",
                ),
            )
            false
        }
        if (!accepted) {
            publish {
                outputDroppedFrames++
                it.copy(droppedFrames = nativeDroppedFrames + outputDroppedFrames)
            }
        }
        return accepted
    }

    private fun FFplayVideoOutput.outputInfo(
        activeDecoder: NativePlayerDecoderKind,
        fallback: DecoderFallbackReason?,
        video: VideoInfo?,
    ): FFplayOutputInfo {
        val capabilities = negotiated
        val color = decideColorOutput(video, capabilities, configuration.hdrPolicy)
        return FFplayOutputInfo(
            decoder = activeDecoder.toPublic(),
            renderer = kind,
            zeroCopy = capabilities.zeroCopy && activeDecoder == NativePlayerDecoderKind.HARDWARE,
            sourcePrimaries = color.sourcePrimaries,
            outputPrimaries = color.outputPrimaries,
            hdrResult = color.hdrResult,
            securePath = capabilities.protectedContent,
            decoderFallback = fallback,
        )
    }

    /**
     * [FFplayHdrPolicy.FORCE_SDR] must not reach a path that would display this source as HDR, so
     * such an output is negotiated as software upload only, which the native player tone maps.
     */
    private fun FFplayOutputCapabilities.applyHdrPolicy(video: VideoInfo?): FFplayOutputCapabilities =
        if (configuration.hdrPolicy == FFplayHdrPolicy.FORCE_SDR && video?.color?.transfer in hdrTransfers) {
            copy(
                hardwareFrameImport = false,
                zeroCopy = false,
                hdrTransfers = emptySet(),
                colorSpaces = setOf(ColorPrimaries.BT709),
            )
        } else {
            this
        }

    private fun FFplayVideoOutput.negotiationFailure(capabilities: FFplayOutputCapabilities): String? = when {
        secureSource && !capabilities.protectedContent ->
            "Protected content requires a verified secure decoder and native output surface"
        secureSource && kind == FFplayRendererKind.COMPOSE_CANVAS ->
            "Protected content cannot be copied into a Compose Canvas frame"
        configuration.outputPreference == FFplayOutputPreference.NATIVE_SURFACE &&
            kind == FFplayRendererKind.COMPOSE_CANVAS ->
            "A native video surface was required, but only the Compose Canvas output is available"
        configuration.decoder == CodecPreference.REQUIRE_HARDWARE &&
            !capabilities.hardwareFrameImport && this.capabilities.hardwareFrameImport ->
            "FORCE_SDR must tone map this HDR source in software, but hardware decoding was required"
        configuration.decoder == CodecPreference.REQUIRE_HARDWARE &&
            !capabilities.hardwareFrameImport ->
            "Hardware decoding was required, but the attached output cannot import hardware frames"
        !capabilities.softwareFrameUpload && !capabilities.hardwareFrameImport ->
            "The attached output accepts neither hardware frames nor software frame uploads"
        else -> null
    }

    /** Throws a [NativePlayerException] whose `errorCode` is the native result when [result] is an error. */
    private fun requireNativeSuccess(operation: String, result: Int) =
        requireNative(result, operation) { _, code ->
            // The engine's words for why, such as a named decoder's, ride on its latest snapshot.
            val reason = runCatching { bridge.snapshot().errorMessage }.getOrNull()
            NativePlayerException(nativeError(operation, code, reason), code)
        }

    private fun checkPrepared() {
        checkOpen()
        check(source != null) { "Prepare a source before controlling playback" }
    }

    private fun checkOpen() {
        check(!closed) { "FFplay engine is closed" }
    }
}

internal fun VideoInfo?.sampleAspectRatioValue(): Double = this?.sampleAspectRatio ?: 1.0

/**
 * A frame the player prepared for [output]: [made] drawable by an output that [FFplayVideoOutput.preparesFrames],
 * which then owns [native]'s frame, else kept as decoded.
 */
private class PreparedFrame(val native: NativeVideoFrame, val output: FFplayVideoOutput, val made: FFplayPreparedFrame?) {
    fun release() {
        if (made != null) made.release() else native.frame.close()
    }
}

private fun FFplayConfiguration.toNative() = NativePlayerConfiguration(
    when (decoder) {
        is CodecPreference -> decoder.toNative()
        // A named decoder is opened alone; the preference it replaces stays AUTO.
        is NamedCodec -> CodecPreference.AUTO.toNative()
    },
    // Auto is 0 and 0: FFmpeg's automatic count and its default thread types.
    decoderThreads = when (threads) {
        DecoderThreads.Auto -> 0
        is DecoderThreads.Fixed -> threads.toNative()
    },
    decoderThreadType = when (threads) {
        DecoderThreads.Auto -> 0
        is DecoderThreads.Fixed -> threads.toNativeThreadType()
    },
    decoderName = (decoder as? NamedCodec)?.name,
    decoderOptions = (decoder as? NamedCodec)?.options.orEmpty(),
    // -1 keeps the decoder.
    codecReleaseDelayUs = if (decoderIdleRelease.isInfinite()) -1 else decoderIdleRelease.inWholeMicroseconds,
    catchUpLagUs = if (catchUpLag.isInfinite()) -1 else catchUpLag.inWholeMicroseconds,
    frameQueueSize = frameQueueSize,
)

/** How often the timer asks a followed clock while the player shows nothing new: ffplay's refresh rate. */
private const val FOLLOW_INTERVAL_US = 10_000L

private fun FFplayOutputCapabilities.toNative() = NativePlayerOutputCapabilities(
    hardwareFrameImport = hardwareFrameImport,
    softwareFrameUpload = softwareFrameUpload,
    zeroCopy = zeroCopy,
    protectedContent = protectedContent,
    toneMapHdrToSdr = toneMapHdrToSdr,
    prepareHardwareFrames = prepareHardwareFrames,
    hardwareFrameLimit = hardwareFrameLimit,
)

private fun NativePlayerState.toPublic(): FFplayState = when (this) {
    NativePlayerState.IDLE -> FFplayState.IDLE
    NativePlayerState.PREPARING -> FFplayState.PREPARING
    NativePlayerState.WAITING_FOR_OUTPUT -> FFplayState.WAITING_FOR_OUTPUT
    NativePlayerState.READY -> FFplayState.READY
    NativePlayerState.PLAYING -> FFplayState.PLAYING
    NativePlayerState.PAUSED -> FFplayState.PAUSED
    NativePlayerState.SEEKING -> FFplayState.SEEKING
    NativePlayerState.ENDED -> FFplayState.ENDED
    NativePlayerState.STOPPED -> FFplayState.STOPPED
    NativePlayerState.FAILED -> FFplayState.FAILED
}

private fun DecoderFallbackReason.message(): String = when (this) {
    DecoderFallbackReason.UNSUPPORTED -> "No hardware decoder takes this video; using software decoding"
    DecoderFallbackReason.FAILED -> "The hardware decoder failed; using software decoding"
    DecoderFallbackReason.BUDGET -> "Every hardware decoder DecoderBudget allows is in use; using software decoding"
}

private fun nativeError(operation: String, code: Int, reason: String?): String =
    nativeError(operation, code) + reason?.let { " ($it)" }.orEmpty()

private fun nativeError(operation: String, code: Int): String = when (code) {
    NativePlayerError.ACCESS_DENIED -> "Unable to $operation: protected content requires a verified secure output path"
    NativePlayerError.INVALID_ARGUMENT -> "Unable to $operation: invalid native player argument"
    NativePlayerError.UNSUPPORTED -> "Unable to $operation: the required decoder or output capability is unsupported"
    NativePlayerError.BUSY -> "Unable to $operation: every hardware decoder DecoderBudget allows is in use"
    else -> "Unable to $operation: native player error $code"
}
