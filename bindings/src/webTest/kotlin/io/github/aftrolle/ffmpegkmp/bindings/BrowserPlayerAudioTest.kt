// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import okio.Buffer

class BrowserPlayerAudioTest {
    @Test
    fun playsTheSourcesAudioInTheWorkerAndReportsTheAudibleClock() = runTest {
        configureBrowserPlayerTestRuntime()
        val media = encodeAudioVideoFixture()
        val bridge = createPlatformPlayerBridge(
            configuration = NativePlayerConfiguration(),
            update = {},
            frame = {},
            platformFrame = { false },
        )
        try {
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities(softwareFrameUpload = true)), "setOutput")
            assertEquals(
                0,
                bridge.prepare(
                    NativePlayerSource(
                        input = FIXTURE,
                        mounts = listOf(NativeMountedIo(FIXTURE, NativeSourceResource(Buffer().write(media)))),
                    ),
                ),
                "prepare",
            )
            val prepared = bridge.awaitPreparation()
            assertEquals(0, prepared, "prepare failed with $prepared: ${bridge.snapshot()}")

            val audio = assertNotNull(bridge.openAudio())
            assertEquals(1, audio.tracks.size)
            assertEquals("pcm_s16le", audio.tracks.single().codec)
            assertTrue(audio.isTrackEnabled(0))
            assertTrue(audio.durationMicros in 900_000L..1_100_000L, "duration ${audio.durationMicros}")

            val advanced = CompletableDeferred<Long>()
            val ended = CompletableDeferred<Unit>()
            audio.setProgressListener { progress ->
                assertFalse(progress.blockedByAutoplay, "The test browser must allow autoplay")
                progress.failure?.let { advanced.completeExceptionally(AssertionError(it)) }
                if (progress.positionMicros > 200_000) advanced.complete(progress.positionMicros)
                if (progress.ended) ended.complete(Unit)
            }
            audio.play()

            assertTrue(advanced.await() <= audio.durationMicros)
            ended.await()

            // A seek starts a new epoch: the clock restarts at the target, not at the old count.
            val afterSeek = CompletableDeferred<Long>()
            audio.setProgressListener { progress ->
                if (!progress.ended) afterSeek.complete(progress.positionMicros)
            }
            audio.seek(500_000)
            assertTrue(afterSeek.await() in 500_000L..1_000_000L)
            audio.close()
        } finally {
            bridge.close()
        }
    }
}

private const val FIXTURE = "tone.nut"
private const val SAMPLE_RATE = 48_000

/** One second of 16x16 video and a 440 Hz stereo tone, encoded by the browser's own FFmpeg. */
private suspend fun encodeAudioVideoFixture(): ByteArray {
    val frames = Buffer().write(ByteArray(16 * 16 * 3 / 2 * 25) { index -> (index % 251).toByte() })
    val tone = Buffer().apply {
        repeat(SAMPLE_RATE) { frame ->
            val sample = (sin(2 * PI * 440 * frame / SAMPLE_RATE) * 0.25).toFloat().toRawBits()
            repeat(2) { writeIntLe(sample) }
        }
    }
    val output = Buffer()
    val diagnostics = StringBuilder()
    val result = createPlatformExecutionBridge().use { bridge ->
        bridge.execute(
            NativeExecutionRequest(
                id = 1,
                kind = NativeCommandKind.FFMPEG,
                arguments = listOf(
                    "-hide_banner", "-loglevel", "error",
                    // The browser runtime has a fixed thread pool; this command needs no parallelism.
                    "-filter_threads", "1",
                    "-f", "rawvideo", "-pixel_format", "yuv420p", "-video_size", "16x16", "-framerate", "25",
                    "-i", "frames.yuv",
                    "-f", "f32le", "-ar", "$SAMPLE_RATE", "-ac", "2", "-i", "tone.f32",
                    "-c:v", "rawvideo", "-c:a", "pcm_s16le", "-f", "nut", FIXTURE,
                ),
                mounts = listOf(
                    NativeMountedIo("frames.yuv", NativeSourceResource(frames)),
                    NativeMountedIo("tone.f32", NativeSourceResource(tone)),
                    NativeMountedIo(FIXTURE, NativeSinkResource(output)),
                ),
            ),
        ) { event ->
            when (event) {
                is NativeExecutionEvent.Log -> diagnostics.append(event.message)
                is NativeExecutionEvent.Output -> diagnostics.append(event.text)
            }
        }
    }
    assertEquals(0, result.returnCode, "Could not encode the audio/video fixture: $diagnostics")
    return output.readByteArray()
}
