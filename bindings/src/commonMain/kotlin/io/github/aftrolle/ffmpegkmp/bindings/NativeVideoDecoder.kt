// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

/** `ffmpegkmp_video_output`: the ordinal is the C value. */
@InternalFFmpegKmpApi
public enum class NativeVideoDecoderOutput { RGBA8, LINEAR_F16, SURFACE, PIXEL_BUFFER }

/** One decoded frame as `ffmpegkmp_decoded_frame` describes it. */
@InternalFFmpegKmpApi
public class NativeDecodedFrame(
    /** Changes whenever a different decoded frame becomes current. */
    public val serial: Long,
    public val ptsNanos: Long,
    public val durationNanos: Long,
    /**
     * RGBA8 or RGBA F16 rows of [stride] bytes. The array is reused by the decoder's next frame,
     * so copy it before calling the decoder again. Null when the frame was rendered to a Surface.
     */
    public val pixels: ByteArray?,
    public val width: Int,
    public val height: Int,
    public val stride: Int,
    public val sampleAspectRatioNumerator: Int,
    public val sampleAspectRatioDenominator: Int,
    public val rotationDegrees: Double,
    public val hardware: Boolean,
    /** The frame's CVPixelBuffer for [NativeVideoDecoderOutput.PIXEL_BUFFER] output. */
    public val pixelBuffer: NativePixelBuffer? = null,
)

/**
 * A decoded frame's platform buffer (a `CVPixelBufferRef` on Apple), borrowed from the decoder
 * until its next call. Each [retain] keeps it past that and needs one [release].
 */
@InternalFFmpegKmpApi
public abstract class NativePixelBuffer(
    public val handle: Any,
    /** The CoreVideo pixel format, an OSType. */
    public val pixelFormat: Int,
    public val ioSurfaceBacked: Boolean,
    /** FFmpeg's AVColorPrimaries, AVColorTransferCharacteristic, AVColorSpace and AVColorRange. */
    public val colorPrimaries: Int,
    public val colorTransfer: Int,
    public val colorSpace: Int,
    public val colorRange: Int,
) {
    public abstract fun retain()

    public abstract fun release()
}

/** What [NativeVideoDecoder.start] opened: the video stream and the decoder that took it. */
@InternalFFmpegKmpApi
public class NativeVideoStream(
    public val info: NativePlayerVideoInfo,
    public val activeDecoder: NativePlayerDecoderKind,
    /** Negative when the input does not report a duration. */
    public val durationMicros: Long,
)

/**
 * The native pull decoder (`ffmpegkmp_decoder.c`). [start], [seek], [frameAt] and [close] must not
 * overlap and, on Android, must run on the thread that started it. [abort] and [timeLeftMicros]
 * are safe from any thread.
 */
@InternalFFmpegKmpApi
public interface NativeVideoDecoder : AutoCloseable {
    /** Opens the input and decodes its first frame. */
    public fun start(): NativeVideoStream
    public fun seek(positionNanos: Long)
    public fun frameAt(positionNanos: Long): NativeDecodedFrame

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
 * [timeoutMicros] bounds each start, seek and frameAt natively (0 for none): calls past it fail
 * with [NativePlayerError.TIMED_OUT]. [surface] is the `android.view.Surface` a
 * [NativeVideoDecoderOutput.SURFACE] decoder renders into; other platforms reject that output.
 */
@InternalFFmpegKmpApi
public expect fun createPlatformVideoDecoder(
    source: NativePlayerSource,
    output: NativeVideoDecoderOutput,
    decoderPreference: NativePlayerDecoderPreference,
    timeoutMicros: Long,
    surface: Any? = null,
): NativeVideoDecoder

/** Raw `ffmpegkmp_video_decoder_*` calls for one open decoder, implemented per platform binding. */
internal interface VideoDecoderEngineCalls {
    fun start(): Int
    fun info(): NativePlayerSnapshot
    fun seek(positionNanos: Long): Int

    /** Describes the frame at [positionNanos], or throws [NativeVideoDecoderException]. */
    fun frameAt(positionNanos: Long): NativeDecodedFrame
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

    override fun start(): NativeVideoStream = guard.use(::closedError) {
        requireVideoSuccess(engine.start(), "decode video from '$input'")
        val snapshot = engine.info()
        NativeVideoStream(
            info = snapshot.videoInfo
                ?: throw NativeVideoDecoderException("'$input' reports no video size", NativePlayerError.UNSUPPORTED),
            activeDecoder = snapshot.activeDecoder,
            durationMicros = snapshot.durationUs ?: -1L,
        )
    }

    override fun seek(positionNanos: Long) = guard.use(::closedError) {
        require(positionNanos >= 0) { "Seek position must not be negative" }
        requireVideoSuccess(engine.seek(positionNanos), "seek to ${positionNanos}ns")
    }

    override fun frameAt(positionNanos: Long): NativeDecodedFrame = guard.use(::closedError) {
        require(positionNanos >= 0) { "Frame position must not be negative" }
        engine.frameAt(positionNanos)
    }

    override fun abort() = guard.use({}) { engine.abort() }

    override fun timeLeftMicros(): Long = guard.use({ 0L }) { engine.timeLeft() }

    override fun close() = guard.close(engine::release)

    private fun closedError(): Nothing = throw IllegalStateException("The video decoder is closed")
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

/** Reuses one array per frame size: frames are copied out before the next decode. */
internal class PixelBuffer {
    private var bytes = ByteArray(0)

    fun take(size: Int): ByteArray {
        if (bytes.size != size) bytes = ByteArray(size)
        return bytes
    }
}
