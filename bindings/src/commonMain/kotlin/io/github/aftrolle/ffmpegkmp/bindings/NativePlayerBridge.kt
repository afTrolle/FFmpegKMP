// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

@InternalFFmpegKmpApi
public enum class NativePlayerState {
    IDLE,
    PREPARING,
    WAITING_FOR_OUTPUT,
    READY,
    PLAYING,
    PAUSED,
    SEEKING,
    ENDED,
    STOPPED,
    FAILED,
}

@InternalFFmpegKmpApi
public enum class NativePlayerDecoderPreference { AUTO, REQUIRE_HARDWARE, SOFTWARE }

@InternalFFmpegKmpApi
public enum class NativePlayerDecoderKind { UNKNOWN, HARDWARE, SOFTWARE }

@InternalFFmpegKmpApi
public enum class NativePlayerHdrType { SDR, HDR10, HLG, HDR10_PLUS, DOLBY_VISION, UNKNOWN_HDR }

@InternalFFmpegKmpApi
public data class NativePlayerMasteringDisplayMetadata(
    val hasPrimaries: Boolean = false,
    val hasLuminance: Boolean = false,
    val redX: Double = 0.0,
    val redY: Double = 0.0,
    val greenX: Double = 0.0,
    val greenY: Double = 0.0,
    val blueX: Double = 0.0,
    val blueY: Double = 0.0,
    val whiteX: Double = 0.0,
    val whiteY: Double = 0.0,
    val minLuminance: Double = 0.0,
    val maxLuminance: Double = 0.0,
)

@InternalFFmpegKmpApi
public data class NativePlayerVideoInfo(
    val width: Int,
    val height: Int,
    val pixelFormat: Int = -1,
    val pixelFormatName: String? = null,
    val bitDepth: Int = 0,
    val sampleAspectRatioNumerator: Int = 0,
    val sampleAspectRatioDenominator: Int = 0,
    val rotationDegrees: Double = 0.0,
    val colorPrimaries: Int = 2,
    val colorTransfer: Int = 2,
    val colorSpace: Int = 2,
    val colorRange: Int = 0,
    val chromaLocation: Int = 0,
    val hdrType: NativePlayerHdrType = NativePlayerHdrType.SDR,
    val masteringDisplay: NativePlayerMasteringDisplayMetadata? = null,
    val maxContentLightLevel: Int? = null,
    val maxFrameAverageLightLevel: Int? = null,
)

@InternalFFmpegKmpApi
public data class NativePlayerConfiguration(
    val decoderPreference: NativePlayerDecoderPreference = NativePlayerDecoderPreference.AUTO,
)

@InternalFFmpegKmpApi
public data class NativePlayerSource(
    val input: String,
    val mounts: List<NativeMountedIo> = emptyList(),
    val requireSecurePath: Boolean = false,
)

@InternalFFmpegKmpApi
public data class NativePlayerOutputCapabilities(
    val hardwareFrameImport: Boolean = false,
    val softwareFrameUpload: Boolean = true,
    val zeroCopy: Boolean = false,
    val protectedContent: Boolean = false,
    val toneMapHdrToSdr: Boolean = false,
)

@InternalFFmpegKmpApi
public data class NativePlayerSnapshot(
    val state: NativePlayerState = NativePlayerState.IDLE,
    val positionUs: Long = 0,
    val durationUs: Long? = null,
    val queueSerial: UInt = 0u,
    val outputCapabilities: NativePlayerOutputCapabilities? = null,
    val errorCode: Int = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val activeDecoder: NativePlayerDecoderKind = NativePlayerDecoderKind.UNKNOWN,
    val videoInfo: NativePlayerVideoInfo? = null,
    val droppedFrames: Long = 0,
)

/** CPU-readable RGBA frame. Secure/protected sources must never produce this type. */
@InternalFFmpegKmpApi
public data class NativeVideoFrame(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
    val stride: Int,
    val presentationTimeUs: Long,
    val queueSerial: UInt,
)

@InternalFFmpegKmpApi
public enum class NativePlatformVideoFrameKind { CV_PIXEL_BUFFER, WEB_VIDEO_FRAME }

/**
 * Borrowed hardware frame. [handle] is valid only while the callback is running; a platform
 * renderer that keeps it beyond the callback must retain the underlying platform object.
 */
@InternalFFmpegKmpApi
public data class NativePlatformVideoFrame(
    val kind: NativePlatformVideoFrameKind,
    val handle: Any,
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long,
    val queueSerial: UInt,
)

@InternalFFmpegKmpApi
public interface NativePlayerBridge : AutoCloseable {
    /** Clears a prior cancellation before the caller performs its final closed-state check. */
    public fun resetCancellation() = Unit
    public fun prepare(source: NativePlayerSource): Int
    /** Waits until an asynchronously dispatched preparation has either completed or failed. */
    public suspend fun awaitPreparation(): Int = 0
    public fun setOutput(capabilities: NativePlayerOutputCapabilities): Int
    /** Attaches a private platform output object before capability negotiation. */
    public fun setPlatformOutputTarget(target: Any?, secure: Boolean): Int =
        if (target == null) 0 else -95
    public fun clearOutput()
    public fun play(): Int
    public fun pause(): Int
    public fun seek(positionUs: Long): Int
    public fun stop(): Int
    public fun cancel()
    public fun snapshot(): NativePlayerSnapshot
}

