// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

/** `ffmpegkmp_container`: the ordinal is the C value. */
@InternalFFmpegKmpApi
public enum class NativeContainer { MP4, FRAGMENTED_MP4, MATROSKA, MPEGTS }

/** `ffmpegkmp_video_codec`: the ordinal is the C value. */
@InternalFFmpegKmpApi
public enum class NativeVideoCodec { H264, HEVC, AV1 }

/** `ffmpegkmp_dynamic_range`: the ordinal is the C value. */
@InternalFFmpegKmpApi
public enum class NativeDynamicRange { SDR, HDR10, HLG }

/** Mastering-display colour volume: CIE 1931 xy for red, green, blue and white; luminance in cd/m². */
@InternalFFmpegKmpApi
public class NativeMasteringDisplay(
    public val primariesX: DoubleArray,
    public val primariesY: DoubleArray,
    public val whitePointX: Double,
    public val whitePointY: Double,
    public val minLuminance: Double,
    public val maxLuminance: Double,
)

@InternalFFmpegKmpApi
public class NativeHdrMetadata(
    public val masteringDisplay: NativeMasteringDisplay?,
    /** MaxCLL and MaxFALL in cd/m², null when not given. */
    public val contentLight: Pair<Int, Int>?,
)

/** `ffmpegkmp_video_encoder_config`. The preference shares the decoders' ordinals. */
@InternalFFmpegKmpApi
public class NativeVideoEncoderConfig(
    public val width: Int,
    public val height: Int,
    /** 0/0 for a variable frame rate. */
    public val frameRateNumerator: Int,
    public val frameRateDenominator: Int,
    public val codec: NativeVideoCodec,
    public val dynamicRange: NativeDynamicRange,
    public val preference: NativePlayerDecoderPreference,
    /** 0 for one that suits the size and rate. */
    public val bitRate: Long,
    public val keyframeIntervalMicros: Long,
    public val hdrMetadata: NativeHdrMetadata?,
    /** 8 or 10; 0 for the dynamic range's own. */
    public val bitDepth: Int = 0,
)

/** The encoder a video track opened, and the format it takes without a conversion. */
@InternalFFmpegKmpApi
public class NativeVideoTrackInfo(
    public val inputFormat: NativeFrameFormat,
    public val hardware: Boolean,
    public val encoder: String,
    /** Bits per second the encoder opened with; 0 where the platform does not say. */
    public val bitRate: Long = 0,
)

@InternalFFmpegKmpApi
public class NativeAddedVideoTrack(public val index: Int, public val info: NativeVideoTrackInfo)

@InternalFFmpegKmpApi
public class NativeWriterResult(
    /** -1 where the output cannot tell its size. */
    public val bytes: Long,
    public val durationMicros: Long,
)

/** Where a writer writes: a path FFmpeg opens itself, or a resource the host serves. */
@InternalFFmpegKmpApi
public sealed interface NativeWriterOutput {
    public class Path(public val path: String) : NativeWriterOutput

    /** [name]'s extension is kept in the protocol URL, as for mounted command outputs. */
    public class Mounted(public val name: String, public val resource: NativeIoResource) : NativeWriterOutput
}

/**
 * The native writer (`ffmpegkmp_writer.c`), or WebCodecs encoders and FFmpeg's muxer in a worker in
 * the browser. A track's add, write and end calls come from one thread; different tracks may run
 * at once. [finish] runs once every track has ended; [abort] is safe from any thread, and [close]
 * frees the writer. Only the browser's calls suspend; the native ones block their thread.
 */
@InternalFFmpegKmpApi
public interface NativeMediaWriter : AutoCloseable {
    public suspend fun addVideoTrack(config: NativeVideoEncoderConfig): NativeAddedVideoTrack

    public suspend fun addAudioTrack(sampleRate: Int, channels: Int, bitRate: Long): Int

    /** Encodes [frame], which the caller still owns, shown from [ptsNanos]. */
    public suspend fun writeVideo(track: Int, frame: NativeFrame, ptsNanos: Long)

    /** Encodes [frames] interleaved frames of [samples] from [offset]. */
    public suspend fun writeAudio(track: Int, samples: FloatArray, offset: Int, frames: Int)

    public suspend fun endTrack(track: Int)

    /**
     * Android 14+ hardware encoders: turns [track], which has taken no frame, into one whose frames
     * are drawn into the encoder's input Surface and queued with [NativeEncoderSurface.queue], and
     * returns that surface. Null, with the track as it was, where the platform or the track's
     * config does not allow it.
     */
    public suspend fun openInputSurface(track: Int): NativeEncoderSurface? = null

    /** Frees [track]'s encoder without draining it, on its thread, for an output that is abandoned. */
    public fun releaseTrack(track: Int)

    public suspend fun finish(): NativeWriterResult

    public fun abort()
}

