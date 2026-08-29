// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

/** Reentrant lock serializing the synchronous commands owned by one player. */
internal expect class FFplayOperationLock() {
    fun <T> withLock(block: () -> T): T
}