@InternalFFmpegKmpApi
public expect fun createPlatformPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: (NativeVideoFrame) -> Unit = {},
    platformFrame: (NativePlatformVideoFrame) -> Boolean = { false },
): NativePlayerBridge

/** Deterministic contract implementation used by web and as an unavailable-runtime fallback. */
@InternalFFmpegKmpApi
public fun createInMemoryPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
): NativePlayerBridge = InMemoryNativePlayerBridge(configuration, update)

private class InMemoryNativePlayerBridge(
    private val configuration: NativePlayerConfiguration,
    private val update: (NativePlayerSnapshot) -> Unit,
) : NativePlayerBridge {
    private var current = NativePlayerSnapshot()
    private var source: NativePlayerSource? = null
    private var output: NativePlayerOutputCapabilities? = null
    private var playWhenReady = false
    private var closed = false

    override fun prepare(source: NativePlayerSource): Int {
        checkOpen()
        this.source = source
        playWhenReady = false
        current = NativePlayerSnapshot(
            state = if (output == null) NativePlayerState.WAITING_FOR_OUTPUT else NativePlayerState.READY,
            queueSerial = current.queueSerial + 1u,
            outputCapabilities = output,
            activeDecoder = output?.let(::selectedDecoder) ?: NativePlayerDecoderKind.UNKNOWN,
        )
        val validationFailure = validateOutput()
        if (validationFailure != null) {
            this.source = null
            fail(validationFailure)
            return validationFailure
        }
        return publish(0)
    }

    override fun setOutput(capabilities: NativePlayerOutputCapabilities): Int {
        checkOpen()
        output = capabilities
        return validateOutput()?.also(::fail) ?: run {
            current = current.copy(
                state = when {
                    source == null -> current.state
                    playWhenReady -> NativePlayerState.PLAYING
                    else -> NativePlayerState.READY
                },
                outputCapabilities = capabilities,
                errorCode = 0,
                activeDecoder = selectedDecoder(capabilities),
            )
            publish(0)
        }
    }

    override fun clearOutput() {
        checkOpen()
        output = null
        current = current.copy(
            state = if (source == null) current.state else NativePlayerState.WAITING_FOR_OUTPUT,
            outputCapabilities = null,
            activeDecoder = NativePlayerDecoderKind.UNKNOWN,
        )
        publish(0)
    }

    override fun play(): Int {
        requirePrepared()
        playWhenReady = true
        current = current.copy(
            state = if (output == null) NativePlayerState.WAITING_FOR_OUTPUT else NativePlayerState.PLAYING,
        )
        return publish(0)
    }

    override fun pause(): Int {
        requirePrepared()
        playWhenReady = false
        current = current.copy(state = NativePlayerState.PAUSED)
        return publish(0)
    }

    override fun seek(positionUs: Long): Int {
        requirePrepared()
        if (positionUs < 0) return -22
        current = current.copy(
            state = NativePlayerState.SEEKING,
            positionUs = positionUs,
            queueSerial = current.queueSerial + 1u,
        )
        publish(0)
        current = current.copy(
            state = when {
                output == null -> NativePlayerState.WAITING_FOR_OUTPUT
                playWhenReady -> NativePlayerState.PLAYING
                else -> NativePlayerState.PAUSED
            },
        )
        return publish(0)
    }

    override fun stop(): Int {
        checkOpen()
        source = null
        playWhenReady = false
        current = current.copy(
            state = NativePlayerState.STOPPED,
            positionUs = 0,
            durationUs = null,
            queueSerial = current.queueSerial + 1u,
            errorCode = 0,
            activeDecoder = NativePlayerDecoderKind.UNKNOWN,
            droppedFrames = 0,
        )
        return publish(0)
    }

    override fun cancel() = Unit
    override fun snapshot(): NativePlayerSnapshot = current

    override fun close() {
        closed = true
        source = null
        output = null
    }

    private fun validateOutput(): Int? {
        val target = output ?: return null
        return when {
            source?.requireSecurePath == true && !target.protectedContent -> -13
            configuration.decoderPreference == NativePlayerDecoderPreference.REQUIRE_HARDWARE &&
                !target.hardwareFrameImport -> -95
            !target.hardwareFrameImport && !target.softwareFrameUpload -> -95
            else -> null
        }
    }

    private fun selectedDecoder(target: NativePlayerOutputCapabilities): NativePlayerDecoderKind =
        when {
            source == null -> NativePlayerDecoderKind.UNKNOWN
            configuration.decoderPreference == NativePlayerDecoderPreference.SOFTWARE ->
                NativePlayerDecoderKind.SOFTWARE
            target.hardwareFrameImport -> NativePlayerDecoderKind.HARDWARE
            target.softwareFrameUpload -> NativePlayerDecoderKind.SOFTWARE
            else -> NativePlayerDecoderKind.UNKNOWN
        }

    private fun fail(code: Int) {
        current = current.copy(state = NativePlayerState.FAILED, errorCode = code)
        update(current)
    }

    private fun publish(result: Int): Int {
        update(current)
        return result
    }

    private fun requirePrepared() {
        checkOpen()
        check(source != null) { "Prepare a source before controlling playback" }
    }

    private fun checkOpen() = check(!closed) { "The player bridge is closed" }
}
