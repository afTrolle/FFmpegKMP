// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

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
) : NativeIoResource

@InternalFFmpegKmpApi
public class NativeSourceResource(public val source: Source) : NativeIoResource

@InternalFFmpegKmpApi
public class NativeSinkResource(public val sink: Sink) : NativeIoResource

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

@InternalFFmpegKmpApi
public interface NativeExecutionBridge : AutoCloseable {
    public suspend fun execute(
        request: NativeExecutionRequest,
        emit: (NativeExecutionEvent) -> Unit,
    ): NativeExecutionResult

    public fun cancel(executionId: Long)
}

@InternalFFmpegKmpApi
public class NativeBridgeUnavailableException(message: String) : IllegalStateException(message)

@InternalFFmpegKmpApi
public expect fun createPlatformExecutionBridge(): NativeExecutionBridge
