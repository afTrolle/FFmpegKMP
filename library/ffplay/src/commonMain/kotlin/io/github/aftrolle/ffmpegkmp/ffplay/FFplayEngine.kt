// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.ImageBitmap
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerConfiguration
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderPreference
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderKind
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerOutputCapabilities
import io.github.aftrolle.ffmpegkmp.bindings.NativePlatformVideoFrame
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSnapshot
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSource
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerState
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.createPlatformPlayerBridge
import io.github.aftrolle.ffmpegkmp.core.toNativeMounts
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlinx.coroutines.flow.StateFlow

internal data class FFplayFrame(
    val image: ImageBitmap,
    val presentationTime: Duration,
    val sampleAspectRatio: Double = 1.0,
    val rotationDegrees: Double = 0.0,
)

internal interface FFplayVideoOutput {
    val kind: FFplayRendererKind
    val frames: StateFlow<FFplayFrame?>
    val capabilities: FFplayOutputCapabilities
    /** Private platform object (for example android.view.Surface) consumed by the native bridge. */
    val platformTarget: Any? get() = null
    val securePlatformTarget: Boolean get() = false

    /** Takes ownership of a scheduled frame. Returns false when it cannot be presented. */
    fun submit(frame: FFplayFrame): Boolean

    /**
     * Accepts the bridge frame at the renderer boundary. Native/GPU outputs may override this to
     * avoid constructing a Compose bitmap; Canvas outputs use the color-managed default.
     */
    fun submitNative(frame: NativeVideoFrame, video: FFplayVideoInfo?): Boolean = submit(
        FFplayFrame(
            image = frame.toImageBitmap(),
            presentationTime = frame.presentationTimeUs.microseconds,
            sampleAspectRatio = video.sampleAspectRatioValue(),
            rotationDegrees = video?.rotationDegrees ?: 0.0,
        ),
    )

    /** Accepts a borrowed platform hardware frame synchronously. */
    fun submitPlatform(frame: NativePlatformVideoFrame, video: FFplayVideoInfo?): Boolean = false

    /** Discards the currently retained frame, for example after a seek or target loss. */
    fun discard()
}

internal data class FFplayOutputCapabilities(
    val hardwareFrameImport: Boolean = false,
    val softwareFrameUpload: Boolean = true,
    val zeroCopy: Boolean = false,
    val hdrTransfers: Set<String> = emptySet(),
    val colorSpaces: Set<String> = setOf("sRGB"),
    /** This output can receive a deterministic, bounded SDR conversion for HDR source frames. */
    val toneMapHdrToSdr: Boolean = false,
    /** True only for a platform output backed by a secure decoder and protected surface. */
    val protectedContent: Boolean = false,
)

internal interface FFplayEngine : AutoCloseable {
    fun resetCancellation() = Unit
    fun prepare(source: FFplaySource)
    suspend fun awaitPreparation() = Unit
    /** May be called concurrently to interrupt an active blocking operation. */
    fun cancel()
    fun play()
    fun pause()
    fun seekTo(position: Duration)
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
    private var source: FFplaySource? = null
    private var output: FFplayVideoOutput? = null
    private var playWhenReady = false
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

