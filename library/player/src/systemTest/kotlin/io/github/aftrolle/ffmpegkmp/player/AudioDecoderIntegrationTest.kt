// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.player

import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegCommand
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path

/**
 * Drives the real native engine end to end. The fixture is encoded through FFmpeg with the audio
 * DSL itself, so these tests also prove the encode-side levels and track mapping.
 *
 * Every track is lossless constant-value PCM, so each mixed sample is exactly predictable:
 * - track 0 ("eng"): 0.2 for the first half second, then 0.4;
 * - track 1 ("fra"): 0.3 on input, encoded at [AudioLevel] volume 0.5, so 0.15.
 */
class AudioDecoderIntegrationTest {
    private val fileSystem = FileSystem.SYSTEM
    private lateinit var directory: Path
    private lateinit var media: Path

    @BeforeTest
    fun encodeFixture() = runTest {
        directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ffmpegkmp-player-${Random.nextLong().toULong()}"
        fileSystem.createDirectories(directory)
        media = directory / "two-tracks.nut"
        val halfSecond = SAMPLE_RATE / 2
        val first = pcm(List(halfSecond) { 0.2f } + List(halfSecond) { 0.4f })
        val second = pcm(List(SAMPLE_RATE) { 0.3f })

        val command = FFmpegCommand {
            overwrite()
            rawAudioInput(FIRST_INPUT)
            rawAudioInput(SECOND_INPUT)
            mapAudio(input = 0)
            mapAudio(input = 1)
            audioLevel(AudioLevel(volume = 0.5), outputTrack = 1)
            audioCodec("pcm_f32le")
            metadata("language", "eng", streamSpecifier = "s:a:0")
            metadata("language", "fra", streamSpecifier = "s:a:1")
            output(OUTPUT, format = "nut")
        }
        val output = fileSystem.openReadWrite(media)
        val result = FFmpegClient().use { client ->
            client.execute(
                command,
                CommandIo {
                    input(FIRST_INPUT, first)
                    input(SECOND_INPUT, second)
                    output(OUTPUT, output)
                },
            )
        }
        assertTrue(result.isSuccess, result.errorOutput)
    }

    @AfterTest
    fun deleteFixture() {
        fileSystem.deleteRecursively(directory)
    }

    @Test
    fun listsTracksAndStartsWithTheDefaultOnly() = decoder { decoder ->
        assertEquals(listOf("eng", "fra"), decoder.tracks.map(AudioTrackInfo::language))
        assertEquals(listOf("pcm_f32le", "pcm_f32le"), decoder.tracks.map(AudioTrackInfo::codec))
        assertTrue(decoder.tracks.all(AudioTrackInfo::isDecodable))
        assertEquals(setOf(0), decoder.enabledTracks)
        // The container's duration is FFmpeg's estimate; the frame count below is the exact truth.
        assertTrue(decoder.duration!! in 900.milliseconds..1_000.milliseconds, "${decoder.duration}")

        val samples = decodeAll(decoder)
        assertEquals(SAMPLE_RATE * CHANNELS, samples.size, "every input frame, and no more")
        assertAll(samples, 0 until 10, 0.2f)
        assertAll(samples, samples.size - 10 until samples.size, 0.4f)
    }

    @Test
    fun encodedLevelAndSelectionReachTheMix() = decoder { decoder ->
        decoder.selectTracks(setOf(1))
        assertAll(read(decoder, 1_000), 0.15f)

        decoder.setTrackEnabled(0, true)
        decoder.seek(0.milliseconds)
        assertAll(read(decoder, 1_000), 0.35f)
    }

    @Test
    fun trackAndMasterLevelsApplyLive() = decoder { decoder ->
        decoder.selectTracks(setOf(0, 1))
        decoder.setTrackLevel(0, AudioLevel(volume = 0.5))
        assertAll(read(decoder, 500), 0.1f + 0.15f)

        decoder.setTrackLevel(1, AudioLevel(volume = 2.0, muted = true))
        assertAll(read(decoder, 500), 0.1f)

        decoder.setTrackLevel(1, decoder.trackLevel(1).copy(muted = false))
        decoder.level = AudioLevel(volume = 2.0)
        assertAll(read(decoder, 500), (0.1f + 0.3f) * 2f)

        decoder.level = AudioLevel.Muted
        assertAll(read(decoder, 500), 0f)
    }

