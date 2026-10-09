// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

/**
 * `ffmpegkmp_frame_format`: a pixel layout and a colour, each the ordinal of the matching Kotlin
 * enum (`ffmpegkmp_pixel_layout`, `ffmpegkmp_color_primaries`, …).
 */
@InternalFFmpegKmpApi
public data class NativeFrameFormat(
    val layout: Int,
    val primaries: Int,
    val transfer: Int,
    val matrix: Int,
    val range: Int,
)

/**
 * One plane of a mapped frame: [memory] is a direct `java.nio.ByteBuffer` on JVM and Android, a
 * `CPointer<ByteVar>` on Kotlin/Native and a `ByteArray` in the browser, holding [size] bytes from
 * [offset] (non-zero only in the browser).
 */
@InternalFFmpegKmpApi
public class NativeFramePlane(
    public val memory: Any,
    public val offset: Int,
    public val size: Int,
    public val rowBytes: Int,
    public val rows: Int,
)

/**
 * One reference to a frame whose memory FFmpeg owns (`ffmpegkmp_frame`). [close] releases it, and
 * [retain] makes another; neither may race with the other calls on the same object.
 */
@InternalFFmpegKmpApi
public interface NativeFrame : AutoCloseable {
    public val width: Int
    public val height: Int

    /** Null when the layout is none of the model's, or the frame has no CPU-visible memory. */
    public val format: NativeFrameFormat?

    /** Whether [usePlanes] can reach the pixels. */
    public val mappable: Boolean

    /** Apple: the `CVPixelBufferRef` holding the frame, or null. */
    public val pixelBuffer: Any?

    /** Another reference to the same memory. */
    public fun retain(): NativeFrame

    /** Maps the planes for [block]; the planes are invalid once it returns. */
    public fun <R> usePlanes(block: (List<NativeFramePlane>) -> R): R

    /** One conversion into a new pooled frame of [format]. */
    public fun convert(format: NativeFrameFormat): NativeFrame

    /** One conversion into [target]'s memory, which keeps its format. */
    public fun convertInto(target: NativeFrame)

    /**
     * Runs [block] with the address and row bytes of a packed, single-plane frame's pixels,
     * writable in place until it returns, for drawing into: the frame must hold the only
     * reference to its memory, as a fresh [allocateNativeFrame] one does.
     */
    public fun <R> useWritablePixels(block: (address: Long, rowBytes: Int) -> R): R =
        throw UnsupportedOperationException("This frame's memory cannot be written in place")
}

/**
 * A frame of [format] from the process-wide pool, its pixels unspecified: pooled memory, or on
 * Apple a CVPixelBuffer for NV12, P010, BGRA8 and RGBA_F16.
 */
@InternalFFmpegKmpApi
public expect fun allocateNativeFrame(format: NativeFrameFormat, width: Int, height: Int): NativeFrame

/** Pixel memory the bridge allocated for frames: pool buffers, converter intermediates and hardware downloads. */
@InternalFFmpegKmpApi
public data class NativeFrameStatistics(val buffers: Long, val bytes: Long)

/** The bridge's allocation counters over the process's life; zero where the platform has no bridge. */
@InternalFFmpegKmpApi
public expect fun nativeFrameStatistics(): NativeFrameStatistics

@InternalFFmpegKmpApi
public class NativeFrameException(message: String, public val errorCode: Int) : IllegalStateException(message)

internal fun requireFrameSuccess(result: Int, action: String) {
    if (result < 0) throw NativeFrameException("Could not $action (error $result)", result)
}
