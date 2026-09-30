// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

// Compose draws the browser through Skia too, so a software frame becomes an image as it does on native and the JVM.
internal actual fun rasterImage(
    pixels: ByteArray,
    width: Int,
    height: Int,
    stride: Int,
    linearF16: Boolean,
): ImageBitmap = if (linearF16) {
    // Image.toComposeImageBitmap redraws into an 8-bit sRGB bitmap; a Bitmap keeps F16 and linear light.
    val info = ImageInfo(width, height, ColorType.RGBA_F16, ColorAlphaType.UNPREMUL, ColorSpace.sRGBLinear)
    Bitmap().apply {
        check(installPixels(info, pixels, stride)) { "Skia rejected a ${width}x$height F16 frame" }
        setImmutable()
    }.asComposeImageBitmap()
} else {
    Image.makeRaster(
        ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB),
        pixels,
        stride,
    ).toComposeImageBitmap()
}

internal actual val platformSupportsLinearF16: Boolean = true

internal actual val platformSupportsPixelBuffer: Boolean = false
