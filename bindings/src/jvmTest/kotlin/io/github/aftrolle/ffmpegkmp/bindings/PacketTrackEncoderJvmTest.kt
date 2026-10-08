// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class PacketTrackEncoderJvmTest {
    private class FakeEncoder(val log: MutableList<String>) : PacketTrackEncoder {
        override fun dequeue(): NativeGpuBuffer = error("Not drawn into")

        override fun queue(buffer: NativeGpuBuffer, ptsNanos: Long) = error("Not drawn into")

        override fun start() {
            log += "start"
        }

        override fun finish(): Int {
            log += "finish"
            return 0
        }

        override fun release() {
            log += "release"
        }
    }

    @Test
    fun aVideoTrackThatHasTakenNoFrameBecomesAPacketTrackOnce() = runBlocking {
        val output = File.createTempFile("packet-track", ".mp4")
        val log = mutableListOf<String>()
        val encoders = AtomicInteger()
        val writer = createJavaCppMediaWriter(
            NativeWriterOutput.Path(output.path), NativeContainer.MP4, fastStart = false, timeoutMicros = 0,
            openEncoder = { _, _, _, _ -> FakeEncoder(log).also { encoders.incrementAndGet() } },
        )
        try {
            val config = NativeVideoEncoderConfig(
                128, 128, 30, 1, NativeVideoCodec.H264, NativeDynamicRange.SDR, NativePlayerDecoderPreference.AUTO, 0, 2_000_000, null,
            )
            val track = try {
                writer.addVideoTrack(config)
            } catch (unsupported: NativeMediaWriterException) {
                println("Skipped: this host has no H.264 encoder (${unsupported.message})")
                return@runBlocking
            }
            assertNotNull(track.info.bitRate.takeIf { it > 0 }, "The track says the bit rate its encoder opened with")
            assertNotNull(writer.openInputSurface(track.index))
            assertEquals(listOf("start"), log)
            // The track is a packet track now: the platform encoder cannot be opened a second time.
            assertNull(writer.openInputSurface(track.index))
            assertEquals(2, encoders.get())
            assertEquals(listOf("start", "release"), log)
            writer.endTrack(track.index)
            assertEquals(listOf("start", "release", "finish"), log)
        } finally {
            writer.abort()
            writer.releaseTrack(0)
            writer.close()
            output.delete()
        }
    }
}
