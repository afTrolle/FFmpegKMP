// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Surface
import io.github.aftrolle.ffmpegkmp.bindings.javacpp.AndroidBitmapFrames
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_video_decoder
import io.github.aftrolle.ffmpegkmp.bindings.javacpp.AndroidPlayerSurface
import org.bytedeco.javacpp.Loader

/**
 * MediaCodecList's answer for the decoder FFmpeg's MediaCodec wrapper would take: the first hardware
 * decoder for the codec's type, not a secure one, that takes [width] × [height]. Its
 * `maxSupportedInstances` is the platform's hint, which devices do not all keep to.
 */
@InternalFFmpegKmpApi
public actual fun nativeHardwareDecoderSupport(codec: String, width: Int, height: Int): NativeHardwareDecoderSupport {
    val mime = MEDIA_CODEC_TYPES[codec] ?: return NativeHardwareDecoderSupport(available = false, maxInstances = null)
    val capabilities = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
        .filter { !it.isEncoder && it.isHardwareDecoder() && !it.name.endsWith(".secure") }
        .mapNotNull { info ->
            val type = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: return@mapNotNull null
            runCatching { info.getCapabilitiesForType(type) }.getOrNull()
        }
        .firstOrNull { it.videoCapabilities?.isSizeSupported(width, height) == true }
    return NativeHardwareDecoderSupport(available = capabilities != null, maxInstances = capabilities?.maxSupportedInstances)
}

/** The MediaCodec type of each video codec FFmpeg decodes through MediaCodec, by FFmpeg's name. */
private val MEDIA_CODEC_TYPES = mapOf(
    "h264" to "video/avc",
    "hevc" to "video/hevc",
    "vp8" to "video/x-vnd.on2.vp8",
    "vp9" to "video/x-vnd.on2.vp9",
    "av1" to "video/av01",
    "mpeg4" to "video/mp4v-es",
    "h263" to "video/3gpp",
    "mpeg2video" to "video/mpeg2",
)

/** Before API 29 says, by the names of the platform's software codecs, which FFmpeg's wrapper skips too. */
private fun MediaCodecInfo.isHardwareDecoder(): Boolean =
    if (Build.VERSION.SDK_INT >= 29) {
        isHardwareAccelerated && !isSoftwareOnly
    } else {
        SOFTWARE_CODEC_PREFIXES.none { name.startsWith(it, ignoreCase = true) }
    }

private val SOFTWARE_CODEC_PREFIXES = listOf("OMX.google.", "OMX.ffmpeg.", "c2.android.", "c2.google.")

@InternalFFmpegKmpApi
public actual fun createPlatformPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: (NativeVideoFrame) -> Unit,
    platformFrame: (NativePlatformVideoFrame) -> Boolean,
): NativePlayerBridge = createJavaCppPlayerBridge(
    configuration = configuration,
    update = update,
    frame = frame,
    platformFrame = platformFrame,
    platformOutputTarget = { player, target, secure ->
        if (target != null && target !is Surface) {
            -22
        } else {
            androidSurfaceNatives.value
            AndroidPlayerSurface.setSurface(target, player, if (secure) 1 else 0)
        }
    },
)

@InternalFFmpegKmpApi
public actual fun createPlatformVideoDecoder(
    source: NativePlayerSource,
    output: NativeVideoDecoderOutput,
    memoryFormat: NativeFrameFormat?,
    memoryWidth: Int,
    memoryHeight: Int,
    decoderPreference: NativePlayerDecoderPreference,
    decoderThreads: Int,
    timeoutMicros: Long,
): NativeVideoDecoder {
    if (output == NativeVideoDecoderOutput.GPU_BUFFERS) {
        require(Build.VERSION.SDK_INT >= 34) {
            "GPU buffer output needs Android 14 (API 34) or later; this is API ${Build.VERSION.SDK_INT}"
        }
        return createGpuBufferVideoDecoder(source, decoderPreference, decoderThreads, timeoutMicros)
    }
    return createJavaCppVideoDecoder(
        source, output, memoryFormat, memoryWidth, memoryHeight, decoderPreference, decoderThreads, timeoutMicros,
    ) { 0 }
}

