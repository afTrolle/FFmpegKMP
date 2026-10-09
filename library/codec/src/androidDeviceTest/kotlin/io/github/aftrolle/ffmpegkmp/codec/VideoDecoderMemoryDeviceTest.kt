// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import android.os.Build
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import okio.Buffer

/**
 * Frames in memory on a device: as decoded, and converted on the decoder's thread, from the
 * software decoder and from MediaCodec's memory (ByteBuffer) output.
 */
class VideoDecoderMemoryDeviceTest {
    @Test
    fun framesAsDecodedAndConvertedCarryTheFrameAtEachPosition() = runBlocking {
        for (format in listOf(null, FrameFormat.Rgba8, FrameFormat.RgbaF16)) {
            open("cfr-30.mp4", VideoOutput.Memory(format)).use { decoder ->
                for (index in listOf(0, 1, 45, 12, 149)) {
                    decoder.frameAt(index.seconds / 30).use { frame ->
                        val actual = assertNotNull(frame.format)
                        if (format != null) assertEquals(format, actual)
                        assertEquals(index, frame.number(), "frame $index as $actual")
                    }
                }
            }
        }
    }

    @Test
    fun anHdr10SourceKeepsItsHighlightsInRgbaF16() = runBlocking {
        open("hdr10-pq.mp4", VideoOutput.Memory(FrameFormat.RgbaF16)).use { decoder ->
            decoder.frameAt(0.5.seconds).use { frame ->
                val highlight = assertNotNull(frame.usePlanes { planes -> planes.single().half(y = 32, x = 84) })
                assertEquals(1000.0 / 203.0, highlight, 0.15)
                assertTrue(highlight > 1.0)
            }
        }
    }

    @Test
    fun aFourthConvertedFrameFailsAtOnceWhileThreeAreHeldAndTheDecoderGoesOnOnceOneCloses() = runBlocking {
        open("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            val held = (0 until 3).map { decoder.frameAt(it.seconds / 30) }
            val asked = TimeSource.Monotonic.markNow()
            val failure = assertFailsWith<IllegalStateException> { decoder.frameAt(3.seconds / 30) }
            val took = asked.elapsedNow()
            println("VideoDecoderMemoryDeviceTest: the fourth frame failed after $took: ${failure.message}")
            assertTrue(failure.message!!.contains("holds all 3"), failure.message)
            assertTrue(took < 2.seconds, "failed after $took, well before the $TIMEOUT timeout")
            held.first().close()
            decoder.frameAt(3.seconds / 30).use { frame -> assertEquals(3, frame.number()) }
            held.drop(1).forEach(VideoFrame::close)
            decoder.frameAt(10.seconds / 30).use { frame -> assertEquals(10, frame.number()) }
        }
    }

    @Test
    fun autoDecodesAnEightBitSourceIntoMemoryWithMediaCodecOrFallsBack() = runBlocking {
        for (format in listOf(null, FrameFormat.Rgba8)) {
            open("cfr-30-h264-128.mp4", VideoOutput.Memory(format), DecoderPreference.AUTO).use { decoder ->
                // The emulator's goldfish decoders reject FFmpeg's input, so there AUTO falls back.
                if (Build.HARDWARE != "ranchu") {
                    assertEquals(DecoderKind.HARDWARE, decoder.decoderKind, "MediaCodec decodes H.264 on every device")
                }
                assertNotEquals(DecoderKind.UNKNOWN, decoder.decoderKind)
                for (index in listOf(0, 1, 45, 12, 149)) {
                    decoder.frameAt(index.seconds / 30).use { frame ->
                        val actual = assertNotNull(frame.format, "${decoder.decoderKind} frames are in memory")
                        if (format != null) assertEquals(format, actual)
                        if (format == null && decoder.decoderKind == DecoderKind.HARDWARE) {
                            assertTrue(actual.layout == PixelLayout.NV12 || actual.layout == PixelLayout.YUV420P, "$actual")
                        }
                        assertEquals(index, frame.number(), "frame $index from ${decoder.decoderKind} as $actual")
                    }
                }
            }
        }
    }

    @Test
    fun requireHardwareDecodesAnEightBitSourceIntoMemoryOrFails() = runBlocking<Unit> {
        runCatching { open("cfr-30-h264-128.mp4", VideoOutput.Memory(), DecoderPreference.REQUIRE_HARDWARE) }
            .onSuccess { opened ->
                opened.use { decoder ->
                    assertEquals(DecoderKind.HARDWARE, decoder.decoderKind)
                    decoder.frameAt(1.seconds).use { frame -> assertEquals(30, frame.number()) }
                }
            }
            .onFailure { failure -> assertTrue(failure is VideoDecodingException, "Unexpected $failure") }
    }

    @Test
    fun aTenBitSourceDecodesIntoMemoryInSoftware() = runBlocking<Unit> {
        open("hdr10-pq.mp4", VideoOutput.Memory(FrameFormat.RgbaF16), DecoderPreference.AUTO).use { decoder ->
            assertEquals(DecoderKind.SOFTWARE, decoder.decoderKind)
        }
        assertFailsWith<VideoDecodingException> {
            open("hdr10-pq.mp4", VideoOutput.Memory(), DecoderPreference.REQUIRE_HARDWARE).close()
        }
    }

    private suspend fun open(
        name: String,
        output: VideoOutput,
        preference: DecoderPreference = DecoderPreference.SOFTWARE,
    ): VideoDecoder {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/video-decoder/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
        return VideoDecoder.open(
            MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }),
            output,
            preference,
            TIMEOUT,
        )
    }

    /** The 12-bit frame number the fixture generator draws as 16x16 cells in the top rows. */
    private fun VideoFrame.number(): Int = assertNotNull(
        usePlanes { planes ->
            val layout = format?.layout
            (0 until 12).sumOf { bit ->
                val x = (bit % 6) * 16 + 8
                val y = (bit / 6) * 16 + 8
                val plane = planes.first()
                val on = when (layout) {
                    PixelLayout.RGBA8 -> plane.bytes[y * plane.rowBytes + x * 4 + 1].toInt() and 0xff > 128
                    PixelLayout.RGBA_F16 -> plane.half(y, x, channel = 1) > 0.22
                    else -> plane.bytes[y * plane.rowBytes + x].toInt() and 0xff > 128
                }
                if (on) 1 shl bit else 0
            }
        },
    )

    private fun FramePlane.half(y: Int, x: Int, channel: Int = 0): Double {
        val offset = y * rowBytes + (x * 4 + channel) * 2
        val bits = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() and 0xff shl 8)
        val exponent = bits shr 10 and 0x1f
        val magnitude = if (exponent == 0) (bits and 0x3ff) / 1024.0 / 16384.0 else (1 + (bits and 0x3ff) / 1024.0) * Math.pow(2.0, exponent - 15.0)
        return if (bits and 0x8000 != 0) -magnitude else magnitude
    }

    private companion object {
        /** A hardware attempt that stalls falls back within this, so the tests stay bounded. */
        val TIMEOUT = 5.seconds
    }
}
