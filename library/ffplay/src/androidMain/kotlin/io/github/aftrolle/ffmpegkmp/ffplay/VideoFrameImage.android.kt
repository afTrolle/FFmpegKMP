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
import android.util.Half
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
import java.nio.ShortBuffer
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_PRI_BT2020
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_PRI_BT709
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_PRI_SMPTE432
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_TRC_ARIB_STD_B67
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_TRC_SMPTE2084
import io.github.aftrolle.ffmpegkmp.bindings.GAMUT_MAP_POINTS
import io.github.aftrolle.ffmpegkmp.bindings.convertIntoBitmap
import io.github.aftrolle.ffmpegkmp.bindings.nativeGamutMap
import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.hardwareBuffer

public actual fun VideoFrame.toImageBitmap(): ImageBitmap = toFrameBitmap(bitmapFormat(), IntSize(width, height)).image

internal actual fun VideoFrame.bitmapFormat(): FrameFormat = imageFormat { it == FrameFormat.Rgba8 || it == FrameFormat.RgbaF16 }

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
 * Display P3, or BT.2020 primaries, which HWUI maps into the canvas's gamut. PQ and HLG wrap as
 * linear sRGB, or, tone mapped for an SDR canvas, as sRGB, so that HWUI applies no tone mapping and no curve:
 * the GPU converts the buffer's YUV to RGB with the matrix its data space names, and the pixels
 * reach [drawCodedImage]'s shader as the signal's codes. A wrap made once for a buffer would go on
 * drawing its first frame, since the GPU texture HWUI makes of a hardware bitmap is cached with the
 * bitmap and never learns that the decoder wrote the buffer again. The last two wraps stay open, as
 * [FrameImage] keeps the last two frames, since a drawing may still use the previous one.
 */
private class HardwareFrameBitmaps : GpuFrameWraps {
    private val live = ArrayDeque<Bitmap>(3)

    override var count: Int = 0
        private set

