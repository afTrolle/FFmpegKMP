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

/**
 * Why a player whose output wanted a hardware decoder decodes in software, mirroring
 * `ffplaykmp_decoder_fallback`: none for the stream on this host, it failed to open or decode, or
 * the process's hardware decoder budget ([nativeDecoderBudget]) was full.
 */
@InternalFFmpegKmpApi
public enum class NativePlayerDecoderFallback { NONE, UNSUPPORTED, FAILED, BUDGET }

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
    /** Whether the stream signals Dolby Vision; the picture's own colour is [colorPrimaries], [colorTransfer] and [colorSpace]. */
    val dolbyVision: Boolean = false,
    val hdr10Plus: Boolean = false,
    val masteringDisplay: NativePlayerMasteringDisplayMetadata? = null,
    val maxContentLightLevel: Int? = null,
    val maxFrameAverageLightLevel: Int? = null,
)

@InternalFFmpegKmpApi
public data class NativePlayerConfiguration(
    val decoderPreference: NativePlayerDecoderPreference = NativePlayerDecoderPreference.AUTO,
    /**
     * The software decoder's threads, 0 for FFmpeg's automatic count.
     */
    val decoderThreads: Int = 0,
    /**
     * How long an idle player keeps its video decoder, in microseconds: 0 releases it as soon as
     * nothing is decoding, a negative value keeps it. `ffplaykmp_configuration.codec_release_delay_us`.
     */
    val codecReleaseDelayUs: Long = 2_000_000,
    /**
     * How many decoded frames the player keeps ready ahead of the one on screen, at least 1.
     * `ffplaykmp_configuration.frame_queue_size`; the browser's player ignores it.
     */
    val frameQueueSize: Int = 3,
    /**
     * FFmpeg's `thread_type` for [decoderThreads] (1 frame, 2 slice, 3 both), 0 for FFmpeg's
     * default, both. The browser's player ignores it.
     */
    val decoderThreadType: Int = 0,
    /**
     * How far behind the clock a decoded frame is, in microseconds, for the player to skip forward
     * to a keyframe; negative never skips. `ffplaykmp_configuration.catch_up_lag_us`; the browser's
     * player keeps 500 ms.
     */
    val catchUpLagUs: Long = 500_000,
    /**
     * An FFmpeg decoder the player opens by name, alone and with [decoderOptions], instead of
     * choosing one by [decoderPreference]; `null` to choose. The browser's player has none and
     * fails as unsupported.
     */
    val decoderName: String? = null,
    /** The named decoder's AVOptions. */
    val decoderOptions: Map<String, String> = emptyMap(),
) {
    init {
        require(frameQueueSize >= 1) { "The frame queue holds at least one frame: $frameQueueSize" }
        require(decoderThreadType in 0..3) { "Unknown decoder thread type: $decoderThreadType" }
        require(decoderName == null || decoderName.isNotEmpty()) { "A decoder name is not empty" }
    }
}

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
    /**
     * Android: MediaCodec frames are rendered into the output's own Surface as they are queued and
     * reach the [NativeVideoFrameSink] like software frames, which waits at prepare for their buffer
     * (`FFPLAYKMP_OUTPUT_PREPARE_HARDWARE_FRAMES`).
     */
    val prepareHardwareFrames: Boolean = false,
    /**
     * With [prepareHardwareFrames], the most hardware frames the player has queued at once, each
     * holding one of the output's buffers, up to 127; 0 for the frame queue's size
     * (`FFPLAYKMP_OUTPUT_HARDWARE_FRAMES_SHIFT`).
     */
    val hardwareFrameLimit: Int = 0,
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
    /** Catch-up skips forward to a keyframe since the source was prepared. */
    val catchUps: Long = 0,
    val decoderFallback: NativePlayerDecoderFallback = NativePlayerDecoderFallback.NONE,
    /** Why the last operation failed, in words, when the engine has more to say than [errorCode]. */
    val errorMessage: String? = null,
)

