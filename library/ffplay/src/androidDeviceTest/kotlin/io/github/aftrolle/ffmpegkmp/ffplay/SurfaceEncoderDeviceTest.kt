// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.FrameRate
import io.github.aftrolle.ffmpegkmp.codec.MediaOutput
import io.github.aftrolle.ffmpegkmp.codec.MediaSource
import io.github.aftrolle.ffmpegkmp.codec.MediaWriter
import io.github.aftrolle.ffmpegkmp.codec.MediaWritingException
import io.github.aftrolle.ffmpegkmp.codec.VideoCodec
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoEncoderConfig
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import io.github.aftrolle.ffmpegkmp.codec.hardwareBuffer
import io.github.aftrolle.ffmpegkmp.codec.hardwareBufferCrop
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import org.junit.Assume.assumeTrue

/**
 * Rendered frames reaching a hardware encoder with no copy (Android 14 and later), then decoded
 * back: the renderer draws into buffers of the encoder's input surface, `track.write` queues them
 * with their pts, and the encoder's packets are muxed through the writer's packet track. Where the
 * device has no such encoder the renderer keeps the one-copy path and the tests still pass, except
 * where they say a track must be zero-copy. The emulator's MediaCodec encoders take frames without
 * giving packets, so there a writer that timed out, within its timeout, is the other outcome that
 * passes.
 *
 * ```
 * ./gradlew :library:ffplay:connectedAndroidDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.aftrolle.ffmpegkmp.ffplay.SurfaceEncoderDeviceTest
 * ```
 */
class SurfaceEncoderDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "surface-encoder-${Random.nextLong().toULong()}"

    init {
        FileSystem.SYSTEM.createDirectories(directory)
    }

    @AfterTest
    fun cleanUp() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    @Test
    fun thirtyRenderedFramesEncodeThroughTheSurfaceAndDecodeBackFrameForFrame() = runBlocking {
        assumeTrue("Needs Android 14", Build.VERSION.SDK_INT >= 34)
        for (codec in listOf(VideoCodec.H264, VideoCodec.HEVC)) {
            val config = VideoEncoderConfig(SIZE, SIZE, FrameRate(30), codec)
            if (!MediaWriter.canEncode(config)) {
                println("Skipped $codec: no encoder on this device")
                continue
            }
            val output = directory / "$codec.mp4"
            timesOutOnTheEmulator {
                MediaWriter.open(MediaOutput.File(output.toString()), timeout = TIMEOUT).use { writer ->
                    val track = writer.addVideoTrack(config)
                    assertFalse(track.zeroCopy, "$codec: the track is zero-copy once a renderer asks for it")
                    ComposeFrameRenderer<Int>(context, track) { Numbered(it) }.use { renderer ->
                        for (index in 0 until FRAMES) {
                            val frame = renderer.render(pts(index), index)
                            if (track.zeroCopy) {
                                assertNull(frame.format, "$codec: a frame over the encoder's buffer has no pixels in memory")
                                assertNotNull(frame.hardwareBuffer, "$codec: its buffer")
                                assertEquals(android.graphics.Rect(0, 0, SIZE, SIZE), frame.hardwareBufferCrop)
                            }
                            assertEquals(pts(index), frame.pts)
                            track.write(frame)
                        }
                        // A hardware encoder on a phone takes the surface; the emulator's may not.
                        if (Build.HARDWARE != "ranchu") assertTrue(track.zeroCopy, "$codec: the surface encoder is open")
                    }
                    assertEquals(FRAMES.toLong(), writer.finish().videoFrames)
                }
                VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.Rgba8), DecoderPreference.SOFTWARE).use { decoder ->
                    assertEquals(SIZE, decoder.info.width)
                    val frames = decoder.frames().map { frame -> frame.use { it.pts to it.number() } }.toList()
                    assertEquals((0 until FRAMES).toList(), frames.map { it.second }, "$codec: frame numbers")
                    frames.forEachIndexed { index, (pts, _) ->
                        assertTrue(abs((pts - pts(index)).inWholeMicroseconds) < 1000, "$codec: frame $index at $pts, not ${pts(index)}")
                    }
                }
            }
        }
    }

    @Test
    fun theSurfaceEncodeKeepsTheOneCopyPathsColours() = runBlocking {
        assumeTrue("Needs Android 14", Build.VERSION.SDK_INT >= 34)
        val config = VideoEncoderConfig(SIZE, SIZE, FrameRate(30))
        assumeTrue("No encoder", MediaWriter.canEncode(config))
        timesOutOnTheEmulator {
            // The same content through the surface and, with a renderer that has no track to draw into, through the copy.
            val patches = listOf(true, false).map { surface ->
                val output = directory / "colours-$surface.mp4"
                MediaWriter.open(MediaOutput.File(output.toString()), timeout = TIMEOUT).use { writer ->
                    val track = writer.addVideoTrack(config)
                    val renderer = if (surface) {
                        ComposeFrameRenderer<Int>(context, track) { Numbered(it) }
                    } else {
                        ComposeFrameRenderer<Int>(context, SIZE, SIZE, config.canvasFormat) { Numbered(it) }
                    }
                    renderer.use { for (index in 0 until 10) track.write(it.render(pts(index), index)) }
                    if (!surface) assertFalse(track.zeroCopy) else if (Build.HARDWARE != "ranchu") assertTrue(track.zeroCopy)
                    writer.finish()
                }
                VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.Rgba8), DecoderPreference.SOFTWARE).use { decoder ->
                    decoder.frameAt(Duration.ZERO).use { it.pixel(x = SIZE / 2, y = SIZE * 3 / 4) }
                }
            }
            val (viaSurface, viaCopy) = patches
            // 8 of 255 covers the encoders' own rate control and a BT.709 conversion done by the encoder rather than swscale.
            assertTrue(viaSurface.indices.all { abs(viaSurface[it] - viaCopy[it]) <= 8 }, "surface ${viaSurface.toList()}, copy ${viaCopy.toList()}")
            assertTrue(viaSurface.indices.all { abs(viaSurface[it] - PATCH_RGB[it]) <= 12 }, "patch ${viaSurface.toList()}, not $PATCH_RGB")
        }
    }

    /** Runs [block]; on the emulator a writer that timed out, within the timeout, passes too. */
    private suspend fun timesOutOnTheEmulator(block: suspend () -> Unit) {
        if (Build.HARDWARE != "ranchu") return block()
        val started = TimeSource.Monotonic.markNow()
        try {
            block()
        } catch (failure: MediaWritingException) {
            assertTrue("timed out" in failure.message.orEmpty(), "Unexpected $failure")
            assertTrue(started.elapsedNow() < TIMEOUT * 3, "Timing out took ${started.elapsedNow()}")
            println("The emulator's encoder timed out: ${failure.message}")
        }
    }

    /** The frame number [Numbered] draws as 16x16 cells in the top rows, read from the red channel. */
    private fun VideoFrame.number(): Int = (0 until 12).sumOf { bit ->
        if (pixel(x = (bit % 6) * 16 + 8, y = (bit / 6) * 16 + 8)[0] > 128) 1 shl bit else 0
    }

    private fun VideoFrame.pixel(x: Int, y: Int): IntArray = assertNotNull(
        usePlanes { planes ->
            val plane = planes.single()
            IntArray(3) { plane.bytes[y * plane.rowBytes + x * 4 + it].toInt() and 0xff }
        },
    )

    /** The frame at [index] of a 30 fps clip, rounded to the nearest nanosecond as the decoder does. */
    private fun pts(index: Int): Duration = ((index * 1_000_000_000L + 15) / 30).nanoseconds

    private companion object {
        const val SIZE = 128
        const val FRAMES = 30
        val TIMEOUT = 5.seconds
        val PATCH_RGB = listOf(200, 100, 40)
    }
}

/** Black with the 12-bit [number] as white 16x16 cells in the top two rows, and an orange patch below. */
@Composable
private fun Numbered(number: Int) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color.Black)
        for (bit in 0 until 12) {
            if (number shr bit and 1 == 1) drawRect(Color.White, Offset((bit % 6) * 16f, (bit / 6) * 16f), Size(16f, 16f))
        }
        drawRect(Color(200, 100, 40), Offset(0f, size.height / 2), Size(size.width, size.height / 2))
    }
}
