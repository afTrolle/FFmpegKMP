// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The constants the decode and encode shaders share: one matrix undoes the other, and white stays white through each. */
class Bt2020MatricesTest {
    @Test
    fun theMatricesAreEachOthersInverse() {
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                val cell = (0 until 3).sumOf { Bt2020Matrices.fromSrgb[row * 3 + it] * Bt2020Matrices.toSrgb[it * 3 + column] }
                val expected = if (row == column) 1.0 else 0.0
                assertTrue(abs(cell - expected) < 2e-5, "cell ($row, $column) of the product is $cell")
            }
        }
    }

    @Test
    fun whiteStaysWhite() {
        for (matrix in listOf(Bt2020Matrices.toSrgb, Bt2020Matrices.fromSrgb)) {
            for (row in 0 until 3) {
                val sum = (0 until 3).sumOf { matrix[row * 3 + it] }
                assertTrue(abs(sum - 1.0) < 2e-5, "row $row sums to $sum")
            }
        }
    }

    @Test
    fun theAgslNamesEveryCellInRowOrder() {
        val lines = Bt2020Matrices.agsl(Bt2020Matrices.fromSrgb, "light")
        assertTrue("dot(float3(0.627404, 0.329283, 0.043313), light)" in lines, lines)
        assertTrue("dot(float3(0.016392, 0.088013, 0.895595), light)" in lines, lines)
        assertTrue(lines.startsWith("float3(") && lines.endsWith(")"), lines)
    }
}
