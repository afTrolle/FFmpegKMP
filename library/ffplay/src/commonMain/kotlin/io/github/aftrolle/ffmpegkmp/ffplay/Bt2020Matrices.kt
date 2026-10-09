// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

/**
 * The 3×3 matrices between BT.2020 and sRGB (BT.709) linear light, row-major, which the GPU
 * shaders decoding and encoding 10-bit frames take as their constants: each is the other's inverse.
 */
internal object Bt2020Matrices {
    val toSrgb: DoubleArray = doubleArrayOf(
        1.660491, -0.587641, -0.072850,
        -0.124550, 1.132900, -0.008349,
        -0.018151, -0.100579, 1.118730,
    )

    val fromSrgb: DoubleArray = doubleArrayOf(
        0.627404, 0.329283, 0.043313,
        0.069097, 0.919540, 0.011362,
        0.016392, 0.088013, 0.895595,
    )

    /** [matrix] applied to the AGSL `float3` [input], as three `dot` lines making a `float3`. */
    fun agsl(matrix: DoubleArray, input: String): String = (0 until 3).joinToString(",\n", "float3(\n", "\n)") { row ->
        val cells = (0 until 3).joinToString(", ") { column -> matrix[row * 3 + column].toString() }
        "    dot(float3($cells), $input)"
    }
}
