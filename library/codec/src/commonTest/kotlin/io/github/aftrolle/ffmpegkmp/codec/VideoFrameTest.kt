// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.NativeFrame
import io.github.aftrolle.ffmpegkmp.bindings.NativeFrameFormat
import io.github.aftrolle.ffmpegkmp.bindings.NativeFramePlane
import io.github.aftrolle.ffmpegkmp.bindings.NativeGpuBuffer
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerHdrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Reference counting and scoping, against a native frame that counts its references. */
class VideoFrameTest {
    @Test
    fun eachReferenceReleasesTheMemoryOnceAndTheLastOneFreesIt() {
        val memory = CountedMemory()
        val frame = memory.frame()
        val retained = frame.retain()
        assertEquals(2, memory.references)
        frame.close()
        frame.close()
        assertEquals(1, memory.references, "a second close does nothing")
        assertEquals(3.toByte(), retained.usePlanes { it.single().bytes[3] })
        retained.close()
        assertEquals(0, memory.references)
    }

    @Test
    fun aClosedFrameFailsClearly() {
        val frame = CountedMemory().frame()
        frame.close()
        for (use in listOf<(VideoFrame) -> Unit>(
            { it.usePlanes { } },
            { it.retain() },
            { it.convert(FrameFormat.RgbaF16) },
            { it.convert(FrameFormat.Rgba8) },
        )) {
            val failure = assertFailsWith<IllegalStateException> { use(frame) }
            assertEquals("The video frame is closed", failure.message)
        }
        val target = CountedMemory().frame()
        assertFailsWith<IllegalStateException> { frame.convertInto(target) }
        assertFailsWith<IllegalStateException> { target.convertInto(frame) }
    }

    @Test
    fun planeViewsAreValidOnlyInsideTheBlock() {
        val frame = CountedMemory().frame()
        val escaped = frame.usePlanes { planes ->
            val plane = planes.single()
            assertEquals(16, plane.bytes.size)
            assertEquals(8, plane.rowBytes)
            assertEquals(2, plane.rows)
            val copy = ByteArray(18)
            plane.bytes.copyInto(copy, offset = 2)
            assertEquals(15.toByte(), copy[17])
            plane.bytes
        }!!
        assertFailsWith<IllegalStateException> { escaped[0] }
        assertFailsWith<IllegalStateException> { escaped.copyInto(ByteArray(16)) }
        frame.close()
    }

    @Test
    fun closingInsideUsePlanesReleasesOnceTheBlockReturns() {
        val memory = CountedMemory()
        val frame = memory.frame()
        frame.usePlanes { planes ->
            frame.close()
            assertEquals(1, memory.references, "still in use")
            assertEquals(1.toByte(), planes.single().bytes[1])
        }
        assertEquals(0, memory.references)
    }

    @Test
    fun convertingToTheSameFormatIsARetainAndToAnotherAConversion() {
        val memory = CountedMemory()
        memory.frame().use { frame ->
            frame.convert(FrameFormat.Rgba8).use { same ->
                assertEquals(FrameFormat.Rgba8, same.format)
                assertEquals(0, memory.conversions)
                assertEquals(2, memory.references)
            }
            frame.convert(FrameFormat.RgbaF16).use { converted ->
                assertEquals(FrameFormat.RgbaF16, converted.format)
                assertEquals(frame.pts, converted.pts)
                assertEquals(1, memory.conversions)
            }
            val target = CountedMemory().frame(width = 2, height = 2)
            frame.convertInto(target)
            assertEquals(1, memory.conversionsInto)
            // A target of another size is scaled into.
            val smaller = CountedMemory().frame(width = 1, height = 1)
            frame.convertInto(smaller)
            assertEquals(2, memory.conversionsInto)
            target.close()
            smaller.close()
        }
        assertEquals(0, memory.references)
    }

    @Test
    fun aFrameRenderedToASurfaceHasNoPixels() {
        VideoFrame.of(null, Duration.ZERO, 40.milliseconds, width = 96, height = 64).use { frame ->
            assertNull(frame.format)
            assertNull(frame.usePlanes { it })
            assertFailsWith<IllegalStateException> { frame.convert(FrameFormat.Rgba8) }
            frame.retain().close()
        }
    }

