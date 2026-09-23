// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

internal actual class FFplayOperationLock {
    actual fun <T> withLock(block: () -> T): T = block()
}
