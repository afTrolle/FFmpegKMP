// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The images in a GPU buffer decoder's `ImageReader`: its ring. */
internal const val GPU_BUFFER_RING = 3

/**
 * How long a frame waits for a place in a full ring before failing: enough for a frame closing on
 * another thread, as a collector's does while the frame ahead of it is taken.
 */
private const val GPU_BUFFER_RING_GRACE_NANOS = 250_000_000L

/**
 * A [NativeVideoDecoderOutput.GPU_BUFFERS] decoder: [decoder] renders each hardware frame into
 * [reader]'s Surface, stamped with its pts, and [frameAt] hands it out as the reader's image with
 * that timestamp, in a [NativeGpuBuffer] that closes the image with the frame's last reference.
 * Software frames, from the `AUTO` fallback, pass through in memory. Sources deeper than 8 bits stay
 * on the GPU when a hardware decoder takes them, 10 bits per component, and their frames carry the
 * source's transfer for the drawing to read PQ and HLG by.
 *
 * The reader's [GPU_BUFFER_RING] images are the ring. This keeps a reference to the latest frame's
 * buffer, so the same position again returns the same buffer. While the caller holds every image,
 * a new frame's fails with [NativePlayerError.RING_FULL] after a short grace, and the next call
 * takes the image the frame was rendered into.
 *
 * It needs Android 14 (API 34), which [createPlatformVideoDecoder] checks.
 */