    @Test
    fun aFrameInGpuMemoryHasNoPixelsAndReleasesItsBufferOnceAfterTheLastReference() {
        var releases = 0
        val buffer = NativeGpuBuffer(Any(), 7L, 0, 0, 128, 128, NativePlayerHdrType.SDR) { releases++ }
        val frame = VideoFrame.of(buffer, Duration.ZERO, 40.milliseconds, width = 128, height = 128)
        assertSame(buffer, frame.gpuBuffer)
        assertNull(frame.format)
        assertNull(frame.usePlanes { it })
        assertNull(frame.useNative { it })
        val failure = assertFailsWith<IllegalStateException> { frame.convert(FrameFormat.Rgba8) }
        assertTrue(failure.message!!.contains("GPU memory"), failure.message)
        val target = CountedMemory().frame()
        assertFailsWith<IllegalStateException> { frame.convertInto(target) }
        assertFailsWith<IllegalStateException> { target.convertInto(frame) }
        target.close()
        val retained = frame.retain()
        assertSame(buffer, retained.gpuBuffer)
        frame.close()
        frame.close()
        assertEquals(0, releases, "a reference is still open")
        retained.gpuBuffer
        retained.close()
        assertEquals(1, releases)
        assertFailsWith<IllegalStateException> { retained.gpuBuffer }
        assertFailsWith<IllegalStateException> { buffer.retain() }
    }

    @Test
    fun formatsKeepRgbLayoutsAndYuvMatricesApart() {
        assertEquals(FrameFormat(PixelLayout.RGBA8, FrameColor.Srgb), FrameFormat.Rgba8)
        assertEquals(ColorTransfer.LINEAR, FrameFormat.RgbaF16.color.transfer)
        assertEquals(FrameColor.Bt2020Pq, FrameFormat.P010Hdr10.color)
        assertEquals(ColorTransfer.HLG, FrameFormat.P010Hlg.color.transfer)
        assertEquals(PixelLayout.NV12, FrameFormat.Nv12.layout)
        assertFailsWith<IllegalArgumentException> { FrameFormat(PixelLayout.RGBA8, FrameColor.Bt709) }
        assertFailsWith<IllegalArgumentException> { FrameFormat(PixelLayout.NV12, FrameColor.Srgb) }
        // The C enums share the Kotlin ordinals.
        assertEquals(NativeFrameFormat(5, 1, 3, 2, 0), FrameFormat.P010Hdr10.toNative())
        assertEquals(FrameFormat.RgbaF16, FrameFormat.RgbaF16.toNative().toFrameFormat())
    }
}

/** 2x2 RGBA8 pixels whose bytes count up from 0, and how many references to them are open. */
private class CountedMemory {
    val bytes = ByteArray(16) { it.toByte() }
    var references = 0
    var conversions = 0
    var conversionsInto = 0

    fun frame(width: Int = 2, height: Int = 2): VideoFrame =
        VideoFrame.of(Reference(width, height, FrameFormat.Rgba8.toNative()), Duration.ZERO, 40.milliseconds)

    inner class Reference(
        override val width: Int,
        override val height: Int,
        override val format: NativeFrameFormat,
    ) : NativeFrame {
        private var open = true

        init {
            references++
        }

        override val mappable: Boolean = true
        override val pixelBuffer: Any? = null

        override fun retain(): NativeFrame = Reference(width, height, format)

        override fun <R> usePlanes(block: (List<NativeFramePlane>) -> R): R {
            check(open) { "Mapped after release" }
            return block(listOf(NativeFramePlane(testPlaneMemory(bytes), 0, bytes.size, rowBytes = 8, rows = 2)))
        }

        override fun convert(format: NativeFrameFormat): NativeFrame {
            conversions++
            return Reference(width, height, format)
        }

        override fun convertInto(target: NativeFrame) {
            conversionsInto++
        }

        override fun close() {
            check(open) { "Released twice" }
            open = false
            references--
        }
    }
}

/** [bytes] as the platform's bindings map a plane: a direct buffer, a native pointer or the array. */
internal expect fun testPlaneMemory(bytes: ByteArray): Any
