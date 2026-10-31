// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

class DecoderReleaseTest {
    @Test
    fun thePlayerHandsItsDecoderReleaseDelayToTheNativeBridge() = runTest {
        assertEquals(2.seconds, FFplayConfiguration().releaseDecoderWhenPaused)
        assertEquals(2_000_000, nativeConfiguration(FFplayConfiguration()).codecReleaseDelayUs)
        assertEquals(0, nativeConfiguration(FFplayConfiguration(releaseDecoderWhenPaused = Duration.ZERO)).codecReleaseDelayUs)
        assertEquals(
            250_000,
            nativeConfiguration(FFplayConfiguration(releaseDecoderWhenPaused = 250.milliseconds)).codecReleaseDelayUs,
        )
        // Negative is the bridge's "keep".
        assertEquals(-1, nativeConfiguration(FFplayConfiguration(releaseDecoderWhenPaused = null)).codecReleaseDelayUs)
    }

    @Test
    fun aNegativeReleaseDelayIsRejected() {
        assertFailsWith<IllegalArgumentException> { FFplayConfiguration(releaseDecoderWhenPaused = (-1).seconds) }
    }
}
