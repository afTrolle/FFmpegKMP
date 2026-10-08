// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import kotlin.math.pow
import kotlin.test.assertNotNull

/** Reads the 12-bit frame number the fixture generator draws as 16x16 cells in the top rows. */
internal fun VideoFrame.number(): Int = assertNotNull(
    usePlanes { planes ->
        (0 until 12).sumOf { bit ->
            if (planes.luma(format, x = (bit % 6) * 16 + 8, y = (bit / 6) * 16 + 8) > 128) 1 shl bit else 0
        }
    },
    "The frame has no pixels",
)

/**
 * The top 8 bits of the luma sample at ([x], [y]), or of green for RGB, the fixtures being grey;
 * for linear half floats, 0 or 255 either side of mid grey.
 */
internal fun VideoFrame.luma(x: Int, y: Int): Int = assertNotNull(usePlanes { it.luma(format, x, y) })

private fun List<FramePlane>.luma(format: FrameFormat?, x: Int, y: Int): Int {
    val plane = first()
    val row = y * plane.rowBytes
    fun byte(index: Int): Int = plane.bytes[index].toInt() and 0xff
    return when (assertNotNull(format, "A frame outside the model").layout) {
        PixelLayout.RGBA8, PixelLayout.BGRA8 -> byte(row + x * 4 + 1)
        PixelLayout.NV12, PixelLayout.YUV420P -> byte(row + x)
        // Little-endian 16-bit samples: P010 in the high bits, YUV420P10 in the low ten.
        PixelLayout.P010 -> byte(row + x * 2 + 1)
        PixelLayout.YUV420P10 -> (byte(row + x * 2) or (byte(row + x * 2 + 1) shl 8)) shr 2
        // Linear light: grey 128 of 255 is 0.22, well clear of black and white.
        PixelLayout.RGBA_F16 -> if (halfToDouble(byte(row + x * 8 + 2) or (byte(row + x * 8 + 3) shl 8)) > 0.22) 255 else 0
        PixelLayout.RGBA_1010102 -> error("No 8-bit luma in $format")
    }
}

/** Component [channel] of the pixel at ([x], [y]) of an RGBA_F16 frame. */
internal fun VideoFrame.linear(x: Int, y: Int, channel: Int = 0): Double = assertNotNull(
    usePlanes { planes ->
        val plane = planes.single()
        val offset = y * plane.rowBytes + (x * 4 + channel) * 2
        halfToDouble((plane.bytes[offset].toInt() and 0xff) or (plane.bytes[offset + 1].toInt() and 0xff shl 8))
    },
)

/** A packed copy of a frame's single plane: [width] × [height] pixels of [pixelBytes] each. */
internal fun VideoFrame.packed(pixelBytes: Int): ByteArray = assertNotNull(
    usePlanes { planes ->
        val plane = planes.single()
        val rowBytes = width * pixelBytes
        val bytes = ByteArray(plane.bytes.size).also { plane.bytes.copyInto(it) }
        ByteArray(rowBytes * height).also { packed ->
            for (row in 0 until height) bytes.copyInto(packed, row * rowBytes, row * plane.rowBytes, row * plane.rowBytes + rowBytes)
        }
    },
)

internal fun halfToDouble(bits: Int): Double {
    val exponent = bits shr 10 and 0x1f
    val mantissa = bits and 0x3ff
    val magnitude = when (exponent) {
        0 -> mantissa / 1024.0 * 2.0.pow(-14)
        0x1f -> Double.POSITIVE_INFINITY
        else -> (1 + mantissa / 1024.0) * 2.0.pow(exponent - 15)
    }
    return if (bits and 0x8000 != 0) -magnitude else magnitude
}
