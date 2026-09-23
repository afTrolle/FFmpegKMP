// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeHandleGuardTest {
    @Test
    fun closeWaitsForAnInFlightCallBeforeReleasing() {
        val guard = NativeHandleGuard()
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val releasedWhileInside = AtomicBoolean(false)

        val caller = thread {
            guard.use({}) {
                inside.countDown()
                finish.await()
                releasedWhileInside.set(released.get())
            }
        }
        assertTrue(inside.await(2, TimeUnit.SECONDS))
        val closer = thread { guard.close { released.set(true) } }
        Thread.sleep(50)
        assertFalse(released.get(), "close must not release while a call is using the handle")
        finish.countDown()
        caller.join(2_000)
        closer.join(2_000)

        assertTrue(released.get())
        assertFalse(releasedWhileInside.get())
    }

    @Test
    fun callsAfterCloseTakeTheClosedPathAndReleaseRunsOnce() {
        val guard = NativeHandleGuard()
        var releases = 0
        guard.close { releases++ }
        guard.close { releases++ }

        assertFalse(guard.isOpen)
        assertEquals("closed", guard.use({ "closed" }) { "ran" })
        assertEquals(1, releases)
    }
}