@InternalFFmpegKmpApi
public actual suspend fun createPlatformMediaWriter(
    output: NativeWriterOutput,
    container: NativeContainer,
    fastStart: Boolean,
    timeoutMicros: Long,
): NativeMediaWriter = createJavaCppMediaWriter(output, container, fastStart, timeoutMicros, openSurfaceVideoEncoder)

/**
 * A decoder rendering into an `ImageReader` of GPU-sampled private buffers, as [GPU_BUFFER_RING]
 * images. The reader is made before the stream's size is known: MediaCodec sets the buffers' size
 * itself, so a nominal one does. Android 14 (API 34) and later.
 */
private fun createGpuBufferVideoDecoder(
    source: NativePlayerSource,
    decoderPreference: NativePlayerDecoderPreference,
    decoderThreads: Int,
    timeoutMicros: Long,
): NativeVideoDecoder {
    val reader = ImageReader.newInstance(
        NOMINAL_READER_SIZE, NOMINAL_READER_SIZE, ImageFormat.PRIVATE, GPU_BUFFER_RING, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    )
    val decoder = try {
        createJavaCppVideoDecoder(
            source, NativeVideoDecoderOutput.GPU_BUFFERS, null, 0, 0, decoderPreference, decoderThreads, timeoutMicros,
        ) { decoder -> attachDecoderSurface(reader.surface, decoder) }
    } catch (failure: Throwable) {
        reader.close()
        throw failure
    }
    return GpuBufferVideoDecoder(decoder, reader, timeoutMicros)
}

private fun attachDecoderSurface(surface: Surface, decoder: ffmpegkmp_video_decoder): Int {
    androidSurfaceNatives.value
    return AndroidPlayerSurface.setDecoderSurface(surface, decoder)
}

/** The size a GPU buffer decoder's reader is made with; the buffers MediaCodec renders take the stream's. */
private const val NOMINAL_READER_SIZE = 128

// Decoders open on threads of their own, so two can reach the Surface natives at once; a bare Loader.load lets the second
// call a native before the first has finished binding it (UnsatisfiedLinkError), as JavaCppBridgeLoader guards the bridge.
private val androidSurfaceNatives = lazy { Loader.load(AndroidPlayerSurface::class.java) }

/**
 * Converts [frame] straight into [bitmap]'s pixels, locked with `AndroidBitmap_lockPixels`: an
 * `ARGB_8888` bitmap as sRGB RGBA8, an `RGBA_F16` one as linear extended sRGB.
 */
@InternalFFmpegKmpApi
public fun convertIntoBitmap(frame: NativeFrame, bitmap: android.graphics.Bitmap) {
    val source = requireNotNull(frame as? JavaCppFrame) { "Frames of another binding cannot be converted" }
    androidBitmapNatives.value
    requireNative(AndroidBitmapFrames.convertInto(bitmap, source.handle), "convert a frame into a Bitmap", ::NativeFrameException)
}

/** Converts [bitmap]'s pixels, an ARGB_8888 or RGBA_F16 bitmap's read in place, into [frame], which keeps its format. */
@InternalFFmpegKmpApi
public fun convertFromBitmap(bitmap: android.graphics.Bitmap, frame: NativeFrame) {
    val target = requireNotNull(frame as? JavaCppFrame) { "Frames of another binding cannot be converted into" }
    androidBitmapNatives.value
    requireNative(AndroidBitmapFrames.convertFrom(bitmap, target.handle), "convert a Bitmap into a frame", ::NativeFrameException)
}

/**
 * Converts [buffer]'s pixels, an RGBA_8888 or RGBA_FP16 HardwareBuffer's locked for reading once the
 * GPU is done with it, into [frame], which keeps its format.
 */
@InternalFFmpegKmpApi
public fun convertFromHardwareBuffer(buffer: android.hardware.HardwareBuffer, frame: NativeFrame) {
    val target = requireNotNull(frame as? JavaCppFrame) { "Frames of another binding cannot be converted into" }
    androidBitmapNatives.value
    requireNative(AndroidBitmapFrames.convertFromHardwareBuffer(buffer, target.handle), "convert a HardwareBuffer into a frame", ::NativeFrameException)
}

private val androidBitmapNatives = lazy { Loader.load(AndroidBitmapFrames::class.java) }
