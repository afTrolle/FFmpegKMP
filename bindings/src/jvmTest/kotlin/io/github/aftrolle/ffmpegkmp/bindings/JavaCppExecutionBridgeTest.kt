// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking

class JavaCppExecutionBridgeTest {
    @Test
    fun repeatedCommandsDoNotExitOrLeakState() = runBlocking {
        createPlatformExecutionBridge().use { nativeBridge ->
            val first = nativeBridge.execute(
                request(1, NativeCommandKind.FFMPEG, "-version"),
                {},
            )
            val invalid = nativeBridge.execute(
                request(2, NativeCommandKind.FFPROBE, "-definitely-not-an-option"),
                {},
            )
            val last = nativeBridge.execute(
                request(3, NativeCommandKind.FFPROBE, "-version"),
                {},
            )

            assertEquals(0, first.returnCode)
            assertNotEquals(0, invalid.returnCode)
            assertEquals(0, last.returnCode)
        }
    }

    private fun request(id: Long, kind: NativeCommandKind, vararg arguments: String) =
        NativeExecutionRequest(id, kind, arguments.toList())
}
