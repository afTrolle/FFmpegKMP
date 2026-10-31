// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.player

import io.github.aftrolle.ffmpegkmp.bindings.NativeInputHint
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerConfiguration
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderPreference
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerSource
import io.github.aftrolle.ffmpegkmp.bindings.createPlatformPlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.nativePlayerStatistics
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import okio.Path

/**
 * The audio engine opens an input a player has prepared with what the player's probe found
 * (`ffmpegkmp_player_open_hinted`): it skips its own probe when the header declares the audio, and
 * probes as before when it does not or the hint does not fit. Either way it must decode exactly
 * what a probed open decodes. The fixtures' audio starts half a second after their video.
 */
class HintedAudioOpenIntegrationTest {
    private val fileSystem = FileSystem.SYSTEM
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ffmpegkmp-hinted-${Random.nextLong().toULong()}"

    @AfterTest
    fun deleteFixtures() {
        fileSystem.deleteRecursively(directory)
    }

    @Test
    fun aDeclaredHeaderSkipsTheProbeAndDecodesAsAProbedOpen() = runBlocking {
        for ((name, audioCodec) in listOf("late-audio.mp4" to "aac", "late-audio.mkv" to "aac", "late-audio.nut" to "pcm_s16le")) {
            val media = encode(name, audioCodec)
            val hint = preparedHint(media)
            assertEquals(1, hint.audioStreams, name)

            val (hinted, hintedProbes) = decodeCounting(media, hint)
            assertEquals(0, hintedProbes, "$name: the hinted open probed the input")
            val (probed, probedProbes) = decodeCounting(media, hint = null)
            assertEquals(1, probedProbes, "$name: an open without a hint probes once")
            assertSameDecode(name, probed, hinted)
            // The audio starts half a second in: silence up to there, the tone after it, as probed.
            assertTrue(hinted.all.take(SAMPLE_RATE * CHANNELS * 4 / 10).all { abs(it) < 1e-4f }, "$name: not silent at the start")
            assertTrue(hinted.all.drop(SAMPLE_RATE * CHANNELS * 6 / 10).take(4_800).any { abs(it) > 0.1f }, "$name: no tone after 0.5 s")
        }
    }

    @Test
    fun aHeaderlessContainerIsProbedDespiteTheHint() = runBlocking {
        val media = encode("late-audio.ts", "aac")
        val hint = preparedHint(media)

        val (hinted, hintedProbes) = decodeCounting(media, hint)
        assertEquals(1, hintedProbes, "MPEG-TS declares no stream parameters up front, so the audio must probe it")
        assertSameDecode("MPEG-TS", decodeCounting(media, hint = null).first, hinted)
    }

    @Test
    fun aHintThatDoesNotFitFallsBackToAProbe() = runBlocking {
        val media = encode("late-audio.mp4", "aac")
        val hint = preparedHint(media)
        val probed = decodeCounting(media, hint = null).first
        val wrong = listOf(
            hint.copy(formatName = "matroska,webm"),
            hint.copy(formatName = "no-such-demuxer"),
            hint.copy(audioStreams = 2),
        )
        for (candidate in wrong) {
            val (decoded, probes) = decodeCounting(media, candidate)
            assertEquals(1, probes, "$candidate: a hint that does not fit must lead to one probe")
            assertSameDecode(candidate.toString(), probed, decoded)
        }
    }

    /** What a player's prepare of [media] found. */
    private fun preparedHint(media: Path): NativeInputHint =
        createPlatformPlayerBridge(NativePlayerConfiguration(NativePlayerDecoderPreference.SOFTWARE), update = {}).use { bridge ->
            assertEquals(0, bridge.prepare(NativePlayerSource(media.toString())))
            assertNotNull(bridge.inputHint(), "a prepared player has a hint")
        }

