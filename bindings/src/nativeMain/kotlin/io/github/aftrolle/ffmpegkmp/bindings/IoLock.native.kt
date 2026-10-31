// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.concurrent.atomics.AtomicBoolean
import platform.posix.sched_yield

/** A spin lock that yields: calls into one resource are short, and rarely contended. */
internal actual class IoLock actual constructor() {
    private val busy = AtomicBoolean(false)

    actual fun lock() {
        while (!busy.compareAndSet(expectedValue = false, newValue = true)) sched_yield()
    }

    actual fun unlock() {
        busy.store(false)
    }
}
