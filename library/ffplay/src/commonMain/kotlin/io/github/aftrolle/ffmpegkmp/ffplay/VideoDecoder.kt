// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.ImageBitmap
import io.github.aftrolle.ffmpegkmp.bindings.NativeBridgeUnavailableException
import io.github.aftrolle.ffmpegkmp.bindings.NativeDecodedFrame
import io.github.aftrolle.ffmpegkmp.bindings.NativePixelBuffer
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderKind
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerError
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSource
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoDecoder
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoDecoderException
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoDecoderOutput
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoStream
import io.github.aftrolle.ffmpegkmp.bindings.createPlatformVideoDecoder
import io.github.aftrolle.ffmpegkmp.core.FFmpegKmpException
import io.github.aftrolle.ffmpegkmp.core.toNativeMounts
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How [VideoDecoder] hands out its frames. */
public sealed interface VideoOutput {
    /** 8-bit sRGB images; PQ and HLG sources are tone mapped to BT.709. */
    public data object Rgba8 : VideoOutput

    /**
     * Half-float images in linear extended sRGB, where 1.0 is SDR reference white (203 nits for
     * PQ and HLG, BT.2408). HDR highlights keep their values above 1.0. Needs Android 8.0 (API 26).
     */
    public data object LinearF16 : VideoOutput

    /**
     * Android only: MediaCodec renders each frame into this `android.view.Surface` (for example
     * an `ImageReader`'s) with its pts as the buffer timestamp, and [VideoFrame.image] is null.
     * Where no hardware decoder takes the source, frames fall back to RGBA8 images and
     * [VideoDecoder.decoderKind] reports [FFplayDecoderKind.SOFTWARE].
     */
    public data class Surface(val surface: Any) : VideoOutput

    /**
     * Apple only: each frame carries the `CVPixelBuffer` VideoToolbox decoded into, as
     * [VideoFrame.pixelBuffer], and [VideoFrame.image] is null. Where VideoToolbox does not take
     * the source, software frames are copied into pooled IOSurface-backed NV12 (8-bit) or P010
     * (deeper) buffers and [VideoDecoder.decoderKind] reports [FFplayDecoderKind.SOFTWARE].
     * Other platforms throw [UnsupportedOperationException] from [VideoDecoder.open].
     *
     * Each frame [VideoDecoder.frameAt] returns holds a retain of its buffer until
     * [VideoFrame.close], also past the decoder's next call and its close, so frameAt returns a new
     * [VideoFrame] every call, sharing the buffer while the same frame is shown.
     * [VideoDecoder.current] is the decoder's own frame: valid until its next call, and closing
     * it does nothing.
     */
    public data object PixelBuffer : VideoOutput
}

/**
 * The CoreVideo buffer of a [VideoOutput.PixelBuffer] frame; `cvPixelBuffer` on Apple is the
 * `CVPixelBufferRef`. Its colour attachments match the colour fields, named as in [FFplayVideoInfo].
 */
public class VideoPixelBuffer internal constructor(
    internal val native: NativePixelBuffer,
    public val width: Int,
    public val height: Int,
    /**
     * The CoreVideo pixel format, an OSType: `'420v'` or `'420f'` for 8-bit 4:2:0
     * (`kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange`/`FullRange`), `'x420'` or `'xf20'` for
     * 10-bit (`kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange`/`FullRange`), or whatever else
     * VideoToolbox decoded into.
     */
    public val pixelFormat: Int,
    /** Whether an IOSurface backs the buffer, which `CVMetalTextureCache` needs. */
    public val ioSurfaceBacked: Boolean,
    public val colorPrimaries: String?,
    public val colorTransfer: String?,
    public val colorMatrix: String?,
    public val colorRange: String?,
) {
    override fun toString(): String =
        "VideoPixelBuffer(${width}x$height, pixelFormat=${pixelFormat.fourCc()}, ioSurfaceBacked=$ioSurfaceBacked, " +
            "colorPrimaries=$colorPrimaries, colorTransfer=$colorTransfer, colorMatrix=$colorMatrix, colorRange=$colorRange)"
}

private fun Int.fourCc(): String = (24 downTo 0 step 8).map { shift -> (this shr shift and 0xff).toChar() }.joinToString("")

/**
 * One decoded frame, shown from [pts] for [duration]. Rotation and sample aspect ratio are
 * reported, never applied to the pixels.
 *
 * [close] releases a [VideoOutput.PixelBuffer] frame's buffer and does nothing for the other
 * outputs.
 */
