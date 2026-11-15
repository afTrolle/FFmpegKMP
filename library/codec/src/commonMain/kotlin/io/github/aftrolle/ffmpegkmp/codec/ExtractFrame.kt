// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.NativeCallException
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerError
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoDecoderException
import io.github.aftrolle.ffmpegkmp.core.ExperimentalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Whether this platform extracts frames ([VideoDecoder.extractFrame]): the browser does not yet. */
internal expect val frameExtractionSupported: Boolean

/**
 * [VideoDecoder.extractFrame]. Under [CodecPreference.AUTO], an attempt that may have used a
 * hardware decoder and failed (it timed out before the decoder opened, or the decoder it opened
 * was a hardware one) is made once more in software. The two share the timeout the caller gave: under AUTO the first
 * attempt gets half of it, so that a hardware decoder that stalls leaves the software one the
 * rest. Every other
 * selection, a [NamedCodec] included, makes one attempt.
 *
 * [format] picks the frame's format once the source is open; any other than RGBA8 reopens the decoder
 * in it. [decoders] opens each attempt's decoder and takes it back. [afterFrame] gets the result once
 * it is made, before the decoder closes: the tests' hook into the window where a timeout or
 * cancellation must still close it.
 */
internal suspend fun extractUprightFrame(
    source: MediaSource,
    position: Duration,
    size: FrameSize?,
    scale: FrameScale,
    decoder: CodecSelection,
    timeout: Duration,
    format: (VideoInfo) -> FrameFormat = { FrameFormat.Rgba8 },
    decoders: ExtractionDecoders = OneShotDecoders,
    afterFrame: suspend (VideoFrame) -> Unit = {},
): VideoFrame {
    if (!frameExtractionSupported) {
        throw UnsupportedOperationException("The browser does not extract frames yet; open a VideoDecoder")
    }
    require(!position.isNegative()) { "Video position must not be negative: $position" }
    require(timeout.isPositive()) { "The timeout must be positive: $timeout" }
    val started = TimeSource.Monotonic.markNow()
    val first = Extraction(source, position, size, scale, if (decoder == CodecPreference.AUTO) timeout / 2 else timeout, format, decoders, afterFrame)
    val failure = try {
        return first.run(decoder)
    } catch (failure: VideoDecodingException) {
        if (decoder != CodecPreference.AUTO || !first.mayHaveUsedHardware) throw failure
        failure
    }
    val left = timeout - started.elapsedNow()
    if (!left.isPositive()) throw failure
    try {
        return Extraction(source, position, size, scale, left, format, decoders, afterFrame).run(CodecPreference.SOFTWARE)
    } catch (second: Throwable) {
        second.addSuppressed(failure)
        throw second
    }
}

/** Where an extraction's decoders come from and go back to. */
internal interface ExtractionDecoders {
    suspend fun open(source: MediaSource, output: VideoOutput.Memory, selection: CodecSelection, timeout: Duration): VideoDecoder

    /** Takes back [decoder], which [open] opened or reopened, once its attempt is over; it [failed] or made its frame. */
    fun finish(decoder: VideoDecoder, failed: Boolean)
}

/** A decoder of its own for each attempt, closed after it. */
internal object OneShotDecoders : ExtractionDecoders {
    override suspend fun open(source: MediaSource, output: VideoOutput.Memory, selection: CodecSelection, timeout: Duration) =
        VideoDecoder.open(source, output, selection, timeout)

    override fun finish(decoder: VideoDecoder, failed: Boolean) = decoder.close()
}

