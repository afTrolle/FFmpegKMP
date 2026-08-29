// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import okio.Buffer

class BufferReadsTest {
    @Test
    fun exactReadCrossesEveryBufferSegment() {
        val expected = Random(42).nextBytes(3 * 32_768 + 1_337)
        val source = Buffer().write(expected)
        val actual = ByteArray(expected.size)

        source.readExactly(actual, actual.size)

        assertContentEquals(expected, actual)
    }

    @Test
    fun exactReadSupportsARequestedPrefix() {
        val expected = Random(7).nextBytes(40_003)
        val source = Buffer().write(expected).writeUtf8("not requested")
        val actual = ByteArray(expected.size)

        source.readExactly(actual, actual.size)

        assertContentEquals(expected, actual)
    }
}
