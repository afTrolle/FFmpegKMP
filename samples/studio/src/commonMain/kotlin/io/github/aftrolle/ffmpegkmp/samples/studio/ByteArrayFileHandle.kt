// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import okio.FileHandle

/**
 * A seekable, read-only [FileHandle] backed by fully-buffered bytes. `CommandIo`'s `Source`/
 * `Sink` mounts are deliberately non-seekable, which breaks any MOV/MP4 whose moov atom sits
 * at the end of the file (the common case for non-"faststart" output, including most camera
 * HDR recordings and FFmpeg's own default MP4 muxer output) — this gives FFmpeg/FFprobe real
 * random access instead. Limited to file sizes under 2 GiB (`Int` offsets); fine for clip-length
 * video, not intended for arbitrarily large files.
 */
internal class ByteArrayFileHandle(private val bytes: ByteArray) : FileHandle(readWrite = false) {
    override fun protectedSize(): Long = bytes.size.toLong()

    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
        if (fileOffset >= bytes.size) return -1
        val count = minOf(byteCount.toLong(), bytes.size - fileOffset).toInt()
        bytes.copyInto(array, arrayOffset, fileOffset.toInt(), fileOffset.toInt() + count)
        return count
    }

    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
        throw UnsupportedOperationException("ByteArrayFileHandle is read-only")
    }

    override fun protectedResize(size: Long) {
        throw UnsupportedOperationException("ByteArrayFileHandle is read-only")
    }

    override fun protectedFlush() = Unit

    override fun protectedClose() = Unit
}
