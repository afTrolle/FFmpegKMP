// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.runBlocking
import okio.Buffer
import platform.CoreFoundation.CFGetRetainCount
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCompare
import platform.CoreFoundation.kCFCompareEqualTo
import platform.CoreVideo.CVBufferCopyAttachment
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange

/** [VideoOutput.PixelBuffer] against the real bridge and VideoToolbox, on the simulator and macOS. */
class VideoDecoderPixelBufferTest {
    @Test
    fun softwareFramesArePooledPixelBuffersHeldLikeImages() = runBlocking {
        // MPEG-4 Part 2: VideoToolbox does not take it, so AUTO falls back to software and the pool.
        decoder("cfr-24.mp4").use { decoder ->
            println("cfr-24.mp4 (MPEG-4 Part 2) under PixelBuffer: ${decoder.decoderKind}")
            assertEquals(FFplayDecoderKind.SOFTWARE, decoder.decoderKind)
            assertHoldsFrames(decoder, fps = 24)
        }
    }

    @Test
    fun h264FramesAreVideoToolboxPixelBuffersWhereTheHostDecodesThem() = runBlocking {
        decoder("cfr-24-h264.mp4").use { decoder ->
            println("cfr-24-h264.mp4 under PixelBuffer: ${decoder.decoderKind}")
            assertNotEquals(FFplayDecoderKind.UNKNOWN, decoder.decoderKind)
            assertHoldsFrames(decoder, fps = 24)
        }
        decoder("cfr-24-h264.mp4", FFplayDecoderPreference.SOFTWARE).use { decoder ->
            assertEquals(FFplayDecoderKind.SOFTWARE, decoder.decoderKind)
            assertHoldsFrames(decoder, fps = 24)
        }
    }

    @Test
    fun eachFrameAtHoldsOneRetainAndTheDecoderKeepsItsOwn() = runBlocking {
        // hdr10-pq.mp4 is the clip VideoToolbox decodes on the simulator: 10 frames, 100 ms each.
        for (clip in CLIPS) {
            val name = clip.name
            val decoder = decoder(name)
            val first = decoder.frameAt(Duration.ZERO)
            val again = decoder.frameAt(clip.frame / 2)
            val buffer = first.buffer()
            assertEquals(buffer, again.buffer(), "$name: the same decoded frame shares its buffer")
            val current = assertNotNull(decoder.current)
            assertSame(first.pixelBuffer, current.pixelBuffer)
            assertNull(first.image)

            val held = CFGetRetainCount(buffer)
            again.close()
            assertEquals(held - 1, CFGetRetainCount(buffer), "$name: close releases one retain")
            again.close()
            current.close()
            assertEquals(held - 1, CFGetRetainCount(buffer), "$name: a second close and the decoder's frame do nothing")

            // Moving on drops the decoder's retains; the caller's outlives them and the decoder.
            decoder.frameAt(clip.frame * 5).close()
            assertEquals(1, CFGetRetainCount(buffer), "$name (${decoder.decoderKind}): only the caller's retain is left")
            decoder.close()
            clip.assertFrame(first, 0)
            first.close()
        }
    }

    @Test
    fun closedFramesGoBackToThePoolOver200Frames() = runBlocking {
        for (clip in CLIPS) {
            val name = clip.name
            decoder(name).use { decoder ->
                // Kept open, every frame needs a buffer of its own.
                val open = (0 until 10).map { index -> decoder.frameAt(clip.frame * index) }
                assertEquals(10, open.map { it.buffer() }.distinct().size, "$name: 10 frames held open")
                open.forEach(VideoFrame::close)

                val seen = mutableSetOf<CVPixelBufferRef>()
                repeat(200) { tick ->
                    // Past the end the position wraps, so the loop also seeks back to the start.
                    val index = tick % clip.frames
                    decoder.frameAt(clip.frame * index).use { frame ->
                        clip.assertFrame(frame, index)
                        seen += frame.buffer()
                    }
                }
                // Leaked, the 200 frames decoded would each keep a buffer of their own.
                assertTrue(seen.size <= 32, "$name (${decoder.decoderKind}): ${seen.size} buffers for 200 frames")
            }
        }
    }

