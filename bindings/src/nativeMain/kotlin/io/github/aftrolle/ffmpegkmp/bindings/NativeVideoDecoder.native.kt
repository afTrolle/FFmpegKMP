// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.UnsafeNumber::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import cnames.structs.ffmpegkmp_video_decoder
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_decoded_frame
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_decoded_frame_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_abort
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_interrupt
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_create
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_destroy
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_frame_at
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_get_info
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_seek
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_start
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_time_left
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_snapshot
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_snapshot_init
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value

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
    requireMemoryOutput(output)
    val mounts = StableRef.create(
        source.mounts.mapIndexed { index, mount ->
            index.toLong() + 1L to NativeMountedResource(resource = mount.resource, replayableSource = true)
        }.toMap(),
    )
    val decoder = memScoped {
        val error = alloc<IntVar>()
        ffmpegkmp_video_decoder_create(
            source.protocolInput(),
            output.value,
            memoryFormat?.toNative(this)?.ptr,
            memoryWidth,
            memoryHeight,
            decoderPreference.ordinal,
            decoderThreads,
            timeoutMicros,
            staticCFunction(::receiveVideoDecoderIo),
            mounts.asCPointer(),
            error.ptr,
        ) ?: run {
            mounts.dispose()
            throw NativeVideoDecoderException(
                "Could not create a decoder for '${source.input}' (error ${error.value})",
                error.value,
            )
        }
    }
    return GuardedVideoDecoder(CInteropVideoDecoderEngine(decoder, mounts), source.input)
}

private fun receiveVideoDecoderIo(
    opaque: COpaquePointer?,
    resourceId: Long,
    operation: UInt,
    offset: Long,
    data: CPointer<UByteVar>?,
    size: ULong,
): Long = runCatching {
    opaque?.asStableRef<Map<Long, NativeMountedResource>>()?.get()?.get(resourceId)
        ?.dispatch(operation.toInt(), offset, data, size)
}.getOrNull() ?: -1L

private class CInteropVideoDecoderEngine(
    private val decoder: CPointer<ffmpegkmp_video_decoder>,
    private val mounts: StableRef<Map<Long, NativeMountedResource>>,
) : VideoDecoderEngineCalls {
    private val frame = nativeHeap.alloc<ffmpegkmp_decoded_frame>().also { ffmpegkmp_decoded_frame_init(it.ptr) }

    override fun start() = ffmpegkmp_video_decoder_start(decoder)

    override fun info(): NativePlayerSnapshot = memScoped {
        val snapshot = alloc<ffplaykmp_snapshot>()
        ffplaykmp_snapshot_init(snapshot.ptr)
        requireVideoSuccess(ffmpegkmp_video_decoder_get_info(decoder, snapshot.ptr), "read the video info")
        snapshot.toNativeSnapshot()
    }

    override fun seek(positionNanos: Long) = ffmpegkmp_video_decoder_seek(decoder, positionNanos)

    override fun frameAt(positionNanos: Long): NativeDecodedFrame {
        requireVideoSuccess(
            ffmpegkmp_video_decoder_frame_at(decoder, positionNanos, frame.ptr),
            "decode the frame at ${positionNanos}ns",
        )
        return NativeDecodedFrame(
            serial = frame.serial.toLong(),
            ptsNanos = frame.pts_ns,
            durationNanos = frame.duration_ns,
            width = frame.width,
            height = frame.height,
            sampleAspectRatioNumerator = frame.sample_aspect_ratio_num,
            sampleAspectRatioDenominator = frame.sample_aspect_ratio_den,
            rotationDegrees = frame.rotation_degrees,
            hardware = frame.hardware != 0,
            // A new reference each call; the struct's own pointer is cleared for the next one.
            frame = frame.frame.toNativeFrame().also { frame.frame = null },
        )
    }

    override fun interrupt() = ffmpegkmp_video_decoder_interrupt(decoder)

    override fun abort() = ffmpegkmp_video_decoder_abort(decoder)

    override fun timeLeft(): Long = ffmpegkmp_video_decoder_time_left(decoder)

    override fun release() {
        ffmpegkmp_video_decoder_destroy(decoder)
        nativeHeap.free(frame)
        mounts.dispose()
    }
}
