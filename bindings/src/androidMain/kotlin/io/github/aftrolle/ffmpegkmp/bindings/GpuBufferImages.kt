// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The `ImageReader` a hardware decoder renders into, and its images handed out as [NativeGpuBuffer]s:
 * how a MediaCodec frame reaches GPU memory the app can draw, for `VideoOutput.GpuBuffers` decoders
 * and for a player's Canvas output alike. MediaCodec renders each frame into [surface] stamped with
 * a timestamp the caller chose, and [take] waits, on the caller's thread, for the image that carries
 * it. The listener that wakes it runs on one thread for the process.
 *
 * [maxImages] is how many images the caller holds at once, the reader's own limit; MediaCodec renders
 * into buffers the reader's queue keeps for it on top of those. The buffers take the stream's size
 * and format, which MediaCodec sets: 10 bits per component for deeper sources. It needs Android 14
 * (API 34), for the images' fences and buffer ids.
 */
@InternalFFmpegKmpApi
public class GpuBufferImages(private val maxImages: Int) : AutoCloseable {
    private val reader = ImageReader.newInstance(
        NOMINAL_READER_SIZE, NOMINAL_READER_SIZE, ImageFormat.PRIVATE, maxImages, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    )

    /** Guards the fields below; [changed] is signalled when an image arrives or closes, and on [wake]. */
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    /** Images acquired and not yet closed: the ones the buffers handed out hold, and none else. */
    private var acquired = 0
    private var closed = false
    private var readerClosed = false

    /** The deadline, in [System.nanoTime], of a [take] waiting; 0 when none is. */
    @Volatile
    private var waitDeadline = 0L

    /** The Surface MediaCodec renders into. */
    public val surface: Surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ lock.withLock { changed.signalAll() } }, gpuBufferCallbacks)
    }

    /**
     * A new buffer holding the image stamped [timestampNanos], once the GPU may read it: the picture
     * in its crop, or the top left [width] × [height] where MediaCodec gives none, read by
     * [colorTransfer] and [colorPrimaries] (`AVColorTransferCharacteristic`, `AVColorPrimaries`).
     * Releasing its last reference hands the image back. Images that came before it, which an earlier
     * call that failed left, are handed back unused.
     *
     * Waits until [deadline], in [System.nanoTime], for MediaCodec to render the frame, and while the
     * caller holds all [maxImages] images a short grace for one to come back. Throws
     * [NativeVideoDecoderException]: [NativePlayerError.TIMED_OUT] past the deadline,
     * [NativePlayerError.RING_FULL] past the grace, and `AVERROR_EXIT` once [cancelled] says so, which
     * it asks whenever it wakes ([wake] wakes it).
     */
    public fun take(
        timestampNanos: Long,
        width: Int,
        height: Int,
        colorTransfer: Int,
        colorPrimaries: Int,
        deadline: Long,
        cancelled: () -> Boolean = { false },
    ): NativeGpuBuffer = lock.withLock {
        check(!closed) { "The GPU buffer images are closed" }
        val image = awaitImage(timestampNanos, deadline, cancelled)
        val hardwareBuffer = image.hardwareBuffer ?: run {
            closeImage(image)
            throw NativeVideoDecoderException("MediaCodec's image has no HardwareBuffer", NativePlayerError.UNSUPPORTED)
        }
        val crop = image.cropRect.takeUnless(Rect::isEmpty) ?: Rect(0, 0, width, height)
        NativeGpuBuffer(
            hardwareBuffer, hardwareBuffer.id, crop.left, crop.top, crop.right, crop.bottom, colorTransfer, colorPrimaries,
        ) {
            lock.withLock {
                hardwareBuffer.close()
                closeImage(image)
            }
        }
    }

    /** Wakes a [take] that is waiting, for it to ask whether it is cancelled. Any thread. */
    public fun wake() {
        lock.withLock { changed.signalAll() }
    }

    /** Microseconds until the deadline of a [take] that is waiting, negative once it has passed; null when none is. */
    public fun waitLeftMicros(): Long? = waitDeadline.takeIf { it != 0L }?.let { (it - System.nanoTime()) / 1000 }

    /** Closes the reader, at once or once the last buffer handed out is released. */
    override fun close() {
        lock.withLock {
            closed = true
            changed.signalAll()
            closeReaderOnceDone()
        }
    }

    /** The image stamped [timestampNanos], once the GPU may read it. Under [lock]. */
    private fun awaitImage(timestampNanos: Long, deadline: Long, cancelled: () -> Boolean): Image {
        var ringFullBy = 0L
        try {
            while (true) {
                if (cancelled() || closed) {
                    throw NativeVideoDecoderException("The wait for the frame at ${timestampNanos}ns was interrupted", AVERROR_EXIT)
                }
                val image = if (acquired < maxImages) acquireNextImage() else null
                if (image != null) {
                    acquired++
                    if (image.timestamp != timestampNanos) {
                        closeImage(image)
                        continue
                    }
                    if (awaitFence(image, deadline)) return image
                    closeImage(image)
                    throw NativeVideoDecoderException(
                        "The GPU did not finish the frame at ${timestampNanos}ns in time",
                        NativePlayerError.TIMED_OUT,
                    )
                }
                val now = System.nanoTime()
                val until = if (acquired >= maxImages) {
                    if (ringFullBy == 0L) ringFullBy = now + RING_GRACE_NANOS
                    if (now >= minOf(deadline, ringFullBy)) {
                        throw NativeVideoDecoderException(
                            "Could not take a buffer for the frame at ${timestampNanos}ns: all $maxImages are held",
                            NativePlayerError.RING_FULL,
                        )
                    }
                    minOf(deadline, ringFullBy)
                } else {
                    ringFullBy = 0L
                    if (now >= deadline) {
                        throw NativeVideoDecoderException(
                            "MediaCodec rendered no buffer for the frame at ${timestampNanos}ns in time",
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

    /** Hands [image] back to the reader, and closes the reader once it is closed and no image is held. Under [lock]. */
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

/**
 * How long a frame waits for a place among the images while the caller holds them all, before
 * failing: enough for a frame closing on another thread, as a collector's does while the frame ahead
 * of it is taken.
 */
private const val RING_GRACE_NANOS = 250_000_000L

/** The size a reader is made with; the buffers MediaCodec renders take the stream's. */
private const val NOMINAL_READER_SIZE = 128

/** `AVERROR_EXIT`, what an interrupted native call returns. */
private const val AVERROR_EXIT = -0x54495845

/** The thread every reader's listener runs on, one for the process: it only wakes whoever waits in [GpuBufferImages.take]. */
private val gpuBufferCallbacks: Handler by lazy {
    Handler(HandlerThread("FFmpegKMP GpuBuffers").apply { start() }.looper)
}