    @Test
    fun seeksSampleAccurately() = decoder { decoder ->
        decoder.seek(600.milliseconds)
        assertEquals(600.milliseconds, decoder.position)
        val remaining = decodeAll(decoder)
        assertEquals(SAMPLE_RATE * 4 / 10 * CHANNELS, remaining.size)
        assertAll(remaining, 0.4f)

        decoder.seek(499.milliseconds)
        val straddling = read(decoder, SAMPLE_RATE / 100)
        assertAll(straddling, 0 until SAMPLE_RATE / 1_000 * CHANNELS, 0.2f)
        assertAll(straddling, SAMPLE_RATE / 1_000 * CHANNELS until straddling.size, 0.4f)
    }

    @Test
    fun aTrackEnabledMidPlaybackJoinsInTime() = decoder { decoder ->
        read(decoder, SAMPLE_RATE / 4)
        decoder.setTrackEnabled(1, true)
        val rest = decodeAll(decoder)

        assertEquals(SAMPLE_RATE * 3 / 4 * CHANNELS, rest.size, "joining must not shift the timeline")
        // The track joins at the demuxer's read position, a packet or so ahead; from then on
        // both tracks must be summed sample-for-sample.
        assertAll(rest, rest.size - 1_000 until rest.size, 0.4f + 0.15f)
        rest.forEach { sample ->
            assertTrue(sample.near(0.2f) || sample.near(0.35f) || sample.near(0.4f) || sample.near(0.55f), "$sample")
        }
    }

    @Test
    fun disablingEveryTrackPlaysSilenceForTheDuration() = decoder { decoder ->
        decoder.selectTracks(emptySet())
        val samples = decodeAll(decoder)
        assertEquals(SAMPLE_RATE * CHANNELS, samples.size)
        assertAll(samples, 0f)
    }

    @Test
    fun reportsUnreadableInputs() {
        assertFailsWith<AudioDecodingException> {
            AudioDecoder.open((directory / "missing.nut").toString())
        }
    }

    private fun decoder(block: (AudioDecoder) -> Unit) {
        AudioDecoder.open(media.toString(), PcmFormat(SAMPLE_RATE, CHANNELS)).use(block)
    }

    private fun read(decoder: AudioDecoder, frames: Int): FloatArray {
        val samples = FloatArray(frames * CHANNELS)
        var filled = 0
        while (filled < frames) {
            val count = decoder.read(samples, filled * CHANNELS, frames - filled)
            if (count == 0) fail("Input ended after $filled of $frames frames")
            filled += count
        }
        return samples
    }

    private fun decodeAll(decoder: AudioDecoder): FloatArray {
        val chunk = FloatArray(4_096 * CHANNELS)
        val all = mutableListOf<Float>()
        while (true) {
            val count = decoder.read(chunk)
            if (count == 0) return all.toFloatArray()
            for (index in 0 until count * CHANNELS) all += chunk[index]
        }
    }
}

private fun FFmpegCommand.Builder.rawAudioInput(path: String) {
    option("-f", "f32le")
    option("-ar", SAMPLE_RATE.toString())
    option("-ch_layout", "stereo")
    input(path)
}

/** Interleaved stereo little-endian float PCM with [values] in both channels. */
private fun pcm(values: List<Float>): Buffer = Buffer().apply {
    values.forEach { value ->
        repeat(CHANNELS) { writeIntLe(value.toRawBits()) }
    }
}

private fun assertAll(samples: FloatArray, expected: Float) = assertAll(samples, samples.indices, expected)

private fun assertAll(samples: FloatArray, range: IntRange, expected: Float) {
    range.forEach { index ->
        assertTrue(samples[index].near(expected), "Sample $index was ${samples[index]}, expected $expected")
    }
}

private fun Float.near(expected: Float) = abs(this - expected) < 1e-5f

private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 2
private const val FIRST_INPUT = "first.f32"
private const val SECOND_INPUT = "second.f32"
private const val OUTPUT = "two-tracks.nut"
