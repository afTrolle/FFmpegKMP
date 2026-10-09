// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.graphics.asSkiaBitmap
import io.github.aftrolle.ffmpegkmp.bindings.nativeFrameStatistics
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.MediaSource
import io.github.aftrolle.ffmpegkmp.codec.PixelLayout
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.jetbrains.skia.ColorType

class VideoFrameImageJvmTest {
    private val directory = createTempDirectory("video-frame-image").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun a4kFramesPixelsAreInOneDecoderBufferAndOneBitmap() = runBlocking<Unit> {
        VideoDecoder.open(MediaSource(uhdClip()), VideoOutput.Memory(), DecoderPreference.SOFTWARE).use { decoder ->
            repeat(3) { index ->
                val before = nativeFrameStatistics()
                val image = decoder.frameAt(1.seconds / 24 * index).use { frame ->
                    // The decoder's own buffer, handed out as decoded.
                    assertEquals(PixelLayout.YUV420P, frame.format?.layout)
                    frame.toImageBitmap()
                }
                // No pooled frame, no converter intermediate and no download: the conversion went straight
                // from the decoder's buffer into the bitmap's memory.
                assertEquals(before, nativeFrameStatistics(), "frame $index")
                val bitmap = image.asSkiaBitmap()
                assertEquals(ColorType.RGBA_8888, bitmap.colorType)
                assertEquals(3840 to 2160, bitmap.width to bitmap.height)
            }
        }
    }

    @Test
    fun aConvertedFrameIsCopiedOnceIntoTheBitmap() = runBlocking<Unit> {
        VideoDecoder.open(MediaSource(uhdClip()), VideoOutput.Memory(FrameFormat.Rgba8), DecoderPreference.SOFTWARE).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { it.toImageBitmap() }
            val before = nativeFrameStatistics()
            // The pooled RGBA8 frame of the first call is reused, and the bitmap takes the one copy.
            decoder.frameAt(1.seconds / 24).use { frame ->
                assertEquals(FrameFormat.Rgba8, frame.format)
                assertEquals(ColorType.RGBA_8888, frame.toImageBitmap().asSkiaBitmap().colorType)
            }
            assertEquals(before, nativeFrameStatistics())
        }
    }

    /** Three frames of cfr-30.mp4 scaled to 3840x2160, MPEG-4 Part 2 so every build decodes it in software. */
    private suspend fun uhdClip(): String {
        val path = directory.resolve("uhd.mp4").absolutePath
        val result = FFmpegClient().use { client ->
            client.execute(
                listOf(
                    "-y", "-i", "cfr-30.mp4", "-vf", "scale=3840:2160", "-frames:v", "3",
                    "-c:v", "mpeg4", "-q:v", "8", "-f", "mp4", path,
                ),
                CommandIo { input("cfr-30.mp4", Buffer().write(readVideoDecoderFixture("cfr-30.mp4"))) },
            )
        }
        assertTrue(result.isSuccess, result.errorOutput)
        return path
    }
}
