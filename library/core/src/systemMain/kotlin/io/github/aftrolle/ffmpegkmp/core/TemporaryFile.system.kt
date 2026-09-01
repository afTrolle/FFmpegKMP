// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import okio.FileHandle
import okio.FileSystem

// Shared by every target with a real synchronous filesystem (JVM, Android, and all Kotlin/Native
// targets) — okio.FileSystem.SYSTEM is common to all of them, so one file covers all three.
internal actual class TemporaryFile actual constructor(label: String) : AutoCloseable {
    private val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "$label.tmp"

    actual val fileHandle: FileHandle = FileSystem.SYSTEM.openReadWrite(path)

    actual override fun close() {
        fileHandle.close()
        FileSystem.SYSTEM.delete(path, mustExist = false)
    }
}

internal actual val stagingSupportedOnThisPlatform: Boolean = true
