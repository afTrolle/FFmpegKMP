// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import android.hardware.HardwareBuffer
import android.os.Build
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue

/** [VideoOutput.GpuBuffers] on a device: MediaCodec's frames in the HardwareBuffers of a ring of three. */
class VideoDecoderGpuBuffersDeviceTest {
    @Test
    fun everyFrameLiesInAGpuSampledBufferCroppedToThePicture() = runBlocking {
        assumeGpuBuffers()
        open("cfr-30-h264-128.mp4").use { decoder ->
            assertEquals(DecoderKind.HARDWARE, decoder.decoderKind, "MediaCodec decodes H.264 on every device")
            for (index in listOf(0, 1, 2, 30, 31, 90, 12, 149)) {
                decoder.frameAt(index.seconds / 30).use { frame ->
                    assertEquals(framePts(index), frame.pts, "frame $index")
                    assertNull(frame.format, "A frame in GPU memory has no pixels in memory")
                    assertNull(frame.usePlanes { it })
                    assertFailsWith<IllegalStateException> { frame.convert(FrameFormat.Rgba8) }
                    val buffer = assertNotNull(frame.hardwareBuffer, "frame $index")
                    assertTrue(buffer.usage and HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE != 0L, "GPU-sampled")
                    val crop = assertNotNull(frame.hardwareBufferCrop)
                    assertEquals(128 to 128, crop.width() to crop.height(), "the picture in a ${buffer.width}x${buffer.height} buffer")
                    assertTrue(crop.right <= buffer.width && crop.bottom <= buffer.height)
                }
            }
        }
    }

    @Test
    fun theSamePositionAgainGivesTheSameBuffer() = runBlocking {
        assumeGpuBuffers()
        open("cfr-30-h264-128.mp4").use { decoder ->
            decoder.frameAt(1.seconds).use { first ->
                decoder.frameAt(1.seconds + 10.milliseconds).use { again -> assertSame(first.hardwareBuffer, again.hardwareBuffer) }
            }
        }
    }

    @Test
    fun aFourthFrameFailsAtOnceWhileThreeAreHeldAndTheDecoderGoesOnOnceOneCloses() = runBlocking {
        assumeGpuBuffers()
        open("cfr-30-h264-128.mp4").use { decoder ->
            val held = (0 until 3).map { decoder.frameAt(it.seconds / 30) }
            val asked = TimeSource.Monotonic.markNow()
            val failure = assertFailsWith<IllegalStateException> { decoder.frameAt(3.seconds / 30) }
            val took = asked.elapsedNow()
            println("VideoDecoderGpuBuffersDeviceTest: the fourth frame failed after $took: ${failure.message}")
            assertTrue(failure.message!!.contains("holds all 3"), failure.message)
            assertTrue(took < 2.seconds, "failed after $took, well before the 10 s timeout")
            held.first().close()
            decoder.frameAt(3.seconds / 30).use { frame -> assertNotNull(frame.hardwareBuffer) }
            held.drop(1).forEach(VideoFrame::close)
            decoder.frameAt(10.seconds / 30).use { frame -> assertNotNull(frame.hardwareBuffer) }
        }
    }

    @Test
    fun framesDeliversEveryFrameInOrder() = runBlocking {
        assumeGpuBuffers()
        open("cfr-30-h264-128.mp4").use { decoder ->
            val pts = mutableListOf<Duration>()
            decoder.frames().collect { frame ->
                frame.use {
                    assertNotNull(it.hardwareBuffer)
                    pts += it.pts
                }
            }
            assertEquals((0 until 150).map(::framePts), pts)
        }
    }

    @Test
    fun theSoftwareDecoderGivesFramesInMemory() = runBlocking<Unit> {
        assumeGpuBuffers()
        open("cfr-30-h264-128.mp4", DecoderPreference.SOFTWARE).use { decoder ->
            assertEquals(DecoderKind.SOFTWARE, decoder.decoderKind)
            decoder.frameAt(1.seconds).use { frame ->
                assertNull(frame.hardwareBuffer)
                assertNotNull(frame.format)
            }
        }
    }

    @Test
    fun aTenBitSourceStaysInGpuMemoryWhenAHardwareDecoderTakesIt() = runBlocking<Unit> {
        assumeGpuBuffers()
        open("hdr10-pq-large.mp4").use { decoder ->
            assumeTrue("a hardware decoder takes the 320x192 HDR10 fixture", decoder.decoderKind == DecoderKind.HARDWARE)
            decoder.frameAt(0.5.seconds).use { frame ->
                val buffer = assertNotNull(frame.hardwareBuffer)
                val crop = assertNotNull(frame.hardwareBufferCrop)
                println("VideoDecoderGpuBuffersDeviceTest: HDR10 frame in a ${buffer.width}x${buffer.height} buffer of format ${buffer.format}")
                assertNull(frame.format)
                assertEquals(320 to 192, crop.width() to crop.height())
            }
        }
    }

    @Test
    fun beforeAndroid14OpeningFails() = runBlocking<Unit> {
        assumeTrue(Build.VERSION.SDK_INT < 34)
        assertFailsWith<IllegalArgumentException> { open("cfr-30-h264-128.mp4") }
    }

    /** The emulator's decoders reject FFmpeg's input, so these need a real device of Android 14 or later. */
    private fun assumeGpuBuffers() {
        assumeTrue("GpuBuffers needs Android 14", Build.VERSION.SDK_INT >= 34)
        assumeFalse("no emulator decoder renders to a Surface through FFmpeg", Build.HARDWARE == "ranchu")
    }

    private suspend fun open(
        name: String,
        preference: DecoderPreference = DecoderPreference.AUTO,
        timeout: Duration = 5.seconds,
    ): VideoDecoder {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/video-decoder/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
        return VideoDecoder.open(MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }), VideoOutput.GpuBuffers, preference, timeout)
    }
}
