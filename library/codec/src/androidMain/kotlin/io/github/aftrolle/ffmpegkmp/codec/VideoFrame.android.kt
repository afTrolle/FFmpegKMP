// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.codec

import android.graphics.Rect
import android.hardware.HardwareBuffer

/**
 * The `HardwareBuffer` that holds this frame, or null when none does: every hardware-decoded frame
 * of a [VideoOutput.GpuBuffers] decoder. Such a frame has no CPU-visible pixels, and its
 * [VideoFrame.format] is null. The buffer can be larger than the picture, which lies in
 * [hardwareBufferCrop]. Both are valid while this frame is open; the decoder reuses the buffer once
 * the frame's last reference closes.
 */
public val VideoFrame.hardwareBuffer: HardwareBuffer?
    get() = gpuBuffer?.handle as? HardwareBuffer

/** The part of [hardwareBuffer] that holds the picture, or null when the frame has no buffer. */
public val VideoFrame.hardwareBufferCrop: Rect?
    get() = gpuBuffer?.let { Rect(it.cropLeft, it.cropTop, it.cropRight, it.cropBottom) }
