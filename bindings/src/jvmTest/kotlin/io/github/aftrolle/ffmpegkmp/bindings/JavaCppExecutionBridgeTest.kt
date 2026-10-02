// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
        val output = Buffer()

        createPlatformExecutionBridge().use { nativeBridge ->
            val result = nativeBridge.execute(transcode(1, frames = 1, paced = false, output), {})

            assertEquals(0, result.returnCode)
            assertTrue(output.size > 0L)
        }
    }

    // runBlocking on purpose: these drive real FFmpeg threads, `-re` paces in wall-clock time,
    // and the unwinding bound below is a wall-clock measurement.
    @Test
    fun cancellingTheCallerStopsATranscodeThatIsStillRunning() = runBlocking {
        createPlatformExecutionBridge().use { nativeBridge ->
            val transcoding = CompletableDeferred<Unit>()
            val run = async {
                nativeBridge.execute(transcode(1, frames = MINUTE_OF_FRAMES, paced = true), transcoding::completeOnStats)
            }
            withTimeout(30.seconds) { transcoding.await() }

            val unwinding = measureTime { run.cancelAndJoin() }

            assertTrue(run.isCancelled)
            // FFmpeg notices the cancel at its next status wake-up (-stats_period, 0.5 s) and
            // then writes the trailer; a minute-long encode must not run on.
            assertTrue(unwinding < 30.seconds, "The cancelled run took $unwinding to unwind")
        }
    }

    @Test
    fun aCancelledRunLeavesTheBridgeReadyForTheNextOne() = runBlocking {
        createPlatformExecutionBridge().use { nativeBridge ->
            val transcoding = CompletableDeferred<Unit>()
            val cancelled = async {
                nativeBridge.execute(transcode(1, frames = MINUTE_OF_FRAMES, paced = true), transcoding::completeOnStats)
            }
            withTimeout(30.seconds) { transcoding.await() }
            cancelled.cancelAndJoin()

            val output = Buffer()
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

/** FFmpeg's first `frame=` status line means the transcode loop, not just setup, is running. */
private fun CompletableDeferred<Unit>.completeOnStats(event: NativeExecutionEvent) {
    if (event is NativeExecutionEvent.Log && "frame=" in event.message) complete(Unit)
}
