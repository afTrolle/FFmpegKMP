// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffmpeg

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.CommandKind
import io.github.aftrolle.ffmpegkmp.core.CommandLineTokenizer
import io.github.aftrolle.ffmpegkmp.core.CommandRuntimeClient
import io.github.aftrolle.ffmpegkmp.core.CommandRuntimeLimits
import io.github.aftrolle.ffmpegkmp.core.ExecutionResult
import io.github.aftrolle.ffmpegkmp.core.ExecutionSession

public typealias FFmpegResult = ExecutionResult
public typealias FFmpegSession = ExecutionSession<FFmpegResult>

public class FFmpegClient(
    private val runtimeLimits: CommandRuntimeLimits = CommandRuntimeLimits.Default,
) : AutoCloseable {
    private val runtime = lazy { CommandRuntimeClient(CommandKind.FFMPEG, runtimeLimits) }

    public suspend fun execute(
        arguments: List<String>,
        io: CommandIo = CommandIo.Empty,
    ): FFmpegResult = runtime.value.execute(arguments, io)

    public suspend fun execute(
        command: String,
        io: CommandIo = CommandIo.Empty,
    ): FFmpegResult = execute(CommandLineTokenizer.tokenize(command, "ffmpeg"), io)

    public suspend fun execute(
        command: FFmpegCommand,
        io: CommandIo = CommandIo.Empty,
    ): FFmpegResult = execute(command.arguments, io)

    public fun enqueue(
        arguments: List<String>,
        io: CommandIo = CommandIo.Empty,
    ): FFmpegSession = runtime.value.enqueue(arguments, io)

    public fun enqueue(
        command: String,
        io: CommandIo = CommandIo.Empty,
    ): FFmpegSession = enqueue(CommandLineTokenizer.tokenize(command, "ffmpeg"), io)

    public fun enqueue(
        command: FFmpegCommand,
        io: CommandIo = CommandIo.Empty,
    ): FFmpegSession = enqueue(command.arguments, io)

    override fun close() {
        if (runtime.isInitialized()) runtime.value.close()
    }
}
