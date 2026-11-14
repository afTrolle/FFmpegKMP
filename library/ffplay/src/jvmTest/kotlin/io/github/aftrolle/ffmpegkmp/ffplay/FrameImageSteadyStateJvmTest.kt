// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class, InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.aftrolle.ffmpegkmp.bindings.NativeFrameStatistics
import io.github.aftrolle.ffmpegkmp.bindings.nativeFrameStatistics
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
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
        VideoDecoder.open(MediaSource(clip), VideoOutput.Memory(FrameFormat.Rgba8), CodecPreference.SOFTWARE).use { decoder ->
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

    /**
     * A player's Canvas output converts each frame as it is queued into a free bitmap of its ring of
     * queue + 2 and presents it with a flip: the ring holds its bitmaps from the first frames on, and
     * nothing grows after warm-up. Frames go through as a player hands them over, three queued ahead
     * of the one shown, and each present is drawn.
     */
    @Test
    fun aPlayersRingOfQueuePlusTwoAllocatesNoBitmapAfterWarmUp() = runBlocking<Unit> {
        val clip = hdClip()
        val queueSize = 3
        var warm: NativeFrameStatistics? = null
        var warmHeap = 0L
        var warmAllocations = 0
        var count = 0
        VideoDecoder.open(MediaSource(clip), VideoOutput.Memory(FrameFormat.Rgba8), CodecPreference.SOFTWARE).use { decoder ->
            // The ring may make its third buffer at any frame: make all three first.
            (0 until 3).map { index -> decoder.frameAt(1.seconds / 30 * index) }.forEach(VideoFrame::close)
            FrameImage(bitmaps = canvasRingSize(queueSize)).use { image ->
                ComposeFrameRenderer<FrameImage>(1920, 1080) { Canvas(Modifier.fillMaxSize()) { drawFrameImage(it) } }.use { renderer ->
                    val queued = ArrayDeque<FrameImage.Prepared>()
                    suspend fun presentOldest() {
                        val prepared = queued.removeFirst()
                        assertTrue(image.present(prepared), "present ${prepared.pts}")
                        renderer.render(prepared.pts, image).close()
                        count++
                        if (count == 10) {
                            warm = nativeFrameStatistics()
                            warmHeap = heapAfterCollection()
                            warmAllocations = image.allocations
                        }
                    }
                    decoder.frames().collect { frame ->
                        val prepared = assertNotNull(image.prepare(frame))
                        // The decoder's ring of three cannot also hold the queue and the frame on screen, which
                        // the image keeps for a redraw while paused: a player's frames, from libavcodec's own
                        // pool, can. Converted, the frame is not needed here.
                        prepared.frame?.close()
                        prepared.frame = null
                        queued += prepared
                        if (queued.size == queueSize) presentOldest()
                    }
                    while (queued.isNotEmpty()) presentOldest()
                }
                assertEquals(300, count)
                assertEquals(assertNotNull(warm), nativeFrameStatistics(), "native frame buffers after frame 10 and 300")
                val grown = heapAfterCollection() - warmHeap
                println("1080p ring steady state: heap grew by ${grown / 1024} KB from frame 10 to 300, ${image.allocations} bitmaps")
                assertTrue(grown <= 16L * 1024 * 1024, "the heap grew by ${grown / 1024 / 1024} MB from frame 10 to 300")
                assertEquals(warmAllocations, image.allocations, "bitmaps allocated after frame 10")
                assertEquals(canvasRingSize(queueSize), image.allocations)
                assertEquals(canvasRingSize(queueSize), image.ringBitmaps)
                assertEquals(300, image.conversions)
            }
        }
    }

    /**
     * A ring of fewer bitmaps than queue + 2 never grows past what it was given: with one, each frame
     * queued behind the one on screen is converted as it is presented, over that one; with two, into
     * the bitmap that left the screen. Every frame is still converted once and shown.
     */
    @Test
    fun aRingOfOneOrTwoBitmapsShowsEveryFrameInThatMany() = runBlocking<Unit> {
        val clip = hdClip()
        for (bitmaps in 1..2) {
            var count = 0
            VideoDecoder.open(MediaSource(clip), VideoOutput.Memory(FrameFormat.Rgba8), CodecPreference.SOFTWARE).use { decoder ->
                FrameImage(bitmaps = bitmaps, keepsPrevious = false).use { image ->
                    ComposeFrameRenderer<FrameImage>(1920, 1080) { Canvas(Modifier.fillMaxSize()) { drawFrameImage(it) } }.use { renderer ->
                        decoder.frames().take(60).collect { frame ->
                            val expected = sampled(frame)
                            val prepared = assertNotNull(image.prepare(frame))
                            assertTrue(image.present(prepared), "present ${prepared.pts}")
                            renderer.render(prepared.pts, image).use { drawn ->
                                val worst = sampled(drawn).zip(expected) { a, b -> kotlin.math.abs(a - b) }.max()
                                assertTrue(worst <= 2, "$bitmaps bitmaps: frame ${prepared.pts} as drawn is $worst codes off")
                            }
                            count++
                        }
                    }
                    assertEquals(60, count)
                    assertEquals(bitmaps, image.allocations, "bitmaps allocated")
                    assertEquals(bitmaps, image.ringBitmaps)
                    assertEquals(60, image.conversions)
                }
            }
        }
    }

    /**
     * A surface resized at every display frame while it plays keeps showing frames at the video's
     * rate: each is converted at the size of its moment, as it is queued, and nothing is flushed. Its
     * refresh comes from the composition's frame clock, here a renderer drawn every 16 ms.
     */
    @Test
    fun resizingEveryDisplayFrameWhilePlayingKeepsTheCadence() = runBlocking<Unit> {
        val probe = PresentRecorder()
        canvasFrameProbe = probe
        val player = FFplayPlayer(FFplayConfiguration(decoder = CodecPreference.SOFTWARE, audio = false))
        try {
            ComposeFrameRenderer<IntSize>(RENDER_SIZE, RENDER_SIZE) { size ->
                Box(Modifier.requiredSize(size.width.dp, size.height.dp)) { FFplaySurface(player, Modifier.fillMaxSize()) }
            }.use { renderer ->
                val started = System.nanoTime()
                var frame = 0
                suspend fun drawFrame() {
                    // From 16 to 64 pixels and back, a new size every frame: steps of the 64-pixel video.
                    val side = 16 + (frame++ * 7) % 49
                    renderer.render((System.nanoTime() - started).nanoseconds, IntSize(side, side)).close()
                    delay(16)
                }
                drawFrame()
                player.prepare(fixtureSource(GOP_FIXTURE))
                probe.clear()
                player.play()
                val deadline = System.nanoTime() + 20_000_000_000L
                while (player.snapshot.value.state != FFplayState.ENDED) {
                    assertTrue(System.nanoTime() < deadline, "the player did not end: ${player.snapshot.value}")
                    drawFrame()
                }
                val presented = probe.presented.map { it.first }
                assertEquals(presented.distinct().sorted(), presented, "shown out of order")
                val dropped = player.snapshot.value.droppedFrames
                println("resize every frame: ${presented.size} shown, $dropped dropped, gaps ${probe.gapsMillis().max()} ms at most")
                assertEquals(GOP_FIXTURE_FRAMES - 1L, presented.size + dropped, "shown ${presented.size}, dropped $dropped")
                assertTrue(dropped <= GOP_FIXTURE_FRAMES / 10, "dropped $dropped of $GOP_FIXTURE_FRAMES")
                // 40 ms a frame: a frame stuck behind a resize would leave a hole of several.
                assertTrue(probe.gapsMillis().all { it < 200 }, "gaps between frames shown (ms): ${probe.gapsMillis()}")
            }
        } finally {
            canvasFrameProbe = null
            player.close()
        }
    }

    /** Paused, a resize converts the frame on screen again at the new size; drawn at a size, the frame is converted at it. */
    @Test
    fun aPausedResizeRedrawsAtTheNewSize() = runBlocking<Unit> {
        val probe = PresentRecorder()
        canvasFrameProbe = probe
        val player = FFplayPlayer(FFplayConfiguration(decoder = CodecPreference.SOFTWARE, audio = false))
        try {
            ComposeFrameRenderer<IntSize>(RENDER_SIZE, RENDER_SIZE) { size ->
                Box(Modifier.requiredSize(size.width.dp, size.height.dp)) { FFplaySurface(player, Modifier.fillMaxSize()) }
            }.use { renderer ->
                var time = Duration.ZERO
                suspend fun drawnWidth(side: Int): Int? {
                    time += 16.seconds / 1000
                    renderer.render(time, IntSize(side, side)).close()
                    return probe.lastDrawn?.image?.width
                }
                suspend fun awaitDrawnWidth(side: Int) {
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (drawnWidth(side) != side) {
                        assertTrue(System.nanoTime() < deadline, "drawn at $side, the bitmap is ${probe.lastDrawn?.image?.width} wide")
                        delay(10)
                    }
                }
                drawnWidth(64)
                player.prepare(fixtureSource(GOP_FIXTURE))
                // The first frame, converted at its own size: the surface's is the video's.
                awaitDrawnWidth(64)
                player.play()
                repeat(10) { drawnWidth(64) }
                player.pause()
                val deadline = System.nanoTime() + 5_000_000_000L
                while (player.snapshot.value.state != FFplayState.PAUSED) {
                    assertTrue(System.nanoTime() < deadline, "not paused: ${player.snapshot.value}")
                    drawnWidth(64)
                }
                // Paused: smaller, then larger again, each converted anew at the size drawn.
                awaitDrawnWidth(16)
                awaitDrawnWidth(64)
                awaitDrawnWidth(32)
                assertEquals(FFplayState.PAUSED, player.snapshot.value.state)
            }
        } finally {
            canvasFrameProbe = null
            player.close()
        }
    }

    private fun fixtureSource(name: String): MediaSource {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/$name")) { "Missing fixture $name" }.use { it.readBytes() }
        return MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) })
    }

    /** The frames presented on a Canvas surface, with System.nanoTime() of each, and the last image a drawing showed. */
    private class PresentRecorder : CanvasFrameProbe {
        val presented = CopyOnWriteArrayList<Pair<Duration, Long>>()

        @Volatile
        var lastDrawn: FrameImage.Shown? = null

        fun clear() = presented.clear()

        fun gapsMillis(): List<Long> = presented.zipWithNext { a, b -> (b.second - a.second) / 1_000_000 }

        override fun submitting(player: FFplayPlayer, pts: Duration) = Unit

        override fun submitted(player: FFplayPlayer, pts: Duration, shown: FrameImage.Shown?) {
            if (shown != null) presented += pts to System.nanoTime()
        }

        override fun drawn(player: FFplayPlayer, shown: FrameImage.Shown?) {
            lastDrawn = shown
        }
    }

    /** Components of an RGBA8 frame, one pixel in 97. */
    private fun sampled(frame: VideoFrame): List<Int> = assertNotNull(
        frame.usePlanes { planes ->
            val plane = planes.single()
            (0 until frame.width * frame.height step 97).flatMap { pixel ->
                (0 until 3).map { plane.bytes[pixel / frame.width * plane.rowBytes + pixel % frame.width * 4 + it].toInt() and 0xff }
            }
        },
    )

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

private const val RENDER_SIZE = 64

/** 6 s of 64x64 at 25 fps, a keyframe every 12 frames; the bindings' catch-up fixture. */
private const val GOP_FIXTURE = "playback-gop-6s.mp4"
private const val GOP_FIXTURE_FRAMES = 150
