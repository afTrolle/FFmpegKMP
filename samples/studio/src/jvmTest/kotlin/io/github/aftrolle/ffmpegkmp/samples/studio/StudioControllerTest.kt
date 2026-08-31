// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import kotlin.test.Test
import kotlin.test.assertEquals

class StudioControllerTest {
    @Test
    fun logsSuccessWhenOutputIsTaggedHdr() {
        val line = formatHdrCheckLog(Result.success(true to "smpte2084"))
        assertEquals("HDR10 check: output is tagged HDR (color_transfer=smpte2084)", line)
    }

    @Test
    fun logsFailureWhenOutputIsNotTaggedHdr() {
        val line = formatHdrCheckLog(Result.success(false to "bt709"))
        assertEquals("HDR10 check FAILED: output is not tagged HDR (color_transfer=bt709)", line)
    }

    @Test
    fun logsFailureWithUnknownWhenColorTransferIsMissing() {
        val line = formatHdrCheckLog(Result.success(false to null))
        assertEquals("HDR10 check FAILED: output is not tagged HDR (color_transfer=unknown)", line)
    }

    @Test
    fun logsFailureWhenProbingThrows() {
        val line = formatHdrCheckLog(Result.failure(IllegalStateException("boom")))
        assertEquals("HDR10 check FAILED: could not probe output (boom)", line)
    }
}
