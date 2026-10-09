// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    // memcpy's size_t differs in width across the shared native targets (arm64_32 watchOS).
    kotlinx.cinterop.UnsafeNumber::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.aftrolle.ffmpegkmp.codec

import kotlin.concurrent.atomics.AtomicBoolean
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

public actual class FrameBytes internal constructor(private val memory: CPointer<ByteVar>, public actual val size: Int) {
    private val valid = AtomicBoolean(true)

    public actual operator fun get(index: Int): Byte {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Index $index outside a $size byte plane")
        return checked()[index]
    }

    public actual fun copyInto(destination: ByteArray, offset: Int) {
        if (offset < 0 || offset > destination.size - size) {
            throw IndexOutOfBoundsException("$size bytes do not fit ${destination.size} from $offset")
        }
        if (size == 0) return
        val source = checked()
        destination.usePinned { pinned -> memcpy(pinned.addressOf(offset), source, size.convert()) }
    }

    /** The plane's memory, which FFmpeg owns; like this object, valid only inside [VideoFrame.usePlanes]. */
    public val pointer: CPointer<ByteVar> get() = checked()

    internal actual fun invalidate() = valid.store(false)

    private fun checked(): CPointer<ByteVar> {
        check(valid.load()) { "The frame's planes are only valid inside VideoFrame.usePlanes" }
        return memory
    }
}

@Suppress("UNCHECKED_CAST")
internal actual fun frameBytes(memory: Any, offset: Int, size: Int): FrameBytes =
    FrameBytes(checkNotNull((memory as CPointer<ByteVar>) + offset), size)
