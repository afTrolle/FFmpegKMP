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

/**
 * Errors the player reports itself, mirroring `ffplaykmp_error`: fixed values, because errno
 * numbering differs between hosts. Any other negative code is an FFmpeg AVERROR.
 */
@InternalFFmpegKmpApi
public object NativePlayerError {
    public const val INVALID_ARGUMENT: Int = -1001
    public const val INVALID_STATE: Int = -1002
    public const val ACCESS_DENIED: Int = -1003
    public const val UNSUPPORTED: Int = -1004
    public const val IO: Int = -1005
}

@InternalFFmpegKmpApi
public interface NativePlayerBridge : AutoCloseable {
    /** Joins any cancelled worker and clears the cancellation before the next [prepare]. */
    public fun resetCancellation() = Unit
    public fun prepare(source: NativePlayerSource): Int
    /** Waits until an asynchronously dispatched preparation has either completed or failed. */
    public suspend fun awaitPreparation(): Int = 0
    public fun setOutput(capabilities: NativePlayerOutputCapabilities): Int
    /** Attaches a private platform output object before capability negotiation. */
    public fun setPlatformOutputTarget(target: Any?, secure: Boolean): Int =
        if (target == null) 0 else NativePlayerError.UNSUPPORTED
    public fun clearOutput()
    public fun play(): Int
    public fun pause(): Int
    public fun seek(positionUs: Long): Int

    /**
     * Reports the media time the listener hears now, making it the master clock for video
     * scheduling; negative clears it. Bridges without native scheduling ignore it.
     */
    public fun setMasterClock(mediaTimeUs: Long): Unit = Unit

    /**
     * Opens the prepared source's audio in the bridge itself, or returns null where the platform
     * audio engine plays it instead. Close it before the next [prepare] or [stop].
     */
    public suspend fun openAudio(): NativePlayerAudio? = null
    public fun stop(): Int
    public fun cancel()
    public fun snapshot(): NativePlayerSnapshot
}

/** Where a bridge's own audio is, as reported by [NativePlayerAudio.setProgressListener]. */
@InternalFFmpegKmpApi
public data class NativeAudioProgress(
    /** Media time the listener hears now. */
    val positionMicros: Long,
    /** Everything up to the end of the input has been played. */
    val ended: Boolean = false,
    /** Playback waits for a user gesture, as the browser's autoplay policy requires. */
    val blockedByAutoplay: Boolean = false,
    /** Set once decoding or the audio output failed; the audio then stays silent. */
    val failure: String? = null,
)

/**
 * Audio a bridge plays itself, mirroring [NativeAudioDecoder]'s track model. Gains apply live;
 * track changes reach what is heard after the little audio decoded ahead.
 */
@InternalFFmpegKmpApi
public interface NativePlayerAudio : AutoCloseable {
    public val tracks: List<NativeAudioTrackInfo>

    /** Negative when the input does not report a duration. */
    public val durationMicros: Long
    public fun isTrackEnabled(track: Int): Boolean
    public fun setTrackEnabled(track: Int, enabled: Boolean)
    public fun setTrackGain(track: Int, gain: Float)
    public fun setMasterGain(gain: Float)
    public fun play()
    public fun pause()
    public fun seek(positionMicros: Long)
    public fun setProgressListener(listener: (NativeAudioProgress) -> Unit)
}

@InternalFFmpegKmpApi
public expect fun createPlatformPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: (NativeVideoFrame) -> Unit = {},
    platformFrame: (NativePlatformVideoFrame) -> Boolean = { false },
): NativePlayerBridge

/** Deterministic contract implementation for tests that must not need a native runtime. */
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
        if (positionUs < 0) return NativePlayerError.INVALID_ARGUMENT
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
            source?.requireSecurePath == true && !target.protectedContent -> NativePlayerError.ACCESS_DENIED
            configuration.decoderPreference == NativePlayerDecoderPreference.REQUIRE_HARDWARE &&
                !target.hardwareFrameImport -> NativePlayerError.UNSUPPORTED
            !target.hardwareFrameImport && !target.softwareFrameUpload -> NativePlayerError.UNSUPPORTED
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

/** `ffplaykmp_output_flags` bits, identical in the C header and the browser worker. */
private const val OUTPUT_HARDWARE_FRAME_IMPORT = 1
private const val OUTPUT_SOFTWARE_FRAME_UPLOAD = 2
private const val OUTPUT_ZERO_COPY = 4
private const val OUTPUT_PROTECTED_CONTENT = 8
private const val OUTPUT_TONE_MAP_HDR_TO_SDR = 16

