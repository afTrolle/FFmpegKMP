// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi

/** In the browser, the bytes the FFmpeg worker transferred to the page. */
public actual class FrameBytes internal constructor(
    private val bytes: ByteArray,
    private val offset: Int,
    public actual val size: Int,
) {
    private var valid = true

    public actual operator fun get(index: Int): Byte {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Index $index outside a $size byte plane")
        return checked()[offset + index]
    }

    public actual fun copyInto(destination: ByteArray, offset: Int) {
        checked().copyInto(destination, offset, this.offset, this.offset + size)
    }

    /** Runs [block] on the backing array and this plane's offset in it, without a copy. */
    @InternalFFmpegKmpApi
    public fun <R> useArray(block: (array: ByteArray, offset: Int) -> R): R = block(checked(), offset)

    internal actual fun invalidate() {
        valid = false
    }

    private fun checked(): ByteArray {
        check(valid) { "The frame's planes are only valid inside VideoFrame.usePlanes" }
        return bytes
    }
}

internal actual fun frameBytes(memory: Any, offset: Int, size: Int): FrameBytes = FrameBytes(memory as ByteArray, offset, size)
