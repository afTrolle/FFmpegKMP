// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import java.nio.ByteBuffer

public actual class FrameBytes internal constructor(private val buffer: ByteBuffer) {
    @Volatile
    private var valid = true

    public actual val size: Int = buffer.capacity()

    public actual operator fun get(index: Int): Byte = checked().get(index)

    public actual fun copyInto(destination: ByteArray, offset: Int) {
        checked().duplicate().apply { position(0) }.get(destination, offset, size)
    }

    /**
     * The plane as a read-only direct buffer over FFmpeg's memory. Unlike this object, the buffer
     * cannot be invalidated: reading it after [VideoFrame.usePlanes] returns reads freed memory.
     */
    public fun asByteBuffer(): ByteBuffer = checked().asReadOnlyBuffer()

    internal actual fun invalidate() {
        valid = false
    }

    private fun checked(): ByteBuffer {
        check(valid) { "The frame's planes are only valid inside VideoFrame.usePlanes" }
        return buffer
    }
}

internal actual fun frameBytes(memory: Any, offset: Int, size: Int): FrameBytes =
    FrameBytes((memory as ByteBuffer).duplicate().apply { position(offset).limit(offset + size) }.slice())
