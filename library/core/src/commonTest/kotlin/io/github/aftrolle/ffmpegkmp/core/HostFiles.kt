// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

/**
 * A path on the host filesystem that fftools opens itself, through its `file` protocol, or null
 * where commands have no such filesystem (the browser, where each command gets a fresh one).
 */
internal expect fun hostFilePath(name: String): String?

internal expect fun writeHostFile(path: String, bytes: ByteArray)

internal expect fun readHostFile(path: String): ByteArray

internal expect fun deleteHostFile(path: String)