    override fun wrap(frame: VideoFrame, toneMap: SdrToneMap?): GpuImage {
        val gpu = checkNotNull(frame.gpuBuffer) { "The frame lies in no GPU buffer: $frame" }
        val transfer = when (gpu.colorTransfer) {
            AVCOL_TRC_SMPTE2084 -> ColorTransfer.PQ
            AVCOL_TRC_ARIB_STD_B67 -> ColorTransfer.HLG
            else -> ColorTransfer.SRGB
        }
        val colorSpace = when {
            transfer != ColorTransfer.SRGB && toneMap == null -> ColorSpace.get(ColorSpace.Named.LINEAR_SRGB)
            // An SDR canvas is sRGB: a buffer of codes tagged so reaches the shader as they are.
            transfer != ColorTransfer.SRGB -> ColorSpace.get(ColorSpace.Named.SRGB)
            gpu.colorPrimaries == AVCOL_PRI_SMPTE432 -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            gpu.colorPrimaries == AVCOL_PRI_BT2020 -> bt2020Srgb
            else -> ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val buffer = checkNotNull(frame.hardwareBuffer) { "The frame's GPU buffer is no HardwareBuffer: $frame" }
        val wrapped = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, colorSpace)) { "Could not wrap $buffer in a bitmap" }
        count++
        live.addLast(wrapped)
        while (live.size > 2) live.removeFirst().recycle()
        return GpuImage(wrapped.asImageBitmap(), transfer, toneMap.takeIf { transfer != ColorTransfer.SRGB })
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

internal actual fun DrawScope.drawCodedImage(
    image: ImageBitmap,
    source: IntRect,
    dstOffset: IntOffset,
    dstSize: IntSize,
    transfer: ColorTransfer,
    toneMap: SdrToneMap?,
) {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { "PQ and HLG frames in GPU memory need Android 13 (API 33)" }
    drawCodedRect(image.asAndroidBitmap(), source, dstOffset, dstSize, transfer, toneMap)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawCodedRect(
    bitmap: Bitmap,
    source: IntRect,
    dstOffset: IntOffset,
    dstSize: IntSize,
    transfer: ColorTransfer,
    toneMap: SdrToneMap?,
) {
    val shader = if (toneMap == null) CodedShaders.getValue(transfer) else CodedShaders.toneMapped(transfer)
    val scale = Offset(source.width.toFloat() / dstSize.width, source.height.toFloat() / dstSize.height)
    val origin = Offset(source.left - dstOffset.x * scale.x, source.top - dstOffset.y * scale.y)
    // A shader's uniforms and input are set and read when the draw records, so renderers on other threads may share it.
    synchronized(shader) {
        shader.setInputShader("image", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        })
        if (toneMap != null) {
            shader.setFloatUniform("peak", toneMap.peakNits.toFloat())
            shader.setFloatUniform("sourceMax", toneMap.sourceMax.toFloat())
            shader.setFloatUniform("target", toneMap.target.toFloat())
            shader.setFloatUniform("knee", toneMap.knee.toFloat())
            val lattice = GamutLattices.to709(toneMap.primaries)
            shader.setFloatUniform("gamut", if (lattice != null) 1f else 0f)
            shader.setInputShader("lattice", BitmapShader(lattice ?: GamutLattices.none, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                filterMode = BitmapShader.FILTER_MODE_NEAREST
            })
        }
        shader.setFloatUniform("origin", origin.x, origin.y)
        shader.setFloatUniform("scale", scale.x, scale.y)
        shader.setFloatUniform("crop", source.left.toFloat(), source.top.toFloat(), source.right.toFloat(), source.bottom.toFloat())
        drawRect(ShaderBrush(shader), dstOffset.toOffset(), dstSize.toSize())
    }
}

/** One shader per coded transfer, which the `hlg` uniform selects, as linear light and tone mapped to SDR. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object CodedShaders {
    private val hlg = mapOf(ColorTransfer.PQ to 0f, ColorTransfer.HLG to 1f)
    private val byTransfer = hlg.mapValues { (_, hlg) -> RuntimeShader(CODED_IMAGE_SHADER).apply { setFloatUniform("hlg", hlg) } }
    private val toneMappedByTransfer by lazy {
        hlg.mapValues { (_, hlg) -> RuntimeShader(TONE_MAPPED_IMAGE_SHADER).apply { setFloatUniform("hlg", hlg) } }
    }

    fun getValue(transfer: ColorTransfer): RuntimeShader = byTransfer.getValue(transfer)

    fun toneMapped(transfer: ColorTransfer): RuntimeShader = toneMappedByTransfer.getValue(transfer)
}

/**
 * The converter's gamut maps into BT.709 for sRGB codes, one per source primaries, tiled for
 * [TONE_MAPPED_IMAGE_SHADER]: the lattice's 65 slices of 65 × 65 points, one per blue, 8 to a row in
 * 9 rows, 520 × 585, since 65 × 65 side by side is wider than many GPUs take. Half floats, tagged
 * sRGB so that an sRGB canvas reads them as they are; 2.4 MB each, made once for the process.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object GamutLattices {
    private val made = mutableMapOf<ColorPrimaries, Bitmap>()

    /** A pixel the shader is given where it maps no gamut, as an input it must have. */
    val none: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.RGBA_F16, false, ColorSpace.get(ColorSpace.Named.SRGB)) }

    /** The lattice from [primaries] into BT.709; null from BT.709 itself. */
    fun to709(primaries: ColorPrimaries): Bitmap? = when (primaries) {
        ColorPrimaries.BT709 -> null
        else -> synchronized(made) { made.getOrPut(primaries) { tile(primaries) } }
    }

    private fun tile(primaries: ColorPrimaries): Bitmap {
        val from = if (primaries == ColorPrimaries.DISPLAY_P3) AVCOL_PRI_SMPTE432 else AVCOL_PRI_BT2020
        val points = nativeGamutMap(from, AVCOL_PRI_BT709, AVCOL_TRC_IEC61966_2_1)
        val side = GAMUT_MAP_POINTS
        val width = side * LATTICE_COLUMNS
        val height = side * ((side + LATTICE_COLUMNS - 1) / LATTICE_COLUMNS)
        val halves = ShortArray(width * height * 4)
        for (blue in 0 until side) {
            val left = blue % LATTICE_COLUMNS * side
            val top = blue / LATTICE_COLUMNS * side
            for (green in 0 until side) {
                for (red in 0 until side) {
                    val point = ((blue * side + green) * side + red) * 3
                    val pixel = ((top + green) * width + left + red) * 4
                    for (channel in 0 until 3) halves[pixel + channel] = Half.toHalf(points[point + channel])
                    halves[pixel + 3] = HALF_ONE
                }
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16, false, ColorSpace.get(ColorSpace.Named.SRGB)).apply {
            copyPixelsFromBuffer(ShortBuffer.wrap(halves))
        }
    }

    private const val LATTICE_COLUMNS = 8
    private val HALF_ONE = Half.toHalf(1f)
}

