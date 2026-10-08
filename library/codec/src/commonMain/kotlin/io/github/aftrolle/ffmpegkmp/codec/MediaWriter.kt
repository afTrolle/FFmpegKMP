// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.DecoderThread
import io.github.aftrolle.ffmpegkmp.bindings.NativeBridgeUnavailableException
import io.github.aftrolle.ffmpegkmp.bindings.NativeContainer
import io.github.aftrolle.ffmpegkmp.bindings.NativeFileResource
import io.github.aftrolle.ffmpegkmp.bindings.NativeIoAccess
import io.github.aftrolle.ffmpegkmp.bindings.NativeMediaWriter
import io.github.aftrolle.ffmpegkmp.bindings.NativeMediaWriterException
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerError
import io.github.aftrolle.ffmpegkmp.bindings.NativeWriterOutput
import io.github.aftrolle.ffmpegkmp.bindings.createPlatformMediaWriter
import io.github.aftrolle.ffmpegkmp.bindings.platformVideoEncoderFor
import io.github.aftrolle.ffmpegkmp.core.FFmpegKmpException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okio.FileHandle

/** Where a [MediaWriter] writes. */
public sealed interface MediaOutput {
    public data class File(val path: String) : MediaOutput

    /** A seekable handle opened for reading and writing, which an MP4's fast start reads back. */
    public data class Handle(val fileHandle: FileHandle) : MediaOutput {
        init {
            require(fileHandle.readWrite) { "The output handle must be opened for reading and writing" }
        }
    }
}

/** What a writer has taken so far. */
public data class WriterProgress(
    val videoFrames: Long = 0,
    val audioFrames: Long = 0,
    /** The end of the latest frame any track has encoded. */
    val position: Duration = Duration.ZERO,
)

public data class WriterResult(
    /** Null where the output could not tell its size. */
    val bytes: Long?,
    val duration: Duration,
    val videoFrames: Long,
    val audioFrames: Long,
)

public class MediaWritingException(message: String, cause: Throwable? = null) : FFmpegKmpException(message, cause)

/**
 * Encodes video and audio tracks into one output, on FFmpeg's libraries directly, so a writer
 * runs alongside `FFmpegClient` commands, decoders and other writers.
 *
 * Add every track first, then write to them, from any number of coroutines, and [finish]. Each
 * track encodes on a thread of its own (MediaCodec binds an encoder to one), and [VideoTrack.write]
 * suspends only while its encoder is full, so encoding overlaps with decoding and drawing the next
 * frame. A failure in a track is thrown by its next write and by [finish].
 *
 * [close] without [finish] abandons the output, which is left incomplete; it returns promptly even
 * while an encoder is busy. Cancelling a write or [finish] leaves the writer to [close].
 */
