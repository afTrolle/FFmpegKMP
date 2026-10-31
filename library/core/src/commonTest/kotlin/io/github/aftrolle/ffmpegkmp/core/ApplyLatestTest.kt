// SPDX-License-Identifier: Apache-2.0
@file:OptIn(InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ApplyLatestTest {
    @Test
    fun appliesAnUnchangedValueOnce() {
        val applied = mutableListOf<Int>()
        applyLatest({ 1 }, applied::add)
        assertEquals(listOf(1), applied)
    }

    @Test
    fun reappliesAValueThatChangedDuringTheWrite() {
        var current = 1
        val applied = mutableListOf<Int>()
        // Another writer's update lands while the first value is being applied.
        applyLatest({ current }) { value ->
            applied += value
            if (value == 1) current = 2
        }
        assertEquals(listOf(1, 2), applied)
    }
}
