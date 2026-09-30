// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import android.view.Surface
import io.github.aftrolle.ffmpegkmp.bindings.javacpp.AndroidPlayerSurface
import org.bytedeco.javacpp.Loader

@InternalFFmpegKmpApi
public actual fun createPlatformExecutionBridge(): NativeExecutionBridge =
    createJavaCppExecutionBridge()

@InternalFFmpegKmpApi
public actual fun createPlatformPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: (NativeVideoFrame) -> Unit,
    platformFrame: (NativePlatformVideoFrame) -> Boolean,
): NativePlayerBridge = createJavaCppPlayerBridge(
    configuration = configuration,
    update = update,
    frame = frame,
    platformFrame = platformFrame,
    platformOutputTarget = { player, target, secure ->
        if (target != null && target !is Surface) {
            -22
        } else {
            androidSurfaceNatives.value
            AndroidPlayerSurface.setSurface(target, player, if (secure) 1 else 0)
        }
    },
)

@InternalFFmpegKmpApi
public actual fun createPlatformVideoDecoder(
    source: NativePlayerSource,
    output: NativeVideoDecoderOutput,
    decoderPreference: NativePlayerDecoderPreference,
    timeoutMicros: Long,
    surface: Any?,
): NativeVideoDecoder {
    require((output == NativeVideoDecoderOutput.SURFACE) == (surface != null)) {
        "A Surface is required for, and only for, Surface output"
    }
    require(surface == null || surface is Surface) { "Surface output needs an android.view.Surface, not $surface" }
    return createJavaCppVideoDecoder(source, output, decoderPreference, timeoutMicros) { decoder ->
        if (surface == null) {
            0
        } else {
            androidSurfaceNatives.value
            AndroidPlayerSurface.setDecoderSurface(surface, decoder)
        }
    }
}

// Decoders open on threads of their own, so two can reach the Surface natives at once; a bare Loader.load lets the second
// call a native before the first has finished binding it (UnsatisfiedLinkError), as JavaCppBridgeLoader guards the bridge.
private val androidSurfaceNatives = lazy { Loader.load(AndroidPlayerSurface::class.java) }
