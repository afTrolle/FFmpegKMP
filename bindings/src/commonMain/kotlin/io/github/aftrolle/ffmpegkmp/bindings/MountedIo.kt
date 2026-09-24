// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import okio.Buffer
import okio.Source

/** `ffmpegkmp_io_operation` / `ffmpegkmp_io_capability` values from ffmpegkmp_bridge.h. */
internal const val IO_OPEN: Int = 0
internal const val IO_READ: Int = 1
internal const val IO_WRITE: Int = 2
internal const val IO_SIZE: Int = 3
internal const val IO_CLOSE: Int = 4
internal const val IO_FAILURE: Long = -1L
private const val IO_CAP_READ = 1
private const val IO_CAP_WRITE = 2
private const val IO_CAP_SEEK = 4
private const val AVIO_FLAG_WRITE = 2

/** The `ffmpegkmp:` URL for mount [id]; keeping [path]'s extension helps FFmpeg pick the format. */
internal fun protocolUrl(id: Long, path: String): String {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    val suffix = name.substringAfterLast('.', missingDelimiterValue = "")
    return "ffmpegkmp:$id" + if (suffix.isEmpty()) "" else ".$suffix"
}

/**
 * Serves the `ffmpegkmp:` protocol's operations for one mounted resource. Platform bindings only
 * move bytes between native memory and the arrays passed here. Not thread-safe: bindings
 * serialize calls per resource.
 */
internal class MountedIo(
    private val resource: NativeIoResource,
    /** Players reopen and seek their input, so a Source is served from a replay cache. */
    private val replayableSource: Boolean = false,
) {
    private var truncated = false

    /** Returns the capability mask for FFmpeg's AVIO open [flags]. */
    fun open(flags: Int): Long = when (resource) {
        is NativeFileResource -> {
            if (resource.truncate && flags and AVIO_FLAG_WRITE != 0 && !truncated) {
                resource.fileHandle.resize(0L)
                truncated = true
            }
            when (resource.access) {
                NativeIoAccess.READ -> IO_CAP_READ or IO_CAP_SEEK
                NativeIoAccess.WRITE -> IO_CAP_WRITE or IO_CAP_SEEK
                NativeIoAccess.READ_WRITE -> IO_CAP_READ or IO_CAP_WRITE or IO_CAP_SEEK
            }
        }
        is NativeSourceResource -> IO_CAP_READ or if (replayableSource) IO_CAP_SEEK else 0
        is NativeSinkResource -> IO_CAP_WRITE
    }.toLong()

    /** Reads up to [size] bytes at [offset] into [into]; returns the count, 0 at the end. */
    fun read(offset: Long, into: ByteArray, size: Int): Int = when (resource) {
        is NativeFileResource -> resource.fileHandle.read(offset, into, 0, size).coerceAtLeast(0)
        is NativeSourceResource -> if (replayableSource) {
            resource.replay.read(offset, into, size)
        } else {
            val buffer = Buffer()
            val read = resource.source.read(buffer, size.toLong()).toInt()
            if (read > 0) buffer.readExactly(into, read)
            read.coerceAtLeast(0)
        }
        is NativeSinkResource -> -1
    }

    /** Writes [size] bytes of [from] at [offset]; false when the resource is read-only. */
    fun write(offset: Long, from: ByteArray, size: Int): Boolean {
        when (resource) {
            is NativeFileResource -> resource.fileHandle.write(offset, from, 0, size)
            is NativeSinkResource -> resource.sink.write(Buffer().write(from, 0, size), size.toLong())
            is NativeSourceResource -> return false
        }
        return true
    }

    fun size(): Long = when (resource) {
        is NativeFileResource -> resource.fileHandle.size()
        is NativeSourceResource -> if (replayableSource) resource.replay.knownSize ?: IO_FAILURE else IO_FAILURE
        is NativeSinkResource -> IO_FAILURE
    }

    fun close(): Long {
        when (resource) {
            is NativeFileResource -> if (resource.access != NativeIoAccess.READ) resource.fileHandle.flush()
            is NativeSinkResource -> resource.sink.flush()
            is NativeSourceResource -> Unit
        }
        return 0L
    }
}

/**
 * Buffers what has been read of a [Source] so it can be re-read at any offset: by a seek, or by a
 * later prepare of the same resource. Reads only as far as requested; the size stays unknown (and
 * the source unread) until the end has actually been reached.
 */
internal class ReplayableSource(private val source: Source) {
    private val cache = Buffer()
    private var exhausted = false

    val knownSize: Long? get() = cache.size.takeIf { exhausted }

    fun read(offset: Long, into: ByteArray, size: Int): Int {
        while (!exhausted && cache.size < offset + size) {
            if (source.read(cache, minOf(offset + size - cache.size, CHUNK)) <= 0L) exhausted = true
        }
        val available = (cache.size - offset).coerceIn(0, size.toLong()).toInt()
        if (available > 0) Buffer().also { cache.copyTo(it, offset, available.toLong()) }.readExactly(into, available)
        return available
    }

    /** Everything the source holds, for bindings that need the whole input up front. */
    fun readAll(): ByteArray {
        while (!exhausted) {
            if (source.read(cache, CHUNK) <= 0L) exhausted = true
        }
        return cache.snapshot().toByteArray()
    }

    private companion object {
        const val CHUNK = 32_768L
    }
}
