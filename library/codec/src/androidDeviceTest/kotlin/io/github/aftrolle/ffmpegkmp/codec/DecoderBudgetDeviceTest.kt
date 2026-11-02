// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import android.util.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What MediaCodecList says about decoding in hardware, through [DecoderBudget.hardwareSupport]. */
class DecoderBudgetDeviceTest {
    @Test
    fun mediaCodecSaysHowManyHardwareDecodersMayOpen() {
        // Every Android device decodes 1080p H.264 in hardware.
        val h264 = DecoderBudget.hardwareSupport("h264", 1920, 1080)
        assertEquals(true, h264.available, "$h264")
        assertTrue((h264.maxInstances ?: 0) > 0, "$h264")
        for (codec in listOf("hevc", "vp9", "av1")) {
            Log.i(TAG, "$codec at 3840x2160: ${DecoderBudget.hardwareSupport(codec, 3840, 2160)}")
        }
        // No hardware decoder takes 64K, nor a codec MediaCodec does not have.
        assertEquals(false, DecoderBudget.hardwareSupport("h264", 65536, 65536).available)
        assertEquals(HardwareDecoderSupport(available = false, maxInstances = null), DecoderBudget.hardwareSupport("prores", 1920, 1080))
    }

    private companion object {
        const val TAG = "DecoderBudgetDeviceTest"
    }
}
