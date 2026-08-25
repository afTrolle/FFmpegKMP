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

/**
 * Memory limits applied to one queued or running command.
 *
 * Accepted native log/output events are not truncated for active collectors: collectors apply
 * backpressure. Progress reports parsed from one native callback are coalesced to the latest
 * report. These limits bound the native-to-Kotlin handoff and the data retained in the final
 * [ExecutionResult].
 */
public data class CommandRuntimeLimits(
    val maxPendingNativeEvents: Int = 1_024,
    val maxCapturedOutputCharacters: Int = 16 * 1_024 * 1_024,
    val maxCapturedErrorOutputCharacters: Int = 4 * 1_024 * 1_024,
    val maxRetainedLogEvents: Int = 2_048,
    val maxRetainedLogCharacters: Int = 1 * 1_024 * 1_024,
) {
    init {
        require(maxPendingNativeEvents > 0) { "maxPendingNativeEvents must be positive" }
        require(maxCapturedOutputCharacters >= 0) { "maxCapturedOutputCharacters must not be negative" }
        require(maxCapturedErrorOutputCharacters >= 0) {
            "maxCapturedErrorOutputCharacters must not be negative"
        }
        require(maxRetainedLogEvents >= 0) { "maxRetainedLogEvents must not be negative" }
        require(maxRetainedLogCharacters >= 0) { "maxRetainedLogCharacters must not be negative" }
    }

    public companion object {
        public val Default: CommandRuntimeLimits = CommandRuntimeLimits()
    }
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

/** Describes data omitted from the retained result after a configured capture limit was reached. */
public data class ExecutionCaptureStatus(
    val outputTruncated: Boolean = false,
    val errorOutputTruncated: Boolean = false,
    val logsTruncated: Boolean = false,
    val omittedLogEvents: Long = 0,
    val omittedLogCharacters: Long = 0,
) {
    public val truncated: Boolean
        get() = outputTruncated || errorOutputTruncated || logsTruncated
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
    /** Set when stdout, stderr, or structured logs exceeded the configured retained-result limits. */
    val captureStatus: ExecutionCaptureStatus = ExecutionCaptureStatus(),
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
