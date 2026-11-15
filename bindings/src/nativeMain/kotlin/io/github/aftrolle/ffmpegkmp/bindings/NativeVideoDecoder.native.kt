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
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_reopen
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_set_memory_format
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_set_size
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_error
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_set_named_decoder
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_set_frame_ring
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_set_thread_type
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_start
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_video_decoder_time_left
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_snapshot
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_snapshot_init
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.free
import kotlinx.cinterop.cstr
import kotlinx.cinterop.toCValues
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
    decoderThreadType: Int,
    frameRing: Int,
    decoderName: String?,
    decoderOptions: Map<String, String>,
): NativeVideoDecoder {
    requireMemoryOutput(output)
    val mounts = StableRef.create(MountTable(source.mounts, replayableSources = true))
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
            staticCFunction(::receiveMountedIo),
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
    val engine = CInteropVideoDecoderEngine(decoder, mounts)
    if (frameRing != DEFAULT_FRAME_RING) {
        val set = ffmpegkmp_video_decoder_set_frame_ring(decoder, frameRing)
        if (set < 0) {
            engine.release()
            throw NativeVideoDecoderException("Could not set the decoder's ring of $frameRing frames (error $set)", set)
        }
    }
    if (decoderThreadType != 0) {
        val set = ffmpegkmp_video_decoder_set_thread_type(decoder, decoderThreadType)
        if (set < 0) {
            engine.release()
            throw NativeVideoDecoderException("Could not set the decoder's thread type $decoderThreadType (error $set)", set)
        }
    }
    if (decoderName != null) {
        val packed = packCodecOptions(decoderOptions)
        // Copied by the decoder before the call returns.
        val named = ffmpegkmp_video_decoder_set_named_decoder(
            decoder,
            decoderName.cstr,
            packed.takeIf(ByteArray::isNotEmpty)?.toCValues(),
            packed.size,
        )
        if (named < 0) {
            val reason = engine.error()
            engine.release()
            throw NativeVideoDecoderException(
                "Could not name the decoder '$decoderName'" + (reason?.let { ": $it" } ?: " (error $named)"),
                named,
                reason,
            )
        }
    }
    return GuardedVideoDecoder(engine, source.input)
}

private class CInteropVideoDecoderEngine(
    private val decoder: CPointer<ffmpegkmp_video_decoder>,
    private var mounts: StableRef<MountTable>,
) : VideoDecoderEngineCalls {
    private val frame = nativeHeap.alloc<ffmpegkmp_decoded_frame>().also { ffmpegkmp_decoded_frame_init(it.ptr) }

    override fun start() = ffmpegkmp_video_decoder_start(decoder)

    override fun info(): NativePlayerSnapshot = memScoped {
        val snapshot = alloc<ffplaykmp_snapshot>()
        ffplaykmp_snapshot_init(snapshot.ptr)
        requireNative(ffmpegkmp_video_decoder_get_info(decoder, snapshot.ptr), "read the video info", ::NativeVideoDecoderException)
        snapshot.toNativeSnapshot()
    }

    override fun seek(positionNanos: Long) = ffmpegkmp_video_decoder_seek(decoder, positionNanos)

    override fun frameAt(positionNanos: Long): NativeDecodedFrame {
        requireNative(
            ffmpegkmp_video_decoder_frame_at(decoder, positionNanos, frame.ptr),
            "decode the frame at ${positionNanos}ns",
            ::NativeVideoDecoderException,
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

    override fun setSize(width: Int, height: Int) = ffmpegkmp_video_decoder_set_size(decoder, width, height)

    override fun setFormat(memoryFormat: NativeFrameFormat?, width: Int, height: Int): Int = memScoped {
        ffmpegkmp_video_decoder_set_memory_format(decoder, memoryFormat?.toNative(this)?.ptr, width, height)
    }

    override fun reopen(
        source: NativePlayerSource,
        memoryFormat: NativeFrameFormat?,
        memoryWidth: Int,
        memoryHeight: Int,
        timeoutMicros: Long,
    ): Int {
        val next = StableRef.create(MountTable(source.mounts, replayableSources = true))
        val result = memScoped {
            ffmpegkmp_video_decoder_reopen(
                decoder,
                source.protocolInput(),
                memoryFormat?.toNative(this)?.ptr,
                memoryWidth,
                memoryHeight,
                timeoutMicros,
                staticCFunction(::receiveMountedIo),
                next.asCPointer(),
            )
        }
        // The old input is closed by the call, so its mounts are read no more once it returns.
        val done = if (result >= 0) mounts.also { mounts = next } else next
        done.dispose()
        return result
    }

    override fun interrupt() = ffmpegkmp_video_decoder_interrupt(decoder)

    override fun abort() = ffmpegkmp_video_decoder_abort(decoder)

    override fun timeLeft(): Long = ffmpegkmp_video_decoder_time_left(decoder)

    override fun error(): String? = ffmpegkmp_video_decoder_error(decoder)?.toKString()?.takeIf(String::isNotEmpty)

    override fun release() {
        ffmpegkmp_video_decoder_destroy(decoder)
        nativeHeap.free(frame)
        mounts.dispose()
    }
}
