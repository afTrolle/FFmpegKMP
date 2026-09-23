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
            Loader.load(AndroidPlayerSurface::class.java)
            AndroidPlayerSurface.setSurface(target, player, if (secure) 1 else 0)
        }
    },
)
