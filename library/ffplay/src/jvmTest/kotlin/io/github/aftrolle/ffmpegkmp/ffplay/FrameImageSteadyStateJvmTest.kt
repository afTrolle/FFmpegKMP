// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.aftrolle.ffmpegkmp.bindings.NativeFrameStatistics
import io.github.aftrolle.ffmpegkmp.bindings.nativeFrameStatistics
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.MediaSource
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okio.Buffer

/** A source drawn frame after frame allocates nothing once running: the decoder's ring, two bitmaps and the renderer's pool. */
class FrameImageSteadyStateJvmTest {
    private val directory = createTempDirectory("frame-image-steady").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun threeHundredFramesOf1080pThroughUpdateAndRenderAllocateNothingAfterTheFirstTen() = runBlocking<Unit> {
        val clip = hdClip()
        var warm: NativeFrameStatistics? = null
        var warmHeap = 0L
        var count = 0
        VideoDecoder.open(MediaSource(clip), VideoOutput.Memory(FrameFormat.Rgba8), DecoderPreference.SOFTWARE).use { decoder ->
            // The ring may make its third buffer at any frame, whenever a hand-out comes before a close
            // that usually precedes it: make all three first.
            (0 until 3).map { index -> decoder.frameAt(1.seconds / 30 * index) }.forEach(VideoFrame::close)
            FrameImage().use { image ->
                ComposeFrameRenderer<FrameImage>(1920, 1080) { Canvas(Modifier.fillMaxSize()) { drawFrameImage(it) } }.use { renderer ->
                    decoder.frames().collect { frame ->
                        frame.use { image.update(it) }
                        renderer.render(frame.pts, image).close()
                        count++
                        if (count == 10) {
                            warm = nativeFrameStatistics()
                            warmHeap = heapAfterCollection()
                        }
                    }
                }
                assertEquals(300, count)
                assertEquals(assertNotNull(warm), nativeFrameStatistics(), "native frame buffers after frame 10 and 300")
                val grown = heapAfterCollection() - warmHeap
                println("1080p steady state: heap grew by ${grown / 1024} KB from frame 10 to 300, ${image.allocations} bitmaps")
                assertTrue(grown <= 16L * 1024 * 1024, "the heap grew by ${grown / 1024 / 1024} MB from frame 10 to 300")
                assertEquals(2, image.allocations)
            }
        }
    }

    private fun heapAfterCollection(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) { System.gc() }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** 300 frames of cfr-30.mp4, twice over, scaled to 1920x1080, MPEG-4 Part 2 so every build decodes it in software. */
    private suspend fun hdClip(): String {
        val path = directory.resolve("hd.mp4").absolutePath
        val result = FFmpegClient().use { client ->
            client.execute(
                listOf(
                    "-y", "-stream_loop", "1", "-i", "cfr-30.mp4", "-vf", "scale=1920:1080", "-frames:v", "300",
                    "-c:v", "mpeg4", "-q:v", "8", "-f", "mp4", path,
                ),
                CommandIo { input("cfr-30.mp4", Buffer().write(readVideoDecoderFixture("cfr-30.mp4"))) },
            )
        }
        assertTrue(result.isSuccess, result.errorOutput)
        return path
    }
}
