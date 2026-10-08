// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.codec

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import platform.CoreVideo.CVPixelBufferRef

/**
 * The `CVPixelBufferRef` that holds this frame, or null when none does: VideoToolbox's own for a
 * hardware-decoded frame, and an IOSurface-backed pooled one for frames in [PixelLayout.NV12],
 * [PixelLayout.P010], [PixelLayout.BGRA8] and [PixelLayout.RGBA_F16], including every frame of a
 * [VideoOutput.Memory] decoder without a format. Its colour attachments match [VideoFrame.format].
 *
 * The buffer is valid while this frame is open; `CVPixelBufferRetain` it to keep it longer. Lock
 * it before reading its planes, or read them through [VideoFrame.usePlanes], which locks it.
 */
public val VideoFrame.cvPixelBuffer: CVPixelBufferRef?
    get() = useNative { native -> (native?.pixelBuffer as COpaquePointer?)?.reinterpret() }
