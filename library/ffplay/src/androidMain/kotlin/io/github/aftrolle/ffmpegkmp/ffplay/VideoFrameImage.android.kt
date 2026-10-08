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
 * A new hardware bitmap over the buffer of every frame, which draws that frame with no copy: SDR as
 * sRGB, PQ and HLG as BT.2020 PQ and HLG. A wrap made once for a buffer would go on drawing its first
 * frame, since the GPU texture HWUI makes of a hardware bitmap is cached with the bitmap and never
 * learns that the decoder wrote the buffer again. The last two wraps stay open, as [FrameImage]
 * keeps the last two frames, since a drawing may still use the previous one.
 */
private class HardwareFrameBitmaps : GpuFrameWraps {
    private val live = ArrayDeque<Bitmap>(3)

    override var count: Int = 0
        private set

    override fun wrap(frame: VideoFrame): ImageBitmap {
        val gpu = checkNotNull(frame.gpuBuffer) { "The frame lies in no GPU buffer: $frame" }
        val colorSpace = when (gpu.hdrType) {
            NativePlayerHdrType.SDR -> ColorSpace.Named.SRGB
            NativePlayerHdrType.HLG -> ColorSpace.Named.BT2020_HLG
            else -> ColorSpace.Named.BT2020_PQ
        }
        val buffer = checkNotNull(frame.hardwareBuffer) { "The frame's GPU buffer is no HardwareBuffer: $frame" }
        val wrapped = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(colorSpace))) { "Could not wrap $buffer in a bitmap" }
        count++
        live.addLast(wrapped)
        while (live.size > 2) live.removeFirst().recycle()
        return wrapped.asImageBitmap()
    }

    override fun close() {
        live.forEach(Bitmap::recycle)
        live.clear()
    }
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
