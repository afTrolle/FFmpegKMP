// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import okio.FileHandle
import okio.sink
import okio.source

/**
 * Mounts an Android descriptor for input. This function takes ownership of [descriptor].
 * Pipe-backed descriptors are mounted as streams; seekable descriptors use Okio random access.
 */
public fun CommandIo.Builder.input(path: String, descriptor: ParcelFileDescriptor) {
    if (descriptor.isSeekable()) {
        input(path, AndroidFileHandle(descriptor, readWrite = false))
    } else {
        input(path, ParcelFileDescriptor.AutoCloseInputStream(descriptor).source())
    }
}

/**
 * Mounts an Android asset descriptor while preserving its start offset and declared length.
 * This function takes ownership of [descriptor].
 */
public fun CommandIo.Builder.input(path: String, descriptor: AssetFileDescriptor) {
    val parcelDescriptor = descriptor.parcelFileDescriptor
    if (parcelDescriptor.isSeekable()) {
        input(
            path,
            AndroidFileHandle(
                parcelDescriptor,
                readWrite = false,
                baseOffset = descriptor.startOffset,
                declaredSize = descriptor.length.takeIf { it >= 0L },
            ),
        )
    } else {
        input(path, descriptor.createInputStream())
    }
}

/**
 * Mounts an Android descriptor for output. This function takes ownership of [descriptor].
 * Pipe-backed descriptors are mounted as streams; seekable descriptors use Okio random access.
 */
public fun CommandIo.Builder.output(
    path: String,
    descriptor: ParcelFileDescriptor,
    truncate: Boolean = true,
) {
    if (descriptor.isSeekable()) {
        output(path, AndroidFileHandle(descriptor, readWrite = true), truncate)
    } else {
        output(path, ParcelFileDescriptor.AutoCloseOutputStream(descriptor).sink())
    }
}

/** Takes ownership of a seekable descriptor and mounts it for random reads and writes. */
public fun CommandIo.Builder.readWrite(
    path: String,
    descriptor: ParcelFileDescriptor,
    truncate: Boolean = false,
) {
    require(descriptor.isSeekable()) { "Read/write descriptor is not seekable" }
    readWrite(path, AndroidFileHandle(descriptor, readWrite = true), truncate)
}

/** Duplicates [descriptor]; the caller retains ownership of the original descriptor. */
public fun CommandIo.Builder.input(path: String, descriptor: FileDescriptor) {
    input(path, ParcelFileDescriptor.dup(descriptor))
}

/** Duplicates [descriptor]; the caller retains ownership of the original descriptor. */
public fun CommandIo.Builder.output(path: String, descriptor: FileDescriptor, truncate: Boolean = true) {
    output(path, ParcelFileDescriptor.dup(descriptor), truncate)
}

/** Duplicates [descriptor]; the caller retains ownership of the original descriptor. */
public fun CommandIo.Builder.readWrite(path: String, descriptor: FileDescriptor, truncate: Boolean = false) {
    readWrite(path, ParcelFileDescriptor.dup(descriptor), truncate)
}

/** Duplicates [fd]; the caller retains ownership of the original integer descriptor. */
public fun CommandIo.Builder.input(path: String, fd: Int) {
    input(path, checkNotNull(ParcelFileDescriptor.fromFd(fd)))
}

/** Duplicates [fd]; the caller retains ownership of the original integer descriptor. */
public fun CommandIo.Builder.output(path: String, fd: Int, truncate: Boolean = true) {
    output(path, checkNotNull(ParcelFileDescriptor.fromFd(fd)), truncate)
}

/** Duplicates [fd]; the caller retains ownership of the original integer descriptor. */
public fun CommandIo.Builder.readWrite(path: String, fd: Int, truncate: Boolean = false) {
    readWrite(path, checkNotNull(ParcelFileDescriptor.fromFd(fd)), truncate)
}

/** Opens [uri] through Android's content resolver and mounts it for input. */
public fun CommandIo.Builder.input(path: String, resolver: ContentResolver, uri: Uri) {
    val descriptor = resolver.openAssetFileDescriptor(uri, "r")
        ?: error("Content provider returned no descriptor for $uri")
    input(path, descriptor)
}

/** Opens [uri] through Android's content resolver and mounts it for output. */
public fun CommandIo.Builder.output(
    path: String,
    resolver: ContentResolver,
    uri: Uri,
    truncate: Boolean = true,
) {
    val descriptor = (runCatching {
        resolver.openFileDescriptor(uri, if (truncate) "rwt" else "rw")
    }.getOrNull() ?: if (truncate) resolver.openFileDescriptor(uri, "wt") else null)
        ?: error("Content provider returned no descriptor for $uri")
    // The ContentResolver mode already applies truncation where requested.
    output(path, descriptor, truncate = false)
}

/** Opens [uri] through Android's content resolver for random reads and writes. */
public fun CommandIo.Builder.readWrite(
    path: String,
    resolver: ContentResolver,
    uri: Uri,
    truncate: Boolean = false,
) {
    val descriptor = resolver.openFileDescriptor(uri, if (truncate) "rwt" else "rw")
        ?: error("Content provider returned no descriptor for $uri")
    readWrite(path, descriptor, truncate = false)
}

/** Mounts a Java input stream through Okio. The command session closes the stream. */
public fun CommandIo.Builder.input(path: String, stream: InputStream) {
    input(path, stream.source())
}

/** Mounts a Java output stream through Okio. The command session closes the stream. */
public fun CommandIo.Builder.output(path: String, stream: OutputStream) {
    output(path, stream.sink())
}

private fun ParcelFileDescriptor.isSeekable(): Boolean = try {
    Os.lseek(fileDescriptor, 0L, OsConstants.SEEK_CUR)
    true
} catch (_: Throwable) {
    false
}

private class AndroidFileHandle(
    private val descriptor: ParcelFileDescriptor,
    readWrite: Boolean,
    private val baseOffset: Long = 0L,
    private val declaredSize: Long? = null,
) : FileHandle(readWrite) {
    private val fd: FileDescriptor
        get() = descriptor.fileDescriptor

    override fun protectedRead(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ): Int {
        val count = declaredSize
            ?.let { remaining -> (remaining - fileOffset).coerceIn(0L, byteCount.toLong()).toInt() }
            ?: byteCount
        if (count == 0) return -1
        val read = Os.pread(fd, array, arrayOffset, count, baseOffset + fileOffset)
        return if (read == 0) -1 else read
    }

    override fun protectedWrite(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ) {
        var written = 0
        while (written < byteCount) {
            val count = Os.pwrite(
                fd,
                array,
                arrayOffset + written,
                byteCount - written,
                baseOffset + fileOffset + written,
            )
            check(count > 0) { "pwrite returned $count" }
            written += count
        }
    }

    override fun protectedFlush() {
        Os.fsync(fd)
    }

    override fun protectedResize(size: Long) {
        require(baseOffset == 0L && declaredSize == null) { "Cannot resize a bounded asset descriptor" }
        Os.ftruncate(fd, size)
    }

    override fun protectedSize(): Long = declaredSize ?: (Os.fstat(fd).st_size - baseOffset).coerceAtLeast(0L)

    override fun protectedClose() {
        descriptor.close()
    }
}
