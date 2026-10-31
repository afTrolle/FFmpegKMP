// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffmpeg

import io.github.aftrolle.ffmpegkmp.core.CommandClient
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.CommandRuntimeLimits
import io.github.aftrolle.ffmpegkmp.core.ExecutionResult
import io.github.aftrolle.ffmpegkmp.core.ExecutionSession

public typealias FFmpegResult = ExecutionResult
public typealias FFmpegSession = ExecutionSession<FFmpegResult>

public class FFmpegClient(
    runtimeLimits: CommandRuntimeLimits = CommandRuntimeLimits.Default,
) : CommandClient("ffmpeg", runtimeLimits) {
    public suspend fun execute(
        command: FFmpegCommand,
        io: CommandIo = CommandIo.Empty,
    ): FFmpegResult = execute(command.arguments, io)

    public fun enqueue(
        command: FFmpegCommand,
        io: CommandIo = CommandIo.Empty,
    ): FFmpegSession = enqueue(command.arguments, io)
}