internal class GpuBufferVideoDecoder(
    private val decoder: NativeVideoDecoder,
    private val reader: ImageReader,
    private val timeoutMicros: Long,
) : NativeVideoDecoder {
    /** Guards the fields below; [changed] is signalled when an image arrives or closes, and on interrupt and abort. */
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    /** Images acquired and not yet closed: the ones the decoder's frames hold. */
    private var acquired = 0
    private var latest: NativeGpuBuffer? = null
    private var latestSerial = 0L
    private var colorTransfer = 0
    private var closed = false
    private var readerClosed = false

    @Volatile
    private var interrupted = false

    @Volatile
    private var aborted = false

    /** The deadline, in [System.nanoTime], of a wait for an image under way; 0 when none is. */
    @Volatile
    private var waitDeadline = 0L

    init {
        reader.setOnImageAvailableListener({ lock.withLock { changed.signalAll() } }, gpuBufferCallbacks)
    }

    override suspend fun start(): NativeVideoStream = decoder.start().also { colorTransfer = it.info.colorTransfer }

    override suspend fun seek(positionNanos: Long) = decoder.seek(positionNanos)

    override suspend fun frameAt(positionNanos: Long): NativeDecodedFrame {
        val deadline = if (timeoutMicros > 0) System.nanoTime() + timeoutMicros * 1000 else Long.MAX_VALUE
        interrupted = false
        val frame = decoder.frameAt(positionNanos)
        if (frame.frame != null) return frame
        return NativeDecodedFrame(
            frame.serial, frame.ptsNanos, frame.durationNanos, frame.width, frame.height,
            frame.sampleAspectRatioNumerator, frame.sampleAspectRatioDenominator, frame.rotationDegrees, frame.hardware,
            frame = null,
            gpu = buffer(frame, deadline),
        )
    }

    override fun interrupt() {
        interrupted = true
        decoder.interrupt()
        lock.withLock { changed.signalAll() }
    }

    override fun abort() {
        aborted = true
        decoder.abort()
        lock.withLock { changed.signalAll() }
    }

    override fun timeLeftMicros(): Long {
        val deadline = waitDeadline
        return if (deadline != 0L) (deadline - System.nanoTime()) / 1000 else decoder.timeLeftMicros()
    }

    /** Closes the decoder; the reader closes once the frames still open have closed too. */
    override fun close() {
        decoder.close()
        lock.withLock {
            closed = true
            latest?.release()
            latest = null
            closeReaderOnceDone()
        }
    }

    /** A new reference to the buffer [frame] was rendered into: the latest one again, or the image stamped with its pts. */
    private fun buffer(frame: NativeDecodedFrame, deadline: Long): NativeGpuBuffer = lock.withLock {
        val previous = latest
        if (previous != null && latestSerial == frame.serial) return previous.retain()
        // The caller's references keep the previous image; this one no longer counts against the ring.
        latest = null
        previous?.release()
        val image = awaitImage(frame.ptsNanos, deadline)
        val hardwareBuffer = image.hardwareBuffer ?: run {
            closeImage(image)
            throw NativeVideoDecoderException("MediaCodec's image has no HardwareBuffer", NativePlayerError.UNSUPPORTED)
        }
        val crop = image.cropRect.takeUnless(Rect::isEmpty) ?: Rect(0, 0, frame.width, frame.height)
        val buffer = NativeGpuBuffer(
            hardwareBuffer, hardwareBuffer.id, crop.left, crop.top, crop.right, crop.bottom, colorTransfer,
        ) {
            lock.withLock {
                hardwareBuffer.close()
                closeImage(image)
            }
        }
        latest = buffer.retain()
        latestSerial = frame.serial
        buffer
    }

    /**
     * The reader's image stamped with [ptsNanos], once the GPU may read it, waiting until [deadline]
     * for MediaCodec to render it, and the grace for a place in the ring. Images an earlier, failed
     * call left behind are closed.
     */
    private fun awaitImage(ptsNanos: Long, deadline: Long): Image {
        var ringFullBy = 0L
        try {
            while (true) {
                if (interrupted || aborted) {
                    throw NativeVideoDecoderException("The wait for the frame at ${ptsNanos}ns was interrupted", AVERROR_EXIT)
                }
                val image = if (acquired < GPU_BUFFER_RING) acquireNextImage() else null
                if (image != null) {
                    acquired++
                    if (image.timestamp != ptsNanos) {
                        closeImage(image)
                        continue
                    }
                    if (awaitFence(image, deadline)) return image
                    closeImage(image)
                    throw NativeVideoDecoderException(
                        "The GPU did not finish the frame at ${ptsNanos}ns in time",
                        NativePlayerError.TIMED_OUT,
                    )
                }
                val now = System.nanoTime()
                val until = if (acquired >= GPU_BUFFER_RING) {
                    if (ringFullBy == 0L) ringFullBy = now + GPU_BUFFER_RING_GRACE_NANOS
                    if (now >= minOf(deadline, ringFullBy)) {
                        throw NativeVideoDecoderException(
                            "Could not take a buffer for the frame at ${ptsNanos}ns: the caller holds all " +
                                "$GPU_BUFFER_RING of the decoder's frames",
                            NativePlayerError.RING_FULL,
                        )
                    }
                    minOf(deadline, ringFullBy)
                } else {
                    ringFullBy = 0L
                    if (now >= deadline) {
                        throw NativeVideoDecoderException(
                            "MediaCodec rendered no buffer for the frame at ${ptsNanos}ns in time",
                            NativePlayerError.TIMED_OUT,
                        )
                    }
                    deadline
                }
                waitDeadline = deadline
                changed.awaitNanos(until - now)
            }
        } finally {
            waitDeadline = 0L
        }
    }

    private fun acquireNextImage(): Image? = try {
        reader.acquireNextImage()
    } catch (full: IllegalStateException) {
        // The reader counts an image as held until its close has reached it; wait for that.
        null
    }

    /** Waits until the GPU has finished writing [image]; false when it has not by [deadline]. */
    private fun awaitFence(image: Image, deadline: Long): Boolean {
        val fence = runCatching { image.fence }.getOrNull() ?: return true
        return fence.use { if (it.isValid) it.await(java.time.Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0))) else true }
    }

    /** Hands [image] back to the reader, and closes the reader once the decoder is closed and no image is held. */
    private fun closeImage(image: Image) {
        image.close()
        acquired--
        changed.signalAll()
        closeReaderOnceDone()
    }

    private fun closeReaderOnceDone() {
        if (!closed || acquired > 0 || readerClosed) return
        readerClosed = true
        reader.close()
    }
}

/** `AVERROR_EXIT`, what an interrupted native call returns. */
private const val AVERROR_EXIT = -0x54495845

/** The thread the GPU buffer decoders' readers call back on, one for the process. */
private val gpuBufferCallbacks: Handler by lazy {
    Handler(HandlerThread("FFmpegKMP GpuBuffers").apply { start() }.looper)
}