/**
 * A CPU-readable frame, as decoded, for a software output: the receiver owns [frame] and closes
 * it. [token] names it from [NativeVideoFrameSink.prepare] to its present or release. Secure/protected
 * sources must never produce this type.
 */
@InternalFFmpegKmpApi
public class NativeVideoFrame(
    public val frame: NativeFrame,
    public val width: Int,
    public val height: Int,
    public val presentationTimeUs: Long,
    public val queueSerial: UInt,
    public val token: Long = 0,
)

/**
 * Where a player's software frames go, mirroring `ffplaykmp_video_frame_event`. The player decodes
 * ahead: [prepare] gets each frame as it enters the player's frame queue, on the player's session
 * thread, and returns whether it keeps it; [frame][NativeVideoFrame.frame] is the sink's to close
 * either way. For a frame it kept, exactly one [present] (when [NativePlayerBridge.refresh] shows
 * it, on the refreshing thread, or at once for a preview, on the session thread) or [release] (when
 * it is dropped as late, flushed by a seek, stop or output change, or the player closes) follows,
 * naming its [token][NativeVideoFrame.token]. None of them may call the bridge's controls.
 */
@InternalFFmpegKmpApi
public interface NativeVideoFrameSink {
    public fun prepare(frame: NativeVideoFrame): Boolean
    public fun present(token: Long)

    /**
     * [present], told when the frame is due on screen: on the clock of the [NativePlayerBridge.refresh]
     * call that shows it (`ffplaykmp_video_frame.display_time_us`), or -1 for a preview, shown at
     * once. A sink that schedules its frames overrides this; the default presents at once.
     */
    public fun present(token: Long, displayTimeUs: Long): Unit = present(token)
    public fun release(token: Long)

    public companion object {
        /** Keeps no frame. */
        public val Discard: NativeVideoFrameSink = object : NativeVideoFrameSink {
            override fun prepare(frame: NativeVideoFrame): Boolean {
                frame.frame.close()
                return false
            }

            override fun present(token: Long) = Unit
            override fun release(token: Long) = Unit
        }
    }
}

/**
 * A sink that keeps each prepared frame and hands it to [present] as it is shown, which then owns
 * it; frames released unshown are closed.
 */
@InternalFFmpegKmpApi
public fun presentingFrames(present: (NativeVideoFrame) -> Unit): NativeVideoFrameSink = object : NativeVideoFrameSink {
    private val lock = IoLock()
    private val prepared = HashMap<Long, NativeVideoFrame>()

    override fun prepare(frame: NativeVideoFrame): Boolean {
        lock.withLock { prepared[frame.token] = frame }
        return true
    }

    override fun present(token: Long) {
        lock.withLock { prepared.remove(token) }?.let(present)
    }

    override fun release(token: Long) {
        lock.withLock { prepared.remove(token) }?.frame?.close()
    }
}

/** `ffplaykmp_video_frame_event` values. */
internal const val VIDEO_FRAME_PREPARE = 0
internal const val VIDEO_FRAME_PRESENT = 1
internal const val VIDEO_FRAME_RELEASE = 2

/**
 * Dispatches one `ffplaykmp_video_frame_callback` call to [sink]; [retain] gives the prepared frame a
 * reference of its own, null when there is none. While [open] is false (the bridge closes) frames are
 * neither taken nor shown, but released ones still are, as destroying the player flushes its queue.
 * A [NativeVideoFrameSink.prepare] that throws loses nothing: the token is released, the frame
 * closed and the frame not taken, so the player sends nothing more for that token. The failure goes
 * no further, as a throw cannot cross into the player's thread: the player goes on without the frame.
 * Returns the callback's result.
 */