public class MediaWriter private constructor(
    private val native: NativeMediaWriter,
    private val thread: DecoderThread,
    private val description: String,
) : AutoCloseable {
    private val tracks = AtomicReference(emptyList<WriterTrack>())
    private val mutableProgress = MutableStateFlow(WriterProgress())
    private val closed = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    /** Frames taken by the encoders so far, updated as each is encoded. */
    public val progress: StateFlow<WriterProgress> = mutableProgress.asStateFlow()

    /**
     * Opens an encoder for a video track, on the track's own thread. Throws
     * [MediaWritingException] when no encoder in this build and on this device takes [config], for
     * example HDR10 without a 10-bit encoder; falling back to another config is the caller's
     * choice. Tracks are added before the first write.
     */
    public suspend fun addVideoTrack(config: VideoEncoderConfig): VideoTrack = addTrack("FFmpegKMP MediaWriter video") { thread ->
        val added = writing("add a video track: $config") { native.addVideoTrack(config.toNative()) }
        VideoTrack(this, thread, added.index, config, checkNotNull(added.info.inputFormat.toFrameFormat()), added.info.encoder, added.info.hardware)
    }

    /** Adds an AAC track for interleaved float PCM. */
    public suspend fun addAudioTrack(config: AudioEncoderConfig = AudioEncoderConfig()): AudioTrack =
        addTrack("FFmpegKMP MediaWriter audio") { thread ->
            val index = writing("add an audio track: $config") {
                native.addAudioTrack(config.sampleRate, config.channels, config.bitRate ?: 0L)
            }
            AudioTrack(this, thread, index, config)
        }

    /**
     * Drains every track's encoder and writes the output's index, then closes the output. With
     * `fastStart` this moves the index to the front, which rewrites the file.
     */
    public suspend fun finish(): WriterResult {
        checkOpen()
        check(!finished.load()) { "The writer has already finished" }
        val all = tracks.load()
        all.forEach { it.end() }
        all.forEach { it.rethrowFailure() }
        val result = withContext(NonCancellable) {
            thread.submit { writing("finish $description") { native.finish() } }.await()
        }
        finished.store(true)
        val progress = mutableProgress.value
        return WriterResult(
            bytes = result.bytes.takeIf { it >= 0 },
            duration = result.durationMicros.microseconds,
            videoFrames = progress.videoFrames,
            audioFrames = progress.audioFrames,
        )
    }

    /** Frees the writer; an output that has not finished is left incomplete. */
    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        if (!finished.load()) native.abort()
        val all = tracks.load()
        // The last thread to wind down frees the writer, so no track is still using it.
        val remaining = AtomicInt(all.size + 1)
        fun release() {
            if (remaining.decrementAndFetch() == 0) native.close()
        }
        all.forEach { track -> track.thread.finish(CLOSE_BOUND) { native.releaseTrack(track.index).also { release() } } }
        thread.finish(CLOSE_BOUND) { release() }
    }

    private suspend fun <T : WriterTrack> addTrack(name: String, open: suspend (DecoderThread) -> T): T {
        checkOpen()
        val thread = DecoderThread(name)
        val track = try {
            // Opening an encoder cannot be interrupted; a cancelled caller's track still belongs to the writer.
            withContext(NonCancellable) { thread.submit { open(thread) }.await() }
        } catch (failure: Throwable) {
            thread.finish(Duration.ZERO) {}
            throw failure
        }
        tracks.update { it + track }
        return track
    }

    internal fun checkOpen() {
        check(!closed.load()) { "The media writer is closed" }
    }

    internal fun encoded(video: Long, audio: Long, end: Duration) {
        mutableProgress.update {
            WriterProgress(it.videoFrames + video, it.audioFrames + audio, maxOf(it.position, end))
        }
    }

    internal fun native(): NativeMediaWriter = native

    public companion object {
        /**
         * Opens [output] for writing as an MP4. With [fastStart] the index moves to the front when
         * the writer finishes, so playback can start before the whole file has arrived.
         *
         * [timeout] bounds the encoding of each write and the draining of each track at
         * [finish]: an encoder that takes frames without ever giving packets, as some emulators'
         * MediaCodec encoders do, fails its track instead of blocking; [Duration.INFINITE] waits
         * indefinitely. It does not bound a [MediaOutput.Handle] that blocks; [close] does.
         */
        public suspend fun open(
            output: MediaOutput,
            fastStart: Boolean = true,
            timeout: Duration = 10.seconds,
        ): MediaWriter {
            require(timeout.isPositive()) { "The timeout must be positive: $timeout" }
            val nativeOutput = when (output) {
                is MediaOutput.File -> NativeWriterOutput.Path(output.path)
                is MediaOutput.Handle ->
                    NativeWriterOutput.Mounted("output.mp4", NativeFileResource(output.fileHandle, NativeIoAccess.READ_WRITE, truncate = true))
            }
            val description = (output as? MediaOutput.File)?.path?.let { "'$it'" } ?: "the output"
            val thread = DecoderThread("FFmpegKMP MediaWriter")
            val native = try {
                withContext(NonCancellable) {
                    thread.submit {
                        writing("open $description") {
                            createPlatformMediaWriter(
                                nativeOutput,
                                NativeContainer.MP4,
                                fastStart = fastStart,
                                timeoutMicros = if (timeout.isInfinite()) 0L else timeout.inWholeMicroseconds,
                            )
                        }
                    }.await()
                }
            } catch (failure: Throwable) {
                thread.finish(Duration.ZERO) {}
                throw failure
            }
            return MediaWriter(native, thread, description)
        }

        /** Whether this platform and build have an encoder for [config], HDR included; it opens one to find out. */
        public suspend fun canEncode(config: VideoEncoderConfig): Boolean {
            val thread = DecoderThread("FFmpegKMP MediaWriter probe")
            return try {
                thread.submit { platformVideoEncoderFor(config.toNative()) != null }.await()
            } catch (_: NativeBridgeUnavailableException) {
                false
            } finally {
                thread.finish(Duration.ZERO) {}
            }
        }
    }
}

