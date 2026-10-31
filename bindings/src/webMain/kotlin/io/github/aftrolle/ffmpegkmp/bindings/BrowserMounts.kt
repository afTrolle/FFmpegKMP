// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import okio.Buffer
import okio.FileHandle

/**
 * The whole of what a mount serves, which the browser's worker takes as bytes: nothing for an
 * output it starts afresh (a sink, or a file it truncates), a file's bytes read straight into one
 * array, and a source's through its replay cache, so preparing the same source again still has
 * its bytes.
 */
internal fun NativeMountedIo.readBytesForBrowser(): ByteArray = when (val resource = resource) {
    is NativeFileResource -> if (resource.access == NativeIoAccess.WRITE && resource.truncate) {
        ByteArray(0)
    } else {
        resource.fileHandle.readAll()
    }
    is NativeSourceResource -> resource.replay.readAll()
    is NativeSinkResource -> ByteArray(0)
}

/**
 * Writes back [bytes], the whole output the browser's worker made for this mount, and flushes it:
 * over a file from its start, truncated to them when the mount truncates, or to a sink. A source
 * takes nothing back.
 */
internal fun NativeIoResource.writeBrowserOutput(bytes: ByteArray) {
    when (this) {
        is NativeFileResource -> {
            fileHandle.write(0L, bytes, 0, bytes.size)
            if (truncate) fileHandle.resize(bytes.size.toLong())
            fileHandle.flush()
        }
        is NativeSinkResource -> {
            sink.write(Buffer().write(bytes), bytes.size.toLong())
            sink.flush()
        }
        is NativeSourceResource -> Unit
    }
}

private fun FileHandle.readAll(): ByteArray {
    val size = size()
    require(size <= Int.MAX_VALUE) { "A browser mount holds at most 2 GiB: $size bytes" }
    val bytes = ByteArray(size.toInt())
    var read = 0
    while (read < bytes.size) {
        val count = read(read.toLong(), bytes, read, bytes.size - read)
        if (count == -1) return bytes.copyOf(read)
        read += count
    }
    return bytes
}
