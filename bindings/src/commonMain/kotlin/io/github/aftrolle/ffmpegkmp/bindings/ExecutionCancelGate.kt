// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.concurrent.atomics.AtomicBoolean

/**
 * Delivers each cancel to the execution it names on a native context reused across executions.
 *
 * The native flag belongs to the context, not to a run, and a cancel can arrive before its run
 * has entered native code or after it has left. An early one has to wait for the run it names;
 * a late one must not fall on whichever run comes next. Every step happens under one lock, so a
 * cancel is either applied to its own run, after that run's reset, or dropped.
 */
internal class ExecutionCancelGate(
    private val resetNative: () -> Unit,
    private val cancelNative: () -> Unit,
) {
    private val held = AtomicBoolean(false)
    private var activeId: Long? = null
    private val waitingIds = mutableSetOf<Long>()

    fun begin(executionId: Long) = withLock {
        resetNative()
        activeId = executionId
        if (executionId in waitingIds) cancelNative()
        // Runs are serialized, so anything else still waiting names a run that already ended.
        waitingIds.clear()
    }

    fun end() = withLock { activeId = null }

    fun cancel(executionId: Long) = withLock {
        if (activeId == executionId) cancelNative() else waitingIds += executionId
    }

    // Spun rather than parked: each section is a few field writes and a native atomic store.
    private inline fun withLock(block: () -> Unit) {
        while (!held.compareAndSet(expectedValue = false, newValue = true)) {
            // Wait for the other section to leave.
        }
        try {
            block()
        } finally {
            held.store(false)
        }
    }
}
