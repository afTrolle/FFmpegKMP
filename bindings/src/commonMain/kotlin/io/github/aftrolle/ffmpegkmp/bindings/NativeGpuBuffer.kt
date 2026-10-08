// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch

/**
 * A frame's pixels in GPU memory a platform owns, with no CPU-visible copy: on Android a
 * `HardwareBuffer` from a decoder's `ImageReader`. The buffer can be larger than the picture, which
 * lies in its crop rectangle, from [cropLeft] and [cropTop] up to [cropRight] and [cropBottom].
 *
 * It starts with one reference; [retain] adds one and [release] drops one, and the last
 * [release] runs [onRelease] once, which hands the buffer back to whoever lent it.
 */
@InternalFFmpegKmpApi
public class NativeGpuBuffer(
    /** The platform's buffer: an `android.hardware.HardwareBuffer` on Android. */
    public val handle: Any,
    /** Identifies the buffer, the same for each frame it holds in turn: `HardwareBuffer.getId()` on Android. */
    public val id: Long,
    public val cropLeft: Int,
    public val cropTop: Int,
    public val cropRight: Int,
    public val cropBottom: Int,
    /** The source's HDR type, which says how to read the pixels: [NativePlayerHdrType.SDR] for sRGB. */
    public val hdrType: NativePlayerHdrType,
    private val onRelease: () -> Unit,
) {
    private val references = AtomicInt(1)

    /** Another reference to this buffer, which needs a [release] of its own. */
    public fun retain(): NativeGpuBuffer {
        while (true) {
            val count = references.load()
            check(count > 0) { "The GPU buffer is released" }
            if (references.compareAndSet(count, count + 1)) return this
        }
    }

    public fun release() {
        val count = references.decrementAndFetch()
        check(count >= 0) { "The GPU buffer is released more often than retained" }
        if (count == 0) onRelease()
    }
}
