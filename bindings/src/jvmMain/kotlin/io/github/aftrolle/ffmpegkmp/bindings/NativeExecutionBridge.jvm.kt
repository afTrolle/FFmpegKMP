// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

@InternalFFmpegKmpApi
public actual fun createPlatformExecutionBridge(): NativeExecutionBridge =
    createJavaCppExecutionBridge()
