// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assume.assumeFalse

class VideoDecoderSurfaceDeviceTest {
    private val callbacks = HandlerThread("VideoDecoderSurfaceDeviceTest").apply { start() }
    private val reader = ImageReader.newInstance(96, 64, ImageFormat.YUV_420_888, 3)
    private val timestamps = LinkedBlockingQueue<Long>()

    init {
        reader.setOnImageAvailableListener(
            { source -> source.acquireLatestImage()?.use { timestamps.put(it.timestamp) } },
            Handler(callbacks.looper),
        )
    }

    @AfterTest
    fun cleanUp() {
        reader.close()
        callbacks.quitSafely()
    }

    @Test
    fun surfaceOutputRendersTheFrameAtEachPositionIntoAnImageReader() = runBlocking {
        // The emulator's goldfish decoders either reject FFmpeg's input (API 37) or take it and never output (API 35),
        // and FFmpeg skips software-only MediaCodec decoders, so this needs a real device.
        assumeFalse("no emulator decoder renders to a Surface through FFmpeg", Build.HARDWARE == "ranchu")
        val name = "cfr-30-h264.mp4"
        val bytes = checkNotNull(javaClass.getResourceAsStream("/video-decoder/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
        VideoDecoder.open(
            FFplaySource(name, CommandIo { input(name, Buffer().write(bytes)) }),
            VideoOutput.Surface(reader.surface),
        ).use { decoder ->
            assertEquals(FFplayDecoderKind.HARDWARE, decoder.decoderKind, "MediaCodec decodes H.264 on every device")
            for (index in listOf(0, 1, 2, 30, 31, 90, 12)) {
                val frame = decoder.frameAt(index.seconds / 30)
                assertNull(frame.image)
                val timestamp = assertNotNull(timestamps.poll(2, TimeUnit.SECONDS), "No image for frame $index")
                assertEquals(frame.pts.inWholeNanoseconds, timestamp, "Buffer timestamp of frame $index")
            }
            // A position the current frame still covers renders nothing new.
            decoder.frameAt(12.seconds / 30 + 0.01.seconds)
            assertNull(timestamps.poll(200, TimeUnit.MILLISECONDS))
        }
    }
}
