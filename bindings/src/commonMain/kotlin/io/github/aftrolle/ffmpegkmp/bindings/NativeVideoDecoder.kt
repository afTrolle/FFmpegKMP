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
    /** A new reference to the frame's pixels, which the receiver closes; null when the frame lies in [gpu]. */
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
    /** Why AUTO decodes in software, as it was when the decoder opened. */
    public val decoderFallback: NativePlayerDecoderFallback = NativePlayerDecoderFallback.NONE,
)

/**
 * The native pull decoder (`ffmpegkmp_decoder.c`), or WebCodecs in a worker in the browser.
 * [start], [seek], [frameAt], [setSize], [reopen] and [close] must not overlap and, on Android, must run on the thread
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
     * Changes the memory format's frame size, 0 and 0 for the source's, as `memoryWidth` and
     * `memoryHeight` of [createPlatformVideoDecoder] take it: the next [frameAt] converts the
     * current frame again at that size, without decoding. Frames handed out before keep theirs.
     * Throws [UnsupportedOperationException] where frames are not converted by the decoder: GPU
     * buffers, and the browser.
     */
    public suspend fun setSize(width: Int, height: Int)

    /**
     * Closes the input and readies the decoder for [source], which the next [start] opens, as a decoder created for it
     * with [memoryFormat], [memoryWidth] × [memoryHeight] and [timeoutMicros], as [createPlatformVideoDecoder] takes them,
     * would be; the output, decoder preference or name, threads and ring stay. That [start] keeps the open codec,
     * flushed, when the new video stream is coded the same way, and closes it otherwise. Frames handed out keep their
     * memory. Throws [UnsupportedOperationException] for GPU buffer output and in the browser.
     */
    public suspend fun reopen(
        source: NativePlayerSource,
        memoryFormat: NativeFrameFormat?,
        memoryWidth: Int,
        memoryHeight: Int,
        timeoutMicros: Long,
    ): Unit = throw UnsupportedOperationException("This decoder does not reopen")

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

/** A decoder call that failed; [reason], where the decoder gave one (a named decoder's), says why in words. */
@InternalFFmpegKmpApi
public class NativeVideoDecoderException(
    message: String,
    errorCode: Int,
    public val reason: String? = null,
) : NativeCallException(message, errorCode)

/**
 * Creates a decoder for [source] without reading it; [NativeVideoDecoder.start] opens it.
 * [memoryFormat] is the format of the frames handed out in memory, null for frames as decoded:
 * every [NativeVideoDecoderOutput.MEMORY] frame, and a [NativeVideoDecoderOutput.GPU_BUFFERS]
 * decoder's software frames, and [memoryWidth] and [memoryHeight] its frames' size, 0 for the source's: frames are
 * scaled to it as they are converted, with the sample aspect ratio that keeps their display
 * aspect. [decoderThreads] is the software decoder's thread count, 0 for FFmpeg's automatic count,
 * and [decoderThreadType] its `thread_type`, FF_THREAD_FRAME (1) and FF_THREAD_SLICE (2) bits,
 * 0 for FFmpeg's default of both; hardware decoders ignore both. The frames' conversions work with
 * as many threads. [frameRing] is how many frames the caller may hold at once, at least 2; the
 * browser keeps 3. A [decoderName] opens that FFmpeg
 * decoder alone, with [decoderOptions] as its AVOptions, instead of choosing by [decoderPreference]:
 * no fallback, and [NativeVideoDecoder.start] fails with a [NativeVideoDecoderException.reason]
 * when it does not open; the browser fails it as unsupported. [timeoutMicros] bounds each start, seek and frameAt natively (0 for none): calls past it fail
 * with [NativePlayerError.TIMED_OUT]. Other platforms reject [NativeVideoDecoderOutput.GPU_BUFFERS], which needs
 * Android 14 (API 34).
 */
/** The frames a decoder's ring holds unless told otherwise. */
internal const val DEFAULT_FRAME_RING: Int = 3

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
    decoderThreadType: Int = 0,
    frameRing: Int = 3,
    decoderName: String? = null,
    decoderOptions: Map<String, String> = emptyMap(),
): NativeVideoDecoder

