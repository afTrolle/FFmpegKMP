// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

/**
 * The resources an engine's `ffplaykmp_io_callback` serves as `ffmpegkmp:<id>`, with ids counting
 * from 1 in [mounts] order. Platform bindings only move bytes between native memory and the arrays
 * [dispatch] hands them. Calls into one resource run one at a time, also across tables that mount
 * it, since engines reading the same input share its replay cache.
 */
internal class MountTable(
    mounts: List<NativeMountedIo>,
    /** Players and decoders reopen and seek their input, so a Source is served from a replay cache. */
    replayableSources: Boolean = false,
) {
    private val resources = mounts.map { MountedResource(it.resource, replayableSources) }

    /**
     * Serves one callback for [resourceId]: [copyOut] receives bytes a READ produced, and [copyIn]
     * fills the array with the bytes a WRITE takes. Returns the operation's result, or
     * [IO_FAILURE] for an unknown resource or a failed operation.
     */
    fun dispatch(
        resourceId: Long,
        operation: Int,
        offset: Long,
        size: Long,
        copyOut: (ByteArray, Int) -> Unit,
        copyIn: (ByteArray, Int) -> Unit,
    ): Long {
        if (resourceId !in 1L..resources.size.toLong()) return IO_FAILURE
        return resources[(resourceId - 1L).toInt()].dispatch(operation, offset, size, copyOut, copyIn)
    }

    companion object {
        val Empty: MountTable = MountTable(emptyList())
    }
}

private class MountedResource(private val resource: NativeIoResource, replayableSource: Boolean) {
    private val io = MountedIo(resource, replayableSource)
    private var scratch = ByteArray(0)

    fun dispatch(
        operation: Int,
        offset: Long,
        size: Long,
        copyOut: (ByteArray, Int) -> Unit,
        copyIn: (ByteArray, Int) -> Unit,
    ): Long = resource.lock.withLock {
        try {
            when (operation) {
                IO_OPEN -> io.open(offset.toInt())
                IO_READ -> {
                    val length = size.checkedSize()
                    val bytes = scratch(length)
                    val count = io.read(offset, bytes, length)
                    if (count > 0) copyOut(bytes, count)
                    count.toLong()
                }
                IO_WRITE -> {
                    val length = size.checkedSize()
                    val bytes = scratch(length)
                    copyIn(bytes, length)
                    if (io.write(offset, bytes, length)) length.toLong() else IO_FAILURE
                }
                IO_SIZE -> io.size()
                IO_CLOSE -> io.close()
                else -> IO_FAILURE
            }
        } catch (_: Throwable) {
            IO_FAILURE
        }
    }

    /** Reused across calls: FFmpeg reads in similar-sized blocks. */
    private fun scratch(size: Int): ByteArray {
        if (scratch.size < size) scratch = ByteArray(size)
        return scratch
    }
}

private val NativeIoResource.lock: IoLock
    get() = when (this) {
        is NativeFileResource -> ioLock
        is NativeSourceResource -> ioLock
        is NativeSinkResource -> ioLock
    }

private fun Long.checkedSize(): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "Invalid native I/O size: $this" }
    return toInt()
}

/** Mutual exclusion between the native threads that call into one resource. */
internal expect class IoLock() {
    fun lock()
    fun unlock()
}

internal inline fun <T> IoLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