/**
 * PQ (ST 2084) or HLG codes, sampled from a linear sRGB wrap and so as the GPU's YUV to RGB
 * conversion left them, to linear light on the canvas's sRGB primaries, 1.0 at 203 nits: what the
 * CPU converter makes of the same frame. HLG takes BT.2100's OOTF for a 1000-nit display. The
 * matrix is BT.2020 to BT.709 primaries; negative results are out-of-gamut colours, which an
 * extended-range canvas keeps. `origin` and `scale` map the draw's coordinates into the image, and
 * `crop` holds the sampling to the picture.
 */
private val CODED_IMAGE_SHADER = """
uniform shader image;
uniform float2 origin;
uniform float2 scale;
uniform float4 crop;
uniform float hlg;

half4 main(float2 coord) {
    float2 at = clamp(coord * scale + origin, crop.xy + 0.5, crop.zw - 0.5);
    float3 code = clamp(float3(image.eval(at).rgb), 0.0, 1.0);
    float3 light;
    if (hlg > 0.5) {
        float3 scene = mix(code * code / 3.0, (exp((code - 0.55991073) / 0.17883277) + 0.28466892) / 12.0, step(float3(0.5), code));
        light = scene * pow(dot(scene, float3(0.2627, 0.6780, 0.0593)), 0.2) * (1000.0 / 203.0);
    } else {
        float3 signal = pow(code, float3(1.0 / 78.84375));
        light = pow(max(signal - 0.8359375, 0.0) / (18.8515625 - 18.6875 * signal), float3(1.0 / 0.1593017578125)) * (10000.0 / 203.0);
    }
    return half4(half3(${Bt2020Matrices.agsl(Bt2020Matrices.toSrgb, "light")}), 1.0);
}
"""

/**
 * PQ or HLG codes, as [CODED_IMAGE_SHADER] samples them from an sRGB wrap, tone mapped to sRGB codes
 * for an SDR canvas as the CPU converter maps the same frame ([SdrToneMap]): light in the source's own
 * primaries, 1.0 at 203 nits; on the brightest component, BT.2390's EETF in PQ to SDR white at the
 * clip's `peak`, with `sourceMax`, `target` and `knee` its signals; clipped and sRGB encoded; then,
 * where `gamut` is set, through the converter's gamut map into BT.709, the `lattice` [GamutLattices]
 * tiles, looked up tetrahedrally with four fetches, as the converter does: trilinear filtering
 * would tint greys.
 */
