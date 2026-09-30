// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class VideoDecoderPixelBufferJvmTest {
    @Test
    fun pixelBufferOutputIsAppleOnly() = runBlocking<Unit> {
        assertFailsWith<UnsupportedOperationException> {
            VideoDecoder.open(FFplaySource("cfr-30.mp4"), VideoOutput.PixelBuffer)
        }
    }
}
