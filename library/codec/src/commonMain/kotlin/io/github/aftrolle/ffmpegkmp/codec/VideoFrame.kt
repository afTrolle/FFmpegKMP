// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.NativeFrame
import io.github.aftrolle.ffmpegkmp.bindings.NativeGpuBuffer
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.time.Duration

/**
 * One video frame, shown from [pts] for [duration], whose memory FFmpeg owns. Rotation and sample
 * aspect ratio are reported, never applied to the pixels.
 *
 * The decoder rescales [pts] and [duration] from the stream's time base to nanoseconds as FFmpeg's
 * `av_rescale_q` does, to the nearest, so frame 2 of a 30 fps clip is 66,666,667 ns, not the
 * truncated 66,666,666 that `2.seconds / 30` gives. Compare against a rounded time, or within a
 * microsecond.
 *
 * A `VideoFrame` is one reference to that memory:
 * - [close] releases it, and the memory goes back to its pool when the last reference closes.
 * - [retain] makes another reference, which needs a [close] of its own.
 * - A function that takes a frame takes its reference; [retain] it first to keep using it.
 * - [usePlanes] views are valid only inside the block.
 * - Using a closed frame throws [IllegalStateException]. Closing twice does nothing.
 *
 * A frame in GPU memory, from [VideoOutput.GpuBuffers], has no CPU-visible pixels: its [format] is
 * null, [usePlanes] returns null and [convert] fails. Its last reference hands the buffer back to
 * the decoder.
 *
 * Frames from different references may be used from different threads at once.
 */
