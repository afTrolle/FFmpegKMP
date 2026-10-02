// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class CancellableNativeRunTest {
    /** Stands in for FFmpeg: blocks its thread until the cancel flag it polls is raised. */
    private class FakeNativeRun {
        val cancelRequested = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        @Volatile
        var returned = false

        fun execute(): Int {
            started.complete(Unit)
            while (!cancelRequested.load()) {
                Thread.onSpinWait()
            }
            Thread.sleep(50) // The trailer write after the loop breaks.
            returned = true
            return 255
        }
    }

    @Test
    fun cancellingTheCallerRaisesTheNativeFlagAndWaitsForTheRunToReturn() = runTest {
        val native = FakeNativeRun()
        var returnedBeforeRethrow = false
        val caller = launch {
            try {
                runCancellableNative(Dispatchers.IO, cancelNative = { native.cancelRequested.store(true) }) {
                    native.execute()
                }
            } catch (cancellation: CancellationException) {
                returnedBeforeRethrow = native.returned
                throw cancellation
            }
        }
        native.started.await()

        caller.cancel()
        caller.join()

        assertTrue(native.cancelRequested.load())
        assertTrue(native.returned)
        assertTrue(returnedBeforeRethrow, "The cancellation left the caller before the native run returned")
    }

    @Test
    fun anUncancelledRunReturnsItsResult() = runTest {
        val result = runCancellableNative(Dispatchers.IO, cancelNative = { error("not expected") }) { 42 }

        assertEquals(42, result)
    }

    @Test
    fun aFailingRunRethrowsItsOwnFailureWithoutCancellingNative() = runTest {
        var cancelled = false

        assertFailsWith<IllegalStateException> {
            runCancellableNative(Dispatchers.IO, cancelNative = { cancelled = true }) {
                throw IllegalStateException("boom")
            }
        }
        assertFalse(cancelled)
    }

    @Test
    fun aCallerCancelledBeforeTheRunIsDispatchedSkipsTheRunAndStillRaisesTheFlag() = runTest {
        var ran = false
        var cancelled = false

        launch {
            cancel()
            assertFailsWith<CancellationException> {
                runCancellableNative(Dispatchers.IO, cancelNative = { cancelled = true }) { ran = true }
            }
        }.join()

        assertFalse(ran)
        // Harmless: the next run clears the flag before it starts.
        assertTrue(cancelled)
    }
}
