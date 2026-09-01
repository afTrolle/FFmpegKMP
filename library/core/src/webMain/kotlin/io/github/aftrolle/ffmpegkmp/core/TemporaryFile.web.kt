// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import okio.FileHandle

internal actual class TemporaryFile actual constructor(label: String) : AutoCloseable {
    actual val fileHandle: FileHandle = throw UnsupportedOperationException(
        "Staging is not supported in the browser (no synchronous filesystem). Mount a plain " +
            "Sink with a streaming-friendly format (e.g. \"-movflags frag_keyframe+empty_moov\") " +
            "instead of requesting Staging.",
    )

    actual override fun close() = Unit
}

internal actual val stagingSupportedOnThisPlatform: Boolean = false
