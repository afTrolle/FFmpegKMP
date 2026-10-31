// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.core

import io.github.aftrolle.ffmpegkmp.bindings.NativeFileResource
import io.github.aftrolle.ffmpegkmp.bindings.NativeIoAccess
import io.github.aftrolle.ffmpegkmp.bindings.NativeIoResource
import io.github.aftrolle.ffmpegkmp.bindings.NativeMountedIo
import io.github.aftrolle.ffmpegkmp.bindings.NativeSinkResource
import io.github.aftrolle.ffmpegkmp.bindings.NativeSourceResource
import okio.FileHandle
import okio.Sink
import okio.Source

public class CommandIo private constructor(
    internal val mounts: List<Mount>,
) {
    internal data class Mount(val path: String, val resource: NativeIoResource, val staged: Boolean = false)

    public class Builder {
        private val mounts = mutableListOf<Mount>()
        private val paths = mutableSetOf<String>()

        public fun input(path: String, source: Source) {
            add(path, NativeSourceResource(source))
        }

        /**
         * Mounts a forward-only [Sink] output. With [staged], FFmpeg writes to a real temporary
         * file instead, which supports seeking like a [FileHandle] mount, and the finished bytes
         * are copied to the sink once the command succeeds; the temporary file is always deleted
         * afterward.
         *
         * Muxers that patch header fields after writing need [staged], the default,
         * non-fragmented MP4/MOV writer chief among them. Formats that mux forward-only
         * (`-f mpegts`, or MP4 with `-movflags frag_keyframe+empty_moov`) do not, and avoid the
         * extra temporary-file write without it. When the final destination is itself a seekable
         * file, mounting a real [FileHandle] output directly is more efficient than staging.
         *
         * Staging is not supported where there is no synchronous filesystem (the browser
         * Kotlin/Wasm and Kotlin/JS targets); requesting it there throws.
         */
        public fun output(path: String, sink: Sink, staged: Boolean = false) {
            require(!staged || stagingSupportedOnThisPlatform) {
                "Staged output is not supported in the browser (no synchronous filesystem). Mount a " +
                    "plain Sink with a streaming-friendly format (e.g. \"-movflags frag_keyframe+empty_moov\") " +
                    "instead."
            }
            add(path, NativeSinkResource(sink), staged)
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

        private fun add(path: String, resource: NativeIoResource, staged: Boolean = false) {
            requirePath(path)
            require(paths.add(path)) { "I/O path is already mounted: $path" }
            mounts += Mount(path, resource, staged)
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

/** The mounts as bindings see them, for long-lived clients such as FFplay that own them. */
@InternalFFmpegKmpApi
public fun CommandIo.toNativeMounts(): List<NativeMountedIo> =
    mounts.map { NativeMountedIo(it.path, it.resource) }
