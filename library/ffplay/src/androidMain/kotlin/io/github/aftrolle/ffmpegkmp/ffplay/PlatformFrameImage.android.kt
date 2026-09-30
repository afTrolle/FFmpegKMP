// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer

internal actual fun rasterImage(
    pixels: ByteArray,
    width: Int,
    height: Int,
    stride: Int,
    linearF16: Boolean,
): ImageBitmap {
    val bitmap = if (linearF16) {
        check(platformSupportsLinearF16) { "RGBA_F16 bitmaps need Android 8.0 (API 26)" }
        Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.RGBA_F16,
            true,
            ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB),
        )
    } else {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }
    return bitmap.apply { copyPixelsFromBuffer(ByteBuffer.wrap(pixels)) }.asImageBitmap()
}

internal actual val platformSupportsLinearF16: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

internal actual val platformSupportsPixelBuffer: Boolean = false
