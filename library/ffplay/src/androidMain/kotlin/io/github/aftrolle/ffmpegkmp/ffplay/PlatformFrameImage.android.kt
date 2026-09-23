// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame
import java.nio.ByteBuffer

internal actual fun NativeVideoFrame.toImageBitmap(): ImageBitmap = Bitmap
    .createBitmap(width, height, Bitmap.Config.ARGB_8888)
    .apply { copyPixelsFromBuffer(ByteBuffer.wrap(rgba)) }
    .asImageBitmap()
