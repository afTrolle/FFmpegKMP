// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.core

/**
 * Runs one command-line tool through the process-wide command queue. `FFmpegClient` and
 * `FFprobeClient` are the two clients; each takes arguments as a list, or as one command line
 * tokenized the way a shell would, with or without the leading executable name.
 */
public abstract class CommandClient @InternalFFmpegKmpApi protected constructor(
    private val executableName: String,
    runtimeLimits: CommandRuntimeLimits,
) : AutoCloseable {
    private val runtime = lazy {
        val kind = when (executableName) {
            "ffmpeg" -> CommandKind.FFMPEG
            "ffprobe" -> CommandKind.FFPROBE
            else -> throw IllegalArgumentException("Unknown command-line tool: $executableName")
        }
        CommandRuntimeClient(kind, limits = runtimeLimits)
    }

    /**
     * Runs the command and awaits its result, first waiting for room in the queue if it is full.
     * Cancelling the caller cancels the native run and returns once it has unwound.
     */
    public suspend fun execute(
        arguments: List<String>,
        io: CommandIo = CommandIo.Empty,
    ): ExecutionResult = runtime.value.execute(arguments, io)

    public suspend fun execute(
        command: String,
        io: CommandIo = CommandIo.Empty,
    ): ExecutionResult = execute(CommandLineTokenizer.tokenize(command, executableName), io)

    /**
     * Starts the command in the background and returns a handle to it. When the queue is full,
     * the session fails instead of waiting for room.
     */
    public fun enqueue(
        arguments: List<String>,
        io: CommandIo = CommandIo.Empty,
    ): ExecutionSession<ExecutionResult> = runtime.value.enqueue(arguments, io)

    public fun enqueue(
        command: String,
        io: CommandIo = CommandIo.Empty,
    ): ExecutionSession<ExecutionResult> = enqueue(CommandLineTokenizer.tokenize(command, executableName), io)

    /** Cancels this client's running and queued sessions and releases its bridge. */
    override fun close() {
        if (runtime.isInitialized()) runtime.value.close()
    }
}
