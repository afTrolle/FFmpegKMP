// SPDX-License-Identifier: Apache-2.0
// Compiled into the JVM, Kotlin/Native and web targets, which all draw through Skia.
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.PixelLayout
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

public actual fun VideoFrame.toImageBitmap(): ImageBitmap {
    val bitmap = SkiaFrameBitmap(this, bitmapFormat())
    // Never written again, so Skia may draw its pixels in place rather than from a copy.
    bitmap.pixels.setImmutable()
    return bitmap.image
}

internal actual fun VideoFrame.bitmapFormat(): FrameFormat = imageFormat { it.skiaColorSpace() != null }

internal actual fun VideoFrame.toFrameBitmap(format: FrameFormat): FrameBitmap = SkiaFrameBitmap(this, format)

internal actual fun gpuFrameWraps(): GpuFrameWraps = NoGpuFrameWraps

internal actual fun DrawScope.checkDrawsGpuImages() = Unit

/** Skia's platforms decode into memory only. */
private object NoGpuFrameWraps : GpuFrameWraps {
    override val count: Int = 0

    override fun wrap(frame: VideoFrame): ImageBitmap = throw IllegalStateException("Only Android frames lie in GPU memory: $frame")

    override fun close() = Unit
}

/**
 * A Skia bitmap holding [frame], converted into it. It stays mutable for [write]: Skia draws a
 * mutable bitmap from a copy of its pixels and caches what it drew by the bitmap's generation, so
 * each write gives it a new one.
 */
private class SkiaFrameBitmap(frame: VideoFrame, override val format: FrameFormat) : FrameBitmap {
    override val width = frame.width
    override val height = frame.height
    private val info = ImageInfo(width, height, format.skiaColorType(), ColorAlphaType.UNPREMUL, format.skiaColorSpace())
    val pixels = Bitmap()

    init {
        try {
            frame.fillBitmap(pixels, info, format)
        } catch (failure: Throwable) {
            pixels.close()
            throw failure
        }
    }

    // A Bitmap keeps F16 and linear light; Image.toComposeImageBitmap would redraw into 8-bit sRGB.
    override val image: ImageBitmap = pixels.asComposeImageBitmap()

    override fun write(frame: VideoFrame) {
        frame.fillBitmap(pixels, info, format)
        pixels.notifyPixelsChanged()
    }

    override fun close() = pixels.close()
}

internal fun FrameFormat.skiaColorType(): ColorType = when (layout) {
    PixelLayout.RGBA8 -> ColorType.RGBA_8888
    PixelLayout.BGRA8 -> ColorType.BGRA_8888
    PixelLayout.RGBA_1010102 -> ColorType.RGBA_1010102
    PixelLayout.RGBA_F16 -> ColorType.RGBA_F16
    else -> error("A bitmap holds RGB layouts only, not $layout")
}

/** Skia's three canvas spaces; null for a colour none of them is. */
internal fun FrameFormat.skiaColorSpace(): ColorSpace? = when {
    color.transfer == ColorTransfer.LINEAR && color.primaries == ColorPrimaries.BT709 -> ColorSpace.sRGBLinear
    layout == PixelLayout.RGBA_F16 -> null
    color.transfer != ColorTransfer.SRGB && color.transfer != ColorTransfer.BT709 -> null
    color.primaries == ColorPrimaries.BT709 -> ColorSpace.sRGB
    color.primaries == ColorPrimaries.DISPLAY_P3 -> ColorSpace.displayP3
    else -> null
}