public class VideoFrame(
    public val pts: Duration,
    public val duration: Duration,
    /** Null when the frame was rendered to a [VideoOutput.Surface] or carries a [pixelBuffer]. */
    public val image: ImageBitmap?,
    public val rotationDegrees: Double,
    public val sampleAspectRatio: Double,
    /** The frame's buffer for [VideoOutput.PixelBuffer]; null for the other outputs. */
    public val pixelBuffer: VideoPixelBuffer? = null,
) : AutoCloseable {
    private val retained = AtomicBoolean(false)
    private var ownedByDecoder = false

    /** A frame holding one more retain of [pixelBuffer]; the decoder's own is released by it alone. */
    internal fun retainedCopy(ownedByDecoder: Boolean = false): VideoFrame =
        VideoFrame(pts, duration, image, rotationDegrees, sampleAspectRatio, pixelBuffer).also { copy ->
            pixelBuffer?.native?.retain()
            copy.retained.store(true)
            copy.ownedByDecoder = ownedByDecoder
        }

    internal fun releaseBuffer() {
        if (retained.compareAndSet(expectedValue = true, newValue = false)) pixelBuffer?.native?.release()
    }

    override fun close() {
        if (!ownedByDecoder) releaseBuffer()
    }

    override fun toString(): String =
        "VideoFrame(pts=$pts, duration=$duration, image=${image?.let { "${it.width}x${it.height}" }}, " +
            "rotationDegrees=$rotationDegrees, sampleAspectRatio=$sampleAspectRatio" +
            (pixelBuffer?.let { ", pixelBuffer=$it)" } ?: ")")
}

public class VideoDecodingException(message: String, cause: Throwable? = null) : FFmpegKmpException(message, cause)

/**
 * Returns the frame of a video that is shown at a given position, decoding on demand: no clock,
 * no scheduler and no dropped frames.
 *
 * The frame at position s is the decoded frame with the largest pts <= s, held until the next
 * frame's pts, which is what players show and what FFmpeg's `fps` filter samples. Before the first
 * frame the first is returned; past the end the last is held. Positions are measured from the
 * input's start time, the origin FFplay and `-ss` use, so 0 is the first frame.
 *
 * [frameAt] is fastest walking forward: a position the current frame still covers returns it
 * without decoding, one ahead decodes forward, and one behind seeks to the keyframe before it and
 * decodes up to it. Variable frame rates are exact because each frame's end is the next frame's pts.
 *
 * It is built on FFmpeg's libraries directly, so it runs alongside `FFmpegClient` commands instead
 * of queueing behind them. Each decoder decodes on a thread of its own (MediaCodec sessions are
 * bound to one), and the suspending calls wait for it. [seekTo], [frameAt] and [close] must not be
 * called concurrently.
 *
 * The timeout given to [open] bounds [open], [seekTo] and [frameAt] each: a stalled input or a
 * hardware decoder that takes input without ever outputting makes the call throw a
 * [VideoDecodingException] instead of blocking. The decoder is then unusable, and [close] still
 * returns promptly.
 */
