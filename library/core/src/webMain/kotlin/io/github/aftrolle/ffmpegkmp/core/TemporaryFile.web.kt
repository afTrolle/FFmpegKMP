// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import okio.FileHandle

internal actual class TemporaryFile actual constructor(label: String) : AutoCloseable {
    actual val fileHandle: FileHandle = throw UnsupportedOperationException(
        "Staged output is not supported in the browser (no synchronous filesystem).",
    )

    actual override fun close() = Unit
}

internal actual val stagingSupportedOnThisPlatform: Boolean = false
