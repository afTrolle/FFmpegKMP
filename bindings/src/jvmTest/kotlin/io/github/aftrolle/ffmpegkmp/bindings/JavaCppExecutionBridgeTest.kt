// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun plainSinkMountWritesAFormatThatDoesNotNeedToSeek() = runBlocking {
        // Sink mounts are plain and non-seekable at this layer; formats that mux forward-only
        // (unlike the default, non-fragmented MP4 writer) work with a raw Sink. Staging for
        // formats that do need to seek is a caller-level opt-in — see library:core's
        // CompiledRuntimeIntegrationTest — not something this bridge does implicitly.
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
                        "-c:v", "rawvideo",
                        "-f", "nut",
                        "output.nut",
                    ),
                    mounts = listOf(
                        NativeMountedIo("input.rgb", NativeSourceResource(input)),
                        NativeMountedIo("output.nut", NativeSinkResource(output)),
                    ),
                ),
                {},
            )

            assertEquals(0, result.returnCode)
            assertTrue(output.size > 0L)
        }
    }

    @Test
    fun cancelStopsATranscodeThatIsStillRunning() = runBlocking {
        // -re paces the read at the input's own frame rate, so this is a minute of work that
        // only a cancel can finish inside the timeout.
        val frame = 16 * 16 * 3
        val input = Buffer().write(ByteArray(frame * 25 * 60))

        createPlatformExecutionBridge().use { nativeBridge ->
            val run = async(Dispatchers.IO) {
                nativeBridge.execute(
                    NativeExecutionRequest(
                        id = 1,
                        kind = NativeCommandKind.FFMPEG,
                        arguments = listOf(
                            "-y",
                            "-re",
                            "-f", "rawvideo",
                            "-pixel_format", "rgb24",
                            "-video_size", "16x16",
                            "-framerate", "25",
                            "-i", "input.rgb",
                            "-c:v", "rawvideo",
                            "-f", "nut",
                            "output.nut",
                        ),
                        mounts = listOf(
                            NativeMountedIo("input.rgb", NativeSourceResource(input)),
                            NativeMountedIo("output.nut", NativeSinkResource(Buffer())),
                        ),
                    ),
                    {},
                )
            }
            delay(2.seconds)
            nativeBridge.cancel(1)

            val result = withTimeout(30.seconds) { run.await() }
            assertEquals(255, result.returnCode)
        }
    }

    private fun request(id: Long, kind: NativeCommandKind, vararg arguments: String) =
        NativeExecutionRequest(id, kind, arguments.toList())
}
