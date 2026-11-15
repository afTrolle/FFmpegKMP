// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.CodecSelection
import io.github.aftrolle.ffmpegkmp.codec.ColorMatrix
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.FrameColor
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.FrameExtractor
import io.github.aftrolle.ffmpegkmp.codec.FrameScale
import io.github.aftrolle.ffmpegkmp.codec.FrameSize
import io.github.aftrolle.ffmpegkmp.codec.PixelLayout
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.extractFrameIn
import io.github.aftrolle.ffmpegkmp.core.ExperimentalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The frame as an image Compose draws, converted once straight into the bitmap's own memory. An
 * RGB frame keeps its format where a bitmap holds it, so [FrameFormat.RgbaF16] frames keep their
 * HDR highlights above 1.0 in a linear extended sRGB bitmap; YUV frames become [FrameFormat.Rgba8],
 * PQ and HLG ones tone mapped to SDR, except on Android 14 and later, which shows them itself. The
 * frame stays open: close it when done with it.
 *
 * - Skiko (JVM, Apple, web): RGBA8, BGRA8, RGBA_1010102 and RGBA_F16 frames keep their layout, in
 *   sRGB, Display P3 or (half floats) linear extended sRGB; other RGB colours become RGBA8 or,
 *   for half floats, RGBA_F16 in linear extended sRGB.
 * - Android: `ARGB_8888` in sRGB, or for half-float frames `RGBA_F16` in linear extended sRGB,
 *   which needs Android 8.0 (API 26). From Android 14 (API 34), PQ and HLG frames keep their codes
 *   in an `RGBA_1010102` bitmap tagged BT.2020 PQ or HLG, which the platform tone maps or shows as
 *   HDR as the canvas and window decide, as [FFplaySurface] shows the video.
 *
 * Each call allocates a bitmap, which only the garbage collector frees. To draw a source frame
 * after frame, a [FrameImage] reuses one.
 *
 * Throws [IllegalStateException] for a frame without CPU-visible pixels, such as one in GPU
 * memory, or a closed one.
 */
public expect fun VideoFrame.toImageBitmap(): ImageBitmap

/**
 * The frame [VideoDecoder.extractFrame] gives at [position], as an image Compose draws in the
 * platform's display colour, as [toImageBitmap] and [FFplaySurface] show it: on Android 14 (API 34)
 * and later a PQ or HLG source keeps its codes for the platform to show, where `extractFrame` tone
 * maps it to SDR. The arguments and failures are `extractFrame`'s.
 */
@ExperimentalFFmpegKmpApi
public suspend fun VideoDecoder.Companion.extractImageBitmap(
    source: MediaSource,
    position: Duration = Duration.ZERO,
    size: FrameSize? = null,
    scale: FrameScale = FrameScale.FIT,
    decoder: CodecSelection = CodecPreference.AUTO,
    timeout: Duration = 10.seconds,
): ImageBitmap = extractFrameIn(source, position, size, scale, decoder, timeout) { it.color.displayFormat() }.use { it.toImageBitmap() }

/** [VideoDecoder.extractImageBitmap]'s image, from the decoder [FrameExtractor] keeps between its frames. */
@ExperimentalFFmpegKmpApi
public suspend fun FrameExtractor.extractImageBitmap(
    source: MediaSource,
    position: Duration = Duration.ZERO,
    size: FrameSize? = null,
    scale: FrameScale = FrameScale.FIT,
    decoder: CodecSelection = CodecPreference.AUTO,
    timeout: Duration = 10.seconds,
): ImageBitmap = extractFrameIn(source, position, size, scale, decoder, timeout) { it.color.displayFormat() }.use { it.toImageBitmap() }

/** The format [VideoDecoder.extractImageBitmap] extracts a source of this colour in: what [toImageBitmap] shows as it is. */
internal expect fun FrameColor.displayFormat(): FrameFormat

/**
 * A bitmap Compose draws, which frames of its format are converted into in place, scaled to its
 * size: what [toImageBitmap] returns and what a [FrameImage] reuses.
 */
internal interface FrameBitmap : AutoCloseable {
    val width: Int
    val height: Int
    val format: FrameFormat
    val image: ImageBitmap

    /** The part of [image] that holds the picture. */
    val source: IntRect get() = IntRect(0, 0, width, height)

    /** Converts [frame] into the pixels, scaled to this size, and tells the platform they changed. */
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
     * It stays valid until two more have been made, and is closed with the wraps. With [platformHdr],
     * a PQ or HLG frame is tagged for the platform to show as it shows HDR, as any canvas or window
     * mode decides, instead of being drawn as linear light.
     */
    fun wrap(frame: VideoFrame, platformHdr: Boolean = false): GpuImage
}

/**
 * An image over a frame's GPU buffer, and how its pixels are coded: [ColorTransfer.PQ] and
 * [ColorTransfer.HLG] ones are the signal's codes as they are, which [drawCodedImage] turns into
 * light, and any other is a plain image.
 */
internal class GpuImage(val image: ImageBitmap, val transfer: ColorTransfer)

/** Android's `HardwareBuffer` wraps; elsewhere no frame lies in GPU memory, and wrapping fails. */
internal expect fun gpuFrameWraps(): GpuFrameWraps

/** Fails, saying why, where this canvas cannot draw an image in GPU memory: on Android, a software canvas. */
internal expect fun DrawScope.checkDrawsGpuImages()

/**
 * Draws [source] of a [GpuImage] whose [transfer] is PQ or HLG, as [drawUpright] draws a plain one
 * into [dstSize] at [dstOffset], as linear light on the canvas's scale, 1.0 at 203 nits, which a canvas
 * that holds values above 1.0 shows in full.
 */
internal expect fun DrawScope.drawCodedImage(
    image: ImageBitmap,
    source: IntRect,
    dstOffset: IntOffset,
    dstSize: IntSize,
    transfer: ColorTransfer,
)

/**
 * A new bitmap of [format] and [size] holding this frame, converted into it. A [size] other than
 * the frame's needs [scalesIntoBitmaps].
 */
internal expect fun VideoFrame.toFrameBitmap(format: FrameFormat, size: IntSize): FrameBitmap

/** Whether this platform's bitmaps take frames of another size, scaling them: not in the browser. */
internal expect val bitmapsScale: Boolean

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
