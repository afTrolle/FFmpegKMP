// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_decoded_frame
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_frame_format
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_video_decoder
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_snapshot
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge
import org.bytedeco.javacpp.BytePointer

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
    decoderThreadType: Int,
    frameRing: Int,
    decoderName: String?,
    decoderOptions: Map<String, String>,
    attachSurface: (ffmpegkmp_video_decoder) -> Int,
): NativeVideoDecoder {
    JavaCppBridgeLoader.load()
    val mounts = SourceMounts(MountTable(source.mounts, replayableSources = true))
    val callbacks = JavaCppCallbacks.register(mounts = { mounts.table })
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
        JavaCppCallbacks.io,
        callbacks.opaque,
        error,
    )?.takeUnless(ffmpegkmp_video_decoder::isNull)
    val decoder = if (memoryFormat != null) memoryFormat.withNative(::create) else create(null)
    if (decoder == null) {
        callbacks.close()
        throw NativeVideoDecoderException("Could not create a decoder for '${source.input}' (error ${error[0]})", error[0])
    }
    val engine = JavaCppVideoDecoderEngine(decoder, callbacks, mounts)
    if (frameRing != DEFAULT_FRAME_RING) {
        val set = bridge.ffmpegkmp_video_decoder_set_frame_ring(decoder, frameRing)
        if (set < 0) {
            engine.release()
            throw NativeVideoDecoderException("Could not set the decoder's ring of $frameRing frames (error $set)", set)
        }
    }
    if (decoderThreadType != 0) {
        val set = bridge.ffmpegkmp_video_decoder_set_thread_type(decoder, decoderThreadType)
        if (set < 0) {
            engine.release()
            throw NativeVideoDecoderException("Could not set the decoder's thread type $decoderThreadType (error $set)", set)
        }
    }
    if (decoderName != null) {
        val packed = packCodecOptions(decoderOptions)
        // Copied by the decoder before the call returns.
        val name = BytePointer(decoderName, Charsets.UTF_8)
        val options = packed.takeIf(ByteArray::isNotEmpty)?.let { BytePointer(*it) }
        val named = try {
            bridge.ffmpegkmp_video_decoder_set_named_decoder(decoder, name, options, packed.size)
        } finally {
            name.close()
            options?.close()
        }
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
    val attached = attachSurface(decoder)
    if (attached < 0) {
        engine.release()
        throw NativeVideoDecoderException("Could not attach the output surface (error $attached)", attached)
    }
    return GuardedVideoDecoder(engine, source.input)
}

/** The mounts of the source a decoder reads, which a reopen replaces. */
private class SourceMounts(@Volatile var table: MountTable)

private class JavaCppVideoDecoderEngine(
    private val decoder: ffmpegkmp_video_decoder,
    private val callbacks: JavaCppCallbacks.Registration,
    private val mounts: SourceMounts,
) : VideoDecoderEngineCalls {
    private val frame = ffmpegkmp_decoded_frame().also(bridge::ffmpegkmp_decoded_frame_init)

    override fun start() = bridge.ffmpegkmp_video_decoder_start(decoder)

    override fun info(): NativePlayerSnapshot {
        val snapshot = ffplaykmp_snapshot()
        bridge.ffplaykmp_snapshot_init(snapshot)
        return try {
            requireNative(bridge.ffmpegkmp_video_decoder_get_info(decoder, snapshot), "read the video info", ::NativeVideoDecoderException)
            snapshot.toNativeSnapshot()
        } finally {
            snapshot.close()
        }
    }

    override fun seek(positionNanos: Long) = bridge.ffmpegkmp_video_decoder_seek(decoder, positionNanos)

    override fun frameAt(positionNanos: Long): NativeDecodedFrame {
        requireNative(
            bridge.ffmpegkmp_video_decoder_frame_at(decoder, positionNanos, frame),
            "decode the frame at ${positionNanos}ns",
            ::NativeVideoDecoderException,
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

    override fun setSize(width: Int, height: Int) = bridge.ffmpegkmp_video_decoder_set_size(decoder, width, height)

    override fun setFormat(memoryFormat: NativeFrameFormat?, width: Int, height: Int): Int {
        fun set(format: ffmpegkmp_frame_format?) = bridge.ffmpegkmp_video_decoder_set_memory_format(decoder, format, width, height)
        return if (memoryFormat != null) memoryFormat.withNative(::set) else set(null)
    }

    override fun reopen(
        source: NativePlayerSource,
        memoryFormat: NativeFrameFormat?,
        memoryWidth: Int,
        memoryHeight: Int,
        timeoutMicros: Long,
    ): Int {
        // The old input is closed by the call, so its mounts are read no more once it returns.
        val next = MountTable(source.mounts, replayableSources = true)
        fun reopen(format: ffmpegkmp_frame_format?) = bridge.ffmpegkmp_video_decoder_reopen(
            decoder,
            source.protocolInput(),
            format,
            memoryWidth,
            memoryHeight,
            timeoutMicros,
            JavaCppCallbacks.io,
            callbacks.opaque,
        )
        val result = if (memoryFormat != null) memoryFormat.withNative(::reopen) else reopen(null)
        if (result >= 0) mounts.table = next
        return result
    }

    override fun interrupt() = bridge.ffmpegkmp_video_decoder_interrupt(decoder)

    override fun abort() = bridge.ffmpegkmp_video_decoder_abort(decoder)

    override fun timeLeft(): Long = bridge.ffmpegkmp_video_decoder_time_left(decoder)

    override fun error(): String? = bridge.ffmpegkmp_video_decoder_error(decoder)?.string?.takeIf(String::isNotEmpty)

    override fun release() {
        bridge.ffmpegkmp_video_decoder_destroy(decoder)
        frame.close()
        callbacks.close()
    }
}
