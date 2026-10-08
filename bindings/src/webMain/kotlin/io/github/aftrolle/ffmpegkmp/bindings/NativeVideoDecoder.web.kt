// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(ExperimentalWasmJsInterop::class, io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decodes with WebCodecs in a worker of its own, which FFmpeg's demuxer feeds packet by packet,
 * and counts frames as `ffmpegkmp_decoder.c` does. The browser decodes mounted inputs only, into
 * frames as decoded or RGBA8 and BGRA8 in sRGB or Display P3, which WebCodecs converts to; each
 * frame is copied once, into a buffer the worker hands the page, and on Kotlin/Wasm once more
 * into Kotlin's heap. A memory size scales by drawing each frame into a canvas of that size.
 */
@InternalFFmpegKmpApi
public actual fun createPlatformVideoDecoder(
    source: NativePlayerSource,
    output: NativeVideoDecoderOutput,
    memoryFormat: NativeFrameFormat?,
    memoryWidth: Int,
    memoryHeight: Int,
    decoderPreference: NativePlayerDecoderPreference,
    decoderThreads: Int,
    timeoutMicros: Long,
): NativeVideoDecoder {
    if (output != NativeVideoDecoderOutput.MEMORY) {
        throw NativeVideoDecoderException("The browser decodes into memory only", NativePlayerError.UNSUPPORTED)
    }
    return BrowserVideoDecoder(source, memoryFormat, memoryWidth, memoryHeight, decoderPreference, timeoutMicros)
}

