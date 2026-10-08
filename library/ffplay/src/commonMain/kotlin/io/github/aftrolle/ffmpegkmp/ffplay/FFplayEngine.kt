// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.ImageBitmap
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerError
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerConfiguration
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderKind
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerOutputCapabilities
import io.github.aftrolle.ffmpegkmp.bindings.NativePlatformVideoFrame
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSnapshot
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSource
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerState
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.createPlatformPlayerBridge
import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.MediaSource
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import io.github.aftrolle.ffmpegkmp.codec.toNative
import io.github.aftrolle.ffmpegkmp.codec.toPublic
import io.github.aftrolle.ffmpegkmp.codec.toPublicVideoInfo
import io.github.aftrolle.ffmpegkmp.core.toNativeMounts
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

internal data class FFplayFrame(
    val image: ImageBitmap,
    val presentationTime: Duration,
    val sampleAspectRatio: Double = 1.0,
    val rotationDegrees: Double = 0.0,
)

internal interface FFplayVideoOutput {
    val kind: FFplayRendererKind
    val capabilities: FFplayOutputCapabilities
    /** Private platform object (for example android.view.Surface) consumed by the native bridge. */
    val platformTarget: Any? get() = null
    val securePlatformTarget: Boolean get() = false

    /** Takes ownership of a scheduled frame. Returns false when it cannot be presented. */
    fun submit(frame: FFplayFrame): Boolean

    /**
     * Accepts a decoded frame at the renderer boundary and takes its reference. Native outputs may
     * override this to draw the frame's memory themselves; Canvas outputs use the default, which
     * converts it once into a Compose bitmap with [toImageBitmap].
     */
    fun submitNative(frame: VideoFrame, video: VideoInfo?): Boolean = submit(
        FFplayFrame(
            image = frame.use { it.toImageBitmap() },
            presentationTime = frame.pts,
            sampleAspectRatio = frame.sampleAspectRatio,
            rotationDegrees = frame.rotationDegrees,
        ),
    )

    /** Accepts a borrowed platform hardware frame synchronously. */
    fun submitPlatform(frame: NativePlatformVideoFrame, video: VideoInfo?): Boolean = false

    /** Discards the currently retained frame, for example after a seek or target loss. */
    fun discard()
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

    /** The prepared source's audio when the engine plays it itself (the browser), else null. */
    suspend fun openAudio(warn: (String) -> Unit): FFplayAudioOutput? = null
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
    frame: (NativeVideoFrame) -> Unit,
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
    private var snapshot = FFplaySnapshot()
    private var source: MediaSource? = null
    private var protection = FFplayContentProtection.CLEAR_OR_AUTO_DETECT
    private val secureSource: Boolean get() = source != null && protection == FFplayContentProtection.REQUIRE_SECURE_PATH
    private var output: FFplayVideoOutput? = null
    /** What [output] was attached with, after [FFplayConfiguration.hdrPolicy] is applied. */
    private var negotiated = FFplayOutputCapabilities()
    private var nativeQueueSerial = 0u
    private var nativeDroppedFrames = 0L
    private var outputDroppedFrames = 0L
    private var decoderFallbackEmitted = false
    private var rendererFallbackEmitted = false
    private var closed = false
    private val bridge: NativePlayerBridge = bridgeFactory?.invoke(
        configuration.toNative(),
        ::acceptNativeSnapshot,
        ::acceptNativeFrame,
        ::acceptPlatformFrame,
    ) ?: createPlatformPlayerBridge(
        configuration.toNative(),
        ::acceptNativeSnapshot,
        ::acceptNativeFrame,
        ::acceptPlatformFrame,
    )

