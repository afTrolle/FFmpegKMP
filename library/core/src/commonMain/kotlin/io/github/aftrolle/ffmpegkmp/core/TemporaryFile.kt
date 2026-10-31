// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import okio.FileHandle

/** A temporary, seekable file backing one staged output mount. Deleting it is `close()`. */
internal expect class TemporaryFile(label: String) : AutoCloseable {
    val fileHandle: FileHandle

    override fun close()
}

/** False only on Kotlin/JS and Kotlin/Wasm, where staging has no synchronous filesystem to use. */
internal expect val stagingSupportedOnThisPlatform: Boolean
