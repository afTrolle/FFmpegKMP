// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import okio.Buffer

class NativePlayerBridgeJvmTest {
    @Test
    fun generatedBindingsDriveTheCompiledNativePlayer() {
        val updates = CopyOnWriteArrayList<NativePlayerSnapshot>()
        createPlatformPlayerBridge(NativePlayerConfiguration(), updates::add).use { bridge ->
            assertEquals(0, bridge.prepare(clearVideoSource()))
            val preparedSerial = bridge.snapshot().queueSerial
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(0, bridge.seek(500_000))

            assertEquals(NativePlayerState.PAUSED, bridge.snapshot().state)
            assertEquals(500_000, bridge.snapshot().positionUs)
            assertNotEquals(preparedSerial, bridge.snapshot().queueSerial)
            assertTrue(updates.any { it.state == NativePlayerState.SEEKING })
        }
    }

    @Test
    fun compiledPlayersAreIndependentAndEnforceSecureOutput() {
        createPlatformPlayerBridge(NativePlayerConfiguration(), update = {}).use { first ->
            createPlatformPlayerBridge(NativePlayerConfiguration(), update = {}).use { second ->
                assertEquals(0, first.prepare(clearVideoSource(requireSecurePath = true)))
                assertEquals(0, second.prepare(clearVideoSource()))

                assertEquals(-13, first.setOutput(NativePlayerOutputCapabilities()))
                assertEquals(0, second.setOutput(NativePlayerOutputCapabilities()))
                assertEquals(NativePlayerState.FAILED, first.snapshot().state)
                assertEquals(NativePlayerState.READY, second.snapshot().state)
            }
        }
    }

