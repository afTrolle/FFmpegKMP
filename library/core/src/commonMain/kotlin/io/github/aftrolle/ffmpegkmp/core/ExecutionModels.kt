// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import kotlin.time.Duration

public enum class SessionState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    CLOSED,
}

public enum class LogLevel {
    QUIET,
    PANIC,
    FATAL,
    ERROR,
    WARNING,
    INFO,
    VERBOSE,
    DEBUG,
    TRACE,
    UNKNOWN,
}

public enum class OutputStream {
    STDOUT,
    STDERR,
}

public sealed interface ExecutionEvent {
    public data class Log(
        val level: LogLevel,
        val message: String,
    ) : ExecutionEvent

    public data class Output(
        val stream: OutputStream,
        val text: String,
    ) : ExecutionEvent

    /**
     * Periodic encoding progress parsed from FFmpeg's status reports.
     * Emission frequency follows `-stats_period` (default 0.5s).
     * Fields are null when FFmpeg reported no value (`N/A`).
     */
    public data class Progress(
        val frame: Long? = null,
        val fps: Double? = null,
        val outTime: Duration? = null,
        val totalSizeBytes: Long? = null,
        val bitrate: String? = null,
        val speed: Double? = null,
        val end: Boolean = false,
    ) : ExecutionEvent
}

public data class ExecutionResult(
    val returnCode: Int,
    val output: String,
    /** Explicit stderr output plus messages delivered through FFmpeg's native log callback. */
    val errorOutput: String,
    val logs: List<ExecutionEvent.Log>,
    val duration: Duration,
    val cancelled: Boolean,
    /**
     * The last progress report FFmpeg emitted, if any. A run can exit 0 without encoding a
     * single video frame (broken hardware encoders); check `finalProgress?.frame` to detect it.
     */
    val finalProgress: ExecutionEvent.Progress? = null,
) {
    public val isSuccess: Boolean get() = !cancelled && returnCode == 0
}

public open class FFmpegKmpException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public class NativeExecutionException(
    message: String,
    cause: Throwable? = null,
) : FFmpegKmpException(message, cause)

public class CommandParseException(message: String) : FFmpegKmpException(message)
