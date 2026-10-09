// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import okio.Buffer

/** How long a collector that works on each frame waits for the next one. */
class VideoDecoderPrefetchJvmTest {
    @Test
    fun oneFrameAheadHidesTheDecodingBehindACollectorThatWorks() = runBlocking<Unit> {
        val clip = File.createTempFile("hd-72", ".mp4")
        try {
            // 72 frames at 1920x1080, MPEG-4 Part 2 so every build decodes it in software, well within the
            // collector's 7 ms, also while other test tasks load the machine.
            val result = FFmpegClient().use { client ->
                client.execute(
                    listOf(
                        "-y", "-i", "cfr-30.mp4", "-vf", "scale=1920:1080", "-r", "24", "-frames:v", "72",
                        "-c:v", "mpeg4", "-q:v", "8", "-f", "mp4", clip.path,
                    ),
                    CommandIo { input("cfr-30.mp4", Buffer().write(readVideoDecoderFixture("cfr-30.mp4"))) },
                )
            }
            assertTrue(result.isSuccess, result.errorOutput)
            val ahead = waits(clip)
            println("A collector working 7 ms a frame waited ${ahead.total()} in all")
            // The median, so that a loaded machine's stalls on a few frames do not count: with one
            // frame ahead the next one is ready when the collector asks.
            val median = ahead.sorted()[ahead.size / 2]
            assertTrue(median <= 2.milliseconds, "waited $median for a frame, the median")
        } finally {
            clip.delete()
        }
    }

    /** How long the collector waited for each frame after the first, which nothing decodes ahead of. */
    private suspend fun waits(clip: File): List<Duration> =
        VideoDecoder.open(MediaSource(clip.path), VideoOutput.Memory(FrameFormat.Rgba8), DecoderPreference.SOFTWARE).use { decoder ->
            val waits = mutableListOf<Duration>()
            var asked = TimeSource.Monotonic.markNow()
            decoder.frames().collect { frame ->
                waits += asked.elapsedNow()
                frame.use { Thread.sleep(7) }
                asked = TimeSource.Monotonic.markNow()
            }
            assertEquals(72, waits.size)
            waits.drop(1)
        }

    private fun List<Duration>.total(): Duration = fold(Duration.ZERO, Duration::plus)
}
