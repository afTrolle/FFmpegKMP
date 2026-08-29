// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer

class BrowserPlayerIntegrationTest {
    @Test
    fun decodesMountedH264ThroughWebCodecsOrTheSoftwareFallback() = runTest {
        configureBrowserPlayerTestRuntime()
        val bytes = loadBrowserPlayerTestResource("/base/kotlin/hardware-h264.mp4")
        val snapshots = mutableListOf<NativePlayerSnapshot>()
        val softwareFrames = mutableListOf<NativeVideoFrame>()
        val platformFrames = mutableListOf<NativePlatformVideoFrame>()
        val firstFrame = CompletableDeferred<Unit>()
        val bridge = createPlatformPlayerBridge(
            configuration = NativePlayerConfiguration(NativePlayerDecoderPreference.AUTO),
            update = snapshots::add,
            frame = { frame ->
                softwareFrames += frame
                firstFrame.complete(Unit)
            },
            platformFrame = { frame ->
                platformFrames += frame
                firstFrame.complete(Unit)
                // Rejection exercises the ownership path: the browser binding must close and
                // remove the transferred VideoFrame immediately.
                false
            },
        )

        try {
            assertEquals(
                0,
                bridge.setOutput(
                    NativePlayerOutputCapabilities(
                        hardwareFrameImport = true,
                        softwareFrameUpload = true,
                    ),
                ),
            )
            assertEquals(
                0,
                bridge.prepare(
                    NativePlayerSource(
                        input = "hardware-h264.mp4",
                        mounts = listOf(
                            NativeMountedIo(
                                path = "hardware-h264.mp4",
                                resource = NativeSourceResource(Buffer().write(bytes)),
                            ),
                        ),
                    ),
                ),
            )
            assertEquals(0, bridge.awaitPreparation())

            assertEquals(0, bridge.play())
            firstFrame.await()

            if (browserSupportsWebCodecs()) {
                assertTrue(
                    platformFrames.isNotEmpty(),
                    "A browser exposing VideoDecoder and EncodedVideoChunk must use the WebCodecs path",
                )
                assertEquals(NativePlatformVideoFrameKind.WEB_VIDEO_FRAME, platformFrames.first().kind)
                // WebCodecs exposes an acceleration preference, not confirmation. Keep the public
                // report truthful instead of claiming hardware from the request alone.
                assertEquals(NativePlayerDecoderKind.UNKNOWN, bridge.snapshot().activeDecoder)
            } else {
                assertTrue(platformFrames.isEmpty())
                assertEquals(NativePlayerDecoderKind.SOFTWARE, bridge.snapshot().activeDecoder)
                assertTrue(softwareFrames.first().rgba.isNotEmpty())
            }

            bridge.pause()
            assertEquals(0, browserPlayerVideoFrameRegistrySize())
            assertTrue(snapshots.none { it.state == NativePlayerState.FAILED })
        } finally {
            bridge.close()
        }
    }

    @Test
    fun cancellationTerminatesPreparationAndTheNextPrepareUsesANewWorker() = runTest {
        val workers = mutableListOf<FakeBrowserPlayerWorker>()
        val bridge = createBrowserPlayerBridge(
            configuration = NativePlayerConfiguration(),
            update = {},
            frame = {},
            platformFrame = { false },
            workerFactory = { _, listener ->
                FakeBrowserPlayerWorker(listener).also(workers::add)
            },
        )

        try {
            bridge.prepare(NativePlayerSource("first.mp4"))
            val firstPreparation = async { bridge.awaitPreparation() }
            runCurrent()
            assertFalse(firstPreparation.isCompleted)

            bridge.cancel()

            assertFailsWith<CancellationException> { firstPreparation.await() }
            assertTrue(workers.single().cancelled)

            bridge.resetCancellation()
            assertEquals(2, workers.size)
            bridge.prepare(NativePlayerSource("second.mp4"))
            val secondPreparation = async { bridge.awaitPreparation() }
            runCurrent()
            workers.last().listener.onFailure("Synthetic worker failure")

            assertEquals(-5, secondPreparation.await())
        } finally {
            bridge.close()
        }
    }

    @Test
    fun secureBrowserPlaybackFailsClosedBeforePixelsAreDecoded() = runTest {
        configureBrowserPlayerTestRuntime()
        val frames = mutableListOf<NativeVideoFrame>()
        val bridge = createPlatformPlayerBridge(
            configuration = NativePlayerConfiguration(),
            update = {},
            frame = frames::add,
            platformFrame = { false },
        )

        try {
            assertTrue(
                bridge.prepare(
                    NativePlayerSource(
                        input = "protected.mp4",
                        requireSecurePath = true,
                    ),
                ) < 0,
            )
            assertEquals(NativePlayerState.FAILED, bridge.snapshot().state)
            assertTrue(frames.isEmpty())
        } finally {
            bridge.close()
        }
    }
}

private class FakeBrowserPlayerWorker(
    val listener: BrowserPlayerWorkerListener,
) : BrowserPlayerWorker {
    var cancelled = false
        private set

    override fun prepare(source: NativePlayerSource, mountBytes: Array<ByteArray>) = Unit
    override fun setOutput(flags: Int) = Unit
    override fun clearOutput() = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seek(positionUs: Long) = Unit
    override fun stop() = Unit
    override fun cancel() {
        cancelled = true
    }
    override fun close() = Unit
}

internal expect suspend fun loadBrowserPlayerTestResource(url: String): ByteArray
internal expect fun configureBrowserPlayerTestRuntime()
internal expect fun browserPlayerVideoFrameRegistrySize(): Int
internal expect fun browserSupportsWebCodecs(): Boolean
