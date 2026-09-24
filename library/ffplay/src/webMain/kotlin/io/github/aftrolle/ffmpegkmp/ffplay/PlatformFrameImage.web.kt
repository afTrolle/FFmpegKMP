// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

// Compose draws the browser through Skia too, so a software frame becomes an image as it does on native and the JVM.
internal actual fun NativeVideoFrame.toImageBitmap(): ImageBitmap = Image.makeRaster(
    ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB),
    rgba,
    stride,
).toComposeImageBitmap()
