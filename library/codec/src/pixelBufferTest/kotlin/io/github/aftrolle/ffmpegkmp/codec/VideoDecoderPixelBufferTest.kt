// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.Buffer
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCompare
import platform.CoreFoundation.kCFCompareEqualTo
import platform.CoreVideo.CVBufferCopyAttachment
import platform.CoreVideo.CVPixelBufferGetIOSurface
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.CoreVideo.kCVPixelFormatType_64RGBAHalf

/**
 * [VideoOutput.Memory] without a format, where every frame is a `CVPixelBuffer`, and the pooled
 * `CVPixelBuffer` formats, against the real bridge and VideoToolbox, on the simulator and macOS.
 */
class VideoDecoderPixelBufferTest {
    @Test
    fun softwareFramesArePooledPixelBuffers() = runBlocking {
        // MPEG-4 Part 2: VideoToolbox does not take it, so AUTO falls back to software and the pool.
        decoder("cfr-24.mp4").use { decoder ->
            println("cfr-24.mp4 (MPEG-4 Part 2) as decoded: ${decoder.decoderKind}")
            assertEquals(DecoderKind.SOFTWARE, decoder.decoderKind)
            assertHoldsFrames(decoder, fps = 24)
        }
    }

    @Test
    fun h264FramesAreVideoToolboxPixelBuffersWhereTheHostDecodesThem() = runBlocking {
        decoder("cfr-24-h264.mp4").use { decoder ->
            println("cfr-24-h264.mp4 as decoded: ${decoder.decoderKind}")
            assertNotEquals(DecoderKind.UNKNOWN, decoder.decoderKind)
            assertHoldsFrames(decoder, fps = 24)
        }
        decoder("cfr-24-h264.mp4", DecoderPreference.SOFTWARE).use { decoder ->
            assertEquals(DecoderKind.SOFTWARE, decoder.decoderKind)
            assertHoldsFrames(decoder, fps = 24)
        }
    }

