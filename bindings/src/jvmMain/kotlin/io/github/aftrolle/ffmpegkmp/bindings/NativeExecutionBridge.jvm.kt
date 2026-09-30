// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

@InternalFFmpegKmpApi
public actual fun createPlatformExecutionBridge(): NativeExecutionBridge =
    createJavaCppExecutionBridge()

@InternalFFmpegKmpApi
public actual fun createPlatformPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: (NativeVideoFrame) -> Unit,
    platformFrame: (NativePlatformVideoFrame) -> Boolean,
): NativePlayerBridge = createJavaCppPlayerBridge(configuration, update, frame, platformFrame)

@InternalFFmpegKmpApi
public actual fun createPlatformVideoDecoder(
    source: NativePlayerSource,
    output: NativeVideoDecoderOutput,
    decoderPreference: NativePlayerDecoderPreference,
    timeoutMicros: Long,
    surface: Any?,
): NativeVideoDecoder {
    require(output != NativeVideoDecoderOutput.SURFACE) { "Surface output is only available on Android" }
    return createJavaCppVideoDecoder(source, output, decoderPreference, timeoutMicros) { 0 }
}
