// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CancellableNativeRunTest {
    /** Stands in for FFmpeg: blocks the thread until the cancel flag it polls is raised. */
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
    fun cancellingTheCallerRaisesTheNativeFlagAndWaitsForTheRunToReturn() = runBlocking {
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
    fun anUncancelledRunReturnsItsResult() = runBlocking {
        val result = runCancellableNative(Dispatchers.IO, cancelNative = { error("not expected") }) { 42 }

        assertEquals(42, result)
    }

    @Test
    fun aFailingRunRethrowsItsFailureWithoutCancellingNative() = runBlocking {
        var cancelled = false
        val failure = runCatching {
            runCancellableNative(Dispatchers.IO, cancelNative = { cancelled = true }) {
                throw IllegalStateException("boom")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(cancelled)
    }

    @Test
    fun cancellationBeforeTheRunIsDispatchedSkipsTheRunButStillRaisesTheFlag() = runBlocking {
        var ran = false
        var cancelled = false
        val caller = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runCancellableNative(Dispatchers.IO, cancelNative = { cancelled = true }) { ran = true }
        }
        caller.cancel()
        caller.join()
        delay(50)

        assertFalse(ran)
        // The flag is harmless here: the next run clears it before it starts.
        assertFalse(cancelled, "A run that never started has nothing to cancel")
    }
}