/**
 * A platform encoder's input surface, which a GPU draws into with no copy. [dequeue] lends a
 * [NativeGpuBuffer] to draw into: on Android a `HardwareBuffer` from an `ImageWriter` on the
 * encoder's `Surface`. Once the GPU has finished, [queue] hands it to the encoder, and releasing
 * the buffer without queueing it gives it back.
 */
@InternalFFmpegKmpApi
public interface NativeEncoderSurface {
    /** The next buffer to draw into, waiting until the encoder has room; throws [NativeMediaWriterException] when the encoder failed. */
    public fun dequeue(): NativeGpuBuffer

    /** Encodes [buffer], which came from [dequeue] and holds a finished picture, shown from [ptsNanos]. */
    public fun queue(buffer: NativeGpuBuffer, ptsNanos: Long)
}

@InternalFFmpegKmpApi
public class NativeMediaWriterException(
    message: String,
    public val errorCode: Int,
) : IllegalStateException(message)

/**
 * Creates a writer for [output], opening it for writing. [fastStart] puts an MP4's index first.
 * [timeoutMicros] bounds each write and end of a track, 0 for none: calls past it fail with
 * [NativePlayerError.TIMED_OUT], as does the track afterwards. The browser writes a mounted output
 * only, which it holds in the worker until [NativeMediaWriter.finish] writes it out.
 */
@InternalFFmpegKmpApi
public expect suspend fun createPlatformMediaWriter(
    output: NativeWriterOutput,
    container: NativeContainer,
    fastStart: Boolean,
    timeoutMicros: Long,
): NativeMediaWriter

/** The encoder that would take [config], opened and closed to find out; null when none would. */
@InternalFFmpegKmpApi
public expect suspend fun platformVideoEncoderFor(config: NativeVideoEncoderConfig): NativeVideoTrackInfo?

/** Raw `ffmpegkmp_writer_*` calls for one open writer, implemented per platform binding. */
internal interface MediaWriterEngineCalls {
    fun addVideoTrack(config: NativeVideoEncoderConfig): NativeAddedVideoTrack
    fun addAudioTrack(sampleRate: Int, channels: Int, bitRate: Long): Int
    fun writeVideo(track: Int, frame: NativeFrame, ptsNanos: Long): Int
    fun writeAudio(track: Int, samples: FloatArray, offset: Int, frames: Int): Int
    fun endTrack(track: Int): Int
    fun openInputSurface(track: Int): NativeEncoderSurface? = null
    fun releaseTrack(track: Int)
    fun finish(): NativeWriterResult
    fun abort()

    /** Frees the writer and anything the binding keeps alive for it. */
    fun release()
}

internal class GuardedMediaWriter(
    private val engine: MediaWriterEngineCalls,
    private val output: String,
) : NativeMediaWriter {
    private val guard = NativeHandleGuard()

    override suspend fun addVideoTrack(config: NativeVideoEncoderConfig): NativeAddedVideoTrack =
        guard.use(::closedError) { engine.addVideoTrack(config) }

    override suspend fun addAudioTrack(sampleRate: Int, channels: Int, bitRate: Long): Int = guard.use(::closedError) {
        engine.addAudioTrack(sampleRate, channels, bitRate).also { requireWriterSuccess(it, "add an audio track") }
    }

    override suspend fun writeVideo(track: Int, frame: NativeFrame, ptsNanos: Long) = guard.use(::closedError) {
        requireWriterSuccess(engine.writeVideo(track, frame, ptsNanos), "encode the frame at ${ptsNanos}ns")
    }

    override suspend fun writeAudio(track: Int, samples: FloatArray, offset: Int, frames: Int) = guard.use(::closedError) {
        require(offset >= 0 && frames >= 0) { "Offset and frame count must not be negative" }
        requireWriterSuccess(engine.writeAudio(track, samples, offset, frames), "encode audio")
    }

    override suspend fun endTrack(track: Int) = guard.use(::closedError) {
        requireWriterSuccess(engine.endTrack(track), "finish track $track")
    }

    override suspend fun openInputSurface(track: Int): NativeEncoderSurface? = guard.use(::closedError) { engine.openInputSurface(track) }

    override fun releaseTrack(track: Int) = guard.use({}) { engine.releaseTrack(track) }

    override suspend fun finish(): NativeWriterResult = guard.use(::closedError) { engine.finish() }

    override fun abort() = guard.use({}) { engine.abort() }

    override fun close() = guard.close(engine::release)

    private fun closedError(): Nothing = throw IllegalStateException("The media writer for '$output' is closed")
}

internal fun requireWriterSuccess(result: Int, action: String) {
    if (result < 0) throw NativeMediaWriterException("Could not $action (error $result)", result)
}

/** The URL [output] is written through, `ffmpegkmp:1.<ext>` for a mounted resource. */
internal fun NativeWriterOutput.url(): String = when (this) {
    is NativeWriterOutput.Path -> path
    is NativeWriterOutput.Mounted -> protocolUrl(1L, name)
}
