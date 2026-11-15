// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.DECODER_THREAD_CLOSE_BOUND
import io.github.aftrolle.ffmpegkmp.bindings.DecoderThread
import io.github.aftrolle.ffmpegkmp.bindings.NativeBridgeUnavailableException
import io.github.aftrolle.ffmpegkmp.bindings.NativeDecodedFrame
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerError
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSource
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoDecoder
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoDecoderException
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoDecoderOutput
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoStream
import io.github.aftrolle.ffmpegkmp.bindings.createPlatformVideoDecoder
import io.github.aftrolle.ffmpegkmp.bindings.openOnDecoderThread
import io.github.aftrolle.ffmpegkmp.core.ExperimentalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.core.FFmpegKmpException
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import io.github.aftrolle.ffmpegkmp.core.toNativeMounts
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** How [VideoDecoder] hands out its frames. */
public sealed interface VideoOutput {
    /**
     * Frames in memory FFmpeg owns. With a [format], the decoder's thread converts each frame into
     * a pooled frame of that format, so conversion overlaps with the caller's work: for example
     * [FrameFormat.Rgba8] tone maps PQ and HLG sources to SDR, and [FrameFormat.RgbaF16] keeps
     * their highlights above 1.0.
     *
     * With no format, frames come as decoded, in the source's own layout and colour, and hardware
     * frames are downloaded. On Apple every frame is then a `CVPixelBuffer` (`VideoFrame.cvPixelBuffer` in
     * the Apple source sets), whatever the source's bit depth: VideoToolbox's own, or for software
     * decoding a pooled IOSurface-backed NV12 (8-bit), P010 (deeper) or, for RGB sources, BGRA
     * copy in the source's colour. That is where Metal and VideoToolbox take frames without a copy.
     *
     * On Android MediaCodec decodes 8-bit sources straight into memory, as NV12 or YUV420P, with
     * the software decoder as the [CodecPreference.AUTO] fallback. Deeper sources decode in
     * software, because MediaCodec's memory output would drop their precision, so
     * [CodecPreference.REQUIRE_HARDWARE] fails on them.
     *
     * With a [size], the decoder's thread also scales each frame to it, bilinearly and in the same
     * pass as the conversion, so a 4K source shown as a 960x540 tile converts, and tone maps, at
     * 960x540. The decoder still decodes at the source's size, and hardware frames, VideoToolbox's
     * included, are read into memory before they are scaled. Frames keep their rotation, and report
     * the sample aspect ratio that keeps the source's display aspect: an anamorphic source scaled
     * to its display aspect comes out with square pixels. A size needs a format, and must be even
     * for the 4:2:0 layouts. [VideoDecoder.resize] changes it on the open decoder, without decoding
     * again. The browser scales RGBA8 and BGRA8 by drawing each frame into a canvas of that size.
     *
     * Frames with a [format] on every native platform, and frames as decoded in software on Apple,
     * come from a ring the decoder keeps of three frames per layout and size, made once and handed
     * out again as they close, so nothing allocates per frame: the one the caller works on, the
     * one decoded ahead, and one so the caller can keep the previous frame while taking the next.
     * While the caller holds all three, a [VideoDecoder.frameAt] that needs a new frame fails with
     * [IllegalStateException] (see there); [VideoFrame.retain] shares its frame's place in the
     * ring, and [VideoFrame.convert] copies into a pool without a bound.
     * Elsewhere frames as decoded are avcodec's own buffers, which it reuses, or a hardware frame
     * read into memory of its own, and VideoToolbox's frames are its own; the browser has no ring.
     */
    public data class Memory(val format: FrameFormat? = null, val size: FrameSize? = null) : VideoOutput {
        init {
            if (size != null) {
                require(format != null) { "A size needs a format: frames as decoded keep the source's size" }
                require(format.layout.isRgb || (size.width % 2 == 0 && size.height % 2 == 0)) {
                    "${format.layout} is 4:2:0 and needs an even size, not ${size.width}x${size.height}"
                }
            }
        }
    }

