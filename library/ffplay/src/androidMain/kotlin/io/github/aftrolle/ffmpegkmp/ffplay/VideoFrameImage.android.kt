// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_PRI_BT2020
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_PRI_SMPTE432
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_TRC_ARIB_STD_B67
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_TRC_SMPTE2084
import io.github.aftrolle.ffmpegkmp.bindings.convertIntoBitmap
import io.github.aftrolle.ffmpegkmp.codec.ColorMatrix
import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.ColorRange
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.FrameColor
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.PixelLayout
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.hardwareBuffer

public actual fun VideoFrame.toImageBitmap(): ImageBitmap = toFrameBitmap(bitmapFormat(), IntSize(width, height)).image

internal actual fun VideoFrame.bitmapFormat(): FrameFormat = format?.color?.hdrBitmapFormat() ?: sdrBitmapFormat()

internal actual fun FrameColor.displayFormat(): FrameFormat = hdrBitmapFormat() ?: FrameFormat.Rgba8

/**
 * PQ and HLG from Android 14 as their own codes in 10 bits, which a bitmap tagged BT.2020 PQ or HLG
 * hands the platform to show as it shows HDR; null for SDR and earlier versions.
 */
private fun FrameColor.hdrBitmapFormat(): FrameFormat? = if (isHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    FrameFormat(PixelLayout.RGBA_1010102, FrameColor(ColorPrimaries.BT2020, transfer, ColorMatrix.RGB, ColorRange.FULL))
} else {
    null
}

/** The format a CPU canvas draws this frame in: PQ and HLG tone mapped to sRGB by the conversion. */
internal fun VideoFrame.sdrBitmapFormat(): FrameFormat = imageFormat { it == FrameFormat.Rgba8 || it == FrameFormat.RgbaF16 }

internal actual fun VideoFrame.toFrameBitmap(format: FrameFormat, size: IntSize): FrameBitmap = AndroidFrameBitmap(this, format, size)

internal actual val bitmapsScale: Boolean = true

internal actual fun gpuFrameWraps(): GpuFrameWraps = HardwareFrameBitmaps()

internal actual fun DrawScope.checkDrawsGpuImages() = check(drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
    "A frame in GPU memory (VideoOutput.GpuBuffers) draws only on a GPU canvas, not on a software one such as " +
        "ComposeFrameRenderer's software path; the renderer draws on the GPU on Android 14 (API 34) and later"
}

/**
 * A new hardware bitmap over the buffer of every frame, which draws that frame with no copy. SDR
 * wraps as its primaries with the sRGB curve, shown as coded as the conversion shows it: sRGB,
 * Display P3, or BT.2020 primaries, which HWUI maps into the canvas's gamut. PQ and HLG wrap, for
 * the platform to show, as BT.2020 PQ or HLG, which HWUI tone maps for an SDR or wide-gamut canvas
 * and shows with headroom in an HDR window; otherwise as linear sRGB, so that HWUI applies no tone
 * mapping: the GPU converts the buffer's YUV to RGB with the matrix its data space names, and
 * [drawCodedImage]'s shader takes the signal's codes back out of whatever canvas it draws on with
 * `toLinearSrgb`. A wrap made once for a buffer would go on
 * drawing its first frame, since the GPU texture HWUI makes of a hardware bitmap is cached with the
 * bitmap and never learns that the decoder wrote the buffer again. The last two wraps stay open, as
 * [FrameImage] keeps the last two frames, since a drawing may still use the previous one.
 */
private class HardwareFrameBitmaps : GpuFrameWraps {
    private val live = ArrayDeque<Bitmap>(3)

    override var count: Int = 0
        private set

    override fun wrap(frame: VideoFrame, platformHdr: Boolean): GpuImage {
        val gpu = checkNotNull(frame.gpuBuffer) { "The frame lies in no GPU buffer: $frame" }
        val transfer = when (gpu.colorTransfer) {
            AVCOL_TRC_SMPTE2084 -> ColorTransfer.PQ
            AVCOL_TRC_ARIB_STD_B67 -> ColorTransfer.HLG
            else -> ColorTransfer.SRGB
        }
        val tagged = platformHdr && transfer != ColorTransfer.SRGB
        val colorSpace = when {
            tagged -> hdrColorSpace(transfer)
            transfer != ColorTransfer.SRGB -> ColorSpace.get(ColorSpace.Named.LINEAR_SRGB)
            gpu.colorPrimaries == AVCOL_PRI_SMPTE432 -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            gpu.colorPrimaries == AVCOL_PRI_BT2020 -> bt2020Srgb
            else -> ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val buffer = checkNotNull(frame.hardwareBuffer) { "The frame's GPU buffer is no HardwareBuffer: $frame" }
        val wrapped = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, colorSpace)) { "Could not wrap $buffer in a bitmap" }
        count++
        live.addLast(wrapped)
        while (live.size > 2) live.removeFirst().recycle()
        return GpuImage(wrapped.asImageBitmap(), if (tagged) ColorTransfer.SRGB else transfer)
    }

    override fun close() {
        live.forEach(Bitmap::recycle)
        live.clear()
    }
}

/**
 * BT.2020 primaries with the sRGB curve: SDR BT.2020 video shown as coded, as the conversion shows
 * it. Android's own BT.2020 space carries the BT.2020 camera curve instead.
 */
