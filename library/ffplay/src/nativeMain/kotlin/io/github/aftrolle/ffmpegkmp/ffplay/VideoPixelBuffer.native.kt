// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import platform.CoreVideo.CVPixelBufferRef

internal actual val platformSupportsPixelBuffer: Boolean = true

/**
 * The `CVPixelBufferRef`, valid while the [VideoFrame] it came with is open (for
 * [VideoDecoder.current], until the decoder's next call). `CVPixelBufferRetain` it to keep it longer.
 */
public val VideoPixelBuffer.cvPixelBuffer: CVPixelBufferRef
    get() = (native.handle as COpaquePointer).reinterpret()
