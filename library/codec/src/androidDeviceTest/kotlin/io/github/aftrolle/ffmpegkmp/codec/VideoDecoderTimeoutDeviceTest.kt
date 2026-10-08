// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import okio.Buffer

/**
 * Bounded MediaCodec paths. The emulator's goldfish decoders either reject FFmpeg's input (API 37)
 * or take it and never output (API 35); both must end within the timeout instead of spinning.
 */
class VideoDecoderTimeoutDeviceTest {
    private val callbacks = HandlerThread("VideoDecoderTimeoutDeviceTest").apply { start() }
    private val reader = ImageReader.newInstance(96, 64, ImageFormat.YUV_420_888, 3).apply {
        setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(callbacks.looper))
    }

    @AfterTest
    fun cleanUp() {
        reader.close()
        callbacks.quitSafely()
    }

    @Test
    fun requireHardwareOnMpeg4EitherDecodesInHardwareOrFailsWithinTheTimeout() = runBlocking<Unit> {
        val started = TimeSource.Monotonic.markNow()
        val decoder = runCatching { open("cfr-30.mp4", DecoderPreference.REQUIRE_HARDWARE) }
        assertTrue(started.elapsedNow() < TIMEOUT + SLACK, "open took ${started.elapsedNow()}")
        decoder.onSuccess { opened ->
            opened.use { assertEquals(DecoderKind.HARDWARE, it.decoderKind) }
        }.onFailure { failure ->
            assertTrue(failure is VideoDecodingException, "Unexpected $failure")
        }
    }

    @Test
    fun autoFallsBackToSoftwareOnceWhenHardwareStallsOrFails() = runBlocking<Unit> {
        val started = TimeSource.Monotonic.markNow()
        open("cfr-30-h264.mp4", DecoderPreference.AUTO).use { decoder ->
            // The hardware attempt and the software fallback each get the timeout.
            assertTrue(started.elapsedNow() < TIMEOUT * 2 + SLACK, "open took ${started.elapsedNow()}")
            decoder.frameAt(1.seconds).use { frame ->
                when (decoder.decoderKind) {
                    DecoderKind.HARDWARE -> assertNull(frame.format)
                    DecoderKind.SOFTWARE -> assertNotNull(frame.format, "The software fallback returns frames in memory")
                    DecoderKind.UNKNOWN -> error("No decoder kind reported")
                }
            }
        }
    }

    private suspend fun open(name: String, preference: DecoderPreference): VideoDecoder {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/video-decoder/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
        return VideoDecoder.open(
            MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }),
            VideoOutput.Surface(reader.surface),
            preference,
            TIMEOUT,
        )
    }

    private companion object {
        val TIMEOUT: Duration = 3.seconds
        val SLACK: Duration = 3.seconds
    }
}
