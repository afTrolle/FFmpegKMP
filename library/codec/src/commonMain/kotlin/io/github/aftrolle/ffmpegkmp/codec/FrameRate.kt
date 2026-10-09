// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** Frames per second as a fraction: `FrameRate(30)`, or `FrameRate(30_000, 1_001)` for NTSC's 29.97. */
public data class FrameRate(val numerator: Int, val denominator: Int = 1) {
    init {
        require(numerator > 0 && denominator > 0) { "A frame rate must be positive: $numerator/$denominator" }
    }

    /** When frame [index] starts, to the nearest nanosecond, without the error a rounded interval builds up. */
    public fun timeOf(index: Long): Duration {
        require(index >= 0) { "Frame index must not be negative: $index" }
        val ticks = index * denominator
        val seconds = ticks / numerator
        val remainder = ticks % numerator
        return (seconds * NANOS_PER_SECOND + (remainder * NANOS_PER_SECOND + numerator / 2) / numerator).nanoseconds
    }

    override fun toString(): String = if (denominator == 1) "$numerator fps" else "$numerator/$denominator fps"

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
