// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativeFrame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/**
 * Skia's memory in the browser is its own Wasm heap, so the frame is drawn there and read back in
 * three copies: the surface into a bitmap, the bitmap into a Kotlin array, and its rows into the frame.
 */
internal fun drawIntoFrame(frame: NativeFrame, info: ImageInfo, draw: (Canvas) -> Unit) {
    val rowBytes = info.width * info.bytesPerPixel
    val surface = Surface.makeRaster(info)
    val bitmap = Bitmap()
    val pixels = try {
        draw(surface.canvas)
        check(bitmap.allocPixels(info) && surface.readPixels(bitmap, 0, 0)) { "Skia could not read the frame back" }
        checkNotNull(bitmap.readPixels(info, rowBytes, 0, 0)) { "Skia could not read the frame back" }
    } finally {
        bitmap.close()
        surface.close()
    }
    frame.usePlanes { planes ->
        val plane = planes.single()
        val bytes = plane.memory as ByteArray
        for (row in 0 until info.height) pixels.copyInto(bytes, plane.offset + row * plane.rowBytes, row * rowBytes, (row + 1) * rowBytes)
    }
}

/** Kotlin/Wasm and Kotlin/JS raise this for a class or member the running Compose lacks. */
internal fun isLinkageFailure(failure: Throwable): Boolean = failure::class.simpleName == "IrLinkageError"
