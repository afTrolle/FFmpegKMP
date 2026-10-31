// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import okio.Buffer
import okio.FileHandle

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

                assertEquals(NativePlayerError.ACCESS_DENIED, first.setOutput(NativePlayerOutputCapabilities()))
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
            assertEquals(frame.width, bridge.snapshot().videoWidth)
            assertEquals(frame.height, bridge.snapshot().videoHeight)
            assertTrue((bridge.snapshot().durationUs ?: 0) > 0)
            // The decoded frame itself, not an RGBA copy: yuv420p in three planes.
            frame.frame.use { decoded ->
                assertEquals(frame.width, decoded.width)
                assertEquals(YUV420P, decoded.format?.layout)
                decoded.usePlanes { planes ->
                    assertEquals(3, planes.size)
                    assertEquals(frame.height, planes[0].rows)
                    assertTrue(planes[0].rowBytes >= frame.width)
                }
            }
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
            assertEquals(NativePlayerDecoderFallback.NONE, bridge.snapshot().decoderFallback)
            assertEquals(1, frames.size)
            // Downloaded from VideoToolbox's NV12.
            frames.single().frame.use { frame ->
                assertEquals(NV12, frame.format?.layout)
                frame.usePlanes { planes -> assertEquals(2, planes.size) }
            }
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
            assertEquals(NativePlayerDecoderFallback.FAILED, bridge.snapshot().decoderFallback)
            assertEquals(1, frames.size)
            // Software from now on for this source: its seeks and playback open no codec, hardware or other.
            val opens = nativePlayerStatistics().codecOpens
            listOf(500_000L, 200_000L).forEach { assertEquals(0, bridge.seek(it)) }
            assertEquals(0, bridge.play())
            assertEquals(0, bridge.pause())
            assertEquals(opens, nativePlayerStatistics().codecOpens)
            assertEquals(NativePlayerDecoderFallback.FAILED, bridge.snapshot().decoderFallback)
            frames.forEach { it.frame.close() }
        }
    }

    /**
     * A desktop hardware frame reaches the output as decoded and is downloaded once, as it is
     * converted, into one of the player's two download buffers: no new memory per frame.
     */
    @Test
    fun desktopHardwareFramesAreDownloadedOnceIntoReusedMemory() {
        if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) return
        val bitmap = allocateNativeFrame(NativeFrameFormat(RGBA8, BT709, SRGB, RGB, FULL), 64, 64)
        val converted = AtomicInteger()
        val played = CountDownLatch(12)
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.AUTO),
            update = {},
            frame = { frame ->
                // As a Canvas output: one conversion into its bitmap.
                frame.frame.use { it.convertInto(bitmap) }
                converted.incrementAndGet()
                played.countDown()
            },
        ).use { bridge ->
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(0, bridge.prepare(videoSource("hardware-h264.mp4")))
            assertEquals(NativePlayerDecoderKind.HARDWARE, bridge.snapshot().activeDecoder)
            val before = nativePlayerStatistics()
            val memory = nativeFrameStatistics()
            val previews = converted.get()

            assertEquals(0, bridge.play())
            assertTrue(played.await(3, TimeUnit.SECONDS), "playback presented no frames")
            assertEquals(0, bridge.pause())
            val frames = converted.get() - previews
            assertEquals(frames.toLong(), nativePlayerStatistics().downloads - before.downloads, "downloads for $frames frames")
            val buffers = nativeFrameStatistics().buffers - memory.buffers
            assertTrue(buffers <= 2, "$buffers buffers allocated for $frames frames")
        }
        bitmap.close()
    }

    @Test
    fun theCodecIsKeptAcrossSeeksPausePlayAndOutputs() {
        val played = CountDownLatch(3)
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
            update = {},
            frame = { frame ->
                frame.frame.close()
                played.countDown()
            },
        ).use { bridge ->
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(0, bridge.prepare(clearVideoSource()))
            val prepared = nativePlayerStatistics().codecOpens
            listOf(900_000L, 100_000L, 500_000L, 0L).forEach { assertEquals(0, bridge.seek(it)) }
            assertEquals(0, bridge.play())
            assertTrue(played.await(3, TimeUnit.SECONDS), "playback presented no frames")
            assertEquals(0, bridge.pause())
            assertEquals(0, bridge.play())
            assertEquals(0, bridge.pause())
            assertEquals(0, bridge.seek(300_000))
            // An output that takes the same frames needs the same codec.
            bridge.clearOutput()
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(prepared, nativePlayerStatistics().codecOpens, "a seek, play, pause or output opened a codec")
            // Software was asked for, so nothing fell back.
            assertEquals(NativePlayerDecoderFallback.NONE, bridge.snapshot().decoderFallback)

            assertEquals(0, bridge.stop())
            assertEquals(0, bridge.prepare(clearVideoSource()))
            assertEquals(prepared + 1, nativePlayerStatistics().codecOpens, "a new source opens a codec of its own")
        }
    }

    /**
     * Stepping forward a frame at a time while paused goes on from the frame on screen: each step
     * reads about one packet and decodes one frame, where a seek would decode from the keyframe.
     */
    @Test
    fun aForwardScrubDecodesEachFrameOnce() {
        val frames = CopyOnWriteArrayList<NativeVideoFrame>()
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
            update = {},
            frame = frames::add,
        ).use { bridge ->
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            // 25 fps, B-frames, one keyframe.
            assertEquals(0, bridge.prepare(videoSource("big-buck-bunny-1s.mp4")))
            assertEquals(0, bridge.seek(40_000))
            val before = nativePlayerStatistics()
            val targets = (2..20).map { it * 40_000L }
            for (target in targets) {
                frames.forEach { it.frame.close() }
                frames.clear()
                assertEquals(0, bridge.seek(target))
                assertEquals(target, frames.single().presentationTimeUs, "the frame shown after stepping to ${target}us")
            }
            val packets = nativePlayerStatistics().videoPackets - before.videoPackets
            assertTrue(packets <= targets.size, "$packets packets read for ${targets.size} steps")

            // Inside the frame on screen: that frame again, at the target, after at most the packet that shows where it ends.
            frames.forEach { it.frame.close() }
            frames.clear()
            val inside = nativePlayerStatistics()
            assertEquals(0, bridge.seek(targets.last() + 10_000))
            assertEquals(listOf(targets.last() + 10_000), frames.map { it.presentationTimeUs })
            assertTrue(nativePlayerStatistics().videoPackets - inside.videoPackets <= 1)
            assertEquals(before.codecOpens, nativePlayerStatistics().codecOpens)
        }
    }

    @Test
    fun theInputIsProbedOncePerPrepare() {
        val input = CountingFileHandle(fixtureBytes("playback-color-patches-1s.mp4"))
        val source = NativePlayerSource(
            input = "playback-color-patches-1s.mp4",
            mounts = listOf(
                NativeMountedIo(
                    "playback-color-patches-1s.mp4",
                    NativeFileResource(input, NativeIoAccess.READ),
                ),
            ),
            requireSecurePath = false,
        )
        val played = CountDownLatch(3)
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
            update = {},
            frame = { frame ->
                frame.frame.close()
                played.countDown()
            },
        ).use { bridge ->
            assertEquals(0, bridge.prepare(source))
            // The mov demuxer asks for the input's size once as it opens it, and never to seek.
            val probes = input.sizeRequests.get()
            assertTrue(probes > 0, "prepare did not open the input")

            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            listOf(900_000L, 100_000L, 500_000L, 0L).forEach { assertEquals(0, bridge.seek(it)) }
            assertEquals(0, bridge.play())
            assertTrue(played.await(3, TimeUnit.SECONDS), "playback presented no frames")
            assertEquals(0, bridge.pause())
            assertEquals(0, bridge.seek(300_000))
            assertEquals(0, bridge.play())
            assertEquals(0, bridge.pause())
            assertEquals(probes, input.sizeRequests.get(), "a preview, seek or play opened the input again")

            assertEquals(0, bridge.stop())
            assertEquals(0, bridge.prepare(source))
            assertEquals(2 * probes, input.sizeRequests.get(), "a new prepare probes the input again")
        }
    }

    @Test
    fun everyDecodeRunsOnThePlayersSessionThread() {
        val decodingThreads = CopyOnWriteArrayList<Thread>()
        val played = CountDownLatch(8)
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
            update = {},
            frame = { frame ->
                decodingThreads += Thread.currentThread()
                frame.frame.close()
                played.countDown()
            },
        ).use { bridge ->
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(0, bridge.prepare(clearVideoSource()))
            listOf(500_000L, 100_000L).forEach { assertEquals(0, bridge.seek(it)) }
            assertEquals(0, bridge.play())
            assertTrue(played.await(3, TimeUnit.SECONDS), "playback presented no frames")
            assertEquals(0, bridge.pause())
            assertEquals(0, bridge.seek(700_000))

            // Previews and playback share one thread, so a codec kept between them stays on it.
            assertEquals(1, decodingThreads.distinct().size, "frames came from ${decodingThreads.distinct()}")
            assertNotEquals(Thread.currentThread(), decodingThreads.first())
        }
    }

    @Test
    fun seeksFromTwoThreadsEndWhenThePlayerIsCancelledAndClosed() {
        val bridge = createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
            update = {},
            frame = { it.frame.close() },
        )
        assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
        assertEquals(0, bridge.prepare(clearVideoSource()))
        val seeks = AtomicInteger()
        val finished = CountDownLatch(2)
        val seekers = List(2) { index ->
            Thread {
                var position = index * 100_000L
                try {
                    while (true) {
                        // Fails once cancelled, and throws once closed: both end the storm.
                        bridge.seek(position % 1_000_000L)
                        if (index == 0 && seeks.get() % 7 == 0) bridge.play()
                        seeks.incrementAndGet()
                        position += 130_000L
                    }
                } catch (_: IllegalStateException) {
                } finally {
                    finished.countDown()
                }
            }.apply { start() }
        }
        while (seeks.get() < 40) Thread.sleep(5)
        bridge.cancel()
        bridge.close()

        assertTrue(finished.await(5, TimeUnit.SECONDS), "a seek was still blocked after close")
        seekers.forEach { it.join(1_000) }
    }

    @Test
    fun aSeekShowsTheFrameOnScreenAtTheTarget() {
        val frames = mutableListOf<NativeVideoFrame>()
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
            update = {},
            frame = frames::add,
        ).use { bridge ->
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(0, bridge.prepare(clearVideoSource()))
            // The fixture runs at 25 fps with keyframes at 0, 0.48 and 0.96 s. A paused seek shows
            // its frame before returning: inside the 0.28 s frame, exactly on the 0.32 s one, and
            // inside the last frame, which only the end of the input shows to be the one there.
            listOf(300_000L, 320_000L, 990_000L).forEach { target ->
                frames.forEach { it.frame.close() }
                frames.clear()
                assertEquals(0, bridge.seek(target))
                val frame = frames.single()
                assertEquals(target, frame.presentationTimeUs, "the frame shown after seeking to ${target}us")
                assertEquals(bridge.snapshot().queueSerial, frame.queueSerial)
            }
            frames.forEach { it.frame.close() }
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
            frames.forEach { it.frame.close() }
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
                it.frame.close()
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
            assertFalse(video.dolbyVision)
            assertFalse(video.hdr10Plus)
        }
    }

    @Test
    fun compiledPlayerReadsColorMetadataFromSharedVideoFixtures() {
        data class ExpectedColor(
            val resource: String,
            val primaries: Int,
            val transfer: Int,
            val matrix: Int,
        )

        listOf(
            ExpectedColor("sdr-bt709.mp4", 1, 1, 1),
            ExpectedColor("sdr-display-p3.mp4", 12, 1, 1),
            ExpectedColor("hdr10-pq.mp4", 9, 16, 9),
            ExpectedColor("hdr-hlg.mp4", 9, 18, 9),
        ).forEach { expected ->
            createPlatformPlayerBridge(NativePlayerConfiguration(), update = {}).use { bridge ->
                assertEquals(0, bridge.prepare(videoSource(expected.resource)))
                val video = requireNotNull(bridge.snapshot().videoInfo)
                assertEquals(expected.primaries, video.colorPrimaries, expected.resource)
                assertEquals(expected.transfer, video.colorTransfer, expected.resource)
                assertEquals(expected.matrix, video.colorSpace, expected.resource)
                assertFalse(video.dolbyVision, expected.resource)
            }
        }
    }

    @Test
    fun compiledPlayerHandsOutHdrFramesAsDecodedForTheRendererToToneMap() {
        listOf("hdr10-pq.mp4" to PQ, "hdr-hlg.mp4" to HLG).forEach { (resource, transfer) ->
            decodePreview(resource).use { frame ->
                assertEquals(BT2020, frame.format?.primaries, resource)
                assertEquals(transfer, frame.format?.transfer, resource)
                // Matrix only, keeping the HDR transfer, against the tone map into sRGB.
                val passthrough = frame.rgba(NativeFrameFormat(RGBA8, BT2020, transfer, RGB, FULL))
                val toneMapped = frame.rgba(NativeFrameFormat(RGBA8, BT709, SRGB, RGB, FULL))
                assertTrue(
                    !passthrough.contentEquals(toneMapped),
                    "$resource must pass through the HDR transfer and gamut conversion",
                )
                assertTrue(
                    toneMapped.indices
                        .filter { it % 4 == 3 }
                        .all { toneMapped[it].toInt() and 0xff == 255 },
                    "$resource must retain opaque alpha",
                )
                val passthroughRgb = passthrough.indices
                    .filter { it % 4 != 3 }
                    .map { passthrough[it].toInt() and 0xff }
                val toneMappedRgb = toneMapped.indices
                    .filter { it % 4 != 3 }
                    .map { toneMapped[it].toInt() and 0xff }
                assertTrue(
                    toneMappedRgb.any { it in 1..254 },
                    "$resource must retain bounded SDR midtones; " +
                        "clipped=${toneMappedRgb.count { it == 255 }}/${toneMappedRgb.size}, " +
                        "passthroughClipped=${passthroughRgb.count { it == 255 }}/${passthroughRgb.size}",
                )
            }
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
            assertEquals(NativePlayerError.ACCESS_DENIED, bridge.prepare(clearVideoSource(requireSecurePath = true)))

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

private fun NativePlayerBridgeJvmTest.decodePreview(resourceName: String): NativeFrame {
    val frames = mutableListOf<NativeVideoFrame>()
    createPlatformPlayerBridge(
        NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
        update = {},
        frame = frames::add,
    ).use { bridge ->
        assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
        assertEquals(0, bridge.prepare(videoSource(resourceName)))
    }
    return frames.single().frame
}

/** The frame converted into [format], an RGBA8 one, as packed rows. */
private fun NativeFrame.rgba(format: NativeFrameFormat): ByteArray = convert(format).use { converted ->
    converted.usePlanes { planes ->
        val plane = planes.single()
        val buffer = plane.memory as java.nio.ByteBuffer
        ByteArray(width * height * 4).also { rgba ->
            for (row in 0 until height) {
                buffer.duplicate().apply { position(row * plane.rowBytes) }.get(rgba, row * width * 4, width * 4)
            }
        }
    }
}

// ffmpegkmp_frame.h's values.
private const val RGBA8 = 0
private const val NV12 = 4
private const val YUV420P = 6
private const val BT709 = 0
private const val BT2020 = 1
private const val SRGB = 0
private const val PQ = 3
private const val HLG = 4
private const val RGB = 0
private const val FULL = 1

private fun NativePlayerBridgeJvmTest.fixtureBytes(resourceName: String): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/$resourceName")) {
        "Missing common video fixture $resourceName"
    }.use { it.readBytes() }

/** Serves [bytes] read-only and counts the size requests each open of it makes. */
private class CountingFileHandle(private val bytes: ByteArray) : FileHandle(readWrite = false) {
    val sizeRequests = AtomicInteger()

    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
        if (fileOffset >= bytes.size) return -1
        val count = minOf(byteCount.toLong(), bytes.size - fileOffset).toInt()
        bytes.copyInto(array, arrayOffset, fileOffset.toInt(), fileOffset.toInt() + count)
        return count
    }

    override fun protectedSize(): Long {
        sizeRequests.incrementAndGet()
        return bytes.size.toLong()
    }

    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
        throw UnsupportedOperationException()

    override fun protectedFlush() = Unit

    override fun protectedResize(size: Long) = throw UnsupportedOperationException()

    override fun protectedClose() = Unit
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