public class VideoDecoder private constructor(
    private val native: NativeVideoDecoder,
    stream: NativeVideoStream,
    private val output: VideoOutput,
    private val thread: VideoDecoderThread,
    private val timeout: Duration,
) : AutoCloseable {
    /** Coded size, rotation, sample aspect ratio, bit depth, HDR type and colour metadata. */
    public val info: FFplayVideoInfo = stream.info.toPublicVideoInfo()

    /** Null when the input does not report a duration. */
    public val duration: Duration? = stream.durationMicros.takeIf { it >= 0 }?.microseconds

    /**
     * The decoder that took the source. Under [FFplayDecoderPreference.AUTO] a hardware decoder
     * that fails or stalls before its first frame is replaced once by the software one, so this is
     * then [FFplayDecoderKind.SOFTWARE].
     */
    public val decoderKind: FFplayDecoderKind = when (stream.activeDecoder) {
        NativePlayerDecoderKind.HARDWARE -> FFplayDecoderKind.HARDWARE
        NativePlayerDecoderKind.SOFTWARE -> FFplayDecoderKind.SOFTWARE
        NativePlayerDecoderKind.UNKNOWN -> FFplayDecoderKind.UNKNOWN
    }

    /** The frame the last [frameAt] or [seekTo] made current. */
    @Volatile
    public var current: VideoFrame? = null
        private set

    private var currentSerial = -1L
    private val closed = AtomicBoolean(false)

    @Volatile
    private var timedOut = false

    /** Seeks to the keyframe before [position] and decodes up to the frame shown there. */
    public suspend fun seekTo(position: Duration) {
        val nanos = position.requireNonNegativeNanos()
        call("seek to $position") {
            native.seek(nanos)
            present(native.frameAt(nanos))
        }
    }

    public suspend fun frameAt(position: Duration): VideoFrame {
        val nanos = position.requireNonNegativeNanos()
        val frame = call("decode the frame at $position") { present(native.frameAt(nanos)) }
        // Retained here, not on the decoder thread, so a call the watchdog gave up on leaks nothing.
        return if (frame.pixelBuffer != null) frame.retainedCopy() else frame
    }

    override fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            thread.release(native) { current?.releaseBuffer() }
        }
    }

    private suspend fun <T> call(action: String, block: () -> T): T {
        check(!closed.load()) { "The video decoder is closed" }
        check(!timedOut) { "The video decoder timed out; close it" }
        return thread.within(native, timeout, action, onTimeout = { timedOut = true }, block)
    }

    /** Runs on the decoder thread; the decoder's frame keeps its own retain of a pixel buffer. */
    private fun present(frame: NativeDecodedFrame): VideoFrame {
        current?.takeIf { frame.serial == currentSerial }?.let { return it }
        val image = frame.pixels?.let { pixels ->
            rasterImage(
                pixels,
                frame.width,
                frame.height,
                frame.stride,
                linearF16 = output == VideoOutput.LinearF16,
            )
        }
        val decoded = VideoFrame(
            pts = frame.ptsNanos.nanoseconds,
            duration = frame.durationNanos.nanoseconds,
            image = image,
            rotationDegrees = frame.rotationDegrees,
            sampleAspectRatio = if (frame.sampleAspectRatioNumerator > 0 && frame.sampleAspectRatioDenominator > 0) {
                frame.sampleAspectRatioNumerator.toDouble() / frame.sampleAspectRatioDenominator
            } else {
                1.0
            },
            pixelBuffer = frame.pixelBuffer?.let { buffer ->
                VideoPixelBuffer(
                    native = buffer,
                    width = frame.width,
                    height = frame.height,
                    pixelFormat = buffer.pixelFormat,
                    ioSurfaceBacked = buffer.ioSurfaceBacked,
                    colorPrimaries = colorPrimariesName(buffer.colorPrimaries),
                    colorTransfer = colorTransferName(buffer.colorTransfer),
                    colorMatrix = colorSpaceName(buffer.colorSpace),
                    colorRange = colorRangeName(buffer.colorRange),
                )
            },
        )
        val shown = if (decoded.pixelBuffer != null) decoded.retainedCopy(ownedByDecoder = true) else decoded
        current?.releaseBuffer()
        current = shown
        currentSerial = frame.serial
        return shown
    }

    public companion object {
        /**
         * Opens [source] (a path, URL or mounted [io.github.aftrolle.ffmpegkmp.core.CommandIo]
         * input, as for FFplay) and decodes its first frame on the decoder's thread.
         *
         * [timeout] bounds this call and every [seekTo] and [frameAt] of the decoder;
         * [Duration.INFINITE] waits indefinitely. When [decoder] is AUTO and the hardware decoder
         * stalls, the software fallback gets a timeout of its own, so opening takes at most twice
         * [timeout].
         */
        public suspend fun open(
            source: FFplaySource,
            output: VideoOutput,
            decoder: FFplayDecoderPreference = FFplayDecoderPreference.AUTO,
            timeout: Duration = 10.seconds,
        ): VideoDecoder {
            require(source.protection != FFplayContentProtection.REQUIRE_SECURE_PATH) {
                "Protected sources need a secure output path; a VideoDecoder hands frames to the caller"
            }
            require(output != VideoOutput.LinearF16 || platformSupportsLinearF16) {
                "LinearF16 output needs Android 8.0 (API 26) or later"
            }
            require(timeout.isPositive()) { "The timeout must be positive: $timeout" }
            if (output == VideoOutput.PixelBuffer && !platformSupportsPixelBuffer) {
                throw UnsupportedOperationException("PixelBuffer output is only available on Apple platforms")
            }
            val action = "open '${source.input}'"
            val thread = VideoDecoderThread()
            var native: NativeVideoDecoder? = null
            try {
                // Creating reads nothing; finish it even when cancelled so the handle is released.
                val created = withContext(NonCancellable) {
                    thread.submit {
                        decoding(action) {
                            createPlatformVideoDecoder(
                                source = NativePlayerSource(source.input, source.io.toNativeMounts()),
                                output = output.toNative(),
                                decoderPreference = decoder.toNative(),
                                timeoutMicros = if (timeout.isInfinite()) 0L else timeout.inWholeMicroseconds,
                                surface = (output as? VideoOutput.Surface)?.surface,
                            )
                        }
                    }.await()
                }
                native = created
                val stream = thread.within(created, timeout, action) { created.start() }
                return VideoDecoder(created, stream, output, thread, timeout)
            } catch (failure: Throwable) {
                if (native != null) thread.release(native) else thread.finish(Duration.ZERO) {}
                throw failure
            }
        }
    }
}

