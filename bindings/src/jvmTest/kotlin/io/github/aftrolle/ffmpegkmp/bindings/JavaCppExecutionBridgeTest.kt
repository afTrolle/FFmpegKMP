// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Buffer

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

    @Test
    fun stagesSinkOutput() = runBlocking {
        val input = Buffer().write(ByteArray(16 * 16 * 3))
        val output = Buffer()

        createPlatformExecutionBridge().use { nativeBridge ->
            val result = nativeBridge.execute(
                NativeExecutionRequest(
                    id = 1,
                    kind = NativeCommandKind.FFMPEG,
                    arguments = listOf(
                        "-y",
                        "-f", "rawvideo",
                        "-pixel_format", "rgb24",
                        "-video_size", "16x16",
                        "-i", "input.rgb",
                        "-frames:v", "1",
                        "-c:v", "mpeg4",
                        "output.mp4",
                    ),
                    mounts = listOf(
                        NativeMountedIo("input.rgb", NativeSourceResource(input)),
                        NativeMountedIo("output.mp4", NativeSinkResource(output)),
                    ),
                ),
                {},
            )

            assertEquals(0, result.returnCode)
            assertTrue(output.size > 0L)
            assertEquals("ftyp", output.snapshot().substring(4, 8).utf8())
        }
    }

    private fun request(id: Long, kind: NativeCommandKind, vararg arguments: String) =
        NativeExecutionRequest(id, kind, arguments.toList())
}
