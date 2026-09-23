// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal actual class FFplayOperationLock {
    private val lock = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
