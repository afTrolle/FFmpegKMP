// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonPrimitive

class WasmWorkerRequestTest {
    @Test
    fun executionRequestIncludesWorkerProtocolType() {
        val request = NativeExecutionRequest(
            id = 1,
            kind = NativeCommandKind.FFMPEG,
            arguments = listOf("-version"),
        )

        assertEquals("execute", request.toWorkerJson().getValue("type").jsonPrimitive.content)
    }
}