public class VideoFrame private constructor(
    private val native: NativeFrame?,
    private val gpu: NativeGpuBuffer?,
    public val pts: Duration,
    public val duration: Duration,
    public val width: Int,
    public val height: Int,
    public val rotationDegrees: Double,
    public val sampleAspectRatio: Double,
) : AutoCloseable {
    /**
     * The pixel layout and colour. Null for frames that exist only on a GPU surface, such as
     * Android's Surface output, for frames in GPU memory, such as [VideoOutput.GpuBuffers]' ones,
     * and for frames decoded in a layout [PixelLayout] does not name, whose planes [usePlanes]
     * still reaches.
     */
    public val format: FrameFormat? = native?.format?.toFrameFormat()

    // The frame's own reference plus each call in flight; the last one out releases the memory.
    private val holds = AtomicInt(1)
    private val closed = AtomicBoolean(false)

    /**
     * Runs [block] with views of the pixels, one [FramePlane] per plane, without a copy. The views
     * are valid only inside [block]. Returns null when the frame has no CPU-visible memory.
     */
    public fun <R> usePlanes(block: (List<FramePlane>) -> R): R? = using { native ->
        if (native == null || !native.mappable) return@using null
        native.usePlanes { planes ->
            val views = planes.map { plane ->
                FramePlane(frameBytes(plane.memory, plane.offset, plane.size), plane.rowBytes, plane.rows)
            }
            try {
                block(views)
            } finally {
                views.forEach { it.bytes.invalidate() }
            }
        }
    }

    /**
     * One threaded pass into a new pooled frame of [to]; a [retain] of this frame when [format]
     * already is [to]. HDR survives wherever [to] can hold it: PQ or HLG to [FrameFormat.RgbaF16]
     * keeps highlights above 1.0, and to an SDR format is tone mapped.
     *
     * The new frame comes from the process-wide pool, which has no bound, so a conversion is how
     * to keep more of a decoder's frames than its ring of three (see [VideoOutput.Memory]).
     *
     * Throws [IllegalStateException] for a frame without CPU-visible memory, such as one in GPU
     * memory.
     */
    public fun convert(to: FrameFormat): VideoFrame {
        if (format == to) return retain()
        return using { native -> copy(pixels(native).convert(to.toNative())) }
    }

    /**
     * One threaded pass into memory [target] owns, such as a render target or an encoder input.
     * [target] keeps its format and size and takes this frame's other properties. A target of
     * another size is scaled into, bilinearly in the same pass, except from one
     * [PixelLayout.RGBA_F16] frame into another, and in the browser, which copies RGBA8 frames of
     * the same size only.
     */
    public fun convertInto(target: VideoFrame) {
        require(target !== this) { "A frame cannot be converted into itself" }
        using { native -> target.using { destination -> pixels(native).convertInto(pixels(destination)) } }
    }

    /**
     * Another reference to the same memory; each reference needs its own [close]. A reference to a
     * frame from a decoder's ring shares its frame's place in it, which stays taken until every
     * reference has closed.
     */
    public fun retain(): VideoFrame = using { native -> copy(native?.retain(), gpu?.retain()) }

    /** Runs [block] with this reference's native frame, which stays valid until it returns. */
    @InternalFFmpegKmpApi
    public fun <R> useNative(block: (NativeFrame?) -> R): R = using(block)

    /** The GPU memory that holds this frame, or null; valid while this reference is open. */
    @InternalFFmpegKmpApi
    public val gpuBuffer: NativeGpuBuffer? get() = using { gpu }

    override fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) release()
    }

    override fun toString(): String =
        "VideoFrame(pts=$pts, duration=$duration, ${width}x$height, format=$format, " +
            "rotationDegrees=$rotationDegrees, sampleAspectRatio=$sampleAspectRatio)"

    private inline fun <R> using(block: (NativeFrame?) -> R): R {
        while (true) {
            val count = holds.load()
            check(count > 0 && !closed.load()) { "The video frame is closed" }
            if (holds.compareAndSet(count, count + 1)) break
        }
        try {
            return block(native)
        } finally {
            release()
        }
    }

    private fun release() {
        if (holds.decrementAndFetch() == 0) {
            native?.close()
            gpu?.release()
        }
    }

    private fun pixels(native: NativeFrame?): NativeFrame = checkNotNull(native?.takeIf { it.mappable }) {
        if (gpu != null) {
            "The frame has no CPU-visible memory: it lies in GPU memory (VideoOutput.GpuBuffers), which FrameImage draws"
        } else {
            "The frame has no CPU-visible memory: it was rendered to a Surface"
        }
    }

    private fun copy(native: NativeFrame?, gpu: NativeGpuBuffer? = null): VideoFrame =
        VideoFrame(native, gpu, pts, duration, width, height, rotationDegrees, sampleAspectRatio)

    public companion object {
        /**
         * A frame holding [native]'s reference, which it takes; null for one rendered to a Surface.
         * Decoders and players create frames through this.
         */
        @InternalFFmpegKmpApi
        public fun of(
            native: NativeFrame?,
            pts: Duration,
            duration: Duration,
            width: Int = native?.width ?: 0,
            height: Int = native?.height ?: 0,
            rotationDegrees: Double = 0.0,
            sampleAspectRatio: Double = 1.0,
        ): VideoFrame = VideoFrame(native, null, pts, duration, width, height, rotationDegrees, sampleAspectRatio)

        /** A frame in [gpu]'s memory, holding the reference to it that it takes. */
        @InternalFFmpegKmpApi
        public fun of(
            gpu: NativeGpuBuffer,
            pts: Duration,
            duration: Duration,
            width: Int,
            height: Int,
            rotationDegrees: Double = 0.0,
            sampleAspectRatio: Double = 1.0,
        ): VideoFrame = VideoFrame(null, gpu, pts, duration, width, height, rotationDegrees, sampleAspectRatio)
    }
}

/** One plane of a frame: [rows] rows of [rowBytes] bytes each, the last possibly padded. */
public class FramePlane(public val bytes: FrameBytes, public val rowBytes: Int, public val rows: Int)

/**
 * Pixel memory FFmpeg owns, read in place: a direct `ByteBuffer` on JVM and Android, a `CPointer`
 * on Kotlin/Native, and the frame's bytes in the page on the web. Valid only inside
 * [VideoFrame.usePlanes]; reading it afterwards throws [IllegalStateException].
 */
public expect class FrameBytes {
    public val size: Int

    public operator fun get(index: Int): Byte

    /** Copies all [size] bytes into [destination] from [offset]. */
    public fun copyInto(destination: ByteArray, offset: Int = 0)

    internal fun invalidate()
}

/** The platform's [FrameBytes] over a plane's memory, as the bindings map it. */
internal expect fun frameBytes(memory: Any, offset: Int, size: Int): FrameBytes
