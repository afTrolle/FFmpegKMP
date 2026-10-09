// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_audio_encoder_config
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_video_encoder_config
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_video_track_info
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_writer
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_writer_result
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_io_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge
import java.util.concurrent.ConcurrentHashMap
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer

/** A writer on the JavaCPP bridge; [openEncoder] opens the platform's own encoder for [NativeMediaWriter.openInputSurface], where it has one. */
internal fun createJavaCppMediaWriter(
    output: NativeWriterOutput,
    container: NativeContainer,
    fastStart: Boolean,
    timeoutMicros: Long,
    openEncoder: PacketEncoderFactory?,
): NativeMediaWriter {
    JavaCppBridgeLoader.load()
    val resource = (output as? NativeWriterOutput.Mounted)?.let { MountedResource(it.resource) }
    val ioCallback = resource?.let {
        object : ffplaykmp_io_callback() {
            override fun call(
                opaque: Pointer?,
                resourceId: Long,
                operation: Int,
                offset: Long,
                data: BytePointer?,
                size: Long,
            ): Long = if (resourceId == 1L) it.dispatch(operation, offset, data, size) else -1L
        }
    }
    val error = IntArray(1)
    val url = output.url()
    val writer = bridge.ffmpegkmp_writer_create(
        url, container.ordinal, if (fastStart) 1 else 0, timeoutMicros, ioCallback, null, error,
    )
        ?.takeUnless(ffmpegkmp_writer::isNull)
    if (writer == null) {
        ioCallback?.close()
        throw NativeMediaWriterException("Could not open '$url' for writing (error ${error[0]})", error[0])
    }
    return GuardedMediaWriter(JavaCppMediaWriterEngine(writer, ioCallback, timeoutMicros, openEncoder), url)
}

@InternalFFmpegKmpApi
public actual suspend fun platformVideoEncoderFor(config: NativeVideoEncoderConfig): NativeVideoTrackInfo? {
    JavaCppBridgeLoader.load()
    return config.withNative { native ->
        val info = ffmpegkmp_video_track_info()
        try {
            bridge.ffmpegkmp_video_track_info_init(info)
            val result = bridge.ffmpegkmp_writer_can_encode(native, info)
            requireWriterSuccess(result, "check the encoder")
            if (result == 0) null else info.toNative()
        } finally {
            info.close()
        }
    }
}

