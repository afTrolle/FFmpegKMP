// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntRect
import io.github.aftrolle.ffmpegkmp.codec.ColorMatrix
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.PixelLayout
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame

/**
 * The frame as an image Compose draws, converted once straight into the bitmap's own memory. An
 * RGB frame keeps its format where a bitmap holds it, so [FrameFormat.RgbaF16] frames keep their
 * HDR highlights above 1.0 in a linear extended sRGB bitmap; YUV frames become [FrameFormat.Rgba8],
 * PQ and HLG ones tone mapped to SDR. The frame stays open: close it when done with it.
 *
 * - Skiko (JVM, Apple, web): RGBA8, BGRA8, RGBA_1010102 and RGBA_F16 frames keep their layout, in
 *   sRGB, Display P3 or (half floats) linear extended sRGB; other RGB colours become RGBA8 or,
 *   for half floats, RGBA_F16 in linear extended sRGB.
 * - Android: `ARGB_8888` in sRGB, or for half-float frames `RGBA_F16` in linear extended sRGB,
 *   which needs Android 8.0 (API 26).
 *
 * Each call allocates a bitmap, which only the garbage collector frees. To draw a source frame
 * after frame, a [FrameImage] reuses one.
 *
 * Throws [IllegalStateException] for a frame without CPU-visible pixels, such as one rendered to
 * a Surface, or a closed one.
 */
public expect fun VideoFrame.toImageBitmap(): ImageBitmap

/**
 * A bitmap Compose draws, which frames of its size and format are converted into in place: what
 * [toImageBitmap] returns and what a [FrameImage] reuses.
 */
internal interface FrameBitmap : AutoCloseable {
    val width: Int
    val height: Int
    val format: FrameFormat
    val image: ImageBitmap

    /** The part of [image] that holds the picture. */
    val source: IntRect get() = IntRect(0, 0, width, height)

    /** Converts [frame], of this size, into the pixels, and tells the platform they changed. */
    fun write(frame: VideoFrame)
}

/**
 * Images over the GPU buffers of frames from `VideoOutput.GpuBuffers`, a new one for each frame:
 * an image of a buffer keeps showing what the buffer held when it was made, however often the
 * decoder writes it again.
 */
internal interface GpuFrameWraps : AutoCloseable {
    /** The images made so far. */
    val count: Int

    /**
     * A new image of [frame]'s whole buffer, which shows the frame while it is open, without a copy.
     * It stays valid until two more have been made, and is closed with the wraps.
     */
    fun wrap(frame: VideoFrame): ImageBitmap
}

/** Android's `HardwareBuffer` wraps; elsewhere no frame lies in GPU memory, and wrapping fails. */
internal expect fun gpuFrameWraps(): GpuFrameWraps

/** Fails, saying why, where this canvas cannot draw an image in GPU memory: on Android, a software canvas. */
internal expect fun DrawScope.checkDrawsGpuImages()

/** A new bitmap of [format] holding this frame, converted into it. */
internal expect fun VideoFrame.toFrameBitmap(format: FrameFormat): FrameBitmap

/** The format [toImageBitmap] and [FrameImage] draw this frame in. */
internal expect fun VideoFrame.bitmapFormat(): FrameFormat

/**
 * The format a bitmap draws this frame in: its own when [holds] it, RGBA_F16 for the other
 * half-float frames, and RGBA8 for everything else.
 */
internal fun VideoFrame.imageFormat(holds: (FrameFormat) -> Boolean): FrameFormat {
    val format = checkNotNull(format) { "The frame has no pixels in a layout a bitmap holds: $this" }
    return when {
        format.color.matrix == ColorMatrix.RGB && holds(format) -> format
        format.layout == PixelLayout.RGBA_F16 -> FrameFormat.RgbaF16
        else -> FrameFormat.Rgba8
    }
}
