// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Keeps a native handle alive while calls are using it. Calls that may run on other threads
 * (setters, abort, cancel) enter through [use]; [close] marks the handle closed, waits for calls
 * already inside, and only then frees it, so no call can touch freed native memory.
 */
internal class NativeHandleGuard {
    private val closed = AtomicBoolean(false)
    private val active = AtomicInt(0)

    val isOpen: Boolean get() = !closed.load()

    /** Runs [block] unless the handle is closed, in which case it returns [whenClosed]. */
    fun <T> use(whenClosed: () -> T, block: () -> T): T {
        active.incrementAndFetch()
        try {
            return if (closed.load()) whenClosed() else block()
        } finally {
            active.decrementAndFetch()
        }
    }

    /** Runs [release] exactly once, after every call that entered before closing has returned. */
    fun close(release: () -> Unit) {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        while (active.load() > 0) {
            // In-flight calls are short native setters; wait them out rather than free under them.
        }
        release()
    }
}
