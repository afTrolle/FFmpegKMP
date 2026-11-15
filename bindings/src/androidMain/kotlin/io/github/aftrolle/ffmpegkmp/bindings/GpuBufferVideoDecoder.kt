// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

/** The images in a GPU buffer decoder's `ImageReader`: its ring. */
internal const val GPU_BUFFER_RING = 3

/**
 * A [NativeVideoDecoderOutput.GPU_BUFFERS] decoder: [decoder] renders each hardware frame into
 * [images]' Surface, stamped with its pts, and [frameAt] hands it out as the image with that
 * timestamp, in a [NativeGpuBuffer] that closes the image with the frame's last reference.
 * Software frames, from the `AUTO` fallback, pass through in memory, converted and scaled as the
 * memory format says. Sources deeper than 8 bits stay
 * on the GPU when a hardware decoder takes them, 10 bits per component, and their frames carry the
 * source's transfer and primaries for the drawing to read them by.
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
    private val images: GpuBufferImages,
    private val timeoutMicros: Long,
) : NativeVideoDecoder {
    /** Guards the fields below, which [frameAt] and [close] use. */
    private val lock = Any()
    private var latest: NativeGpuBuffer? = null
    private var latestSerial = 0L
    private var colorTransfer = 0
    private var colorPrimaries = AVCOL_PRI_BT709

    @Volatile
    private var interrupted = false

    @Volatile
    private var aborted = false

    override suspend fun start(): NativeVideoStream = decoder.start().also {
        colorTransfer = it.info.colorTransfer
        colorPrimaries = it.info.colorPrimaries
    }

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

    // Scales the software frames only: MediaCodec's are its own buffers, drawn at any size by the GPU.
    override suspend fun setSize(width: Int, height: Int) = decoder.setSize(width, height)

    override fun interrupt() {
        interrupted = true
        decoder.interrupt()
        images.wake()
    }

    override fun abort() {
        aborted = true
        decoder.abort()
        images.wake()
    }

    override fun timeLeftMicros(): Long = images.waitLeftMicros() ?: decoder.timeLeftMicros()

    /** Closes the decoder; the reader closes once the frames still open have closed too. */
    override fun close() {
        decoder.close()
        synchronized(lock) {
            latest?.release()
            latest = null
        }
        images.close()
    }

    /** A new reference to the buffer [frame] was rendered into: the latest one again, or the image stamped with its pts. */
    private fun buffer(frame: NativeDecodedFrame, deadline: Long): NativeGpuBuffer = synchronized(lock) {
        val previous = latest
        if (previous != null && latestSerial == frame.serial) return previous.retain()
        // The caller's references keep the previous image; this one no longer counts against the ring.
        latest = null
        previous?.release()
        val buffer = images.take(frame.ptsNanos, frame.width, frame.height, colorTransfer, colorPrimaries, deadline) {
            interrupted || aborted
        }
        latest = buffer.retain()
        latestSerial = frame.serial
        buffer
    }
}
