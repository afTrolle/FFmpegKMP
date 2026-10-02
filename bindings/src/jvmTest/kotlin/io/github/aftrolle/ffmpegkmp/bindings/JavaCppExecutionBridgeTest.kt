// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
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
        createPlatformExecutionBridge().use { nativeBridge ->
            val run = async(Dispatchers.IO) {
                nativeBridge.execute(transcode(1, frames = MINUTE_OF_FRAMES, paced = true), {})
            }
            delay(2.seconds)
            nativeBridge.cancel(1)

            val result = withTimeout(30.seconds) { run.await() }
            assertEquals(255, result.returnCode)
        }
    }

    @Test
    fun cancelIssuedBeforeTheRunStartsStillStopsIt() = runBlocking {
        createPlatformExecutionBridge().use { nativeBridge ->
            nativeBridge.cancel(1)

            val elapsed = measureTime {
                nativeBridge.execute(transcode(1, frames = MINUTE_OF_FRAMES, paced = true), {})
            }
            assertTrue(elapsed < 30.seconds, "The cancelled run took $elapsed")
        }
    }

    @Test
    fun cancelNamingAnEarlierRunLeavesTheNextOneAlone() = runBlocking {
        createPlatformExecutionBridge().use { nativeBridge ->
            val output = Buffer()
            nativeBridge.execute(transcode(1, frames = 1, paced = false), {})
            nativeBridge.cancel(1)

            val next = nativeBridge.execute(transcode(2, frames = 1, paced = false, output), {})
            assertEquals(0, next.returnCode)
            assertTrue(output.size > 0L)
        }
    }

    // With paced set, -re reads the input at its own frame rate, so a minute of frames is a
    // minute of work that only a cancel can finish early.
    private fun transcode(
        id: Long,
        frames: Int,
        paced: Boolean,
        output: Buffer = Buffer(),
    ): NativeExecutionRequest {
        val input = Buffer().write(ByteArray(16 * 16 * 3 * frames))
        return NativeExecutionRequest(
            id = id,
            kind = NativeCommandKind.FFMPEG,
            arguments = listOfNotNull(
                "-y",
                "-re".takeIf { paced },
                "-f", "rawvideo",
                "-pixel_format", "rgb24",
                "-video_size", "16x16",
                "-framerate", "$FRAME_RATE",
                "-i", "input.rgb",
                "-c:v", "rawvideo",
                "-f", "nut",
                "output.nut",
            ),
            mounts = listOf(
                NativeMountedIo("input.rgb", NativeSourceResource(input)),
                NativeMountedIo("output.nut", NativeSinkResource(output)),
            ),
        )
    }

    private fun request(id: Long, kind: NativeCommandKind, vararg arguments: String) =
        NativeExecutionRequest(id, kind, arguments.toList())

    private companion object {
        const val FRAME_RATE = 25
        const val MINUTE_OF_FRAMES = FRAME_RATE * 60
    }
}
