// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.test.Test
import kotlin.test.assertEquals

class ExecutionCancelGateTest {
    private val calls = mutableListOf<String>()
    private val gate = ExecutionCancelGate(
        resetNative = { calls += "reset" },
        cancelNative = { calls += "cancel" },
    )

    @Test
    fun cancelForTheRunningExecutionReachesNative() {
        gate.begin(1)
        gate.cancel(1)

        assertEquals(listOf("reset", "cancel"), calls)
    }

    @Test
    fun cancelIssuedBeforeItsExecutionBeginsIsAppliedAfterTheReset() {
        gate.cancel(1)
        gate.begin(1)

        assertEquals(listOf("reset", "cancel"), calls)
    }

    @Test
    fun cancelIssuedAfterItsExecutionEndedDoesNotReachTheNextOne() {
        gate.begin(1)
        gate.end()
        gate.cancel(1)
        gate.begin(2)

        assertEquals(listOf("reset", "reset"), calls)
    }

    @Test
    fun cancelNamingAnotherExecutionLeavesTheRunningOneAlone() {
        gate.begin(2)
        gate.cancel(1)
        gate.end()
        gate.begin(3)

        assertEquals(listOf("reset", "reset"), calls)
    }

    @Test
    fun staleCancelDoesNotDisplaceOneWaitingForTheNextExecution() {
        gate.begin(1)
        gate.end()
        gate.cancel(2)
        gate.cancel(1)
        gate.begin(2)

        assertEquals(listOf("reset", "reset", "cancel"), calls)
    }
}
