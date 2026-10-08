// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerHdrType
import io.github.aftrolle.ffmpegkmp.bindings.convertIntoBitmap
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.hardwareBuffer

public actual fun VideoFrame.toImageBitmap(): ImageBitmap = toFrameBitmap(bitmapFormat()).image

internal actual fun VideoFrame.bitmapFormat(): FrameFormat = imageFormat { it == FrameFormat.Rgba8 || it == FrameFormat.RgbaF16 }

internal actual fun VideoFrame.toFrameBitmap(format: FrameFormat): FrameBitmap = AndroidFrameBitmap(this, format)

internal actual fun gpuFrameWraps(): GpuFrameWraps = HardwareFrameBitmaps()

internal actual fun DrawScope.checkDrawsGpuImages() = check(drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
    "A frame in GPU memory (VideoOutput.GpuBuffers) draws only on a GPU canvas, not on a software one such as " +
        "ComposeFrameRenderer's software path; the renderer draws on the GPU on Android 14 (API 34) and later"
}

/**
 * Hardware bitmaps over a decoder's buffers, by buffer id, which draw whatever frame their buffer
 * holds with no copy: SDR as sRGB, PQ and HLG as BT.2020 PQ and HLG. The decoder's frames take turns
 * in a few buffers, so after the first frames every update finds its wrap here. Beyond [MAX_WRAPS]
 * the least recently used is recycled, by then a buffer of a decoder no longer shown.
 */
private class HardwareFrameBitmaps : GpuFrameWraps {
    private val bitmaps = LinkedHashMap<Long, HardwareFrameBitmap>(MAX_WRAPS, 0.75f, true)

    override var count: Int = 0
        private set

    override fun wrap(frame: VideoFrame): ImageBitmap {
        val gpu = checkNotNull(frame.gpuBuffer) { "The frame lies in no GPU buffer: $frame" }
        val colorSpace = when (gpu.hdrType) {
            NativePlayerHdrType.SDR -> ColorSpace.Named.SRGB
            NativePlayerHdrType.HLG -> ColorSpace.Named.BT2020_HLG
            else -> ColorSpace.Named.BT2020_PQ
        }
        bitmaps[gpu.id]?.takeIf { it.colorSpace == colorSpace }?.let { return it.image }
        val buffer = checkNotNull(frame.hardwareBuffer) { "The frame's GPU buffer is no HardwareBuffer: $frame" }
        val wrapped = HardwareFrameBitmap(
            checkNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(colorSpace))) { "Could not wrap $buffer in a bitmap" },
            colorSpace,
        )
        count++
        bitmaps.put(gpu.id, wrapped)?.close()
        val eldest = bitmaps.entries.iterator()
        while (bitmaps.size > MAX_WRAPS) {
            eldest.next().value.close()
            eldest.remove()
        }
        return wrapped.image
    }

    override fun close() {
        bitmaps.values.forEach(HardwareFrameBitmap::close)
        bitmaps.clear()
    }

    private companion object {
        const val MAX_WRAPS = 16
    }
}

/** `Bitmap.wrapHardwareBuffer` over one of a decoder's buffers: nothing is copied or allocated for the pixels. */
private class HardwareFrameBitmap(private val bitmap: Bitmap, val colorSpace: ColorSpace.Named) : AutoCloseable {
    val image: ImageBitmap = bitmap.asImageBitmap()

    override fun close() = bitmap.recycle()
}

/**
 * An Android bitmap holding [frame], converted straight into its pixels, locked with
 * `AndroidBitmap_lockPixels`. Unlocking them gives the bitmap a new generation, which is what
 * tells the renderer to upload it again after a [write].
 */
private class AndroidFrameBitmap(frame: VideoFrame, override val format: FrameFormat) : FrameBitmap {
    override val width = frame.width
    override val height = frame.height
    private val bitmap = if (format == FrameFormat.RgbaF16) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { "RGBA_F16 bitmaps need Android 8.0 (API 26)" }
        Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.RGBA_F16,
            true,
            ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB),
        )
    } else {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }
    override val image: ImageBitmap = bitmap.asImageBitmap()

    init {
        try {
            write(frame)
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    override fun write(frame: VideoFrame) {
        frame.useNative { native -> convertIntoBitmap(checkNotNull(native) { "The frame has no pixels: $frame" }, bitmap) }
    }

    override fun close() = bitmap.recycle()
}