    @Test
    fun compiledPlayerDecodesMountedVideoIntoARealPreviewFrame() {
        val frames = mutableListOf<NativeVideoFrame>()
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
            update = {},
            frame = frames::add,
        ).use { bridge ->
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(0, bridge.prepare(clearVideoSource()))

            val frame = frames.single()
            assertTrue(frame.width > 0)
            assertTrue(frame.height > 0)
            assertEquals(frame.stride * frame.height, frame.rgba.size)
            assertEquals(frame.width, bridge.snapshot().videoWidth)
            assertEquals(frame.height, bridge.snapshot().videoHeight)
            assertTrue((bridge.snapshot().durationUs ?: 0) > 0)
        }
    }

    @Test
    fun videoToolboxDecodeCanDownloadIntoTheDesktopSoftwareRenderer() {
        if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) return
        val frames = mutableListOf<NativeVideoFrame>()
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.REQUIRE_HARDWARE),
            update = {},
            frame = frames::add,
        ).use { bridge ->
            assertEquals(
                0,
                bridge.setOutput(
                    NativePlayerOutputCapabilities(
                        hardwareFrameImport = false,
                        softwareFrameUpload = true,
                        zeroCopy = false,
                    ),
                ),
            )
            assertEquals(0, bridge.prepare(videoSource("hardware-h264.mp4")))

            assertEquals(NativePlayerDecoderKind.HARDWARE, bridge.snapshot().activeDecoder)
            assertEquals(1, frames.size)
            assertTrue(frames.single().rgba.isNotEmpty())
        }
    }

    @Test
    fun autoDecoderFallsBackWhenVideoToolboxCannotProduceAFrame() {
        if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) return
        val frames = mutableListOf<NativeVideoFrame>()
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.AUTO),
            update = {},
            frame = frames::add,
        ).use { bridge ->
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            // VideoToolbox advertises this MPEG-2 format on the test host but cannot initialize
            // it. AUTO must retry with the software decoder and report what produced the frame.
            assertEquals(0, bridge.prepare(clearVideoSource()))

            assertEquals(NativePlayerDecoderKind.SOFTWARE, bridge.snapshot().activeDecoder)
            assertEquals(1, frames.size)
        }
    }

    @Test
    fun compiledPlayerSchedulesDecodedFramesAndAdvancesItsClock() {
        val frames = CopyOnWriteArrayList<NativeVideoFrame>()
        val updates = CopyOnWriteArrayList<NativePlayerSnapshot>()
        val scheduledFrames = CountDownLatch(4)
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
            update = updates::add,
            frame = { frame ->
                frames += frame
                scheduledFrames.countDown()
            },
        ).use { bridge ->
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(0, bridge.prepare(clearVideoSource()))
            assertEquals(0, bridge.play())

            assertTrue(
                scheduledFrames.await(3, TimeUnit.SECONDS),
                "Expected the preview plus multiple scheduled playback frames",
            )
            assertTrue(frames.map { it.presentationTimeUs }.distinct().size > 1)
            assertTrue(updates.any { it.state == NativePlayerState.PLAYING })
            assertTrue(updates.any { it.positionUs > 0 })
            assertEquals(0, bridge.pause())
        }
    }

    @Test
    fun compiledPlayerDropsLateFramesAndReportsThem() {
        val ended = CountDownLatch(1)
        val updates = CopyOnWriteArrayList<NativePlayerSnapshot>()
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
            update = { snapshot ->
                updates += snapshot
                if (snapshot.state == NativePlayerState.ENDED) ended.countDown()
            },
            frame = {
                // Simulate a renderer that takes longer than a video-frame interval.
                Thread.sleep(120)
            },
        ).use { bridge ->
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(0, bridge.prepare(clearVideoSource()))
            assertEquals(0, bridge.play())

            assertTrue(ended.await(5, TimeUnit.SECONDS), "Expected playback to reach EOF")
            assertTrue(bridge.snapshot().droppedFrames > 0)
            assertTrue(updates.any { it.droppedFrames > 0 })
        }
    }

    @Test
    fun protectedSourceNeverEmitsCpuReadableFrames() {
        val frames = mutableListOf<NativeVideoFrame>()
        createPlatformPlayerBridge(
            NativePlayerConfiguration(),
            update = {},
            frame = frames::add,
        ).use { bridge ->
            assertEquals(
                0,
                bridge.setOutput(
                    NativePlayerOutputCapabilities(
                        hardwareFrameImport = true,
                        softwareFrameUpload = false,
                        zeroCopy = true,
                        protectedContent = true,
                    ),
                ),
            )
            assertEquals(0, bridge.prepare(clearVideoSource(requireSecurePath = true)))
            assertTrue(frames.isEmpty())
        }
    }

    @Test
    fun prepareInspectsMountedInputBeforeAnOutputIsAttached() {
        createPlatformPlayerBridge(NativePlayerConfiguration(), update = {}).use { bridge ->
            assertEquals(0, bridge.prepare(clearVideoSource()))
            assertEquals(NativePlayerState.WAITING_FOR_OUTPUT, bridge.snapshot().state)
            assertTrue(bridge.snapshot().videoWidth > 0)
            assertTrue(bridge.snapshot().videoHeight > 0)
            assertTrue((bridge.snapshot().durationUs ?: 0) > 0)
            val video = requireNotNull(bridge.snapshot().videoInfo)
            assertEquals(bridge.snapshot().videoWidth, video.width)
            assertEquals(bridge.snapshot().videoHeight, video.height)
            assertEquals("yuv420p", video.pixelFormatName)
            assertEquals(8, video.bitDepth)
            assertEquals(NativePlayerHdrType.SDR, video.hdrType)
        }
    }

    @Test
    fun compiledPlayerReadsColorMetadataFromSharedVideoFixtures() {
        data class ExpectedColor(
            val resource: String,
            val primaries: Int,
            val transfer: Int,
            val matrix: Int,
            val hdrType: NativePlayerHdrType,
        )

        listOf(
            ExpectedColor("sdr-bt709.mp4", 1, 1, 1, NativePlayerHdrType.SDR),
            ExpectedColor("sdr-display-p3.mp4", 12, 1, 1, NativePlayerHdrType.SDR),
            ExpectedColor("hdr10-pq.mp4", 9, 16, 9, NativePlayerHdrType.HDR10),
            ExpectedColor("hdr-hlg.mp4", 9, 18, 9, NativePlayerHdrType.HLG),
        ).forEach { expected ->
            createPlatformPlayerBridge(NativePlayerConfiguration(), update = {}).use { bridge ->
                assertEquals(0, bridge.prepare(videoSource(expected.resource)))
                val video = requireNotNull(bridge.snapshot().videoInfo)
                assertEquals(expected.primaries, video.colorPrimaries, expected.resource)
                assertEquals(expected.transfer, video.colorTransfer, expected.resource)
                assertEquals(expected.matrix, video.colorSpace, expected.resource)
                assertEquals(expected.hdrType, video.hdrType, expected.resource)
            }
        }
    }

    @Test
    fun compiledPlayerToneMapsPqAndHlgSoftwareFramesIntoBoundedSdr() {
        listOf("hdr10-pq.mp4", "hdr-hlg.mp4").forEach { resource ->
            val passthrough = decodePreview(resource, toneMapHdrToSdr = false)
            val toneMapped = decodePreview(resource, toneMapHdrToSdr = true)

            assertEquals(passthrough.width, toneMapped.width, resource)
            assertEquals(passthrough.height, toneMapped.height, resource)
            assertTrue(
                !passthrough.rgba.contentEquals(toneMapped.rgba),
                "$resource must pass through the HDR transfer and gamut conversion",
            )
            assertTrue(
                toneMapped.rgba.indices
                    .filter { it % 4 == 3 }
                    .all { toneMapped.rgba[it].toInt() and 0xff == 255 },
                "$resource must retain opaque alpha",
            )
            val passthroughRgb = passthrough.rgba.indices
                .filter { it % 4 != 3 }
                .map { passthrough.rgba[it].toInt() and 0xff }
            val toneMappedRgb = toneMapped.rgba.indices
                .filter { it % 4 != 3 }
                .map { toneMapped.rgba[it].toInt() and 0xff }
            assertTrue(
                toneMappedRgb.any { it in 1..254 },
                "$resource must retain bounded SDR midtones; " +
                    "clipped=${toneMappedRgb.count { it == 255 }}/${toneMappedRgb.size}, " +
                    "passthroughClipped=${passthroughRgb.count { it == 255 }}/${passthroughRgb.size}",
            )
        }
    }

    @Test
    fun prepareRejectsAnInvalidMountedSourceWithoutWaitingForOutput() {
        val source = Buffer().writeUtf8("not a media container")
        createPlatformPlayerBridge(NativePlayerConfiguration(), update = {}).use { bridge ->
            assertTrue(
                bridge.prepare(
                    NativePlayerSource(
                        input = "invalid.mp4",
                        mounts = listOf(NativeMountedIo("invalid.mp4", NativeSourceResource(source))),
                    ),
                ) < 0,
            )
            assertEquals(NativePlayerState.FAILED, bridge.snapshot().state)
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertTrue(bridge.play() < 0)
        }
    }

    @Test
    fun preparingANewMountedSourceJoinsTheOutgoingPlaybackGeneration() {
        val updates = CopyOnWriteArrayList<NativePlayerSnapshot>()
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
            updates::add,
        ).use { bridge ->
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(0, bridge.prepare(clearVideoSource()))
            assertEquals(0, bridge.play())
            val outgoingSerial = bridge.snapshot().queueSerial

            assertEquals(0, bridge.prepare(clearVideoSource()))

            assertNotEquals(outgoingSerial, bridge.snapshot().queueSerial)
            assertEquals(NativePlayerState.READY, bridge.snapshot().state)
            assertEquals(0, bridge.snapshot().positionUs)
            assertEquals(0, bridge.play())
            assertTrue(updates.last().queueSerial == bridge.snapshot().queueSerial)
        }
    }

    @Test
    fun failedSecurePrepareCannotBeRevivedByReplacingTheOutput() {
        createPlatformPlayerBridge(NativePlayerConfiguration(), update = {}).use { bridge ->
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(-13, bridge.prepare(clearVideoSource(requireSecurePath = true)))

            assertEquals(
                0,
                bridge.setOutput(
                    NativePlayerOutputCapabilities(
                        hardwareFrameImport = true,
                        softwareFrameUpload = false,
                        zeroCopy = true,
                        protectedContent = true,
                    ),
                ),
            )
            assertTrue(bridge.play() < 0)
        }
    }
}

