// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativeFrame
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/** Draws straight into the frame's memory, which Skia wraps without a copy. */
internal fun drawIntoFrame(frame: NativeFrame, info: ImageInfo, draw: (Canvas) -> Unit) {
    frame.useWritablePixels { address, rowBytes ->
        Surface.makeRasterDirect(info, address, rowBytes).use { surface -> draw(surface.canvas) }
    }
}

/** A class or member the library was built against that the running Compose lacks. */
internal fun isLinkageFailure(failure: Throwable): Boolean = failure is LinkageError
