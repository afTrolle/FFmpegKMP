// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import okio.FileSystem
import okio.Path.Companion.toPath

internal actual fun hostFilePath(name: String): String? =
    (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / name).toString()

internal actual fun writeHostFile(path: String, bytes: ByteArray) {
    FileSystem.SYSTEM.write(path.toPath()) { write(bytes) }
}

internal actual fun readHostFile(path: String): ByteArray =
    FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }

internal actual fun deleteHostFile(path: String) {
    FileSystem.SYSTEM.delete(path.toPath(), mustExist = false)
}
