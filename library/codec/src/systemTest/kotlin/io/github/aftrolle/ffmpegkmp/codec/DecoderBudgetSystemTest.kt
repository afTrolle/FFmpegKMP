// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerError
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoDecoderException
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import okio.Buffer

/** [DecoderBudget] as video decoders see it, where the host decodes H.264 in hardware (VideoToolbox takes this 128x128 clip). */
class DecoderBudgetSystemTest {
    @Test
    fun aFullBudgetOpensSoftwareUnderAutoAndFailsRequireHardware() = runBlocking {
        val limit = DecoderBudget.hardwareDecoders
        val held = DecoderBudget.hardwareDecodersInUse
        DecoderBudget.hardwareDecoders = held + 1
        try {
            decoder(CodecPreference.AUTO).use { first ->
                // A host without a hardware H.264 decoder has nothing to budget.
                if (first.decoderKind != DecoderKind.HARDWARE) return@runBlocking
                assertNull(first.decoderFallback)
                assertEquals(held + 1, DecoderBudget.hardwareDecodersInUse)

                decoder(CodecPreference.AUTO).use { second ->
                    assertEquals(DecoderKind.SOFTWARE, second.decoderKind)
                    assertEquals(DecoderFallbackReason.BUDGET, second.decoderFallback)
                    second.frameAt(Duration.ZERO).use { assertTrue(it.width > 0) }
                }
                val failure = assertFailsWith<VideoDecodingException> { decoder(CodecPreference.REQUIRE_HARDWARE) }
                assertEquals(NativePlayerError.BUSY, (failure.cause as? NativeVideoDecoderException)?.errorCode, "$failure")
                assertEquals(held + 1, DecoderBudget.hardwareDecodersInUse)
            }
            // Closing gave the place back.
            assertEquals(held, DecoderBudget.hardwareDecodersInUse)
            decoder(CodecPreference.AUTO).use { assertEquals(DecoderKind.HARDWARE, it.decoderKind) }
        } finally {
            DecoderBudget.hardwareDecoders = limit
        }
    }

    @Test
    fun softwareDecodersTakeNoPlaceAndReportNoFallback() = runBlocking {
        val held = DecoderBudget.hardwareDecodersInUse
        decoder(CodecPreference.SOFTWARE).use {
            assertEquals(DecoderKind.SOFTWARE, it.decoderKind)
            assertNull(it.decoderFallback)
            assertEquals(held, DecoderBudget.hardwareDecodersInUse)
        }
    }

    @Test
    fun aNegativeLimitIsRejected() {
        assertFailsWith<IllegalArgumentException> { DecoderBudget.hardwareDecoders = -1 }
    }

    private suspend fun decoder(preference: CodecPreference): VideoDecoder {
        val name = "cfr-30-h264-128.mp4"
        return VideoDecoder.open(
            MediaSource(name, CommandIo { input(name, Buffer().write(readVideoDecoderFixture(name))) }),
            decoder = preference,
        )
    }
}