/** One track of a [MediaWriter], encoding on [thread] behind a bounded queue. */
public sealed class WriterTrack(
    internal val writer: MediaWriter,
    internal val thread: DecoderThread,
    internal val index: Int,
) {
    private val queue = Semaphore(QUEUED_WRITES)
    private val failure = AtomicReference<Throwable?>(null)
    private val ended = AtomicBoolean(false)

    /** Runs [encode] on the track's thread once the queue has room; [discard] frees the input if it never runs. */
    internal suspend fun enqueue(discard: () -> Unit, encode: suspend () -> Unit) {
        try {
            writer.checkOpen()
            check(!ended.load()) { "The track has ended" }
            rethrowFailure()
            queue.acquire()
        } catch (failure: Throwable) {
            discard()
            throw failure
        }
        val call: Deferred<Unit> = thread.submit {
            try {
                encode()
            } finally {
                discard()
            }
        }
        call.invokeOnCompletion { cause ->
            if (cause != null) failure.compareAndSet(null, cause)
            queue.release()
        }
    }

    internal suspend fun end() {
        if (!ended.compareAndSet(expectedValue = false, newValue = true)) return
        rethrowFailure()
        thread.submit { writing("finish track $index") { writer.native().endTrack(index) } }.await()
    }

    internal fun rethrowFailure() {
        failure.load()?.let { cause ->
            throw cause as? MediaWritingException ?: MediaWritingException("Track $index failed: ${cause.message}", cause)
        }
    }
}

public class VideoTrack internal constructor(
    writer: MediaWriter,
    thread: DecoderThread,
    index: Int,
    public val config: VideoEncoderConfig,
    /** Frames in this format reach the encoder without a conversion. */
    public val inputFormat: FrameFormat,
    /** The FFmpeg encoder that took the track, such as `hevc_videotoolbox`. */
    public val encoderName: String,
    public val isHardware: Boolean,
) : WriterTrack(writer, thread, index) {
    /**
     * Encodes [frame], shown from [pts], and takes ownership of it: the writer closes it once it has
     * encoded it. Suspends while the encoder is full. A frame in [inputFormat] goes to the encoder
     * as it is; another is converted once, on the track's thread. It must have the track's size,
     * and each frame's [pts] must be later than the one before.
     */
    public suspend fun write(frame: VideoFrame, pts: Duration = frame.pts) {
        require(!pts.isNegative()) { "Timestamps must not be negative: $pts" }
        enqueue(discard = frame::close) {
            // A reference of its own, since the browser's encoder suspends while it has the pixels.
            val pixels = frame.useNative { native ->
                requireNotNull(native?.takeIf { it.mappable }) {
                    "The frame has no pixels to encode: it lies in GPU memory"
                }.retain()
            }
            try {
                writing("encode the frame at $pts") { writer.native().writeVideo(index, pixels, pts.inWholeNanoseconds) }
            } finally {
                pixels.close()
            }
            val end = pts + (frame.duration.takeIf { it.isPositive() } ?: config.frameRate?.timeOf(1) ?: Duration.ZERO)
            writer.encoded(video = 1, audio = 0, end = end)
        }
    }
}

public class AudioTrack internal constructor(
    writer: MediaWriter,
    thread: DecoderThread,
    index: Int,
    public val config: AudioEncoderConfig,
) : WriterTrack(writer, thread, index) {
    private var written = 0L

    /**
     * Encodes [frames] frames of interleaved float PCM from [pcm], each [AudioEncoderConfig.channels]
     * samples wide. They are copied, so [pcm] can be reused once this returns.
     */
    public suspend fun write(pcm: FloatArray, frames: Int = pcm.size / config.channels) {
        require(frames >= 0 && frames * config.channels <= pcm.size) {
            "$frames frames of ${config.channels} channels do not fit an array of ${pcm.size} samples"
        }
        if (frames == 0) return
        val samples = pcm.copyOf(frames * config.channels)
        enqueue(discard = {}) {
            writing("encode audio") { writer.native().writeAudio(index, samples, 0, frames) }
            written += frames
            writer.encoded(video = 0, audio = frames.toLong(), end = (written * 1_000_000_000L / config.sampleRate).nanoseconds)
        }
    }
}

/** Writes a track's encoder may have queued before it suspends a caller. */
private const val QUEUED_WRITES = 2

/** How long [MediaWriter.close] waits for each thread before leaving it to wind down. */
private val CLOSE_BOUND = 1.seconds

private inline fun <T> writing(action: String, block: () -> T): T = try {
    block()
} catch (failure: NativeMediaWriterException) {
    val reason = when (failure.errorCode) {
        NativePlayerError.UNSUPPORTED -> "no encoder in this build and on this device takes it"
        NativePlayerError.INVALID_STATE -> "the writer is not in a state to do it"
        NativePlayerError.INVALID_ARGUMENT -> "the input does not fit the track"
        NativePlayerError.TIMED_OUT -> "the encoder timed out: it took input without giving output"
        else -> "error ${failure.errorCode}"
    }
    throw MediaWritingException("Could not $action: $reason", failure)
} catch (failure: NativeBridgeUnavailableException) {
    throw MediaWritingException(failure.message ?: "The FFmpegKMP native runtime is unavailable", failure)
}