private val bt2020Srgb: ColorSpace by lazy {
    val srgb = ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb
    val bt2020 = ColorSpace.get(ColorSpace.Named.BT2020) as ColorSpace.Rgb
    ColorSpace.Rgb("BT.2020 (sRGB curve)", bt2020.primaries, bt2020.whitePoint, checkNotNull(srgb.transferParameters))
}

/** Android's BT.2020 PQ or HLG, which HWUI tone maps or shows as HDR as the canvas and window decide. */
private fun hdrColorSpace(transfer: ColorTransfer): ColorSpace {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { "PQ and HLG bitmaps need Android 14 (API 34)" }
    return ColorSpace.get(if (transfer == ColorTransfer.HLG) ColorSpace.Named.BT2020_HLG else ColorSpace.Named.BT2020_PQ)
}

internal actual fun DrawScope.drawCodedImage(
    image: ImageBitmap,
    source: IntRect,
    dstOffset: IntOffset,
    dstSize: IntSize,
    transfer: ColorTransfer,
) {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { "PQ and HLG frames in GPU memory need Android 13 (API 33)" }
    drawCodedRect(image.asAndroidBitmap(), source, dstOffset, dstSize, transfer)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawCodedRect(
    bitmap: Bitmap,
    source: IntRect,
    dstOffset: IntOffset,
    dstSize: IntSize,
    transfer: ColorTransfer,
) {
    val shader = CodedShaders.getValue(transfer)
    val scale = Offset(source.width.toFloat() / dstSize.width, source.height.toFloat() / dstSize.height)
    val origin = Offset(source.left - dstOffset.x * scale.x, source.top - dstOffset.y * scale.y)
    // A shader's uniforms and input are set and read when the draw records, so renderers on other threads may share it.
    synchronized(shader) {
        shader.setInputShader("image", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        })
        shader.setFloatUniform("origin", origin.x, origin.y)
        shader.setFloatUniform("scale", scale.x, scale.y)
        shader.setFloatUniform("crop", source.left.toFloat(), source.top.toFloat(), source.right.toFloat(), source.bottom.toFloat())
        drawRect(ShaderBrush(shader), dstOffset.toOffset(), dstSize.toSize())
    }
}

/** One shader per coded transfer, which the `hlg` uniform selects. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object CodedShaders {
    private val hlg = mapOf(ColorTransfer.PQ to 0f, ColorTransfer.HLG to 1f)
    private val byTransfer = hlg.mapValues { (_, hlg) -> RuntimeShader(CODED_IMAGE_SHADER).apply { setFloatUniform("hlg", hlg) } }
    fun getValue(transfer: ColorTransfer): RuntimeShader = byTransfer.getValue(transfer)
}

/**
 * PQ (ST 2084) or HLG codes, read out of a linear sRGB wrap with `toLinearSrgb` and so as the GPU's
 * YUV to RGB conversion left them, whatever canvas draws, to linear light, 1.0 at 203 nits: what the
 * CPU converter makes of the same frame, handed to the canvas with `fromLinearSrgb`. HLG takes
 * BT.2100's OOTF for a 1000-nit display. The matrix is BT.2020 to BT.709 primaries; negative results
 * are out-of-gamut colours, which a wide or extended-range canvas keeps. `origin` and `scale` map the
 * draw's coordinates into the image, and `crop` holds the sampling to the picture.
 */
private val CODED_IMAGE_SHADER = """
uniform shader image;
uniform float2 origin;
uniform float2 scale;
uniform float4 crop;
uniform float hlg;

half4 main(float2 coord) {
    float2 at = clamp(coord * scale + origin, crop.xy + 0.5, crop.zw - 0.5);
    float3 code = clamp(float3(toLinearSrgb(image.eval(at).rgb)), 0.0, 1.0);
    float3 light;
    if (hlg > 0.5) {
        float3 scene = mix(code * code / 3.0, (exp((code - 0.55991073) / 0.17883277) + 0.28466892) / 12.0, step(float3(0.5), code));
        light = scene * pow(dot(scene, float3(0.2627, 0.6780, 0.0593)), 0.2) * (1000.0 / 203.0);
    } else {
        float3 signal = pow(code, float3(1.0 / 78.84375));
        light = pow(max(signal - 0.8359375, 0.0) / (18.8515625 - 18.6875 * signal), float3(1.0 / 0.1593017578125)) * (10000.0 / 203.0);
    }
    return half4(fromLinearSrgb(half3(${Bt2020Matrices.agsl(Bt2020Matrices.toSrgb, "light")})), 1.0);
}
"""

/**
 * An Android bitmap of [size] holding [frame], converted straight into its pixels, locked with
 * `AndroidBitmap_lockPixels`, and scaled to the bitmap's size in the same pass. Unlocking them
 * gives the bitmap a new generation, which is what tells the renderer to upload it again after a
 * [write].
 */
private class AndroidFrameBitmap(frame: VideoFrame, override val format: FrameFormat, size: IntSize) : FrameBitmap {
    override val width = size.width
    override val height = size.height
    private val bitmap = when {
        format.color.isHdr -> Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_1010102, true, hdrColorSpace(format.color.transfer))
        format == FrameFormat.RgbaF16 -> {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { "RGBA_F16 bitmaps need Android 8.0 (API 26)" }
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.RGBA_F16,
                true,
                ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB),
            )
        }
        else -> Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