    override fun prepare(source: MediaSource, protection: FFplayContentProtection) {
        checkOpen()
        this.source = source
        this.protection = protection
        decoderFallbackEmitted = false
        rendererFallbackEmitted = false
        nativeDroppedFrames = 0
        outputDroppedFrames = 0
        snapshot = FFplaySnapshot(state = FFplayState.PREPARING)
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

    override fun stop() {
        checkOpen()
        nativeDroppedFrames = 0
        outputDroppedFrames = 0
        output?.discard()
        requireNativeSuccess("stop", bridge.stop())
        source = null
    }

    override fun attachOutput(output: FFplayVideoOutput) {
        checkOpen()
        val previous = this.output
        if (previous !== output) previous?.discard()
        // Output replacement is transactional. Remove both the negotiated capabilities and the
        // platform object first so a rejected or failed replacement can never leave an old native
        // surface reachable behind a snapshot that reports no active output.
        clearBridgeOutput()
        val capabilities = output.capabilities.applyHdrPolicy(snapshot.video)
        val negotiationFailure = output.negotiationFailure(capabilities)
        if (negotiationFailure != null) {
            this.output = null
            val failure = FFplayFailure(negotiationFailure)
            snapshot = snapshot.copy(state = FFplayState.FAILED, failure = failure, output = null)
            update(snapshot)
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
            snapshot = snapshot.copy(
                state = FFplayState.FAILED,
                failure = FFplayFailure(message),
                output = null,
            )
            update(snapshot)
            emit(FFplayEvent.Fatal(message))
            return
        }
        val result = bridge.setOutput(capabilities.toNative())
        if (result < 0) {
            this.output = null
            clearBridgeOutput()
            val message = nativeError("attach output", result)
            snapshot = snapshot.copy(
                state = FFplayState.FAILED,
                failure = FFplayFailure(message),
                output = null,
            )
            update(snapshot)
            emit(FFplayEvent.Fatal(message))
        } else if (!rendererFallbackEmitted &&
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
        this.output = null
        clearBridgeOutput()
        emit(FFplayEvent.SurfaceLost("The video output was detached"))
    }

    override fun close() {
        output?.discard()
        closed = true
        bridge.cancel()
        bridge.setPlatformOutputTarget(null, false)
        bridge.close()
        source = null
        output = null
    }

    private fun clearBridgeOutput() {
        bridge.clearOutput()
        bridge.setPlatformOutputTarget(null, false)
    }

    private fun acceptNativeSnapshot(native: NativePlayerSnapshot) {
        nativeQueueSerial = native.queueSerial
        nativeDroppedFrames = native.droppedFrames
        if (!decoderFallbackEmitted &&
            configuration.decoderPreference == DecoderPreference.AUTO &&
            output != null && negotiated.hardwareFrameImport &&
            native.activeDecoder == NativePlayerDecoderKind.SOFTWARE
        ) {
            decoderFallbackEmitted = true
            emit(FFplayEvent.DecoderFallback("Hardware decoding was unavailable; using software decoding"))
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
                output?.outputInfo(native.activeDecoder, video)
            },
            droppedFrames = nativeDroppedFrames + outputDroppedFrames,
            failure = native.errorCode.takeIf { it < 0 }?.let { code ->
                FFplayFailure(nativeError("player operation", code))
            },
        )
        update(snapshot)
    }

    private fun acceptNativeFrame(native: NativeVideoFrame) {
        val video = snapshot.video
        val frame = VideoFrame.of(
            native = native.frame,
            pts = native.presentationTimeUs.microseconds,
            duration = Duration.ZERO,
            width = native.width,
            height = native.height,
            rotationDegrees = video?.rotationDegrees ?: 0.0,
            sampleAspectRatio = video.sampleAspectRatioValue(),
        )
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
            outputDroppedFrames++
            snapshot = snapshot.copy(droppedFrames = nativeDroppedFrames + outputDroppedFrames)
            update(snapshot)
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
            outputDroppedFrames++
            snapshot = snapshot.copy(droppedFrames = nativeDroppedFrames + outputDroppedFrames)
            update(snapshot)
        }
        return accepted
    }

    private fun FFplayVideoOutput.outputInfo(
        activeDecoder: NativePlayerDecoderKind,
        video: VideoInfo?,
    ): FFplayOutputInfo {
        val capabilities = negotiated
        val color = decideColorOutput(video, capabilities, configuration.hdrPolicy)
        return FFplayOutputInfo(
            decoder = activeDecoder.toPublic(),
            renderer = kind,
            zeroCopy = capabilities.zeroCopy && activeDecoder == NativePlayerDecoderKind.HARDWARE,
            sourceColorSpace = color.sourceColorSpace,
            outputColorSpace = color.outputColorSpace,
            hdrResult = color.hdrResult,
            securePath = capabilities.protectedContent,
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
        configuration.decoderPreference == DecoderPreference.REQUIRE_HARDWARE &&
            !capabilities.hardwareFrameImport && this.capabilities.hardwareFrameImport ->
            "FORCE_SDR must tone map this HDR source in software, but hardware decoding was required"
        configuration.decoderPreference == DecoderPreference.REQUIRE_HARDWARE &&
            !capabilities.hardwareFrameImport ->
            "Hardware decoding was required, but the attached output cannot import hardware frames"
        !capabilities.softwareFrameUpload && !capabilities.hardwareFrameImport ->
            "The attached output accepts neither hardware frames nor software frame uploads"
        else -> null
    }

    private fun requireNativeSuccess(operation: String, result: Int) {
        if (result < 0) throw IllegalStateException(nativeError(operation, result))
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

private fun FFplayConfiguration.toNative() = NativePlayerConfiguration(decoderPreference.toNative(), threads.toNative())

private fun FFplayOutputCapabilities.toNative() = NativePlayerOutputCapabilities(
    hardwareFrameImport = hardwareFrameImport,
    softwareFrameUpload = softwareFrameUpload,
    zeroCopy = zeroCopy,
    protectedContent = protectedContent,
    toneMapHdrToSdr = toneMapHdrToSdr,
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

private fun nativeError(operation: String, code: Int): String = when (code) {
    NativePlayerError.ACCESS_DENIED -> "Unable to $operation: protected content requires a verified secure output path"
    NativePlayerError.INVALID_ARGUMENT -> "Unable to $operation: invalid native player argument"
    NativePlayerError.UNSUPPORTED -> "Unable to $operation: the required decoder or output capability is unsupported"
    else -> "Unable to $operation: native player error $code"
}
