// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

/**
 * FFplay-style clock math kept independent of wall-clock APIs so scheduling is deterministic in
 * tests and every platform backend uses the same pause, speed, and seek-serial behavior.
 */
internal class FFplayClock {
    private var ptsSeconds = 0.0
    private var updatedAtSeconds = 0.0
    private var speed = 1.0
    private var paused = true
    private var serial = 0

    fun set(ptsSeconds: Double, serial: Int, nowSeconds: Double) {
        this.ptsSeconds = ptsSeconds
        this.serial = serial
        updatedAtSeconds = nowSeconds
    }

    fun value(nowSeconds: Double, expectedSerial: Int): Double? {
        if (expectedSerial != serial) return null
        return if (paused) ptsSeconds else ptsSeconds + (nowSeconds - updatedAtSeconds) * speed
    }

    fun pause(nowSeconds: Double) {
        if (paused) return
        ptsSeconds += (nowSeconds - updatedAtSeconds) * speed
        updatedAtSeconds = nowSeconds
        paused = true
    }

    fun resume(nowSeconds: Double) {
        if (!paused) return
        updatedAtSeconds = nowSeconds
        paused = false
    }

    fun setSpeed(newSpeed: Double, nowSeconds: Double) {
        require(newSpeed > 0.0 && newSpeed.isFinite()) { "Clock speed must be finite and positive" }
        if (!paused) ptsSeconds += (nowSeconds - updatedAtSeconds) * speed
        updatedAtSeconds = nowSeconds
        speed = newSpeed
    }
}

/** Queue generation used to invalidate packets and frames that predate a seek. */
internal class FFplayQueueSerial {
    var value: Int = 0
        private set

    fun invalidate(): Int {
        value = if (value == Int.MAX_VALUE) 0 else value + 1
        return value
    }

    fun accepts(frameSerial: Int): Boolean = frameSerial == value
}