private fun NativePlayerBridgeJvmTest.decodePreview(
    resourceName: String,
    toneMapHdrToSdr: Boolean,
): NativeVideoFrame {
    val frames = mutableListOf<NativeVideoFrame>()
    createPlatformPlayerBridge(
        NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
        update = {},
        frame = frames::add,
    ).use { bridge ->
        assertEquals(
            0,
            bridge.setOutput(
                NativePlayerOutputCapabilities(toneMapHdrToSdr = toneMapHdrToSdr),
            ),
        )
        assertEquals(0, bridge.prepare(videoSource(resourceName)))
    }
    return frames.single()
}

private fun NativePlayerBridgeJvmTest.clearVideoSource(
    requireSecurePath: Boolean = false,
): NativePlayerSource = videoSource("playback-color-patches-1s.mp4", requireSecurePath)

private fun NativePlayerBridgeJvmTest.videoSource(
    resourceName: String,
    requireSecurePath: Boolean = false,
): NativePlayerSource {
    val bytes = checkNotNull(javaClass.getResourceAsStream("/$resourceName")) {
        "Missing common video fixture $resourceName"
    }.use { it.readBytes() }
    return NativePlayerSource(
        input = resourceName,
        mounts = listOf(
            NativeMountedIo(resourceName, NativeSourceResource(Buffer().write(bytes))),
        ),
        requireSecurePath = requireSecurePath,
    )
}
