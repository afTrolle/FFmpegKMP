// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

@InternalFFmpegKmpApi
public class UnavailableNativeExecutionBridge(
    private val platform: String,
) : NativeExecutionBridge {
    override suspend fun execute(
        request: NativeExecutionRequest,
        emit: (NativeExecutionEvent) -> Unit,
    ): NativeExecutionResult {
        throw NativeBridgeUnavailableException(
            "The generated FFmpegKMP native bridge is not available for $platform. " +
                "Run the bindings assembly task for the selected ffmpegkmp.profile.",
        )
    }

    override fun cancel(executionId: Long) = Unit

    override fun close() = Unit
}
