// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo

/**
 * The browser's frames are RGBA8 in Kotlin memory, the bytes the FFmpeg worker transferred, and
 * Skia's are in its own heap: [Bitmap.installPixels] copies them across once, into new pixels
 * that replace the bitmap's earlier ones.
 */
internal fun VideoFrame.fillBitmap(bitmap: Bitmap, info: ImageInfo, format: FrameFormat) {
    check(this.format == format) { "The browser draws RGBA8 frames only, not ${this.format}" }
    checkNotNull(
        usePlanes { planes ->
            val plane = planes.single()
            val size = plane.rowBytes * height
            plane.bytes.useArray { array, offset ->
                val pixels = if (offset == 0 && array.size == size) array else array.copyOfRange(offset, offset + size)
                check(bitmap.installPixels(info, pixels, plane.rowBytes)) { "Skia rejected a ${width}x$height frame" }
            }
        },
    ) { "The frame has no pixels" }
}
