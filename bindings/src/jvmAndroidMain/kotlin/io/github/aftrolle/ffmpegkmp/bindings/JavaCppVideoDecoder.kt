// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_decoded_frame
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_frame_format
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_video_decoder
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_io_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_snapshot
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer

/** [attachSurface] hands a Surface output's target to the created decoder; 0 on success. */
internal fun createJavaCppVideoDecoder(
    source: NativePlayerSource,
    output: NativeVideoDecoderOutput,
    memoryFormat: NativeFrameFormat?,
    memoryWidth: Int,
    memoryHeight: Int,
    decoderPreference: NativePlayerDecoderPreference,
    decoderThreads: Int,
    timeoutMicros: Long,
    attachSurface: (ffmpegkmp_video_decoder) -> Int,
): NativeVideoDecoder {
    JavaCppBridgeLoader.load()
    val mounts = source.mounts.mapIndexed { index, mount ->
        index.toLong() + 1L to MountedResource(resource = mount.resource, replayableSource = true)
    }.toMap()
    val ioCallback = object : ffplaykmp_io_callback() {
        override fun call(
            opaque: Pointer?,
            resourceId: Long,
            operation: Int,
            offset: Long,
            data: BytePointer?,
            size: Long,
        ): Long = mounts[resourceId]?.dispatch(operation, offset, data, size) ?: -1L
    }
    val error = IntArray(1)
    fun create(format: ffmpegkmp_frame_format?) = bridge.ffmpegkmp_video_decoder_create(
        source.protocolInput(),
        output.value,
        format,
        memoryWidth,
        memoryHeight,
        decoderPreference.ordinal,
        decoderThreads,
        timeoutMicros,
        ioCallback,
        null,
        error,
    )?.takeUnless(ffmpegkmp_video_decoder::isNull)
    val decoder = if (memoryFormat != null) memoryFormat.withNative(::create) else create(null)
    if (decoder == null) {
        ioCallback.close()
        throw NativeVideoDecoderException("Could not create a decoder for '${source.input}' (error ${error[0]})", error[0])
    }
    val engine = JavaCppVideoDecoderEngine(decoder, ioCallback)
    val attached = attachSurface(decoder)
    if (attached < 0) {
        engine.release()
        throw NativeVideoDecoderException("Could not attach the output surface (error $attached)", attached)
    }
    return GuardedVideoDecoder(engine, source.input)
}

private class JavaCppVideoDecoderEngine(
    private val decoder: ffmpegkmp_video_decoder,
    /** Kept reachable for the decoder's lifetime: native code calls back into it. */
    private val ioCallback: ffplaykmp_io_callback,
) : VideoDecoderEngineCalls {
    private val frame = ffmpegkmp_decoded_frame().also(bridge::ffmpegkmp_decoded_frame_init)

    override fun start() = bridge.ffmpegkmp_video_decoder_start(decoder)

    override fun info(): NativePlayerSnapshot {
        val snapshot = ffplaykmp_snapshot()
        bridge.ffplaykmp_snapshot_init(snapshot)
        return try {
            requireVideoSuccess(bridge.ffmpegkmp_video_decoder_get_info(decoder, snapshot), "read the video info")
            snapshot.toNativeSnapshot()
        } finally {
            snapshot.close()
        }
    }

    override fun seek(positionNanos: Long) = bridge.ffmpegkmp_video_decoder_seek(decoder, positionNanos)

    override fun frameAt(positionNanos: Long): NativeDecodedFrame {
        requireVideoSuccess(
            bridge.ffmpegkmp_video_decoder_frame_at(decoder, positionNanos, frame),
            "decode the frame at ${positionNanos}ns",
        )
        return NativeDecodedFrame(
            serial = frame.serial(),
            ptsNanos = frame.pts_ns(),
            durationNanos = frame.duration_ns(),
            width = frame.width(),
            height = frame.height(),
            sampleAspectRatioNumerator = frame.sample_aspect_ratio_num(),
            sampleAspectRatioDenominator = frame.sample_aspect_ratio_den(),
            rotationDegrees = frame.rotation_degrees(),
            hardware = frame.hardware() != 0,
            // A new reference each call; the struct's own pointer is cleared for the next one.
            frame = frame.frame().toNativeFrame().also { frame.frame(null) },
        )
    }

    override fun interrupt() = bridge.ffmpegkmp_video_decoder_interrupt(decoder)

    override fun abort() = bridge.ffmpegkmp_video_decoder_abort(decoder)

    override fun timeLeft(): Long = bridge.ffmpegkmp_video_decoder_time_left(decoder)

    override fun release() {
        bridge.ffmpegkmp_video_decoder_destroy(decoder)
        frame.close()
        ioCallback.close()
    }
}
