// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * [VideoOutput.Memory] with a size, on the JVM and Kotlin/Native: frames scaled as they convert,
 * against FFmpeg's own bilinear `scale` filter with the same conversion.
 */
class VideoDecoderSizeTest {
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "video-decoder-size-${Random.nextLong().toULong()}"

    @AfterTest
    fun cleanUp() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    /** SDR to `Rgba8` is swscale alone, so FFmpeg's `scale` into RGBA is the same pass. */
    @Test
    fun sdrSourcesScaleAsFfmpegsBilinearScale() = runBlocking {
        for ((clip, size) in listOf("cfr-30.mp4" to FrameSize(48, 32), "cfr-30-h264.mp4" to FrameSize(40, 24))) {
            val scaled = ffmpeg(
                clip, "${clip.substringBefore('.')}-${size.width}x${size.height}.rgba",
                "-vf", "${scale(size)}:in_color_matrix=bt709,format=rgba", "-f", "rawvideo",
            )
            decoder(clip, VideoOutput.Memory(FrameFormat.Rgba8, size)).use { decoder ->
                decoder.frameAt(Duration.ZERO).use { frame ->
                    assertEquals(size, FrameSize(frame.width, frame.height))
                    assertClose(FileSystem.SYSTEM.read(scaled.toPath()) { readByteArray() }, frame.packed(4), "$clip at $size")
                }
            }
        }
    }