internal inline fun dispatchVideoFrame(
    sink: NativeVideoFrameSink,
    event: Int,
    token: Long,
    open: Boolean,
    displayTimeUs: Long,
    retain: () -> NativeVideoFrame?,
): Int {
    when (event) {
        VIDEO_FRAME_PREPARE -> {
            if (!open) return 0
            val frame = retain() ?: return 0
            val taken = try {
                sink.prepare(frame)
            } catch (_: Throwable) {
                // Not taken, so the player sends this token nothing more: whatever the sink kept goes, and the frame.
                try {
                    sink.release(token)
                } catch (_: Throwable) {
                    // Released as far as the sink got.
                }
                frame.frame.close()
                false
            }
            return if (taken) 1 else 0
        }
        VIDEO_FRAME_PRESENT -> if (open) sink.present(token, displayTimeUs) else sink.release(token)
        VIDEO_FRAME_RELEASE -> sink.release(token)
    }
    return 0
}

/** What [NativePlayerBridge.refresh] returns while the player is not playing, `FFPLAYKMP_REFRESH_IDLE`. */
@InternalFFmpegKmpApi
public const val NATIVE_PLAYER_REFRESH_IDLE: Long = -1

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
    public const val TIMED_OUT: Int = -1007

    /** The caller holds every frame of the decoder's ring; the call fails and the decoder stays usable. */
    public const val RING_FULL: Int = -1008

    /** Every hardware decoder the process's budget allows is open, and the call required hardware. */
    public const val BUSY: Int = -1009
}

/** A player call that failed with [errorCode], a [NativePlayerError] code or an FFmpeg `AVERROR`. */
@InternalFFmpegKmpApi
public class NativePlayerException(message: String, errorCode: Int) : NativeCallException(message, errorCode)

/**
 * What the native players and decoders of the process have done since it started, mirroring
 * `ffplaykmp_statistics`, for tests and benchmarks: the video decoders opened and closed, the video
 * packets the players read, the hardware frames downloaded into memory, and the inputs the players,
 * decoders and audio engines opened and, of those, probed. Always zero in the browser, whose worker
 * reports its own.
 */
@InternalFFmpegKmpApi
public data class NativePlayerStatistics(
    val codecOpens: Long,
    val videoPackets: Long,
    val downloads: Long,
    val codecCloses: Long,
    val inputOpens: Long = 0,
    val inputProbes: Long = 0,
)

/**
 * What a player's prepare found when it probed its input, mirroring `ffplaykmp_input_hint`, for the
 * audio engine to open the same input without probing it again ([openPlatformAudioDecoder]): its
 * demuxer ([formatName], FFmpeg's), where its media time starts, its duration (negative when the
 * container does not say), and how many audio streams it has.
 */
@InternalFFmpegKmpApi
public data class NativeInputHint(
    val formatName: String,
    val startUs: Long,
    val durationUs: Long,
    val audioStreams: Int,
)

@InternalFFmpegKmpApi
public expect fun nativePlayerStatistics(): NativePlayerStatistics

/**
 * The process's hardware video decoder budget, mirroring `ffplaykmp_decoder_budget_get` and
 * `ffplaykmp_decoder_budget_in_use`: at most [hardwareDecoders] hardware decoders open at once, 0 for
 * no limit, shared by every player and video decoder, of which [inUse] are open now. The browser
 * has none: its player decodes in software, and WebCodecs keeps its own.
 */
@InternalFFmpegKmpApi
public data class NativeDecoderBudget(val hardwareDecoders: Int, val inUse: Int)

@InternalFFmpegKmpApi
public expect fun nativeDecoderBudget(): NativeDecoderBudget

/**
 * Sets the budget's limit (`ffplaykmp_decoder_budget_set`), 0 for none. Decoders already open stay
 * open; the next ones see the new limit. The browser keeps the value and applies it to nothing.
 */
@InternalFFmpegKmpApi
public expect fun setNativeDecoderBudget(hardwareDecoders: Int)

/**
 * What the platform says about decoding a video codec in hardware: whether it [available] has a
 * decoder for it at a size, and the most of them it says may be open at once, [maxInstances]; each
 * null where the platform does not say.
 */
