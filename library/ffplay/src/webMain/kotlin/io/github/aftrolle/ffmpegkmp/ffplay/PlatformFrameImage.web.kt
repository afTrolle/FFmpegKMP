// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.ImageBitmap
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame

internal actual fun NativeVideoFrame.toImageBitmap(): ImageBitmap = ImageBitmap(width, height)
