// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
            assertTrue(decoder.decoderKind != DecoderKind.UNKNOWN, "No decoder kind reported")
            decoder.frameAt(1.seconds).use { frame -> assertNotNull(frame.format) }
        }
    }

    private suspend fun open(name: String, preference: DecoderPreference): VideoDecoder {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/video-decoder/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
        return VideoDecoder.open(
            MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }),
            VideoOutput.Memory(),
            preference,
            TIMEOUT,
        )
    }

    private companion object {
        val TIMEOUT: Duration = 3.seconds
        val SLACK: Duration = 3.seconds
    }
}
