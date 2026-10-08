// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import cnames.structs.ffmpegkmp_writer
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_audio_encoder_config
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_audio_encoder_config_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_encoder_config
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_encoder_config_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_track_info
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_track_info_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_abort
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_add_audio_track
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_add_video_track
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_can_encode
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_create
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_destroy
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_end_track
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_finish
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_release_track
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_result
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_result_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_write_audio
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_writer_write_video
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

@InternalFFmpegKmpApi
public actual suspend fun createPlatformMediaWriter(
    output: NativeWriterOutput,
    container: NativeContainer,
    fastStart: Boolean,
    timeoutMicros: Long,
): NativeMediaWriter {
    val resource = (output as? NativeWriterOutput.Mounted)?.let { StableRef.create(NativeMountedResource(it.resource)) }
    val url = output.url()
    val writer = memScoped {
        val error = alloc<IntVar>()
        ffmpegkmp_writer_create(
            url,
            container.ordinal,
            if (fastStart) 1 else 0,
            timeoutMicros,
            resource?.let { staticCFunction(::receiveMediaWriterIo) },
            resource?.asCPointer(),
            error.ptr,
        ) ?: run {
            resource?.dispose()
            throw NativeMediaWriterException("Could not open '$url' for writing (error ${error.value})", error.value)
        }
    }
    return GuardedMediaWriter(CInteropMediaWriterEngine(writer, resource), url)
}

@InternalFFmpegKmpApi
public actual suspend fun platformVideoEncoderFor(config: NativeVideoEncoderConfig): NativeVideoTrackInfo? = memScoped {
    val info = alloc<ffmpegkmp_video_track_info>().also { ffmpegkmp_video_track_info_init(it.ptr) }
    val result = ffmpegkmp_writer_can_encode(config.toNative(this).ptr, info.ptr)
    requireWriterSuccess(result, "check the encoder")
    if (result == 0) null else info.toNative()
}

private fun receiveMediaWriterIo(
    opaque: COpaquePointer?,
    resourceId: Long,
    operation: UInt,
    offset: Long,
    data: CPointer<UByteVar>?,
    size: ULong,
): Long = runCatching {
    if (resourceId != 1L) return@runCatching null
    opaque?.asStableRef<NativeMountedResource>()?.get()?.dispatch(operation.toInt(), offset, data, size)
}.getOrNull() ?: -1L

private class CInteropMediaWriterEngine(
    private val writer: CPointer<ffmpegkmp_writer>,
    private val resource: StableRef<NativeMountedResource>?,
) : MediaWriterEngineCalls {
    override fun addVideoTrack(config: NativeVideoEncoderConfig): NativeAddedVideoTrack = memScoped {
        val info = alloc<ffmpegkmp_video_track_info>().also { ffmpegkmp_video_track_info_init(it.ptr) }
        val index = ffmpegkmp_writer_add_video_track(writer, config.toNative(this).ptr, info.ptr)
        requireWriterSuccess(index, "add a ${config.width}x${config.height} ${config.codec} ${config.dynamicRange} track")
        NativeAddedVideoTrack(index, info.toNative())
    }

    override fun addAudioTrack(sampleRate: Int, channels: Int, bitRate: Long): Int = memScoped {
        val config = alloc<ffmpegkmp_audio_encoder_config>().also { ffmpegkmp_audio_encoder_config_init(it.ptr) }
        config.sample_rate = sampleRate
        config.channels = channels
        config.bit_rate = bitRate
        ffmpegkmp_writer_add_audio_track(writer, config.ptr)
    }

    override fun writeVideo(track: Int, frame: NativeFrame, ptsNanos: Long): Int {
        val handle = requireNotNull(frame as? CInteropFrame) { "Frames of another binding cannot be encoded" }.handle
        return ffmpegkmp_writer_write_video(writer, track, handle, ptsNanos)
    }

    override fun writeAudio(track: Int, samples: FloatArray, offset: Int, frames: Int): Int {
        if (frames == 0) return 0
        return samples.usePinned { pinned -> ffmpegkmp_writer_write_audio(writer, track, pinned.addressOf(offset), frames) }
    }

    override fun endTrack(track: Int): Int = ffmpegkmp_writer_end_track(writer, track)

    override fun releaseTrack(track: Int) = ffmpegkmp_writer_release_track(writer, track)

    override fun finish(): NativeWriterResult = memScoped {
        val result = alloc<ffmpegkmp_writer_result>().also { ffmpegkmp_writer_result_init(it.ptr) }
        requireWriterSuccess(ffmpegkmp_writer_finish(writer, result.ptr), "finish the output")
        NativeWriterResult(result.bytes, result.duration_us)
    }

    override fun abort() = ffmpegkmp_writer_abort(writer)

    override fun release() {
        ffmpegkmp_writer_destroy(writer)
        resource?.dispose()
    }
}

private fun NativeVideoEncoderConfig.toNative(scope: MemScope): ffmpegkmp_video_encoder_config =
    scope.alloc<ffmpegkmp_video_encoder_config>().also { native ->
        ffmpegkmp_video_encoder_config_init(native.ptr)
        native.width = width
        native.height = height
        native.frame_rate_num = frameRateNumerator
        native.frame_rate_den = frameRateDenominator
        native.codec = codec.ordinal
        native.dynamic_range = dynamicRange.ordinal
        native.preference = preference.ordinal
        native.bit_rate = bitRate
        native.keyframe_interval_us = keyframeIntervalMicros
        native.bit_depth = bitDepth
        hdrMetadata?.let { metadata ->
            native.has_hdr_metadata = 1
            val hdr = native.hdr_metadata
            metadata.masteringDisplay?.let { display ->
                hdr.has_mastering_display = 1
                for (index in 0 until 3) {
                    hdr.primaries_x[index] = display.primariesX[index]
                    hdr.primaries_y[index] = display.primariesY[index]
                }
                hdr.white_point_x = display.whitePointX
                hdr.white_point_y = display.whitePointY
                hdr.min_luminance = display.minLuminance
                hdr.max_luminance = display.maxLuminance
            }
            metadata.contentLight?.let { (maxCll, maxFall) ->
                hdr.has_content_light = 1
                hdr.max_content_light_level = maxCll
                hdr.max_frame_average_light_level = maxFall
            }
        }
    }

private fun ffmpegkmp_video_track_info.toNative(): NativeVideoTrackInfo = NativeVideoTrackInfo(
    inputFormat = NativeFrameFormat(
        input_format.layout,
        input_format.primaries,
        input_format.transfer,
        input_format.matrix,
        input_format.range,
    ),
    hardware = hardware != 0,
    encoder = encoder.toKString(),
)