private class BrowserVideoDecoder(
    private val source: NativePlayerSource,
    private val memoryFormat: NativeFrameFormat?,
    private val memoryWidth: Int,
    private val memoryHeight: Int,
    private val preference: NativePlayerDecoderPreference,
    private val timeoutMicros: Long,
) : NativeVideoDecoder {
    private var worker: BrowserWorkerCalls? = null
    private var info: NativePlayerVideoInfo? = null

    // The last frame the worker handed over: later replies for the same frame come without pixels.
    private var presented: NativeFrame? = null
    private var deadline: TimeMark? = null
    private var running = false
    private var aborted = false
    private var closed = false

    override suspend fun start(): NativeVideoStream {
        val canvas = memoryFormat?.let { format ->
            webCanvas(format) ?: throw NativeVideoDecoderException(
                "The browser decodes to RGBA8 or BGRA8 in sRGB or Display P3, or to frames as decoded, not $format",
                NativePlayerError.UNSUPPORTED,
            )
        }
        if (memoryWidth > 0 && canvas == null) {
            throw NativeVideoDecoderException("The browser scales RGBA8 and BGRA8 frames only, not frames as decoded", NativePlayerError.UNSUPPORTED)
        }
        // WebCodecs takes a preference but never says which decoder it chose.
        if (preference == NativePlayerDecoderPreference.REQUIRE_HARDWARE) {
            throw NativeVideoDecoderException("WebCodecs cannot confirm a hardware decoder", NativePlayerError.UNSUPPORTED)
        }
        val mount = source.mounts.firstOrNull { it.path == source.input } ?: throw NativeVideoDecoderException(
            "The browser decodes mounted inputs only, not '${source.input}'",
            NativePlayerError.UNSUPPORTED,
        )
        val calls = BrowserWorkerCalls.start().also { worker = it }
        val bytes = copyToJsUint8Array(mount.readBytesForBrowser())
        val opened = call {
            calls.request(
                openMessage(
                    bytes,
                    source.input.substringAfterLast('.', ""),
                    canvas?.first,
                    canvas?.second,
                    memoryWidth,
                    memoryHeight,
                    if (preference == NativePlayerDecoderPreference.SOFTWARE) "prefer-software" else "no-preference",
                ),
                transferOf(bytes),
            )
        }
        val snapshot = openedSnapshot(opened).toBrowserNativePlayerSnapshot()
        val video = snapshot.videoInfo
            ?: throw NativeVideoDecoderException("'${source.input}' reports no video size", NativePlayerError.UNSUPPORTED)
        info = video
        return NativeVideoStream(video, NativePlayerDecoderKind.UNKNOWN, snapshot.durationUs ?: -1L)
    }

    override suspend fun seek(positionNanos: Long) {
        require(positionNanos >= 0) { "Seek position must not be negative" }
        call { requireWorker().request(positionMessage("video-seek", positionNanos.toDouble())) }
    }

    override suspend fun frameAt(positionNanos: Long): NativeDecodedFrame {
        require(positionNanos >= 0) { "Frame position must not be negative" }
        val reply = call { requireWorker().request(positionMessage("video-frame", positionNanos.toDouble())) }
        val width = replyInt(reply, "width")
        val height = replyInt(reply, "height")
        val frame = if (hasPixels(reply)) {
            BrowserFrame(
                bytes = jsBufferToByteArray(replyBuffer(reply)),
                format = replyInt(reply, "layout").takeIf { it >= 0 }?.let { layout ->
                    NativeFrameFormat(layout, replyInt(reply, "primaries"), replyInt(reply, "transfer"), replyInt(reply, "matrix"), replyInt(reply, "range"))
                },
                width = width,
                height = height,
                planes = List(planeCount(reply)) { BrowserFrame.Plane(planeInt(reply, it, "offset"), planeInt(reply, it, "rowBytes"), planeInt(reply, it, "rows")) },
            ).also { presented = it }
        } else {
            checkNotNull(presented) { "The worker repeated a frame the page does not have" }
        }
        val video = checkNotNull(info)
        val (aspectNumerator, aspectDenominator) = scaledAspect(
            video.sampleAspectRatioNumerator, video.sampleAspectRatioDenominator, video.width, video.height, width, height,
        )
        return NativeDecodedFrame(
            serial = replyInt(reply, "serial").toLong(),
            ptsNanos = replyDouble(reply, "ptsNs").toLong(),
            durationNanos = replyDouble(reply, "durationNs").toLong(),
            width = width,
            height = height,
            sampleAspectRatioNumerator = aspectNumerator,
            sampleAspectRatioDenominator = aspectDenominator,
            rotationDegrees = video.rotationDegrees,
            hardware = false,
            frame = frame.retain(),
        )
    }

    // The worker drops the running call at its next wait; the next call seeks to its own position first.
    override fun interrupt() {
        if (deadline != null || running) worker?.send(interruptMessage())
    }

    override fun abort() {
        aborted = true
        worker?.terminate()
    }

    override fun timeLeftMicros(): Long = deadline?.let { -it.elapsedNow().inWholeMicroseconds } ?: 0L

    override fun close() {
        if (closed) return
        closed = true
        worker?.terminate()
        worker = null
        presented = null
    }

    /** Runs [block] within the decoder's timeout, which ends the worker once it has passed, as a native deadline would. */
    private suspend fun call(block: suspend () -> JsAny): JsAny {
        check(!closed) { "The video decoder is closed" }
        if (aborted) throw NativeVideoDecoderException("The decoder was aborted", AVERROR_EXIT)
        val bound = timeoutMicros.takeIf { it > 0 }?.microseconds
        deadline = bound?.let { TimeSource.Monotonic.markNow() + it }
        running = true
        try {
            if (bound == null) return block()
            return withTimeoutOrNull(bound) { block() } ?: run {
                abort()
                throw NativeVideoDecoderException("WebCodecs did not decode within $bound", NativePlayerError.TIMED_OUT)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NativeVideoDecoderException) {
            throw failure
        } catch (failure: Throwable) {
            throw NativeVideoDecoderException(failure.message ?: "The worker failed", if (aborted) AVERROR_EXIT else failure.browserErrorCode)
        } finally {
            deadline = null
            running = false
        }
    }

    private fun requireWorker(): BrowserWorkerCalls = checkNotNull(worker) { "The video decoder has not started" }
}

/** AVERROR_EXIT: the call was interrupted or the decoder aborted. */
private const val AVERROR_EXIT = -0x54495845

/** The WebCodecs format and canvas colour space that copy a decoded frame into [format], if it can. */
private fun webCanvas(format: NativeFrameFormat): Pair<String, String>? {
    // RGB in full range with the sRGB curve, in sRGB or Display P3 primaries.
    if (format.matrix != 0 || format.range != 1 || format.transfer != 0) return null
    val name = when (format.layout) {
        0 -> "RGBA"
        1 -> "BGRA"
        else -> return null
    }
    val colorSpace = when (format.primaries) {
        0 -> "srgb"
        2 -> "display-p3"
        else -> return null
    }
    return name to colorSpace
}

/**
 * The sample aspect ratio that keeps a [width]x[height] frame's display aspect at [scaledWidth]x[scaledHeight], as
 * `ffmpegkmp_scaled_aspect` gives it: unchanged at the same size, and an unknown one counted as square.
 */
private fun scaledAspect(numerator: Int, denominator: Int, width: Int, height: Int, scaledWidth: Int, scaledHeight: Int): Pair<Int, Int> {
    if (width == scaledWidth && height == scaledHeight) return numerator to denominator
    val known = numerator > 0 && denominator > 0
    val scaledNumerator = (if (known) numerator else 1).toLong() * width * scaledHeight
    val scaledDenominator = (if (known) denominator else 1).toLong() * height * scaledWidth
    val divisor = greatestCommonDivisor(scaledNumerator, scaledDenominator)
    return (scaledNumerator / divisor).toInt() to (scaledDenominator / divisor).toInt()
}

private tailrec fun greatestCommonDivisor(first: Long, second: Long): Long =
    if (second == 0L) first else greatestCommonDivisor(second, first % second)

/** [width] and [height] are 0 for frames at the source's size. */
private fun openMessage(
    bytes: JsAny,
    extension: String,
    format: String?,
    colorSpace: String?,
    width: Int,
    height: Int,
    hardwareAcceleration: String,
): JsAny = js("({ type: 'video-open', bytes, extension, format, colorSpace, width, height, hardwareAcceleration })")

private fun positionMessage(type: String, positionNs: Double): JsAny = js("({ type, positionNs })")

private fun interruptMessage(): JsAny = js("({ type: 'video-interrupt' })")

private fun transferOf(bytes: JsAny): JsAny = js("[bytes.buffer]")

private fun openedSnapshot(opened: JsAny): String = js("opened.snapshot")

private fun hasPixels(reply: JsAny): Boolean = js("reply.buffer instanceof ArrayBuffer")

private fun replyBuffer(reply: JsAny): JsAny = js("reply.buffer")

private fun replyInt(reply: JsAny, name: String): Int = js("reply[name]")

private fun replyDouble(reply: JsAny, name: String): Double = js("reply[name]")

private fun planeCount(reply: JsAny): Int = js("reply.planes.length")

private fun planeInt(reply: JsAny, index: Int, name: String): Int = js("reply.planes[index][name]")