    /**
     * The first swscale step scales, so the transfer step, the tone map and the gamut pass run on
     * the small frame: as FFmpeg's `scale` into 16-bit 4:4:4 in the source's colour, then the same
     * conversion at that size. 4:4:4 keeps FFmpeg from subsampling chroma that the one pass
     * interpolates straight into RGB.
     */
    @Test
    fun hdrSourcesScaleAsFfmpegsBilinearScaleThenToneMap() = runBlocking {
        for ((clip, size) in listOf("hdr10-pq.mp4" to FrameSize(48, 32), "hdr10-pq.mp4" to FrameSize(40, 24), "hlg.mp4" to FrameSize(48, 32))) {
            val scaled = ffmpeg(
                clip, "${clip.substringBefore('.')}-${size.width}x${size.height}.mkv",
                "-vf", "${scale(size)},format=yuv444p16le", "-c:v", "ffv1", "-f", "matroska",
            )
            for (format in listOf(FrameFormat.Rgba8, FrameFormat.RgbaF16)) {
                decoder(clip, VideoOutput.Memory(format, size)).use { sized ->
                    open(scaled, VideoOutput.Memory(format)).use { reference ->
                        sized.frameAt(Duration.ZERO).use { frame ->
                            assertEquals(size, FrameSize(frame.width, frame.height))
                            reference.frameAt(Duration.ZERO).use { expected ->
                                if (format == FrameFormat.Rgba8) {
                                    assertClose(expected.packed(4), frame.packed(4), "$clip at $size")
                                } else {
                                    assertCloseLinear(expected, frame, "$clip at $size in RgbaF16")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun anAnamorphicSourceReportsTheAspectOfEachSize() = runBlocking {
        // 96x64 pixels twice as wide as they are high: 192x64 on screen.
        val clip = ffmpeg("cfr-30.mp4", "anamorphic.mp4", "-vf", "setsar=2/1", "-c:v", "mpeg4", "-q:v", "2")
        open(clip, VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            assertEquals(2.0, decoder.info.sampleAspectRatio)
            decoder.frameAt(Duration.ZERO).use { assertEquals(2.0, it.sampleAspectRatio) }
        }
        for ((size, aspect) in listOf(FrameSize(192, 64) to 1.0, FrameSize(48, 64) to 4.0, FrameSize(96, 32) to 1.0)) {
            open(clip, VideoOutput.Memory(FrameFormat.Rgba8, size)).use { decoder ->
                assertEquals(96, decoder.info.width, "the source's size")
                decoder.frameAt(Duration.ZERO).use { frame ->
                    assertEquals(size, FrameSize(frame.width, frame.height))
                    assertEquals(aspect, frame.sampleAspectRatio, "at $size")
                }
            }
        }
    }

    @Test
    fun aRotatedSourceKeepsItsRotationAtASize() = runBlocking {
        decoder("rotated-90.mp4", VideoOutput.Memory(FrameFormat.Rgba8, FrameSize(48, 32))).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { frame ->
                assertEquals(90.0, frame.rotationDegrees)
                assertEquals(1.0, frame.sampleAspectRatio)
                assertEquals(FrameSize(48, 32), FrameSize(frame.width, frame.height))
                assertEquals(FrameFormat.Rgba8, frame.format)
            }
        }
    }

    @Test
    fun aFrameConvertsIntoATargetOfAnotherSize() = runBlocking {
        decoder("cfr-30.mp4", VideoOutput.Memory(FrameFormat.Rgba8, FrameSize(48, 32))).use { small ->
            decoder("cfr-30.mp4").use { full ->
                small.frameAt(Duration.ZERO).use { expected ->
                    val reference = expected.packed(4)
                    full.frameAt(Duration.ZERO).use { decoded -> decoded.convertInto(expected) }
                    assertEquals(FrameSize(48, 32), FrameSize(expected.width, expected.height))
                    assertClose(reference, expected.packed(4), "convertInto a 48x32 frame")
                }
            }
        }
    }

    /** FFmpeg's bilinear scale, with chroma sited at the centre as the converter takes it into RGB. */
    private fun scale(size: FrameSize) = "scale=${size.width}:${size.height}:flags=bilinear:in_chroma_loc=center"

    /** RGB within two codes. */
    private fun assertClose(expected: ByteArray, actual: ByteArray, name: String) {
        assertEquals(expected.size, actual.size, "$name size")
        var worst = 0
        for (index in expected.indices) {
            if (index % 4 == 3) continue
            worst = maxOf(worst, abs((expected[index].toInt() and 0xff) - (actual[index].toInt() and 0xff)))
        }
        println("$name: max error $worst codes")
        assertTrue(worst <= 2, "$name: $worst codes from FFmpeg's scale")
    }

    /** Linear light within 0.2%, highlights above 1.0 included. */
    private fun assertCloseLinear(expected: VideoFrame, actual: VideoFrame, name: String) {
        var worst = 0.0
        var brightest = 0.0
        for (y in 0 until expected.height) for (x in 0 until expected.width) for (channel in 0 until 3) {
            val reference = expected.linear(x, y, channel)
            worst = maxOf(worst, abs(actual.linear(x, y, channel) - reference) / maxOf(1.0, abs(reference)))
            brightest = maxOf(brightest, reference)
        }
        println("$name: max relative error $worst, brightest $brightest")
        assertTrue(worst <= 0.002, "$name: $worst from FFmpeg's scale")
        assertTrue(brightest > 1.0, "$name keeps its highlights: $brightest")
    }

    /** Runs FFmpeg on the first frame of fixture [clip] into [name] in the test's directory; returns its path. */
    private suspend fun ffmpeg(clip: String, name: String, vararg arguments: String): String {
        FileSystem.SYSTEM.createDirectories(directory)
        val path = (directory / name).toString()
        val result = FFmpegClient().use { client ->
            client.execute(
                listOf("-y", "-i", clip, "-frames:v", "1") + arguments + path,
                CommandIo { input(clip, Buffer().write(readVideoDecoderFixture(clip))) },
            )
        }
        assertTrue(result.isSuccess, result.errorOutput)
        return path
    }

    private suspend fun decoder(name: String, output: VideoOutput = VideoOutput.Memory()): VideoDecoder = VideoDecoder.open(
        MediaSource(name, CommandIo { input(name, Buffer().write(readVideoDecoderFixture(name))) }),
        output,
        DecoderPreference.SOFTWARE,
        10.seconds,
    )

    private suspend fun open(path: String, output: VideoOutput): VideoDecoder =
        VideoDecoder.open(MediaSource(path), output, DecoderPreference.SOFTWARE, 10.seconds)
}
