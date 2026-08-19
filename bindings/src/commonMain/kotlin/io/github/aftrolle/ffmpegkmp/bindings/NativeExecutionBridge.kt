// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import kotlinx.io.RawSink
import kotlinx.io.RawSource

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
public class NativeMountedInput(
    public val path: String,
    public val source: RawSource,
)

@InternalFFmpegKmpApi
public class NativeMountedOutput(
    public val path: String,
    public val sink: RawSink,
)

@InternalFFmpegKmpApi
public data class NativeExecutionRequest(
    val id: Long,
    val kind: NativeCommandKind,
    val arguments: List<String>,
    val inputs: List<NativeMountedInput> = emptyList(),
    val outputs: List<NativeMountedOutput> = emptyList(),
)

@InternalFFmpegKmpApi
public sealed interface NativeExecutionEvent {
    public data class Log(val level: Int, val message: String) : NativeExecutionEvent
    public data class Output(val stream: Stream, val text: String) : NativeExecutionEvent

    public enum class Stream { STDOUT, STDERR }
}

/** Mounted output data is streamed into each [NativeMountedOutput.sink] before execute returns. */
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
