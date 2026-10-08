// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

/**
 * How a decoder hands out its frames. [value] is the `ffmpegkmp_video_output` C value: a
 * [GPU_BUFFERS] decoder renders into an `ImageReader`'s Surface, which C sees as Surface output.
 */
@InternalFFmpegKmpApi
public enum class NativeVideoDecoderOutput(public val value: Int) {
    MEMORY(0),
    SURFACE(1),

    /** Android 14 and later: frames in an `ImageReader`'s `HardwareBuffer`s, as [NativeDecodedFrame.gpu]. */
    GPU_BUFFERS(1),
}

/** One decoded frame as `ffmpegkmp_decoded_frame` describes it. */
@InternalFFmpegKmpApi
public class NativeDecodedFrame(
    /** Changes whenever a different decoded frame becomes current. */
    public val serial: Long,
    public val ptsNanos: Long,
    public val durationNanos: Long,
    public val width: Int,
    public val height: Int,
    public val sampleAspectRatioNumerator: Int,
    public val sampleAspectRatioDenominator: Int,
    public val rotationDegrees: Double,
    public val hardware: Boolean,
    /** A new reference to the frame's pixels, which the receiver closes; null when it was rendered to a Surface. */
    public val frame: NativeFrame?,
    /** A new reference to the GPU buffer the frame was rendered into, which the receiver releases; null for others. */
    public val gpu: NativeGpuBuffer? = null,
)

/** What [NativeVideoDecoder.start] opened: the video stream and the decoder that took it. */
@InternalFFmpegKmpApi
public class NativeVideoStream(
    public val info: NativePlayerVideoInfo,
    public val activeDecoder: NativePlayerDecoderKind,
    /** Negative when the input does not report a duration. */
    public val durationMicros: Long,
)

/**
 * The native pull decoder (`ffmpegkmp_decoder.c`), or WebCodecs in a worker in the browser.
 * [start], [seek], [frameAt] and [close] must not overlap and, on Android, must run on the thread
 * that started it. [interrupt], [abort] and [timeLeftMicros] are safe from any thread. Only the
 * browser's calls suspend; the native ones block their thread.
 */
@InternalFFmpegKmpApi
public interface NativeVideoDecoder : AutoCloseable {
    /** Opens the input and decodes its first frame. */
    public suspend fun start(): NativeVideoStream
    public suspend fun seek(positionNanos: Long)
    public suspend fun frameAt(positionNanos: Long): NativeDecodedFrame

    /**
     * Fails the in-flight call, whose caller no longer wants it; later calls work, the next one
     * seeking first. Between calls it has no effect.
     */
    public fun interrupt()

    /** Unblocks an in-flight call on a stalled input; later calls fail. */
    public fun abort()

    /**
     * Microseconds until the running call's native deadline, negative once it has passed; 0 when
     * no call with a deadline is running. A native fallback can move the deadline later.
     */
    public fun timeLeftMicros(): Long
}

@InternalFFmpegKmpApi
public class NativeVideoDecoderException(
    message: String,
    public val errorCode: Int,
) : IllegalStateException(message)

/**
 * Creates a decoder for [source] without reading it; [NativeVideoDecoder.start] opens it.
 * [memoryFormat] is the [NativeVideoDecoderOutput.MEMORY] frames' format, null for frames as
 * decoded, and [memoryWidth] and [memoryHeight] its frames' size, 0 for the source's: frames are
 * scaled to it as they are converted, with the sample aspect ratio that keeps their display
 * aspect. [decoderThreads] is the software decoder's thread count, 0 for FFmpeg's automatic count capped
 * at 8; hardware decoders ignore it. [timeoutMicros] bounds each start, seek and frameAt natively (0 for none): calls past it fail
 * with [NativePlayerError.TIMED_OUT]. [surface] is the `android.view.Surface` a
 * [NativeVideoDecoderOutput.SURFACE] decoder renders into; other platforms reject that output, and
 * [NativeVideoDecoderOutput.GPU_BUFFERS] too, which needs Android 14 (API 34).
 */
@InternalFFmpegKmpApi
public expect fun createPlatformVideoDecoder(
    source: NativePlayerSource,
    output: NativeVideoDecoderOutput,
    memoryFormat: NativeFrameFormat?,
    memoryWidth: Int,
    memoryHeight: Int,
    decoderPreference: NativePlayerDecoderPreference,
    decoderThreads: Int,
    timeoutMicros: Long,
    surface: Any? = null,
): NativeVideoDecoder

/** Raw `ffmpegkmp_video_decoder_*` calls for one open decoder, implemented per platform binding. */
internal interface VideoDecoderEngineCalls {
    fun start(): Int
    fun info(): NativePlayerSnapshot
    fun seek(positionNanos: Long): Int

    /** Describes the frame at [positionNanos] with a new reference to it, or throws [NativeVideoDecoderException]. */
    fun frameAt(positionNanos: Long): NativeDecodedFrame
    fun interrupt()
    fun abort()
    fun timeLeft(): Long

    /** Frees the decoder and anything the binding keeps alive for it. */
    fun release()
}

internal class GuardedVideoDecoder(
    private val engine: VideoDecoderEngineCalls,
    private val input: String,
) : NativeVideoDecoder {
    private val guard = NativeHandleGuard()

    override suspend fun start(): NativeVideoStream = guard.use(::closedError) {
        requireVideoSuccess(engine.start(), "decode video from '$input'")
        val snapshot = engine.info()
        NativeVideoStream(
            info = snapshot.videoInfo
                ?: throw NativeVideoDecoderException("'$input' reports no video size", NativePlayerError.UNSUPPORTED),
            activeDecoder = snapshot.activeDecoder,
            durationMicros = snapshot.durationUs ?: -1L,
        )
    }

    override suspend fun seek(positionNanos: Long) = guard.use(::closedError) {
        require(positionNanos >= 0) { "Seek position must not be negative" }
        requireVideoSuccess(engine.seek(positionNanos), "seek to ${positionNanos}ns")
    }

    override suspend fun frameAt(positionNanos: Long): NativeDecodedFrame = guard.use(::closedError) {
        require(positionNanos >= 0) { "Frame position must not be negative" }
        engine.frameAt(positionNanos)
    }

    override fun interrupt() = guard.use({}) { engine.interrupt() }

    override fun abort() = guard.use({}) { engine.abort() }

    override fun timeLeftMicros(): Long = guard.use({ 0L }) { engine.timeLeft() }

    override fun close() = guard.close(engine::release)

    private fun closedError(): Nothing = throw IllegalStateException("The video decoder is closed")
}

/** Fails, as platforms other than Android do, for the outputs only Android has. */
internal fun requireMemoryOutput(output: NativeVideoDecoderOutput) {
    require(output == NativeVideoDecoderOutput.MEMORY) {
        if (output == NativeVideoDecoderOutput.SURFACE) {
            "Surface output is only available on Android"
        } else {
            "GPU buffer output is only available on Android 14 (API 34) and later"
        }
    }
}

internal fun requireVideoSuccess(result: Int, action: String) {
    if (result < 0) throw NativeVideoDecoderException("Could not $action (error $result)", result)
}

/** The `ffmpegkmp:<id>` URL of the mount [NativePlayerSource.input] names, ids counting from 1. */
internal fun NativePlayerSource.protocolInput(): String =
    mounts.indexOfFirst { it.path == input }
        .takeIf { it >= 0 }
        ?.let { protocolUrl(it.toLong() + 1L, input) }
        ?: input