    override fun prepare(source: FFplaySource) {
        checkOpen()
        this.source = source
        decoderFallbackEmitted = false
        rendererFallbackEmitted = false
        nativeDroppedFrames = 0
        outputDroppedFrames = 0
        snapshot = FFplaySnapshot(state = FFplayState.PREPARING)
        val result = bridge.prepare(
            NativePlayerSource(
                input = source.input,
                mounts = source.io.toNativeMounts(),
                requireSecurePath = source.protection == FFplayContentProtection.REQUIRE_SECURE_PATH,
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
        playWhenReady = true
        requireNativeSuccess("play", bridge.play())
    }

    override fun pause() {
        checkPrepared()
        playWhenReady = false
        requireNativeSuccess("pause", bridge.pause())
    }

    override fun seekTo(position: Duration) {
        checkPrepared()
        val duration = snapshot.duration
        val bounded = if (duration == null) position else minOf(position, duration)
        requireNativeSuccess("seek", bridge.seek(bounded.inWholeMicroseconds))
    }

    override fun stop() {
        checkOpen()
        playWhenReady = false
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
        val negotiationFailure = output.negotiationFailure()
        if (negotiationFailure != null) {
            this.output = null
            val failure = FFplayFailure(negotiationFailure)
            snapshot = snapshot.copy(state = FFplayState.FAILED, failure = failure, output = null)
            update(snapshot)
            if (source?.protection == FFplayContentProtection.REQUIRE_SECURE_PATH) {
                emit(FFplayEvent.ProtectionRequired(negotiationFailure))
            } else {
                emit(FFplayEvent.Fatal(negotiationFailure))
            }
            return
        }
        this.output = output
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
        val result = bridge.setOutput(output.capabilities.toNative())
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
            configuration.decoderPreference == FFplayDecoderPreference.AUTO &&
            output?.capabilities?.hardwareFrameImport == true &&
            native.activeDecoder == NativePlayerDecoderKind.SOFTWARE
        ) {
            decoderFallbackEmitted = true
            emit(FFplayEvent.DecoderFallback("Hardware decoding was unavailable; using software decoding"))
        }
        val video = native.videoInfo?.toPublicVideoInfo() ?: if (
            native.videoWidth > 0 && native.videoHeight > 0
        ) {
            FFplayVideoInfo(
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
        if (closed || native.queueSerial != nativeQueueSerial) return
        if (source?.protection == FFplayContentProtection.REQUIRE_SECURE_PATH) {
            emit(FFplayEvent.Fatal("A protected source attempted to cross the CPU-readable frame boundary"))
            return
        }
        val target = output ?: return
        if (!target.capabilities.softwareFrameUpload) return
        val accepted = try {
            target.submitNative(native, snapshot.video)
        } catch (failure: Throwable) {
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
        if (!target.capabilities.hardwareFrameImport) return false
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
        video: FFplayVideoInfo?,
    ): FFplayOutputInfo {
        val color = decideColorOutput(video, capabilities, configuration.hdrPolicy)
        return FFplayOutputInfo(
            decoder = when (activeDecoder) {
                NativePlayerDecoderKind.HARDWARE -> FFplayDecoderKind.HARDWARE
                NativePlayerDecoderKind.SOFTWARE -> FFplayDecoderKind.SOFTWARE
                NativePlayerDecoderKind.UNKNOWN -> FFplayDecoderKind.UNKNOWN
            },
            renderer = kind,
            zeroCopy = capabilities.zeroCopy && activeDecoder == NativePlayerDecoderKind.HARDWARE,
            sourceColorSpace = color.sourceColorSpace,
            outputColorSpace = color.outputColorSpace,
            hdrResult = color.hdrResult,
            securePath = capabilities.protectedContent,
        )
    }

    private fun FFplayVideoOutput.negotiationFailure(): String? = when {
        source?.protection == FFplayContentProtection.REQUIRE_SECURE_PATH &&
            !capabilities.protectedContent ->
            "Protected content requires a verified secure decoder and native output surface"
        source?.protection == FFplayContentProtection.REQUIRE_SECURE_PATH &&
            kind == FFplayRendererKind.COMPOSE_CANVAS ->
            "Protected content cannot be copied into a Compose Canvas frame"
        configuration.outputPreference == FFplayOutputPreference.NATIVE_SURFACE &&
            kind == FFplayRendererKind.COMPOSE_CANVAS ->
            "A native video surface was required, but only the Compose Canvas output is available"
        configuration.decoderPreference == FFplayDecoderPreference.REQUIRE_HARDWARE &&
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

internal fun FFplayVideoInfo?.sampleAspectRatioValue(): Double {
    val value = this?.sampleAspectRatio ?: return 1.0
    val numerator = value.substringBefore(':').toDoubleOrNull() ?: return 1.0
    val denominator = value.substringAfter(':', "").toDoubleOrNull() ?: return 1.0
    return (numerator / denominator).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
}

private fun FFplayConfiguration.toNative() = NativePlayerConfiguration(
    decoderPreference = when (decoderPreference) {
        FFplayDecoderPreference.AUTO -> NativePlayerDecoderPreference.AUTO
        FFplayDecoderPreference.REQUIRE_HARDWARE -> NativePlayerDecoderPreference.REQUIRE_HARDWARE
        FFplayDecoderPreference.SOFTWARE -> NativePlayerDecoderPreference.SOFTWARE
    },
)

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
    -13 -> "Unable to $operation: protected content requires a verified secure output path"
    -22 -> "Unable to $operation: invalid native player argument"
    -95 -> "Unable to $operation: the required decoder or output capability is unsupported"
    else -> "Unable to $operation: native player error $code"
}
