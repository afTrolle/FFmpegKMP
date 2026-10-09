// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

internal actual fun hostFilePath(name: String): String? = null

internal actual fun writeHostFile(path: String, bytes: ByteArray): Unit = unsupported()

internal actual fun readHostFile(path: String): ByteArray = unsupported()

internal actual fun deleteHostFile(path: String): Unit = unsupported()

private fun unsupported(): Nothing = throw UnsupportedOperationException("Browsers have no host filesystem")
