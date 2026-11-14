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
        assertEquals(2.seconds, FFplayConfiguration().decoderIdleRelease)
        assertEquals(2_000_000, nativeConfiguration(FFplayConfiguration()).codecReleaseDelayUs)
        assertEquals(0, nativeConfiguration(FFplayConfiguration(decoderIdleRelease = Duration.ZERO)).codecReleaseDelayUs)
        assertEquals(
            250_000,
            nativeConfiguration(FFplayConfiguration(decoderIdleRelease = 250.milliseconds)).codecReleaseDelayUs,
        )
        // Negative is the bridge's "keep".
        assertEquals(-1, nativeConfiguration(FFplayConfiguration(decoderIdleRelease = Duration.INFINITE)).codecReleaseDelayUs)
    }

    @Test
    fun aNegativeReleaseDelayIsRejected() {
        assertFailsWith<IllegalArgumentException> { FFplayConfiguration(decoderIdleRelease = (-1).seconds) }
    }
}