@InternalFFmpegKmpApi
public data class NativeHardwareDecoderSupport(val available: Boolean?, val maxInstances: Int?)

/**
 * Asks the platform about decoding the codec FFmpeg names [codec] in hardware at [width] × [height]:
 * Android's MediaCodecList says both, VideoToolbox (`ffplaykmp_hardware_decoder_available`) only
 * whether, per codec, and other desktops and the browser neither.
 */
@InternalFFmpegKmpApi
public expect fun nativeHardwareDecoderSupport(codec: String, width: Int, height: Int): NativeHardwareDecoderSupport

@InternalFFmpegKmpApi
public interface NativePlayerBridge : AutoCloseable {
    /** Joins any cancelled worker and clears the cancellation before the next [prepare]. */
    public fun resetCancellation(): Unit = Unit
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
     * Asks for software frames of [width] × [height], the size the output draws them at, in the
     * frame's own orientation; 0 × 0 for each frame's own size. A bridge whose frames the page
     * cannot scale converts them to it (the browser's worker), from the next frame on, keeping the
     * display aspect; one larger than the frame, or not at its aspect, gives the frame's own. The
     * other bridges ignore it: their frames are converted where they are drawn.
     */
    public fun setFrameSize(width: Int, height: Int): Unit = Unit

    /**
     * Opens the prepared source's audio in the bridge itself, or returns null where the platform
     * audio engine plays it instead. Close it before the next [prepare] or [stop].
     */
    public suspend fun openAudio(): NativePlayerAudio? = null

    /**
     * What the last prepare found when it probed the source, for the platform audio engine to open
     * it without a probe of its own; null while nothing is prepared, after [close], and for bridges
     * whose audio, if any, opens itself.
     */
    public fun inputHint(): NativeInputHint? = null
    public fun stop(): Int
    public fun cancel()
    public fun snapshot(): NativePlayerSnapshot

    /**
     * Shows the queued frame due at [nowUs] + [leadUs], `ffplaykmp_player_refresh`: [nowUs] on the
     * player's monotonic clock (negative reads it), [leadUs] how long after [nowUs] the frame shown
     * becomes visible. Returns the microseconds until the next call, or [NATIVE_PLAYER_REFRESH_IDLE]
     * while not playing; after IDLE nothing becomes due before the next snapshot. Bridges that pace
     * frames themselves ([refreshes] false) return [NATIVE_PLAYER_REFRESH_IDLE]. After [close] it
     * does nothing.
     */
    public fun refresh(nowUs: Long = -1, leadUs: Long = 0): Long = NATIVE_PLAYER_REFRESH_IDLE

    /**
     * [refresh] for a player that follows an external clock, `ffplaykmp_player_refresh_at`: shows the
     * newest queued frame at or before [positionNs], the position in nanoseconds the clock gives the
     * display frame at [nowUs], compared in the stream's time base as `VideoDecoder.frameAt` compares
     * it, and holds the one on screen while none has been reached; starting the play run that decodes
     * ahead, and seeking, remain the caller's. Only bridges that [refreshes]; the others throw
     * [UnsupportedOperationException].
     */
    public fun refreshAt(nowUs: Long, leadUs: Long, positionNs: Long): Long =
        throw UnsupportedOperationException("This player paces its own frames and cannot follow a clock")

    /** The monotonic clock [refresh] reads, in microseconds (`ffplaykmp_clock_now_us`), where it [refreshes]. */
    public fun clockNowUs(): Long =
        throw UnsupportedOperationException("This player paces its own frames and has no refresh clock")

    /** Whether frames are shown only by [refresh]: false where the bridge paces them itself. */
    public val refreshes: Boolean get() = false
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
 * Audio a bridge plays itself, with [NativeAudioDecoder]'s mixer. Gains apply live; track changes
 * reach what is heard after the little audio decoded ahead.
 */
@InternalFFmpegKmpApi
public interface NativePlayerAudio : NativeAudioMixer, AutoCloseable {
    public fun play()
    public fun pause()
    public fun seek(positionMicros: Long)
    public fun setProgressListener(listener: (NativeAudioProgress) -> Unit)
}

/** [frame] gets the software frames; see [NativeVideoFrameSink]. [platformFrame] is called as a hardware frame is shown. */
@InternalFFmpegKmpApi
public expect fun createPlatformPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: NativeVideoFrameSink = NativeVideoFrameSink.Discard,
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
private const val OUTPUT_PREPARE_HARDWARE_FRAMES = 32
private const val OUTPUT_HARDWARE_FRAMES_SHIFT = 24
private const val OUTPUT_HARDWARE_FRAMES_MASK = 0x7f

/** `ffplaykmp_snapshot.hdr_flags` bits. */
private const val HDR_DOLBY_VISION = 1
private const val HDR_HDR10_PLUS = 2

/** FFmpeg's `AVCOL_TRC_SMPTE2084` (PQ) and `AVCOL_TRC_ARIB_STD_B67` (HLG). */
@InternalFFmpegKmpApi
public const val AVCOL_TRC_SMPTE2084: Int = 16

@InternalFFmpegKmpApi
public const val AVCOL_TRC_ARIB_STD_B67: Int = 18

/** Raw `ffplaykmp_player_*` calls for one native player, implemented per platform binding. */
internal interface PlayerEngineCalls {
    /** Replaces the mounts the player's I/O callback serves, ids counting from 1. */
    fun mount(mounts: List<NativeMountedIo>)
    fun prepare(input: String, requireSecurePath: Boolean): Int
    fun resetCancel()
    fun setOutput(flags: Int): Int
    fun setPlatformOutputTarget(target: Any?, secure: Boolean): Int
    fun clearOutput()
    fun play(): Int
    fun pause(): Int
    fun seek(positionUs: Long): Int
    fun stop(): Int
    fun cancel()
    fun setMasterClock(mediaTimeUs: Long)
    fun refresh(nowUs: Long, leadUs: Long): Long
    fun refreshAt(nowUs: Long, leadUs: Long, positionNs: Long): Long
    fun clockNowUs(): Long
    fun snapshot(): NativePlayerSnapshot

    /** `ffplaykmp_player_get_input_hint`: null while nothing is prepared. */
    fun inputHint(): NativeInputHint?

    /** Frees the player and anything the binding keeps alive for it. */
    fun release()
}

/**
 * A [NativePlayerBridge] over [engine]. Every call enters [guard], which the engine's native
 * callbacks also check, so [close] never frees the player under a call or a callback.
 */
internal class GuardedPlayerBridge(
    private val guard: NativeHandleGuard,
    private val engine: PlayerEngineCalls,
) : NativePlayerBridge {
    override fun prepare(source: NativePlayerSource): Int = call {
        engine.mount(source.mounts)
        engine.prepare(source.protocolInput(), source.requireSecurePath)
            .also { result -> if (result < 0) engine.mount(emptyList()) }
    }

    // Joins the outgoing worker before a subsequent prepare reuses its mount ids.
    override fun resetCancellation() = call { engine.resetCancel() }

    override fun setOutput(capabilities: NativePlayerOutputCapabilities): Int =
        call { engine.setOutput(capabilities.toNativeFlags()) }

    override fun setPlatformOutputTarget(target: Any?, secure: Boolean): Int =
        call { engine.setPlatformOutputTarget(target, secure) }

    override fun clearOutput() = call { engine.clearOutput() }
    override fun play(): Int = call { engine.play() }
    override fun pause(): Int = call { engine.pause() }
    override fun seek(positionUs: Long): Int = call { engine.seek(positionUs) }
    override fun stop(): Int = call { engine.stop().also { if (it == 0) engine.mount(emptyList()) } }
    override fun snapshot(): NativePlayerSnapshot = call { engine.snapshot() }

    /** Cross-thread calls: after [close] they do nothing. */
    override fun setMasterClock(mediaTimeUs: Long) = guard.use({}) { engine.setMasterClock(mediaTimeUs) }
    override val refreshes: Boolean get() = true
    override fun refresh(nowUs: Long, leadUs: Long): Long =
        guard.use({ NATIVE_PLAYER_REFRESH_IDLE }) { engine.refresh(nowUs, leadUs) }
    override fun refreshAt(nowUs: Long, leadUs: Long, positionNs: Long): Long =
        guard.use({ NATIVE_PLAYER_REFRESH_IDLE }) { engine.refreshAt(nowUs, leadUs, positionNs) }
    override fun clockNowUs(): Long = engine.clockNowUs()
    override fun cancel() = guard.use({}) { engine.cancel() }
    override fun inputHint(): NativeInputHint? = guard.use({ null }) { engine.inputHint() }

    // The player keeps its input open until it is released, so its IO_CLOSE must still find the mounts.
    override fun close() = guard.close {
        engine.release()
        engine.mount(emptyList())
    }

    private inline fun <T> call(block: () -> T): T = guard.use(::closedError, block)

    private fun closedError(): Nothing = throw IllegalStateException("The native player bridge is closed")
}

internal fun NativePlayerOutputCapabilities.toNativeFlags(): Int =
    (if (hardwareFrameImport) OUTPUT_HARDWARE_FRAME_IMPORT else 0) or
        (if (softwareFrameUpload) OUTPUT_SOFTWARE_FRAME_UPLOAD else 0) or
        (if (zeroCopy) OUTPUT_ZERO_COPY else 0) or
        (if (protectedContent) OUTPUT_PROTECTED_CONTENT else 0) or
        (if (toneMapHdrToSdr) OUTPUT_TONE_MAP_HDR_TO_SDR else 0) or
        (if (prepareHardwareFrames) OUTPUT_PREPARE_HARDWARE_FRAMES else 0) or
        (hardwareFrameLimit.coerceIn(0, OUTPUT_HARDWARE_FRAMES_MASK) shl OUTPUT_HARDWARE_FRAMES_SHIFT)

private fun nativeOutputCapabilities(flags: Int): NativePlayerOutputCapabilities? =
    flags.takeIf { it != 0 }?.let {
        NativePlayerOutputCapabilities(
            hardwareFrameImport = it and OUTPUT_HARDWARE_FRAME_IMPORT != 0,
            softwareFrameUpload = it and OUTPUT_SOFTWARE_FRAME_UPLOAD != 0,
            zeroCopy = it and OUTPUT_ZERO_COPY != 0,
            protectedContent = it and OUTPUT_PROTECTED_CONTENT != 0,
            toneMapHdrToSdr = it and OUTPUT_TONE_MAP_HDR_TO_SDR != 0,
            prepareHardwareFrames = it and OUTPUT_PREPARE_HARDWARE_FRAMES != 0,
            hardwareFrameLimit = (it ushr OUTPUT_HARDWARE_FRAMES_SHIFT) and OUTPUT_HARDWARE_FRAMES_MASK,
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
    hdrFlags: Int,
    masteringHasPrimaries: Boolean,
    masteringHasLuminance: Boolean,
    mastering: () -> DoubleArray,
    contentLightPresent: Boolean,
    maxContentLightLevel: Int,
    maxFrameAverageLightLevel: Int,
    decoderFallback: Int = 0,
    errorMessage: String? = null,
    catchUps: Long = 0,
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
    catchUps = catchUps,
    decoderFallback = NativePlayerDecoderFallback.entries.getOrElse(decoderFallback) { NativePlayerDecoderFallback.FAILED },
    errorMessage = errorMessage?.takeIf(String::isNotEmpty),
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
            dolbyVision = hdrFlags and HDR_DOLBY_VISION != 0,
            hdr10Plus = hdrFlags and HDR_HDR10_PLUS != 0,
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
