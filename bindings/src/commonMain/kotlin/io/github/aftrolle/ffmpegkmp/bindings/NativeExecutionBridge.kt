// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import okio.FileHandle
import okio.Sink
import okio.Source

/** Internal ABI shared by the public Kotlin modules and the generated platform bindings. */
@RequiresOptIn(
    message = "This is an internal FFmpegKMP binding API and may change without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
public annotation class InternalFFmpegKmpApi

@InternalFFmpegKmpApi
public enum class NativeCommandKind {
    FFMPEG,
    FFPROBE,
}

@InternalFFmpegKmpApi
public enum class NativeIoAccess {
    READ,
    WRITE,
    READ_WRITE,
}

@InternalFFmpegKmpApi
public sealed interface NativeIoResource

@InternalFFmpegKmpApi
public class NativeFileResource(
    public val fileHandle: FileHandle,
    public val access: NativeIoAccess,
    public val truncate: Boolean = access == NativeIoAccess.WRITE,
) : NativeIoResource {
    /** Held by every [MountTable] call into this resource. */
    internal val ioLock = IoLock()
}

@InternalFFmpegKmpApi
public class NativeSourceResource(public val source: Source) : NativeIoResource {
    /** Shared by every mount of this resource, so a re-prepare replays what was already read. */
    internal val replay: ReplayableSource by lazy { ReplayableSource(source) }

    /** Held by every [MountTable] call into this resource, as the player and its audio share [replay]. */
    internal val ioLock = IoLock()
}

@InternalFFmpegKmpApi
public class NativeSinkResource(public val sink: Sink) : NativeIoResource {
    /** Held by every [MountTable] call into this resource. */
    internal val ioLock = IoLock()
}

@InternalFFmpegKmpApi
public class NativeMountedIo(
    public val path: String,
    public val resource: NativeIoResource,
)

@InternalFFmpegKmpApi
public data class NativeExecutionRequest(
    val id: Long,
    val kind: NativeCommandKind,
    val arguments: List<String>,
    val mounts: List<NativeMountedIo> = emptyList(),
)

@InternalFFmpegKmpApi
public sealed interface NativeExecutionEvent {
    public data class Log(val level: Int, val message: String) : NativeExecutionEvent
    public data class Output(val stream: Stream, val text: String) : NativeExecutionEvent

    public enum class Stream { STDOUT, STDERR }
}

/** Mounted resources remain open until the command session completes. */
@InternalFFmpegKmpApi
public data class NativeExecutionResult(
    val returnCode: Int,
)

/**
 * Runs one command at a time on the process-wide native runtime.
 *
 * Cancellation follows the coroutine: cancelling the caller of [execute] stops the native run,
 * and [execute] rethrows the cancellation only once the native code has returned. The bridge is
 * therefore free for the next command the moment a cancelled caller resumes, and no separate
 * cancel call or execution id is needed.
 */
@InternalFFmpegKmpApi
public interface NativeExecutionBridge : AutoCloseable {
    public suspend fun execute(
        request: NativeExecutionRequest,
        emit: (NativeExecutionEvent) -> Unit,
    ): NativeExecutionResult
}

@InternalFFmpegKmpApi
public class NativeBridgeUnavailableException(message: String) : IllegalStateException(message)

/** Raw `ffmpegkmp_context` calls for one command context, implemented per platform binding. */
internal interface CommandEngineCalls {
    /** Routes the context's I/O to [mounts] (ids counting from 1) and its events to [emit]. */
    fun attach(mounts: List<NativeMountedIo>, emit: (NativeExecutionEvent) -> Unit)
    fun detach()
    fun resetCancel()
    fun cancel()

    /** Runs the tool [kind] names with [arguments] (`argv[0]` included), blocking until it returns. */
    fun execute(kind: NativeCommandKind, arguments: List<String>): Int

    /** Frees the context and anything the binding keeps alive for it. */
    fun release()
}

/** A [NativeExecutionBridge] over [engine], blocking on [dispatcher] while a command runs. */
internal class CommandEngineBridge(
    private val engine: CommandEngineCalls,
    private val dispatcher: CoroutineDispatcher,
) : NativeExecutionBridge {
    @Volatile
    private var closed = false

    override suspend fun execute(
        request: NativeExecutionRequest,
        emit: (NativeExecutionEvent) -> Unit,
    ): NativeExecutionResult {
        check(!closed) { "The native execution bridge is closed" }
        val mountedPaths = request.mounts.mapIndexed { index, mount ->
            mount.path to protocolUrl(index.toLong() + 1L, mount.path)
        }.toMap()
        val executable = if (request.kind == NativeCommandKind.FFMPEG) "ffmpeg" else "ffprobe"
        val arguments = listOf(executable) + request.arguments.map { mountedPaths[it] ?: it }
        engine.attach(request.mounts, emit)
        val returnCode = try {
            // A cancel stays on the context until cleared, so clearing it here (with no run in
            // flight on this bridge) means any cancel from now on belongs to this run.
            engine.resetCancel()
            runCancellableNative(dispatcher, cancelNative = engine::cancel) {
                engine.execute(request.kind, arguments)
            }
        } finally {
            engine.detach()
        }
        if (returnCode == FFTOOLS_UNAVAILABLE) {
            throw NativeBridgeUnavailableException("The FFmpegKMP bridge was built without embedded fftools entry points")
        }
        return NativeExecutionResult(returnCode)
    }

    override fun close() {
        if (closed) return
        closed = true
        engine.detach()
        engine.release()
    }

    private companion object {
        /** What `ffmpegkmp_execute` returns from a build without fftools. */
        const val FFTOOLS_UNAVAILABLE = -38
    }
}

@InternalFFmpegKmpApi
public expect fun createPlatformExecutionBridge(): NativeExecutionBridge
