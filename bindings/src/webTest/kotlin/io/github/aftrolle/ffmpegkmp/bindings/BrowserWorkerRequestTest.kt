// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer

class BrowserWorkerRequestTest {
    @Test
    fun executionRequestIncludesWorkerProtocolType() {
        val request = NativeExecutionRequest(
            id = 1,
            kind = NativeCommandKind.FFMPEG,
            arguments = listOf("-version"),
        )

        assertEquals("execute", request.toWorkerJson().getValue("type").jsonPrimitive.content)
    }

    @Test
    fun mountedBytesAreTransferredOutsideJson() {
        val request = NativeExecutionRequest(
            id = 2,
            kind = NativeCommandKind.FFMPEG,
            arguments = listOf("-i", "input.bin"),
            mounts = listOf(
                NativeMountedIo(
                    path = "input.bin",
                    resource = NativeSourceResource(Buffer().write(byteArrayOf(0, 1, 2, -1))),
                ),
            ),
        )

        val mountJson = request.toWorkerJson().getValue("mounts").jsonArray.single().jsonObject

        assertFalse("base64" in mountJson)
        assertContentEquals(byteArrayOf(0, 1, 2, -1), request.readMountBytes().single())
    }
}
