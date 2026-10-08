// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

/** `FrameFormat.Rgba8`: what Compose draws and the software player hands the page. */
internal val BROWSER_RGBA8 = NativeFrameFormat(layout = 0, primaries = 0, transfer = 0, matrix = 0, range = 1)

/**
 * A frame the page holds in [bytes]: one the worker decoded and copied once into a buffer it
 * transferred, or one drawn into here. [planes] lie in [bytes] at their offsets. The page owns
 * the bytes, so references share them and closing releases nothing. Converting is copying,
 * between RGBA8 frames only: the browser has no FFmpeg converter on the page.
 */
internal class BrowserFrame(
    private val bytes: ByteArray,
    override val format: NativeFrameFormat?,
    override val width: Int,
    override val height: Int,
    private val planes: List<Plane>,
) : NativeFrame {
    class Plane(val offset: Int, val rowBytes: Int, val rows: Int)

    init {
        require(width > 0 && height > 0 && planes.isNotEmpty()) { "A ${width}x$height frame needs a plane" }
        planes.forEach { plane ->
            require(plane.offset >= 0 && plane.rowBytes > 0 && plane.offset + plane.rowBytes * plane.rows <= bytes.size) {
                "A plane of ${plane.rows} rows of ${plane.rowBytes} bytes from ${plane.offset} does not fit ${bytes.size} bytes"
            }
        }
    }

    override val mappable: Boolean = true
    override val pixelBuffer: Any? = null

    override fun retain(): NativeFrame = this

    override fun <R> usePlanes(block: (List<NativeFramePlane>) -> R): R = block(
        planes.map { NativeFramePlane(memory = bytes, offset = it.offset, size = it.rowBytes * it.rows, rowBytes = it.rowBytes, rows = it.rows) },
    )

    override fun convert(format: NativeFrameFormat): NativeFrame {
        if (format != BROWSER_RGBA8 || this.format != BROWSER_RGBA8) {
            throw UnsupportedOperationException("The browser converts RGBA8 frames to RGBA8 only; decode to the format you need")
        }
        return BrowserFrame(bytes.copyOf(), format, width, height, planes)
    }

    override fun convertInto(target: NativeFrame) {
        val destination = target as? BrowserFrame
        if (format != BROWSER_RGBA8 || destination?.format != BROWSER_RGBA8 || destination.width != width || destination.height != height) {
            throw UnsupportedOperationException("The browser converts RGBA8 frames into RGBA8 frames of their size only")
        }
        val from = planes.single()
        val to = destination.planes.single()
        for (row in 0 until height) {
            bytes.copyInto(destination.bytes, to.offset + row * to.rowBytes, from.offset + row * from.rowBytes, from.offset + row * from.rowBytes + width * 4)
        }
    }

    override fun close() = Unit

    companion object {
        /** An sRGB RGBA8 frame of [height] rows of [stride] bytes. */
        fun rgba(bytes: ByteArray, width: Int, height: Int, stride: Int): BrowserFrame {
            require(stride >= width * 4) { "A ${width}x$height RGBA frame needs rows of at least ${width * 4} bytes, not $stride" }
            return BrowserFrame(bytes, BROWSER_RGBA8, width, height, listOf(Plane(0, stride, height)))
        }
    }
}

/** An RGBA8 frame over a fresh array, which [NativeFrame.usePlanes] hands out to be written: the page owns it. */
@InternalFFmpegKmpApi
public actual fun allocateNativeFrame(format: NativeFrameFormat, width: Int, height: Int): NativeFrame {
    if (format != BROWSER_RGBA8) throw UnsupportedOperationException("The browser holds RGBA8 frames only")
    return BrowserFrame.rgba(ByteArray(width * height * 4), width, height, width * 4)
}

@InternalFFmpegKmpApi
public actual fun nativeFrameStatistics(): NativeFrameStatistics = NativeFrameStatistics(buffers = 0, bytes = 0)