private val TONE_MAPPED_IMAGE_SHADER = """
uniform shader image;
uniform shader lattice;
uniform float2 origin;
uniform float2 scale;
uniform float4 crop;
uniform float hlg;
uniform float peak;
uniform float sourceMax;
uniform float target;
uniform float knee;
uniform float gamut;

float pqSignal(float nits) {
    float power = pow(clamp(nits / 10000.0, 0.0, 1.0), 0.1593017578125);
    return pow((0.8359375 + 18.8515625 * power) / (1.0 + 18.6875 * power), 78.84375);
}

float pqNits(float signal) {
    float root = pow(clamp(signal, 0.0, 1.0), 1.0 / 78.84375);
    return pow(max(root - 0.8359375, 0.0) / (18.8515625 - 18.6875 * root), 1.0 / 0.1593017578125) * 10000.0;
}

float eetf(float x) {
    float e1 = pqSignal(clamp(x, 0.0, 1.0) * peak) / sourceMax;
    float e2 = e1;
    if (target < 1.0 && e1 > knee) {
        float t = (e1 - knee) / (1.0 - knee);
        float t2 = t * t;
        float t3 = t2 * t;
        e2 = (2.0 * t3 - 3.0 * t2 + 1.0) * knee + (t3 - 2.0 * t2 + t) * (1.0 - knee) + (-2.0 * t3 + 3.0 * t2) * target;
    }
    return pqNits(e2 * sourceMax) / 203.0;
}

float3 latticePoint(float3 at) {
    float2 tile = float2(mod(at.b, 8.0), floor(at.b / 8.0)) * 65.0;
    return float3(lattice.eval(tile + at.rg + 0.5).rgb);
}

float3 mapGamut(float3 code) {
    float3 position = code * 64.0;
    float3 cell = min(floor(position), float3(63.0));
    float3 f = position - cell;
    float high = max(f.r, max(f.g, f.b));
    float low = min(f.r, min(f.g, f.b));
    float middle = f.r + f.g + f.b - high - low;
    float3 first = f.r >= f.g && f.r >= f.b ? float3(1.0, 0.0, 0.0) : (f.g >= f.b ? float3(0.0, 1.0, 0.0) : float3(0.0, 0.0, 1.0));
    float3 last = f.r < f.g && f.r < f.b ? float3(1.0, 0.0, 0.0) : (f.g < f.b ? float3(0.0, 1.0, 0.0) : float3(0.0, 0.0, 1.0));
    return (1.0 - high) * latticePoint(cell) + (high - middle) * latticePoint(cell + first) +
        (middle - low) * latticePoint(cell + float3(1.0) - last) + low * latticePoint(cell + float3(1.0));
}

half4 main(float2 coord) {
    float2 at = clamp(coord * scale + origin, crop.xy + 0.5, crop.zw - 0.5);
    float3 code = clamp(float3(image.eval(at).rgb), 0.0, 1.0);
    float3 light;
    if (hlg > 0.5) {
        float3 scene = mix(code * code / 3.0, (exp((code - 0.55991073) / 0.17883277) + 0.28466892) / 12.0, step(float3(0.5), code));
        light = scene * pow(dot(scene, float3(0.2627, 0.6780, 0.0593)), 0.2) * (1000.0 / 203.0);
    } else {
        float3 signal = pow(code, float3(1.0 / 78.84375));
        light = pow(max(signal - 0.8359375, 0.0) / (18.8515625 - 18.6875 * signal), float3(1.0 / 0.1593017578125)) * (10000.0 / 203.0);
    }
    float brightest = max(light.r, max(light.g, light.b));
    float3 mapped = brightest > 0.0 ? clamp(light * (eetf(brightest * 203.0 / peak) / brightest), 0.0, 1.0) : float3(0.0);
    float3 srgb = mix(mapped * 12.92, 1.055 * pow(mapped, float3(1.0 / 2.4)) - 0.055, step(float3(0.0031308), mapped));
    return half4(half3(gamut > 0.5 ? mapGamut(srgb) : srgb), 1.0);
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

/** `AVCOL_TRC_IEC61966_2_1`: sRGB, the curve an SDR canvas's codes are in. */
private const val AVCOL_TRC_IEC61966_2_1 = 13