/** One attempt: a decoder opened, resized, asked for its frame, and handed back. */
private class Extraction(
    private val source: MediaSource,
    private val position: Duration,
    private val size: FrameSize?,
    private val scale: FrameScale,
    private val timeout: Duration,
    private val format: (VideoInfo) -> FrameFormat,
    private val decoders: ExtractionDecoders,
    private val afterFrame: suspend (VideoFrame) -> Unit,
) {
    /** The decoder that opened, or null when opening did not finish. */
    private var opened: DecoderKind? = null
    private var timedOut = false

    /** Whether the attempt failed in a way a hardware decoder may have caused. */
    val mayHaveUsedHardware: Boolean get() = opened?.let { it == DecoderKind.HARDWARE } ?: timedOut

    suspend fun run(selection: CodecSelection): VideoFrame {
        // Held out here, so a timeout or cancellation that lands once it is made still closes it.
        var result: VideoFrame? = null
        try {
            // Each of the decoder's calls is bounded by the timeout on its own; this bounds them together.
            // Timing out cancels the call under way, which stops its native work.
            withTimeoutOrNull(timeout) {
                var decoder = decoders.open(source, VideoOutput.Memory(FrameFormat.Rgba8), selection, timeout)
                var failed = true
                try {
                    opened = decoder.decoderKind
                    val wanted = format(decoder.info)
                    // A failed reopen closes the decoder it was called on, which finishing then leaves as it is.
                    if (wanted != FrameFormat.Rgba8) decoder = decoder.reopen(source, VideoOutput.Memory(wanted))
                    opened = decoder.decoderKind
                    val info = decoder.info
                    val fit = frameFit(info.width, info.height, info.sampleAspectRatio, info.rotationDegrees, size, scale)
                    // Always, even at the coded size: nothing is converted before frameAt, and the frame then
                    // comes at exactly this size whatever the stream does.
                    decoder.resize(fit.decodeSize)
                    decoder.frameAt(position).use { frame ->
                        // Off the caller's thread, which may be the UI's; the result is kept as soon as it exists.
                        withContext(Dispatchers.Default) { result = frame.upright(fit) }
                    }
                    afterFrame(checkNotNull(result))
                    failed = false
                } finally {
                    // Closing waits a bounded time for an interrupted native call to unwind; not on the caller's thread.
                    withContext(NonCancellable + Dispatchers.Default) { decoders.finish(decoder, failed) }
                }
            } ?: run {
                timedOut = true
                throw VideoDecodingException("Could not extract the frame at $position of '${source.input}': timed out after $timeout")
            }
            return checkNotNull(result)
        } catch (failure: Throwable) {
            result?.close()
            if (failure is VideoDecodingException && failure.isTimeout()) timedOut = true
            throw failure
        }
    }

    /** A new frame of this one, upright and cut as [fit] says, with square pixels and no rotation left. */
    private fun VideoFrame.upright(fit: FrameFit): VideoFrame {
        val crop = fit.crop
        val native = try {
            useNative { native ->
                checkNotNull(native) { "The frame has no CPU-visible memory" }
                    .upright(fit.clockwiseQuarterTurns, crop?.x ?: 0, crop?.y ?: 0, crop?.width ?: 0, crop?.height ?: 0)
            }
        } catch (failure: NativeCallException) {
            throw VideoDecodingException("Could not turn the frame at $position of '${source.input}' upright: ${failure.message}", failure)
        }
        // The decoder scaled to the display aspect, so the pixels are square to within the rounding of a whole pixel.
        return VideoFrame.of(native, pts, duration, rotationDegrees = 0.0, sampleAspectRatio = 1.0)
    }
}

/** A decoder call that hit the native deadline, which a stalled hardware decoder does. */
private fun VideoDecodingException.isTimeout(): Boolean {
    val native = cause as? NativeVideoDecoderException
    return native?.errorCode == NativePlayerError.TIMED_OUT
}

/**
 * [VideoDecoder.extractFrame] in the format [format] picks for the source once it is open, rather
 * than RGBA8: a display module's way to keep PQ and HLG as they are coded for a platform that shows
 * them. A format other than RGBA8 opens the decoder a second time.
 */
@InternalFFmpegKmpApi
@ExperimentalFFmpegKmpApi
public suspend fun VideoDecoder.Companion.extractFrameIn(
    source: MediaSource,
    position: Duration,
    size: FrameSize?,
    scale: FrameScale,
    decoder: CodecSelection,
    timeout: Duration,
    format: (VideoInfo) -> FrameFormat,
): VideoFrame = extractUprightFrame(source, position, size, scale, decoder, timeout, format)
