// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.ImageBitmap
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame

internal fun NativeVideoFrame.toImageBitmap(): ImageBitmap = rasterImage(rgba, width, height, stride, linearF16 = false)

/** Copies RGBA8 (sRGB) or RGBA half-float (linear extended sRGB) rows into an image. */
internal expect fun rasterImage(pixels: ByteArray, width: Int, height: Int, stride: Int, linearF16: Boolean): ImageBitmap

internal expect val platformSupportsLinearF16: Boolean

/** Whether [VideoOutput.PixelBuffer] is available: Apple only. */
internal expect val platformSupportsPixelBuffer: Boolean