    /**
     * Android 14 (API 34) and later: MediaCodec decodes each frame into GPU memory, an
     * `android.hardware.HardwareBuffer` (`VideoFrame.hardwareBuffer` in the Android source set),
     * with no CPU-visible pixels: the frame's [VideoFrame.format] is null, [VideoFrame.usePlanes]
     * returns null and [VideoFrame.convert] fails. A `FrameImage` draws it between Compose's layers
     * with no copy, on a GPU canvas. The buffer can be larger than the picture, which lies in
     * `VideoFrame.hardwareBufferCrop`.
     *
     * The buffers are the decoder's ring of three, an `ImageReader`'s images: a frame's buffer goes
     * back to MediaCodec when its last reference closes. The decoder keeps the latest frame's, so
     * the same position again returns the same buffer, and [VideoDecoder.frames] holds its one
     * frame ahead, which is that latest frame. That leaves the caller two, which is what a
     * `FrameImage` holds: the frames of its last two updates, the one on screen and the one a
     * drawing may still use. So `decoder.frames().collect { frame -> frame.use(image::update); … }`
     * uses exactly the three; holding more fails the next [VideoDecoder.frameAt] that needs a new
     * frame with [IllegalStateException] (see there). These frames cannot be converted, so to
     * keep more, hold fewer.
     *
     * Sources deeper than 8 bits stay in GPU memory too, in 10 bits, and an HDR10 or HLG one's
     * buffer holds its PQ or HLG codes, which `FrameImage` draws as linear light with its own shader
     * (1.0 at 203 nits), so a canvas that keeps values above 1.0 shows the highlights.
     *
     * Where no hardware decoder takes the source under [CodecPreference.AUTO], frames come in
     * memory instead, as [memory] says, and [VideoDecoder.decoderKind] reports
     * [DecoderKind.SOFTWARE]: as decoded by default, or converted and scaled as with [Memory], which
     * [VideoDecoder.resize] changes. MediaCodec's frames keep their own size. Elsewhere and on earlier Android
     * versions, [VideoDecoder.open] fails with [IllegalArgumentException]; the browser fails it with
     * a [VideoDecodingException].
     */
    public data class GpuBuffers(val memory: Memory = Memory()) : VideoOutput
}

/** Which frames [VideoDecoder.frames] delivers. */
public sealed interface FrameStep {
    /** Every decoded frame once, in presentation order. */
    public data object Decoded : FrameStep

