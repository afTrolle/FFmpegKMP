// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.core

import io.github.aftrolle.ffmpegkmp.bindings.NativeFileResource
import io.github.aftrolle.ffmpegkmp.bindings.NativeIoAccess
import io.github.aftrolle.ffmpegkmp.bindings.NativeIoResource
import io.github.aftrolle.ffmpegkmp.bindings.NativeSinkResource
import io.github.aftrolle.ffmpegkmp.bindings.NativeSourceResource
import okio.FileHandle
import okio.Sink
import okio.Source

public class CommandIo private constructor(
    internal val mounts: List<Mount>,
) {
    internal data class Mount(val path: String, val resource: NativeIoResource, val staging: Boolean = false)

    public class Builder {
        private val mounts = mutableListOf<Mount>()
        private val paths = mutableSetOf<String>()

        public fun input(path: String, source: Source) {
            add(path, NativeSourceResource(source))
        }

        public fun output(path: String, sink: Sink) {
            add(path, NativeSinkResource(sink))
        }

        /**
         * Mounts a [Sink] output backed by a real temporary file instead of `Sink`'s default
         * forward-only write capability. See [Staging] for when this is (and isn't) needed.
         */
        public fun output(path: String, sink: Sink, staging: Staging) {
            add(path, NativeSinkResource(sink), staging = true)
        }

        /** Mounts an Okio file handle for seekable, random-access input. */
        public fun input(path: String, fileHandle: FileHandle) {
            add(path, NativeFileResource(fileHandle, NativeIoAccess.READ, truncate = false))
        }

        /** Mounts an Okio file handle for seekable, random-access output. */
        public fun output(path: String, fileHandle: FileHandle, truncate: Boolean = true) {
            require(fileHandle.readWrite) { "Output file handle must be opened for reading and writing" }
            add(path, NativeFileResource(fileHandle, NativeIoAccess.WRITE, truncate))
        }

        /** Mounts one Okio file handle for both random reads and random writes. */
        public fun readWrite(path: String, fileHandle: FileHandle, truncate: Boolean = false) {
            require(fileHandle.readWrite) { "Read/write file handle must be opened for reading and writing" }
            add(path, NativeFileResource(fileHandle, NativeIoAccess.READ_WRITE, truncate))
        }

        private fun add(path: String, resource: NativeIoResource, staging: Boolean = false) {
            requirePath(path)
            require(paths.add(path)) { "I/O path is already mounted: $path" }
            mounts += Mount(path, resource, staging)
        }

        internal fun build(): CommandIo = CommandIo(mounts.toList())

        private fun requirePath(path: String) {
            require(path.isNotBlank()) { "Mounted I/O path must not be blank" }
            require('\u0000' !in path) { "Mounted I/O path must not contain NUL" }
        }
    }

    public companion object {
        public val Empty: CommandIo = CommandIo(emptyList())

        public operator fun invoke(block: Builder.() -> Unit): CommandIo =
            Builder().apply(block).build()
    }
}