    @Test
    fun anHdr10SourceKeepsTenBitsAndItsColour() = runBlocking {
        for (preference in listOf(FFplayDecoderPreference.AUTO, FFplayDecoderPreference.SOFTWARE)) {
            decoder("hdr10-pq.mp4", preference).use { decoder ->
                println("hdr10-pq.mp4 under PixelBuffer ($preference): ${decoder.decoderKind}")
                decoder.frameAt(0.5.seconds).use { frame ->
                    val pixels = assertNotNull(frame.pixelBuffer)
                    assertEquals(kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange.toInt(), pixels.pixelFormat)
                    assertEquals(kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange, CVPixelBufferGetPixelFormatType(frame.buffer()))
                    assertEquals("BT.2020", pixels.colorPrimaries)
                    assertEquals("PQ", pixels.colorTransfer)
                    assertEquals("BT.2020 NCL", pixels.colorMatrix)
                    assertEquals("Limited", pixels.colorRange)
                    assertTrue(pixels.ioSurfaceBacked)
                    val transfer = assertNotNull(CVBufferCopyAttachment(frame.buffer(), kCVImageBufferTransferFunctionKey, null))
                    assertEquals(
                        kCFCompareEqualTo,
                        CFStringCompare(transfer.reinterpret(), kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ, 0u),
                        "PQ attachment",
                    )
                    CFRelease(transfer)
                    // 100 nits on the left and 1000 on the right: PQ 0.508 and 0.752, limited range.
                    assertEquals(127.0, frame.luma(x = 12, y = 32).toDouble(), 6.0, "100 nits")
                    assertEquals(181.0, frame.luma(x = 84, y = 32).toDouble(), 6.0, "1000 nits")
                }
            }
        }
    }

    private suspend fun assertHoldsFrames(decoder: VideoDecoder, fps: Int) {
        repeat(5 * 30) { tick ->
            decoder.frameAt(tick.seconds / 30).use { frame ->
                val expected = tick * fps / 30
                assertEquals(expected, frame.number(), "tick $tick")
                assertTrue((expected.seconds / fps - frame.pts).absoluteValue <= 1.microseconds, "pts of frame $expected")
                val pixels = assertNotNull(frame.pixelBuffer)
                assertEquals(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange.toInt(), pixels.pixelFormat)
                assertEquals(96 to 64, pixels.width to pixels.height)
                assertTrue(pixels.ioSurfaceBacked, "IOSurface-backed")
            }
        }
        for ((index, position) in listOf(96 to 4.seconds, 24 to 1.seconds, 119 to 10.seconds, 0 to Duration.ZERO)) {
            decoder.frameAt(position).use { assertEquals(index, it.number(), "frameAt $position") }
        }
        decoder.seekTo(2.5.seconds + 10.milliseconds)
        assertEquals(60, decoder.current?.number(), "seekTo back inside frame 60")
    }

    private suspend fun decoder(
        name: String,
        preference: FFplayDecoderPreference = FFplayDecoderPreference.AUTO,
    ): VideoDecoder = VideoDecoder.open(
        FFplaySource(name, CommandIo { input(name, Buffer().write(readVideoDecoderFixture(name))) }),
        VideoOutput.PixelBuffer,
        preference,
    )

    private fun VideoFrame.buffer(): CVPixelBufferRef = assertNotNull(pixelBuffer).cvPixelBuffer

    /** The top 8 bits of the luma sample, for 8-bit and 16-bit (P010) planes. */
    private fun VideoFrame.luma(x: Int, y: Int): Int {
        val buffer = buffer()
        CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
        try {
            val base = assertNotNull(CVPixelBufferGetBaseAddressOfPlane(buffer, 0u))
            val row = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0u).toInt()
            return if (CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange) {
                base.reinterpret<UShortVar>()[y * row / 2 + x].toInt() shr 8
            } else {
                base.reinterpret<UByteVar>()[y * row + x].toInt()
            }
        } finally {
            CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
        }
    }

    private inner class Clip(val name: String, val frames: Int, val frame: Duration, val numbered: Boolean) {
        fun assertFrame(frame: VideoFrame, index: Int) = if (numbered) {
            assertEquals(index, frame.number(), "$name frame $index")
        } else {
            assertEquals(181.0, frame.luma(x = 84, y = 32).toDouble(), 6.0, "$name frame $index")
        }
    }

    private val CLIPS = listOf(
        Clip("cfr-24.mp4", frames = 120, frame = 1.seconds / 24, numbered = true),
        Clip("cfr-24-h264.mp4", frames = 120, frame = 1.seconds / 24, numbered = true),
        Clip("hdr10-pq.mp4", frames = 10, frame = 100.milliseconds, numbered = false),
    )

    /** Reads the 12-bit frame number the fixture generator draws as 16x16 cells in the top rows. */
    private fun VideoFrame.number(): Int = (0 until 12).sumOf { bit ->
        if (luma(x = (bit % 6) * 16 + 8, y = (bit / 6) * 16 + 8) > 128) 1 shl bit else 0
    }
}