    /**
     * The frame shown at each frame time of [rate] after the start, as a constant-rate export at
     * [rate] samples a video, exact over any length.
     */
    public data class Rate(val rate: FrameRate) : FrameStep
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
 * without decoding or converting, one ahead decodes forward, and one behind seeks to the keyframe
 * before it and decodes up to it. Variable frame rates are exact because each frame's end is the
 * next frame's pts.
 *
 * It is built on FFmpeg's libraries directly, so it runs alongside `FFmpegClient` commands instead
 * of queueing behind them. Each decoder decodes on a thread of its own (MediaCodec sessions are
 * bound to one), and the suspending calls wait for it, one at a time: calls from several
 * coroutines queue in order. [frames] decodes ahead of its collector.
 *
 * Cancelling a call stops its native work instead of letting it finish: the call returns at once,
 * the next one waits for that work to unwind and then seeks to its own position, so
 * `positions.collectLatest { decoder.frameAt(it) }` drops stale work instead of queueing it.
 *
 * The timeout given to [open] bounds [open], [seekTo] and [frameAt] each: a stalled input or a
 * hardware decoder that takes input without ever outputting makes the call throw a
 * [VideoDecodingException] instead of blocking. The decoder is then unusable, and [close] still
 * returns promptly.
 */
public class VideoDecoder private constructor(
    private val native: NativeVideoDecoder,
    private val output: VideoOutput,
    stream: NativeVideoStream,
    private val thread: DecoderThread,
    private val timeout: Duration,
) : AutoCloseable {
    /**
     * The source's coded size, rotation, sample aspect ratio, bit depth, colour and HDR metadata.
     * Frames come at [VideoOutput.Memory.size] instead when the output has one, or at the size
     * last given to [resize].
     */
    public val info: VideoInfo = stream.info.toPublicVideoInfo()

    /** Null when the input does not report a duration. */
    public val duration: Duration? = stream.durationMicros.takeIf { it >= 0 }?.microseconds

    /**
     * The decoder that took the source. Under [CodecPreference.AUTO] a hardware decoder that
     * fails or stalls before its first frame is replaced once by the software one, so this is then
     * [DecoderKind.SOFTWARE].
     */
    public val decoderKind: DecoderKind = stream.activeDecoder.toPublic()

    /**
     * Why a decoder opened under [CodecPreference.AUTO] decodes in software, fixed when it opened:
     * no hardware decoder takes the source, the hardware one failed, or [DecoderBudget] was full.
     * Null when it decodes in hardware, under another preference, and in the browser.
     */
    public val decoderFallback: DecoderFallbackReason? = stream.decoderFallback.toPublic()

    private val closed = AtomicBoolean(false)

    /** Held for each call until its native work has returned, or until it failed or timed out. */
    private val calls = Mutex()

    @Volatile
    private var timedOut = false

    /** Seeks to the keyframe before [position] and decodes up to the frame shown there. */
    public suspend fun seekTo(position: Duration) {
        val nanos = position.requireNonNegativeNanos()
        call("seek to $position") { native.seek(nanos) }
    }

    /**
     * Changes the size of the frames the decoder hands out from now on, as if it had been opened
     * with `VideoOutput.Memory(format, size)`: null is the source's own size. For
     * [VideoOutput.GpuBuffers] it is the size of the frames that come in memory, with
     * [VideoOutput.GpuBuffers.memory]'s format; MediaCodec's GPU frames are not scaled. The frame [frameAt]
     * returns next, the same position included, comes at the new size and is the same, byte for
     * byte, as a decoder opened at that size returns. Frames are converted as they are handed out,
     * so this decodes nothing and does not seek: the decoder's current frame is converted again.
     * Frames handed out before keep their size and pixels until they close, and [frames] may still
     * deliver the one frame it decoded ahead at the old size.
     *
     * The decoder frees its ring of three (see [VideoOutput.Memory]) of the old size, so a decoder
     * resized again and again holds one size's ring and the frames its caller still has, which
     * free as they close. A resize back allocates that size's frames again.
     *
     * It runs on the decoder's thread like [seekTo], queued in order with the other calls, and is
     * safe between the emissions of a [frames] collection; one cancelled once the decoder's thread
     * has taken it may still have changed the size. Throws [IllegalArgumentException] for a
     * size [VideoOutput.Memory] rejects with the decoder's format: any size without a format, and
     * an odd one for the 4:2:0 layouts. Throws [UnsupportedOperationException] in the browser,
     * which does not resize an open decoder yet.
     */
    public suspend fun resize(size: FrameSize?) {
        val memory = output.memory
        // The same rules as opening at that size.
        VideoOutput.Memory(memory.format, size)
        call("resize frames to ${size ?: "the source's size"}") { native.setSize(size?.width ?: 0, size?.height ?: 0) }
    }

    /**
     * The frame shown at [position], which the caller closes. Each call returns a new reference;
     * calls the same decoded frame covers share its memory. Frames stay valid past the decoder's
     * next call and its [close].
     *
     * Where frames come from the decoder's ring of three (see [VideoOutput.Memory] and
     * [VideoOutput.GpuBuffers]), a call that needs a new frame while the caller holds three others
     * fails with [IllegalStateException] after a short grace for one closing on another thread,
     * and the decoder stays usable: close a frame and call again. [VideoFrame.convert] keeps a
     * memory frame beyond the ring.
     */
    public suspend fun frameAt(position: Duration): VideoFrame {
        val nanos = position.requireNonNegativeNanos()
        return call("decode the frame at $position", discard = NativeDecodedFrame::discard) { native.frameAt(nanos) }.toVideoFrame()
    }

    /**
     * The frames from [from] until [until] (exclusive), [step] apart, each the collector's to close.
     * [FrameStep.Decoded] delivers each decoded frame once, from the one shown at [from];
     * [FrameStep.Rate] the frame shown at [from] and at each frame time of its rate after it.
     * The flow ends at [until] or at the end of the video.
     *
     * One frame is decoded ahead on the decoder's thread while the collector works, and a frame it
     * never receives is closed. That holds the next frame ready while the collector works on the
     * current one, which is all a collector slower than the decoder can use, and leaves two of the
     * decoder's ring of three (see [VideoOutput.Memory]) for the collector's own frames. While the
     * collector holds the whole ring, the frame ahead waits for it to close one, however long that
     * takes: the collector is the one that closes frames, so this is its pace, not a mistake.
     */
    public fun frames(
        from: Duration = Duration.ZERO,
        until: Duration = Duration.INFINITE,
        step: FrameStep = FrameStep.Decoded,
    ): Flow<VideoFrame> {
        from.requireNonNegativeNanos()
        return flow {
            // The producer holds one frame while it waits to send, so the channel itself holds none.
            val ahead = Channel<VideoFrame>(Channel.RENDEZVOUS, onUndeliveredElement = VideoFrame::close)
            coroutineScope {
                val producer = launch {
                    val failure = runCatching { FlowCollector<VideoFrame> { ahead.send(it) }.decodeFrames(from, until, step) }
                    ahead.close(failure.exceptionOrNull())
                }
                try {
                    for (frame in ahead) emit(frame)
                } finally {
                    producer.cancel()
                    ahead.cancel()
                }
            }
        }
    }

    private suspend fun FlowCollector<VideoFrame>.decodeFrames(from: Duration, until: Duration, step: FrameStep) {
        var position = from
        var index = 0L
        var last: Duration? = null
        while (position < until) {
            val frame = awaitingRing { frameAt(position) }
            val end = frame.pts + frame.duration
            // Past the last frame the decoder holds it: the video has ended. A last frame without a duration ends at
            // its own pts, so asked for there it is still new once.
            if (position >= end && (frame.pts < position || frame.pts == last)) {
                frame.close()
                return
            }
            emit(frame)
            last = frame.pts
            index++
            position = when (step) {
                FrameStep.Decoded -> end
                is FrameStep.Rate -> from + step.rate.timeOf(index)
            }
        }
    }

    /**
     * Switches this decoder to [source], returning it as a decoder that [open] would have opened for [source] with [output]
     * and [timeout], the codec selection, threads and ring kept; this one is closed by then, whether the switch works or
     * fails. It keeps the decoder's thread and, when the new video is coded as the old one was (codec, size, profile and
     * setup bytes), its open codec, flushed, and with it any place in [DecoderBudget]: switching between a project's
     * videos opens no new MediaCodec session. Otherwise, and when [CodecPreference.AUTO] had opened a software codec
     * because the hardware one failed or [DecoderBudget] was full, it closes the codec and opens one for [source].
     * Frames handed out before keep their memory until they close.
     *
     * It waits for this decoder's other calls, and [timeout] bounds it as it bounds [open]. Throws
     * [UnsupportedOperationException] for [VideoOutput.GpuBuffers], as this decoder's output or [output], and in the
     * browser.
     */
    @ExperimentalFFmpegKmpApi
    public suspend fun reopen(
        source: MediaSource,
        output: VideoOutput = this.output,
        timeout: Duration = this.timeout,
    ): VideoDecoder {
        require(timeout.isPositive()) { "The timeout must be positive: $timeout" }
        if (this.output !is VideoOutput.Memory || output !is VideoOutput.Memory) {
            throw UnsupportedOperationException("A decoder of GPU buffers does not reopen")
        }
        val stream = try {
            call("open '${source.input}'") {
                native.reopen(
                    source = NativePlayerSource(source.input, source.io.toNativeMounts()),
                    memoryFormat = output.format?.toNative(),
                    memoryWidth = output.size?.width ?: 0,
                    memoryHeight = output.size?.height ?: 0,
                    timeoutMicros = timeout.toNativeMicros(),
                )
                native.start()
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
        check(closed.compareAndSet(expectedValue = false, newValue = true)) { "The video decoder is closed" }
        return VideoDecoder(native, output, stream, thread, timeout)
    }

    override fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) thread.release(native)
    }

    private suspend fun <T> call(action: String, discard: (T) -> Unit = {}, block: suspend () -> T): T {
        calls.lock()
        var held = true
        try {
            check(!closed.load()) { "The video decoder is closed" }
            check(!timedOut) { "The video decoder timed out; close it" }
            return thread.within(
                native, timeout, action,
                onTimeout = { timedOut = true },
                onCancel = { running ->
                    // The next call starts once the interrupted work has unwound.
                    held = false
                    running.invokeOnCompletion { calls.unlock() }
                },
                discard = discard,
                block = block,
            )
        } finally {
            if (held) calls.unlock()
        }
    }

    public companion object {
        /**
         * Opens [source] (a path, URL or mounted [io.github.aftrolle.ffmpegkmp.core.CommandIo]
         * input, as for FFplay) and decodes its first frame on the decoder's thread.
         *
         * [threads] sets how many threads a software decoder uses, including the software
         * fallback, and of which [DecoderThreadType]: [DecoderThreads.Auto] is FFmpeg's own
         * count and types, and [DecoderThreads.Fixed] the count given on the types it allows; each
         * frame thread holds one decoded picture. Frames are converted with as many threads.
         * Frames and positions are the same for every choice. [ringSize] is how many of the
         * decoder's frames the caller may hold at once, at least 2 (the "ring of three" below is
         * this many); each costs a frame's memory or, for [VideoOutput.GpuBuffers], a GPU buffer.
         * The browser keeps 3. [timeout] bounds this call
         * and every [seekTo] and [frameAt] of the decoder; [Duration.INFINITE] waits indefinitely.
         * When [decoder] is AUTO and the hardware decoder stalls, the software fallback gets a
         * timeout of its own, so opening takes at most twice [timeout].
         *
         * A hardware decoder takes a place in [DecoderBudget] until [close]. With every place
         * taken, AUTO opens the software decoder at once ([decoderFallback] is
         * [DecoderFallbackReason.BUDGET]) and REQUIRE_HARDWARE fails with a
         * [VideoDecodingException]; neither waits for a place.
         *
         * A [NamedCodec] opens exactly the decoder FFmpeg knows by that name, as `ffmpeg -c:v
         * name` before `-i` takes it ("h264", "libaom-av1", "av1_mediacodec"), with its options
         * set as AVOptions: no other is tried in its place and nothing falls back, so
         * [decoderFallback] is null. It must decode the source's codec. An unknown name, a decoder
         * for another codec, an option it does not recognise (every such option is named) or that
         * is no video decoding option, and a value it does not take fail with a
         * [VideoDecodingException] that says so. A platform wrapper such as a MediaCodec decoder is
         * a hardware decoder and takes a place in [DecoderBudget], failing when none is free;
         * FFmpeg's own decoders decode in software with [threads], unless their options set
         * threads themselves. The browser decodes with WebCodecs and fails a [NamedCodec] as
         * unsupported.
         */
        public suspend fun open(
            source: MediaSource,
            output: VideoOutput = VideoOutput.Memory(),
            decoder: CodecSelection = CodecPreference.AUTO,
            timeout: Duration = 10.seconds,
            threads: DecoderThreads = DecoderThreads.Auto,
            ringSize: Int = 3,
        ): VideoDecoder {
            require(timeout.isPositive()) { "The timeout must be positive: $timeout" }
            require(ringSize >= 2) { "The decoder's ring holds at least two frames: $ringSize" }
            val action = "open '${source.input}'"
            // Creating reads nothing; it runs to its end even when cancelled so the handle is released.
            val (thread, native) = openOnDecoderThread("FFmpegKMP VideoDecoder") {
                decoding(action) {
                    createPlatformVideoDecoder(
                        source = NativePlayerSource(source.input, source.io.toNativeMounts()),
                        output = when (output) {
                            is VideoOutput.Memory -> NativeVideoDecoderOutput.MEMORY
                            is VideoOutput.GpuBuffers -> NativeVideoDecoderOutput.GPU_BUFFERS
                        },
                        memoryFormat = output.memory.format?.toNative(),
                        memoryWidth = output.memory.size?.width ?: 0,
                        memoryHeight = output.memory.size?.height ?: 0,
                        // A named decoder ignores the preference.
                        decoderPreference = (decoder as? CodecPreference ?: CodecPreference.AUTO).toNative(),
                        decoderThreads = threads.toNative(),
                        timeoutMicros = timeout.toNativeMicros(),
                        decoderThreadType = threads.toNativeThreadType(),
                        frameRing = ringSize,
                        decoderName = (decoder as? NamedCodec)?.name,
                        decoderOptions = (decoder as? NamedCodec)?.options.orEmpty(),
                    )
                }
            }
            try {
                val stream = thread.within(native, timeout, action) { native.start() }
                return VideoDecoder(native, output, stream, thread, timeout)
            } catch (failure: Throwable) {
                thread.release(native)
                throw failure
            }
        }

        /**
         * The frame of [source] shown at [position], ready to show as a poster or thumbnail: RGBA8
         * in sRGB ([FrameFormat.Rgba8]), upright, with square pixels, at [size] fitted by [scale].
         * The caller owns and closes it, and it stays valid after this returns. Everything else
         * this opened is closed or being closed by then, whether it returns, fails, times out or
         * is cancelled: after a timeout or cancellation the interrupted native call may still be
         * unwinding, and the decoder closes as soon as it has (it is not waited for longer than
         * about a second), so a stalled input read may hold its decoder until the read returns.
         *
         * - **Position**: the frame [frameAt] returns there, exactly, not the nearest keyframe:
         *   before the first frame the first, past the end the last.
         * - **Colour**: PQ and HLG sources are tone mapped to SDR, the same pixels a decoder opened
         *   with `VideoOutput.Memory(FrameFormat.Rgba8)` at that size gives.
         * - **Upright**: the frame's display matrix is applied exactly as FFmpeg's own autorotate
         *   applies it, with the same libavfilter filters (`transpose` and its flipped variants,
         *   `hflip`, `vflip`), so a portrait phone video comes out portrait and a mirrored matrix
         *   comes out mirrored as `ffmpeg` shows it. An angle between quarter turns goes to the
         *   nearest quarter turn of [VideoInfo.rotationDegrees] (FFmpeg's tools would rotate by
         *   the exact angle). The frame reports a rotation of 0 and carries no display matrix.
         * - **Square pixels**: an anamorphic source comes out at its display aspect, its width
         *   times its sample aspect ratio, rounded, by its height; the frame reports a sample
         *   aspect ratio of 1. A sample aspect ratio outside 1/16 to 16 is taken as that bound.
         * - **Size**: a box in upright pixels, never enlarged. Null is the upright source at its
         *   display size. [FrameScale.FIT] gives the whole picture as large as fits in the box, its
         *   aspect kept: the side the box limits is the box's, and the other one is rounded to the
         *   nearest pixel, a half up. [FrameScale.CROP] gives exactly the box, the picture scaled
         *   to cover it the same way and cut evenly on both sides of the other axis (an odd pixel
         *   more from the right or the bottom). When the box is larger than the picture, FIT gives the
         *   picture at its display size and CROP the largest centred part of it with the box's
         *   aspect. No side is ever below one pixel, and any size is allowed, odd ones included.
         * - **Cost**: the decoder is resized before it converts, so a 4K HDR source asked for a
         *   256-pixel thumbnail is tone mapped and scaled in one pass straight to the small size,
         *   and the turn and cut work on the small frame, off the caller's thread. What stays is
         *   one frame of the result's size.
         *
         * [timeout] bounds the call, opening included: a stalled input or decoder ends it with a
         * [VideoDecodingException], as [open]'s does. Under [CodecPreference.AUTO] an attempt that
         * fails after opening a hardware decoder, or that times out before its decoder opened, is
         * made once more with [CodecPreference.SOFTWARE]. The two attempts share [timeout], the
         * first taking at most half of it, so the call takes no longer than [timeout] (plus the
         * bounded close of a stalled decoder); the other preferences make one attempt, and so does a [NamedCodec], which opens as
         * [open] opens it, with no software retry. A source that is missing or cannot be decoded
         * fails with a [VideoDecodingException] too.
         *
         * The browser does not extract frames yet: there it throws [UnsupportedOperationException].
         */
        @ExperimentalFFmpegKmpApi
        public suspend fun extractFrame(
            source: MediaSource,
            position: Duration = Duration.ZERO,
            size: FrameSize? = null,
            scale: FrameScale = FrameScale.FIT,
            decoder: CodecSelection = CodecPreference.AUTO,
            timeout: Duration = 10.seconds,
        ): VideoFrame = extractUprightFrame(source, position, size, scale, decoder, timeout)
    }
}

private fun NativeDecodedFrame.toVideoFrame(): VideoFrame {
    val sampleAspectRatio = if (sampleAspectRatioNumerator > 0 && sampleAspectRatioDenominator > 0) {
        sampleAspectRatioNumerator.toDouble() / sampleAspectRatioDenominator
    } else {
        1.0
    }
    val pts = ptsNanos.nanoseconds
    val duration = durationNanos.nanoseconds
    val gpu = gpu ?: return VideoFrame.of(checkNotNull(frame), pts, duration, width, height, rotationDegrees, sampleAspectRatio)
    return VideoFrame.of(gpu, pts, duration, width, height, rotationDegrees, sampleAspectRatio)
}

/** Releases what a frame nobody takes any more holds. */
private fun NativeDecodedFrame.discard() {
    frame?.close()
    gpu?.release()
}

/** How long past its native deadline a call may run before the watchdog gives up on it. */
private val DEADLINE_GRACE = 500.milliseconds

/**
 * Runs [block] on this thread within [timeout]. The native deadline bounds FFmpeg itself, including
 * MediaCodec's waits, and a native fallback may extend it; the watchdog only acts once a call
 * overruns it, as one blocked in a host read (a mounted Source) does: it aborts the decoder,
 * interrupts the thread where the platform can, and stops waiting for it. A cancelled caller
 * interrupts the call instead, which leaves the decoder usable, and hands it, still unwinding, to
 * [onCancel]. A result nobody waits for any more, after a timeout or a cancelled caller, goes to
 * [discard] once the thread returns it.
 */
private suspend fun <T> DecoderThread.within(
    native: NativeVideoDecoder,
    timeout: Duration,
    action: String,
    onTimeout: () -> Unit = {},
    onCancel: (running: Deferred<T>) -> Unit = {},
    discard: (T) -> Unit = {},
    block: suspend () -> T,
): T {
    val call: Deferred<T> = submit(block)
    fun abandon() = call.invokeOnCompletion { cause -> if (cause == null) discard(call.getCompleted()) }
    fun timedOut(cause: Throwable?): Nothing {
        onTimeout()
        abandon()
        throw VideoDecodingException("Could not $action: timed out after $timeout", cause)
    }
    try {
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
    } catch (cancelled: CancellationException) {
        // Nobody wants the result any more: stop the native work, and a host read it is blocked in
        // where the platform can, instead of waiting for it. The thread clears its interrupt
        // before the next call, which cannot start before this one has unwound.
        native.interrupt()
        interrupt()
        abandon()
        onCancel(call)
        throw cancelled
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

/** Closes [native] after any call still running, without waiting on one that is stuck. */
private fun DecoderThread.release(native: NativeVideoDecoder) {
    native.abort()
    finish(DECODER_THREAD_CLOSE_BOUND) { native.close() }
}

private fun Duration.toNativeMicros(): Long = if (isInfinite()) 0L else inWholeMicroseconds

private fun Duration.requireNonNegativeNanos(): Long {
    require(!isNegative()) { "Video position must not be negative: $this" }
    return inWholeNanoseconds
}


/**
 * [block] again each time the decoder's ring is full, after its grace: the frames the collector
 * holds are what fill it, and it closes them as it goes on.
 */
private suspend fun <T> awaitingRing(block: suspend () -> T): T {
    while (true) {
        try {
            return block()
        } catch (_: RingFullException) {
            continue
        }
    }
}

private class RingFullException(message: String, cause: Throwable) : IllegalStateException(message, cause)

private inline fun <T> decoding(action: String, block: () -> T): T = try {
    block()
} catch (failure: NativeVideoDecoderException) {
    if (failure.errorCode == NativePlayerError.RING_FULL) {
        throw RingFullException(
            "Could not $action: the caller holds every frame of the decoder's ring; close one to take another",
            failure,
        )
    }
    throw VideoDecodingException("Could not $action: ${failure.message}", failure)
} catch (failure: NativeBridgeUnavailableException) {
    throw VideoDecodingException(failure.message ?: "The FFmpegKMP native runtime is unavailable", failure)
}

/** How the frames that come in memory are handed out. */
private val VideoOutput.memory: VideoOutput.Memory
    get() = when (this) {
        is VideoOutput.Memory -> this
        is VideoOutput.GpuBuffers -> memory
    }