/** Raw `ffmpegkmp_video_decoder_*` calls for one open decoder, implemented per platform binding. */
internal interface VideoDecoderEngineCalls {
    fun start(): Int
    fun info(): NativePlayerSnapshot
    fun seek(positionNanos: Long): Int

    /** Describes the frame at [positionNanos] with a new reference to it, or throws [NativeVideoDecoderException]. */
    fun frameAt(positionNanos: Long): NativeDecodedFrame
    fun setSize(width: Int, height: Int): Int

    /** [NativeVideoDecoder.reopen]'s native call. */
    fun reopen(
        source: NativePlayerSource,
        memoryFormat: NativeFrameFormat?,
        memoryWidth: Int,
        memoryHeight: Int,
        timeoutMicros: Long,
    ): Int = throw UnsupportedOperationException("This decoder does not reopen")

    fun interrupt()
    fun abort()
    fun timeLeft(): Long

    /** Why the last call failed, in the decoder's words, where it says (a named decoder's open); null otherwise. */
    fun error(): String? = null

    /** Frees the decoder and anything the binding keeps alive for it. */
    fun release()
}

internal class GuardedVideoDecoder(
    private val engine: VideoDecoderEngineCalls,
    private var input: String,
) : NativeVideoDecoder {
    private val guard = NativeHandleGuard()

    override suspend fun start(): NativeVideoStream = guard.use(::closedError) {
        requireNative(engine.start(), "decode video from '$input'") { message, code ->
            val reason = engine.error()?.takeIf(String::isNotEmpty)
            NativeVideoDecoderException(reason?.let { "$message: $it" } ?: message, code, reason)
        }
        val snapshot = engine.info()
        NativeVideoStream(
            info = snapshot.videoInfo
                ?: throw NativeVideoDecoderException("'$input' reports no video size", NativePlayerError.UNSUPPORTED),
            activeDecoder = snapshot.activeDecoder,
            durationMicros = snapshot.durationUs ?: -1L,
            decoderFallback = snapshot.decoderFallback,
        )
    }

    override suspend fun seek(positionNanos: Long) = guard.use(::closedError) {
        require(positionNanos >= 0) { "Seek position must not be negative" }
        requireNative(engine.seek(positionNanos), "seek to ${positionNanos}ns", ::NativeVideoDecoderException)
    }

    override suspend fun frameAt(positionNanos: Long): NativeDecodedFrame = guard.use(::closedError) {
        require(positionNanos >= 0) { "Frame position must not be negative" }
        engine.frameAt(positionNanos)
    }

    override suspend fun setSize(width: Int, height: Int) = guard.use(::closedError) {
        requireNative(engine.setSize(width, height), "resize frames to ${width}x$height", ::NativeVideoDecoderException)
    }

    override suspend fun reopen(
        source: NativePlayerSource,
        memoryFormat: NativeFrameFormat?,
        memoryWidth: Int,
        memoryHeight: Int,
        timeoutMicros: Long,
    ) = guard.use(::closedError) {
        requireNative(
            engine.reopen(source, memoryFormat, memoryWidth, memoryHeight, timeoutMicros),
            "reopen the decoder for '${source.input}'",
            ::NativeVideoDecoderException,
        )
        input = source.input
    }

    override fun interrupt() = guard.use({}) { engine.interrupt() }

    override fun abort() = guard.use({}) { engine.abort() }

    override fun timeLeftMicros(): Long = guard.use({ 0L }) { engine.timeLeft() }

    override fun close() = guard.close(engine::release)

    private fun closedError(): Nothing = throw IllegalStateException("The video decoder is closed")
}

/** Fails, as platforms other than Android do, for the output only Android has. */
internal fun requireMemoryOutput(output: NativeVideoDecoderOutput) {
    require(output == NativeVideoDecoderOutput.MEMORY) {
        "GPU buffer output is only available on Android 14 (API 34) and later"
    }
}

/** The `ffmpegkmp:<id>` URL of the mount [NativePlayerSource.input] names, ids counting from 1. */
internal fun NativePlayerSource.protocolInput(): String =
    mounts.indexOfFirst { it.path == input }
        .takeIf { it >= 0 }
        ?.let { protocolUrl(it.toLong() + 1L, input) }
        ?: input