    @Test
    fun framesOfOneDecodedFrameShareTheirBufferAndOutliveTheDecoder() = runBlocking {
        // hdr10-pq.mp4 is the clip VideoToolbox decodes on the simulator: 10 frames, 100 ms each.
        for (clip in CLIPS) {
            val name = clip.name
            val decoder = decoder(name)
            val first = decoder.frameAt(Duration.ZERO)
            val again = decoder.frameAt(clip.frame / 2)
            assertEquals(first.buffer(), again.buffer(), "$name: the same decoded frame shares its buffer")
            again.close()
            // Moving on and closing the decoder leave the caller's reference intact.
            decoder.frameAt(clip.frame * 5).close()
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
                val open = (0 until 3).map { index -> decoder.frameAt(clip.frame * index) }
                val seen = open.map { it.buffer() }.toMutableSet()
                assertEquals(3, seen.size, "$name: 3 frames held open")
                // Software frames are copies into the decoder's ring of three pooled buffers, so a
                // fourth waits for one to close. VideoToolbox's are its own.
                val ring = decoder.decoderKind == DecoderKind.SOFTWARE
                val fourth = async(Dispatchers.Default) { decoder.frameAt(clip.frame * 3) }
                if (ring) {
                    delay(200.milliseconds)
                    assertFalse(fourth.isCompleted, "$name: the fourth frame waits while three are held")
                }
                open.first().close()
                fourth.await().use { frame ->
                    clip.assertFrame(frame, 3)
                    seen += frame.buffer()
                }
                open.drop(1).forEach(VideoFrame::close)

                repeat(200) { tick ->
                    // Past the end the position wraps, so the loop also seeks back to the start.
                    val index = tick % clip.frames
                    decoder.frameAt(clip.frame * index).use { frame ->
                        clip.assertFrame(frame, index)
                        seen += frame.buffer()
                    }
                }
                // Leaked, the 200 frames decoded would each keep a buffer of their own; the ring
                // makes its three once.
                if (ring) {
                    assertEquals(3, seen.size, "$name: buffers for 204 frames")
                } else {
                    assertTrue(seen.size <= 32, "$name (${decoder.decoderKind}): ${seen.size} buffers for 204 frames")
                }
            }
        }
    }

    @Test
    fun anHdr10SourceKeepsTenBitsAndItsColour() = runBlocking {
        for (preference in listOf(DecoderPreference.AUTO, DecoderPreference.SOFTWARE)) {
            decoder("hdr10-pq.mp4", preference).use { decoder ->
                println("hdr10-pq.mp4 as decoded ($preference): ${decoder.decoderKind}")
                decoder.frameAt(0.5.seconds).use { frame ->
                    assertEquals(FrameFormat.P010Hdr10, frame.format)
                    assertEquals(kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange, CVPixelBufferGetPixelFormatType(frame.buffer()))
                    assertNotNull(CVPixelBufferGetIOSurface(frame.buffer()), "IOSurface-backed")
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

    @Test
    fun theCanvasAndEncoderFormatsArePooledPixelBuffersToo() = runBlocking {
        val expected = mapOf(
            FrameFormat(PixelLayout.BGRA8, FrameColor.Srgb) to kCVPixelFormatType_32BGRA,
            FrameFormat.RgbaF16 to kCVPixelFormatType_64RGBAHalf,
            FrameFormat.Nv12 to kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            FrameFormat.P010Hdr10 to kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange,
        )
        decoder("cfr-24.mp4", DecoderPreference.SOFTWARE).use { decoder ->
            decoder.frameAt(1.seconds).use { decoded ->
                for ((format, type) in expected) {
                    decoded.convert(format).use { frame ->
                        assertEquals(format, frame.format)
                        assertEquals(type, CVPixelBufferGetPixelFormatType(frame.buffer()), "$format")
                        assertNotNull(CVPixelBufferGetIOSurface(frame.buffer()), "$format is IOSurface-backed")
                        if (format.layout != PixelLayout.RGBA_F16) assertEquals(24, frame.number(), "$format")
                    }
                }
                // RGBA8 has no CoreVideo pool: an AVBuffer frame, without a CVPixelBuffer.
                decoded.convert(FrameFormat.Rgba8).use { assertEquals(null, it.cvPixelBuffer) }
            }
        }
    }

    private suspend fun assertHoldsFrames(decoder: VideoDecoder, fps: Int) {
        repeat(5 * 30) { tick ->
            decoder.frameAt(tick.seconds / 30).use { frame ->
                val expected = tick * fps / 30
                assertEquals(expected, frame.number(), "tick $tick")
                assertTrue((expected.seconds / fps - frame.pts).absoluteValue <= 1.microseconds, "pts of frame $expected")
                assertEquals(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, CVPixelBufferGetPixelFormatType(frame.buffer()))
                assertEquals(PixelLayout.NV12, frame.format?.layout)
                assertEquals(96 to 64, frame.width to frame.height)
                assertNotNull(CVPixelBufferGetIOSurface(frame.buffer()), "IOSurface-backed")
            }
        }
        for ((index, position) in listOf(96 to 4.seconds, 24 to 1.seconds, 119 to 10.seconds, 0 to Duration.ZERO)) {
            decoder.frameAt(position).use { assertEquals(index, it.number(), "frameAt $position") }
        }
        decoder.seekTo(2.5.seconds + 10.milliseconds)
        decoder.frameAt(2.5.seconds + 10.milliseconds).use { assertEquals(60, it.number(), "seekTo back inside frame 60") }
    }

    private suspend fun decoder(
        name: String,
        preference: DecoderPreference = DecoderPreference.AUTO,
    ): VideoDecoder = VideoDecoder.open(
        MediaSource(name, CommandIo { input(name, Buffer().write(readVideoDecoderFixture(name))) }),
        VideoOutput.Memory(),
        preference,
    )

    private fun VideoFrame.buffer(): CVPixelBufferRef = assertNotNull(cvPixelBuffer, "$this has no CVPixelBuffer")

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
}