internal fun NativePlayerOutputCapabilities.toNativeFlags(): Int =
    (if (hardwareFrameImport) OUTPUT_HARDWARE_FRAME_IMPORT else 0) or
        (if (softwareFrameUpload) OUTPUT_SOFTWARE_FRAME_UPLOAD else 0) or
        (if (zeroCopy) OUTPUT_ZERO_COPY else 0) or
        (if (protectedContent) OUTPUT_PROTECTED_CONTENT else 0) or
        (if (toneMapHdrToSdr) OUTPUT_TONE_MAP_HDR_TO_SDR else 0)

private fun nativeOutputCapabilities(flags: Int): NativePlayerOutputCapabilities? =
    flags.takeIf { it != 0 }?.let {
        NativePlayerOutputCapabilities(
            hardwareFrameImport = it and OUTPUT_HARDWARE_FRAME_IMPORT != 0,
            softwareFrameUpload = it and OUTPUT_SOFTWARE_FRAME_UPLOAD != 0,
            zeroCopy = it and OUTPUT_ZERO_COPY != 0,
            protectedContent = it and OUTPUT_PROTECTED_CONTENT != 0,
            toneMapHdrToSdr = it and OUTPUT_TONE_MAP_HDR_TO_SDR != 0,
        )
    }

/**
 * Builds a snapshot from the raw `ffplaykmp_snapshot` fields, however a binding reads them
 * (struct accessors, cinterop fields, or the browser worker's JSON). Unknown enum values from a
 * newer engine map to safe defaults instead of throwing.
 */
internal fun nativePlayerSnapshot(
    state: Int,
    positionUs: Long,
    durationUs: Long,
    queueSerial: UInt,
    outputFlags: Int,
    errorCode: Int,
    videoWidth: Int,
    videoHeight: Int,
    activeDecoder: Int,
    droppedFrames: Long,
    pixelFormat: Int,
    pixelFormatName: String?,
    bitDepth: Int,
    sampleAspectRatioNumerator: Int,
    sampleAspectRatioDenominator: Int,
    rotationDegrees: Double,
    colorPrimaries: Int,
    colorTransfer: Int,
    colorSpace: Int,
    colorRange: Int,
    chromaLocation: Int,
    hdrType: Int,
    masteringHasPrimaries: Boolean,
    masteringHasLuminance: Boolean,
    mastering: () -> DoubleArray,
    contentLightPresent: Boolean,
    maxContentLightLevel: Int,
    maxFrameAverageLightLevel: Int,
): NativePlayerSnapshot = NativePlayerSnapshot(
    state = NativePlayerState.entries.getOrElse(state) { NativePlayerState.FAILED },
    positionUs = positionUs,
    durationUs = durationUs.takeIf { it >= 0L },
    queueSerial = queueSerial,
    outputCapabilities = nativeOutputCapabilities(outputFlags),
    errorCode = errorCode,
    videoWidth = videoWidth,
    videoHeight = videoHeight,
    activeDecoder = NativePlayerDecoderKind.entries.getOrElse(activeDecoder) { NativePlayerDecoderKind.UNKNOWN },
    droppedFrames = droppedFrames,
    videoInfo = if (videoWidth > 0 && videoHeight > 0) {
        NativePlayerVideoInfo(
            width = videoWidth,
            height = videoHeight,
            pixelFormat = pixelFormat,
            pixelFormatName = pixelFormatName?.takeIf(String::isNotEmpty),
            bitDepth = bitDepth,
            sampleAspectRatioNumerator = sampleAspectRatioNumerator,
            sampleAspectRatioDenominator = sampleAspectRatioDenominator,
            rotationDegrees = rotationDegrees,
            colorPrimaries = colorPrimaries,
            colorTransfer = colorTransfer,
            colorSpace = colorSpace,
            colorRange = colorRange,
            chromaLocation = chromaLocation,
            hdrType = NativePlayerHdrType.entries.getOrElse(hdrType) { NativePlayerHdrType.UNKNOWN_HDR },
            masteringDisplay = if (masteringHasPrimaries || masteringHasLuminance) {
                // Red, green, blue and white x/y, then min and max luminance.
                val values = mastering()
                NativePlayerMasteringDisplayMetadata(
                    hasPrimaries = masteringHasPrimaries,
                    hasLuminance = masteringHasLuminance,
                    redX = values[0],
                    redY = values[1],
                    greenX = values[2],
                    greenY = values[3],
                    blueX = values[4],
                    blueY = values[5],
                    whiteX = values[6],
                    whiteY = values[7],
                    minLuminance = values[8],
                    maxLuminance = values[9],
                )
            } else {
                null
            },
            maxContentLightLevel = maxContentLightLevel.takeIf { contentLightPresent },
            maxFrameAverageLightLevel = maxFrameAverageLightLevel.takeIf { contentLightPresent },
        )
    } else {
        null
    },
)