    /** Everything [media]'s audio decodes to, and then from 0.7 s after a seek, with the probes its open made. */
    private suspend fun decodeCounting(media: Path, hint: NativeInputHint?): Pair<Decoded, Long> {
        val before = nativePlayerStatistics().inputProbes
        return AudioDecoder.open(MediaSource(media.toString()), PcmFormat(SAMPLE_RATE, CHANNELS), hint).use { decoder ->
            val probes = nativePlayerStatistics().inputProbes - before
            val tracks = decoder.tracks
            val duration = decoder.duration
            val all = decodeAll(decoder)
            decoder.seek(700.milliseconds)
            Decoded(tracks, duration?.inWholeMicroseconds, all, decodeAll(decoder)) to probes
        }
    }

    private suspend fun decodeAll(decoder: AudioDecoder): List<Float> {
        val chunk = FloatArray(4_096 * CHANNELS)
        val all = ArrayList<Float>()
        while (true) {
            val count = decoder.read(chunk)
            if (count == 0) return all
            for (index in 0 until count * CHANNELS) all += chunk[index]
        }
    }

    private fun assertSameDecode(name: String, expected: Decoded, actual: Decoded) {
        assertEquals(expected.tracks, actual.tracks, "$name: tracks")
        assertEquals(expected.durationUs, actual.durationUs, "$name: duration")
        assertContentEquals(expected.all, actual.all, "$name: PCM from the start")
        assertContentEquals(expected.afterSeek, actual.afterSeek, "$name: PCM after a seek")
    }

    /**
     * One second of 64x48 MPEG-4 video and a 440 Hz stereo tone that starts 0.5 s in, as [name]'s
     * container. The tone is encoded first and then copied in with its offset: an encode from raw
     * PCM would start its audio at zero.
     */
    private suspend fun encode(name: String, audioCodec: String): Path {
        fileSystem.createDirectories(directory)
        val tone = directory / "tone-$audioCodec.mka"
        if (!fileSystem.exists(tone)) {
            val pcm = Buffer().apply {
                repeat(SAMPLE_RATE) { frame ->
                    val sample = (sin(2 * PI * 440 * frame / SAMPLE_RATE) * 0.25).toFloat().toRawBits()
                    repeat(CHANNELS) { writeIntLe(sample) }
                }
            }
            ffmpeg(
                listOf("-f", "f32le", "-ar", "$SAMPLE_RATE", "-ch_layout", "stereo", "-i", "tone.f32", "-c:a", audioCodec, "-f", "matroska"),
                output = tone,
            ) { input("tone.f32", pcm) }
        }
        val frames = Buffer().apply {
            repeat(25) { index -> write(ByteArray(64 * 48 * 3 / 2) { ((index * 10 + it) % 251).toByte() }) }
        }
        val path = directory / name
        val toneHandle = fileSystem.openReadOnly(tone)
        try {
            ffmpeg(
                listOf(
                    "-f", "rawvideo", "-pixel_format", "yuv420p", "-video_size", "64x48", "-framerate", "25",
                    "-i", "frames.yuv",
                    "-itsoffset", "0.5", "-i", "tone.mka",
                    "-map", "0:v", "-map", "1:a", "-c:v", "mpeg4", "-c:a", "copy",
                ),
                output = path,
            ) {
                input("frames.yuv", frames)
                input("tone.mka", toneHandle)
            }
        } finally {
            toneHandle.close()
        }
        return path
    }

    /** Runs FFmpeg with [arguments] and then the output, written to [output] under its file name. */
    private suspend fun ffmpeg(arguments: List<String>, output: Path, inputs: CommandIo.Builder.() -> Unit) {
        val handle = fileSystem.openReadWrite(output, mustCreate = true)
        val result = try {
            FFmpegClient().use { client ->
                client.execute(
                    listOf("-y") + arguments + output.name,
                    CommandIo {
                        inputs()
                        output(output.name, handle)
                    },
                )
            }
        } finally {
            handle.close()
        }
        assertTrue(result.isSuccess, result.errorOutput)
    }

    private data class Decoded(
        val tracks: List<AudioTrackInfo>,
        val durationUs: Long?,
        val all: List<Float>,
        val afterSeek: List<Float>,
    )
}

private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 2
