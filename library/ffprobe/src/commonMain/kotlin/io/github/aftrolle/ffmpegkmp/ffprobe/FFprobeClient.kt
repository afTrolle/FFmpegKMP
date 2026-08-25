// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffprobe

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.CommandKind
import io.github.aftrolle.ffmpegkmp.core.CommandLineTokenizer
import io.github.aftrolle.ffmpegkmp.core.CommandRuntimeClient
import io.github.aftrolle.ffmpegkmp.core.CommandRuntimeLimits
import io.github.aftrolle.ffmpegkmp.core.ExecutionResult
import io.github.aftrolle.ffmpegkmp.core.ExecutionSession
import io.github.aftrolle.ffmpegkmp.core.FFmpegKmpException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

public typealias FFprobeResult = ExecutionResult
public typealias FFprobeSession = ExecutionSession<FFprobeResult>

public class FFprobeParseException(
    message: String,
    cause: Throwable? = null,
) : FFmpegKmpException(message, cause)

public class FFprobeClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    },
    private val runtimeLimits: CommandRuntimeLimits = CommandRuntimeLimits.Default,
) : AutoCloseable {
    private val runtime = lazy { CommandRuntimeClient(CommandKind.FFPROBE, runtimeLimits) }

    public suspend fun execute(
        arguments: List<String>,
        io: CommandIo = CommandIo.Empty,
    ): FFprobeResult = runtime.value.execute(arguments, io)

    public suspend fun execute(
        command: String,
        io: CommandIo = CommandIo.Empty,
    ): FFprobeResult = execute(CommandLineTokenizer.tokenize(command, "ffprobe"), io)

    public fun enqueue(
        arguments: List<String>,
        io: CommandIo = CommandIo.Empty,
    ): FFprobeSession = runtime.value.enqueue(arguments, io)

    public fun enqueue(
        command: String,
        io: CommandIo = CommandIo.Empty,
    ): FFprobeSession = enqueue(CommandLineTokenizer.tokenize(command, "ffprobe"), io)

    public suspend fun inspect(
        input: String,
        query: ProbeQuery = ProbeQuery.Default,
        io: CommandIo = CommandIo.Empty,
    ): MediaInformation {
        val result = execute(query.arguments(input), io)
        if (!result.isSuccess) {
            throw FFprobeParseException(
                "FFprobe failed with return code ${result.returnCode}: ${result.errorOutput}",
            )
        }
        if (result.captureStatus.outputTruncated) {
            throw FFprobeParseException(
                "FFprobe output exceeded the configured capture limit; increase " +
                    "CommandRuntimeLimits.maxCapturedOutputCharacters",
            )
        }
        return parse(result.output)
    }

    public fun parse(output: String): MediaInformation = try {
        json.decodeFromString(MediaInformationSerializer, output)
    } catch (failure: SerializationException) {
        throw FFprobeParseException("FFprobe returned malformed JSON", failure)
    } catch (failure: IllegalArgumentException) {
        throw FFprobeParseException("FFprobe returned malformed JSON", failure)
    }

    override fun close() {
        if (runtime.isInitialized()) runtime.value.close()
    }
}
