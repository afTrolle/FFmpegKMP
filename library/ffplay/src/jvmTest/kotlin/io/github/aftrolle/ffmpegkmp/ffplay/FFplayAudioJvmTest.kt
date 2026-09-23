// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerConfiguration
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderPreference
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerOutputCapabilities
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSource
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerState
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame
import io.github.aftrolle.ffmpegkmp.bindings.createPlatformPlayerBridge
import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.toNativeMounts
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath

class FFplayAudioJvmTest {
    private val directory = createTempDirectory("ffplay-audio").toOkioPath()

    @AfterTest
    fun cleanUp() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    @Test
    fun anExternalMasterClockDrivesVideoScheduling() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/playback-color-patches-1s.mp4")).use { it.readBytes() }
        val presented = CopyOnWriteArrayList<Long>()
        createPlatformPlayerBridge(
            NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE),
            update = {},
            frame = { frame: NativeVideoFrame -> presented += frame.presentationTimeUs },
        ).use { bridge ->
            val io = CommandIo { input(FIXTURE, Buffer().write(bytes)) }
            assertEquals(0, bridge.setOutput(NativePlayerOutputCapabilities()))
            assertEquals(0, bridge.prepare(NativePlayerSource(FIXTURE, io.toNativeMounts())))
            presented.clear()

            val started = TimeSource.Monotonic.markNow()
            assertEquals(0, bridge.play())
            // The "audio" is already 0.8 s in: earlier frames are late and must be dropped, and the
            // rest follow that clock rather than a wall clock started at zero.
            bridge.setMasterClock(800_000)
            while (bridge.snapshot().state == NativePlayerState.PLAYING && started.elapsedNow() < 3.seconds) {
                Thread.sleep(10)
            }
            val elapsed = started.elapsedNow()

            assertEquals(NativePlayerState.ENDED, bridge.snapshot().state)
            assertTrue(elapsed < 0.7.seconds, "Playback took $elapsed; it should end ~0.2 s after the clock")
            assertTrue(bridge.snapshot().droppedFrames > 0, "Frames before the master clock should be dropped")
            assertTrue(presented.all { it >= 600_000 }, "Presented early frames: $presented")
        }
    }

    @Test
    fun playsAudioWithVideoAndAppliesLiveControls() = runBlocking {
        val media = encodeAudioVideoFixture()
        val fileHandle = FileSystem.SYSTEM.openReadOnly(media)
        val player = FFplayPlayer(FFplayConfiguration(decoderPreference = FFplayDecoderPreference.SOFTWARE))
        val output = CountingOutput()
        player.attachOutput(output)
        try {
            player.prepare(FFplaySource(AV_FIXTURE, CommandIo { input(AV_FIXTURE, fileHandle) }))

            val audio = player.audio.value
            if (!audio.available && !hasAudioDevice()) {
                println("Skipping: this machine has no audio output device")
                return@runBlocking
            }
            assertTrue(audio.available, "The fixture's audio track should be playable")
            assertEquals(1, audio.tracks.size)
            assertEquals(setOf(0), audio.enabledTracks)

            player.setVolume(0.5)
            player.setAudioTrackMuted(0, true)
            assertEquals(AudioLevel(volume = 0.5), player.audio.value.level)
            assertEquals(listOf(AudioLevel.Muted), player.audio.value.trackLevels)
            player.setAudioTrackMuted(0, false)

            val started = TimeSource.Monotonic.markNow()
            player.play()
            withTimeout(5.seconds) { player.snapshot.first { it.state == FFplayState.ENDED } }
            val elapsed = started.elapsedNow()

            assertTrue(output.presentedFrames > 10, "Only ${output.presentedFrames} frames were presented")
            // One second of media, paced by the audio device: neither instant nor stalled.
            assertTrue(elapsed in 0.8.seconds..2.5.seconds, "Playback took $elapsed")

            // Replaying from the end restarts audio together with video.
            player.play()
            delay(300)
            assertEquals(FFplayState.PLAYING, player.snapshot.value.state)
        } finally {
            player.close()
            fileHandle.close()
        }
    }

    /** One second of 25 fps MPEG-4 video with a PCM audio track, encoded by FFmpeg itself. */
    private suspend fun encodeAudioVideoFixture(): okio.Path {
        val width = 64
        val height = 48
        val frames = Buffer().apply {
            repeat(25) { index ->
                val shade = (index * 10).toByte()
                write(ByteArray(width * height * 3) { shade })
            }
        }
        val pcm = Buffer().apply {
            repeat(48_000) { sample ->
                val value = (kotlin.math.sin(sample * 2 * Math.PI * 440 / 48_000) * 0.1).toFloat()
                repeat(2) { writeIntLe(value.toRawBits()) }
            }
        }
        val path = directory / AV_FIXTURE
        val output = FileSystem.SYSTEM.openReadWrite(path)
        val result = FFmpegClient().use { client ->
            client.execute(
                listOf(
                    "-y",
                    "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", "${width}x$height",
                    "-framerate", "25", "-i", "frames.rgb",
                    "-f", "f32le", "-ar", "48000", "-ch_layout", "stereo", "-i", "audio.f32",
                    "-c:v", "mpeg4", "-pix_fmt", "yuv420p", "-c:a", "pcm_s16le",
                    "-f", "matroska", AV_FIXTURE,
                ),
                CommandIo {
                    input("frames.rgb", frames)
                    input("audio.f32", pcm)
                    output(AV_FIXTURE, output)
                },
            )
        }
        output.close()
        assertTrue(result.isSuccess, result.errorOutput)
        return path
    }

    private fun hasAudioDevice(): Boolean = runCatching {
        javax.sound.sampled.AudioSystem.getSourceDataLine(
            javax.sound.sampled.AudioFormat(48_000f, 16, 2, true, false),
        ).close()
    }.isSuccess

    private class CountingOutput : FFplayVideoOutput {
        override val kind = FFplayRendererKind.COMPOSE_CANVAS
        override val frames = kotlinx.coroutines.flow.MutableStateFlow<FFplayFrame?>(null)
        override val capabilities = FFplayOutputCapabilities()

        @Volatile
        var presentedFrames: Int = 0

        override fun submit(frame: FFplayFrame): Boolean = true

        override fun submitNative(frame: NativeVideoFrame, video: FFplayVideoInfo?): Boolean {
            presentedFrames++
            return true
        }

        override fun discard() = Unit
    }

    private companion object {
        const val FIXTURE = "playback-color-patches-1s.mp4"
        const val AV_FIXTURE = "audio-video.mkv"
    }
}
