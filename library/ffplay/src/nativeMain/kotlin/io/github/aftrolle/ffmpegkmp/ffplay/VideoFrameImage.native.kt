// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.wrapNativeFrame
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.toNative
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.interpretCPointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo

internal actual val bitmapsScale: Boolean = true

/**
 * Converts the frame straight into the bitmap's own pixels (`peekPixels().addr`), which the first
 * call allocates, scaling it to [info]'s size in the same pass.
 */
internal fun VideoFrame.fillBitmap(bitmap: Bitmap, info: ImageInfo, format: FrameFormat) {
    if (bitmap.isNull) {
        check(bitmap.allocPixels(info)) { "Skia could not allocate a ${info.width}x${info.height} ${info.colorType} bitmap" }
    }
    val pixels = checkNotNull(bitmap.peekPixels()) { "Skia has no pixels for a ${info.width}x${info.height} bitmap" }
    try {
        val address = checkNotNull(interpretCPointer<ByteVar>(pixels.addr)) { "Skia has no pixels for a bitmap" }
        VideoFrame.of(wrapNativeFrame(format.toNative(), info.width, info.height, address, pixels.rowBytes), pts, duration)
            .use { target -> convertInto(target) }
    } finally {
        pixels.close()
    }
}
