// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FFplayTimingTest {
    @Test
    fun clockFreezesResumesAndRejectsOldSerials() {
        val clock = FFplayClock()
        clock.set(10.0, serial = 2, nowSeconds = 100.0)
        clock.resume(nowSeconds = 100.0)
        assertEquals(11.5, clock.value(nowSeconds = 101.5, expectedSerial = 2))

        clock.pause(nowSeconds = 102.0)
        assertEquals(12.0, clock.value(nowSeconds = 500.0, expectedSerial = 2))
        assertNull(clock.value(nowSeconds = 500.0, expectedSerial = 1))
    }

    @Test
    fun clockSpeedChangeDoesNotJump() {
        val clock = FFplayClock()
        clock.set(0.0, serial = 0, nowSeconds = 0.0)
        clock.resume(nowSeconds = 0.0)
        clock.setSpeed(2.0, nowSeconds = 1.0)
        assertEquals(3.0, clock.value(nowSeconds = 2.0, expectedSerial = 0))
    }

    @Test
    fun seekSerialInvalidatesQueuedFrames() {
        val serial = FFplayQueueSerial()
        assertTrue(serial.accepts(0))
        assertEquals(1, serial.invalidate())
        assertFalse(serial.accepts(0))
        assertTrue(serial.accepts(1))
    }
}
