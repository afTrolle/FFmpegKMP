// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import okio.FileHandle

/**
 * Requests that an `output(path, sink, staging = Staging())` mount be backed by a real temporary
 * file instead of `Sink`'s default forward-only write capability. FFmpeg writes to the temporary
 * file — which supports seeking, like a [FileHandle] mount — and the finished bytes are copied to
 * the sink once the command succeeds; the temporary file is always deleted afterward.
 *
 * Muxers that patch header fields after writing need this — the default, non-fragmented MP4/MOV
 * writer chief among them. Formats that mux forward-only (`-f mpegts`, or MP4 with
 * `-movflags frag_keyframe+empty_moov`) do not, and should mount a plain `Sink` instead to avoid
 * the extra temporary-file write. When the final destination is itself a seekable file, mounting
 * a real [FileHandle] output directly is more efficient than staging through a `Sink`.
 *
 * Not supported where there is no synchronous filesystem (the browser Kotlin/Wasm and Kotlin/JS
 * targets); requesting it there throws.
 */
public class Staging

/** A temporary, seekable file backing one staged output mount. Deleting it is `close()`. */
internal expect class TemporaryFile(label: String) : AutoCloseable {
    val fileHandle: FileHandle

    override fun close()
}

/** False only on Kotlin/JS and Kotlin/Wasm, where [Staging] has no synchronous filesystem to use. */
internal expect val stagingSupportedOnThisPlatform: Boolean