/** How long past its native deadline a call may run before the watchdog gives up on it. */
private val DEADLINE_GRACE = 500.milliseconds

/** How long [VideoDecoder.close] waits for the native close queued behind a stuck call. */
private val CLOSE_BOUND = 1.seconds

/**
 * Runs [block] on this thread within [timeout]. The native deadline bounds FFmpeg itself, including
 * MediaCodec's waits, and a native fallback may extend it; the watchdog only acts once a call
 * overruns it, as one blocked in a host read (a mounted Source) does: it aborts the decoder,
 * interrupts the thread where the platform can, and stops waiting for it.
 */
private suspend fun <T> VideoDecoderThread.within(
    native: NativeVideoDecoder,
    timeout: Duration,
    action: String,
    onTimeout: () -> Unit = {},
    block: () -> T,
): T {
    fun timedOut(cause: Throwable?): Nothing {
        onTimeout()
        throw VideoDecodingException("Could not $action: timed out after $timeout", cause)
    }
    val call: Deferred<T> = submit(block)
    var wait = timeout + DEADLINE_GRACE
    while (withTimeoutOrNull(wait) { call.join() } == null) {
        val left = native.timeLeftMicros().microseconds
        if (left.isPositive()) {
            wait = left + DEADLINE_GRACE
            continue
        }
        native.abort()
        interrupt()
        withTimeoutOrNull(DEADLINE_GRACE) { call.join() }
        timedOut(null)
    }
    return decoding(action) {
        try {
            call.await()
        } catch (failure: NativeVideoDecoderException) {
            if (failure.errorCode == NativePlayerError.TIMED_OUT) timedOut(failure)
            throw failure
        }
    }
}

/** Closes [native], then runs [closed], after any call still running, without waiting on one that is stuck. */
private fun VideoDecoderThread.release(native: NativeVideoDecoder, closed: () -> Unit = {}) {
    native.abort()
    finish(CLOSE_BOUND) {
        native.close()
        closed()
    }
}

private fun VideoOutput.toNative(): NativeVideoDecoderOutput = when (this) {
    VideoOutput.Rgba8 -> NativeVideoDecoderOutput.RGBA8
    VideoOutput.LinearF16 -> NativeVideoDecoderOutput.LINEAR_F16
    is VideoOutput.Surface -> NativeVideoDecoderOutput.SURFACE
    VideoOutput.PixelBuffer -> NativeVideoDecoderOutput.PIXEL_BUFFER
}

private fun Duration.requireNonNegativeNanos(): Long {
    require(!isNegative()) { "Video position must not be negative: $this" }
    return inWholeNanoseconds
}

private inline fun <T> decoding(action: String, block: () -> T): T = try {
    block()
} catch (failure: NativeVideoDecoderException) {
    throw VideoDecodingException("Could not $action: ${failure.message}", failure)
} catch (failure: NativeBridgeUnavailableException) {
    throw VideoDecodingException(failure.message ?: "The FFmpegKMP native runtime is unavailable", failure)
}

/**
 * The one thread a decoder's native calls run on, in order: MediaCodec sessions are bound to the
 * thread that created them, and a call stuck in a host read must not block the caller.
 */
internal expect class VideoDecoderThread() {
    /** Runs [block] after the blocks submitted before it. */
    fun <T> submit(block: () -> T): Deferred<T>

    /** Wakes the running block from a blocking wait where the platform can: JVM threads are interrupted. */
    fun interrupt()

    /** Runs [block] last and ends the thread, waiting at most [bound] for it: it still runs after a stuck block. */
    fun finish(bound: Duration, block: () -> Unit)
}