private class JavaCppMediaWriterEngine(
    private val writer: ffmpegkmp_writer,
    /** Kept reachable for the writer's lifetime: native code calls back into it. */
    private val ioCallback: ffplaykmp_io_callback?,
    private val timeoutMicros: Long,
    private val openEncoder: PacketEncoderFactory?,
) : MediaWriterEngineCalls {
    /** Audio from an offset goes through here: JavaCPP passes arrays from their start. */
    private var samples = FloatArray(0)

    private val videoTracks = ConcurrentHashMap<Int, Pair<NativeVideoEncoderConfig, NativeVideoTrackInfo>>()
    private val encoders = ConcurrentHashMap<Int, PacketTrackEncoder>()

    override fun addVideoTrack(config: NativeVideoEncoderConfig): NativeAddedVideoTrack = config.withNative { native ->
        val info = ffmpegkmp_video_track_info()
        try {
            bridge.ffmpegkmp_video_track_info_init(info)
            val index = bridge.ffmpegkmp_writer_add_video_track(writer, native, info)
            requireWriterSuccess(index, "add a ${config.width}x${config.height} ${config.codec} ${config.dynamicRange} track")
            NativeAddedVideoTrack(index, info.toNative()).also { videoTracks[index] = config to it.info }
        } finally {
            info.close()
        }
    }

    override fun addAudioTrack(sampleRate: Int, channels: Int, bitRate: Long): Int {
        val config = ffmpegkmp_audio_encoder_config()
        return try {
            bridge.ffmpegkmp_audio_encoder_config_init(config)
            config.sample_rate(sampleRate).channels(channels).bit_rate(bitRate)
            bridge.ffmpegkmp_writer_add_audio_track(writer, config)
        } finally {
            config.close()
        }
    }

    override fun writeVideo(track: Int, frame: NativeFrame, ptsNanos: Long): Int {
        val handle = requireNotNull(frame as? JavaCppFrame) { "Frames of another binding cannot be encoded" }.handle
        return bridge.ffmpegkmp_writer_write_video(writer, track, handle, ptsNanos)
    }

    override fun writeAudio(track: Int, samples: FloatArray, offset: Int, frames: Int): Int {
        if (offset == 0) return bridge.ffmpegkmp_writer_write_audio(writer, track, samples, frames)
        val count = samples.size - offset
        if (this.samples.size < count) this.samples = FloatArray(count)
        samples.copyInto(this.samples, 0, offset, samples.size)
        return bridge.ffmpegkmp_writer_write_audio(writer, track, this.samples, frames)
    }

    override fun endTrack(track: Int): Int {
        val failure = encoders[track]?.finish() ?: 0
        return if (failure < 0) failure else bridge.ffmpegkmp_writer_end_track(writer, track)
    }

    /**
     * The platform's encoder opens first, beside the track's own, so that a device that cannot run
     * two leaves the track as it was; the track gives up its encoder only once the platform's is
     * ready, and the platform's packets flow only once the track takes them.
     */
    override fun openInputSurface(track: Int): NativeEncoderSurface? {
        val (config, info) = videoTracks[track] ?: return null
        val encoder = openEncoder?.invoke(config, info, packetSink(track), timeoutMicros) ?: return null
        val switched = bridge.ffmpegkmp_writer_use_packets(writer, track)
        if (switched < 0) {
            println("FFmpegKMP: the track did not switch to the platform encoder's packets ($switched); its frames stay on the one-copy path")
            encoder.release()
            return null
        }
        encoders[track] = encoder
        encoder.start()
        return encoder
    }

    private fun packetSink(track: Int) = PacketSink { data, size, ptsNanos, durationNanos, keyFrame, extradata ->
        bridge.ffmpegkmp_writer_write_packet(
            writer, track, data, size, ptsNanos, durationNanos, if (keyFrame) 1 else 0, extradata, extradata?.remaining() ?: 0,
        )
    }

    override fun releaseTrack(track: Int) {
        encoders.remove(track)?.release()
        bridge.ffmpegkmp_writer_release_track(writer, track)
    }

    override fun finish(): NativeWriterResult {
        val result = ffmpegkmp_writer_result()
        return try {
            bridge.ffmpegkmp_writer_result_init(result)
            requireWriterSuccess(bridge.ffmpegkmp_writer_finish(writer, result), "finish the output")
            NativeWriterResult(result.bytes(), result.duration_us())
        } finally {
            result.close()
        }
    }

    override fun abort() = bridge.ffmpegkmp_writer_abort(writer)

    override fun release() {
        bridge.ffmpegkmp_writer_destroy(writer)
        ioCallback?.close()
    }
}

private inline fun <T> NativeVideoEncoderConfig.withNative(block: (ffmpegkmp_video_encoder_config) -> T): T {
    val native = ffmpegkmp_video_encoder_config()
    return try {
        bridge.ffmpegkmp_video_encoder_config_init(native)
        native.width(width).height(height)
            .frame_rate_num(frameRateNumerator).frame_rate_den(frameRateDenominator)
            .codec(codec.ordinal).dynamic_range(dynamicRange.ordinal).preference(preference.ordinal)
            .bit_rate(bitRate).keyframe_interval_us(keyframeIntervalMicros).bit_depth(bitDepth)
        hdrMetadata?.let { metadata ->
            native.has_hdr_metadata(1)
            val hdr = native.hdr_metadata()
            metadata.masteringDisplay?.let { display ->
                hdr.has_mastering_display(1)
                for (index in 0 until 3) {
                    hdr.primaries_x(index, display.primariesX[index])
                    hdr.primaries_y(index, display.primariesY[index])
                }
                hdr.white_point_x(display.whitePointX).white_point_y(display.whitePointY)
                hdr.min_luminance(display.minLuminance).max_luminance(display.maxLuminance)
            }
            metadata.contentLight?.let { (maxCll, maxFall) ->
                hdr.has_content_light(1).max_content_light_level(maxCll).max_frame_average_light_level(maxFall)
            }
        }
        block(native)
    } finally {
        native.close()
    }
}

private fun ffmpegkmp_video_track_info.toNative(): NativeVideoTrackInfo {
    val format = input_format()
    return NativeVideoTrackInfo(
        inputFormat = NativeFrameFormat(format.layout(), format.primaries(), format.transfer(), format.matrix(), format.range()),
        hardware = hardware() != 0,
        encoder = encoder().string,
        bitRate = bit_rate(),
    )
}
