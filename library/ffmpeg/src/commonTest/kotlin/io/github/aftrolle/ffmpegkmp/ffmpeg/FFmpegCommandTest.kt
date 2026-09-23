// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffmpeg

import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import io.github.aftrolle.ffmpegkmp.core.CommandLineTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class FFmpegCommandTest {
    @Test
    fun buildsCommandForMountedVideo() {
        val command = FFmpegCommand {
            overwrite()
            input(BUNNY_PATH)
            videoCodec("h264")
            audioCodec("aac")
            map("0:v:0")
            metadata("title", "Example")
            output("output.mp4")
        }

        assertEquals(
            listOf(
                "-y", "-i", BUNNY_PATH, "-c:v", "h264", "-c:a", "aac",
                "-map", "0:v:0", "-metadata", "title=Example", "output.mp4",
            ),
            command.arguments,
        )
    }

    @Test
    fun selectsAudioTracksWithIndependentLevels() {
        val command = FFmpegCommand {
            input(BUNNY_PATH)
            map("0:v:0")
            mapAudio(track = 1)
            mapAudio(track = 0)
            mapAudio(input = 0, track = 2, optional = true)
            audioLevel(AudioLevel(volume = 0.5), outputTrack = 0)
            audioLevel(AudioLevel(volume = 1.5, muted = true), outputTrack = 1)
            audioCodec("aac")
            audioBitrate("128k", streamSpecifier = "0")
            audioSampleRate(48_000)
            audioChannels(2, streamSpecifier = "1")
            output("output.mp4")
        }

        assertEquals(
            listOf(
                "-i", BUNNY_PATH, "-map", "0:v:0",
                "-map", "0:a:1", "-map", "0:a:0", "-map", "0:a:2?",
                "-filter:a:0", "volume=0.5", "-filter:a:1", "volume=0",
                "-c:a", "aac", "-b:a:0", "128k", "-ar", "48000", "-ac:a:1", "2",
                "output.mp4",
            ),
            command.arguments,
        )
    }

    @Test
    fun mapsAllAudioOrDropsIt() {
        assertEquals(
            listOf("-map", "1:a", "-filter:a", "volume=2"),
            FFmpegCommand { mapAudio(input = 1); audioLevel(AudioLevel(volume = 2.0)) }.arguments,
        )
        assertEquals(listOf("-an"), FFmpegCommand { disableAudio() }.arguments)
    }

    @Test
    fun rejectsInvalidAudioLevels() {
        assertFails { AudioLevel(volume = -0.1) }
        assertFails { AudioLevel(volume = Double.NaN) }
        assertFails { FFmpegCommand { mapAudio(track = -1) } }
        assertEquals(0.0, AudioLevel(volume = 0.8, muted = true).effectiveVolume)
        assertEquals(0.8, AudioLevel(volume = 0.8).effectiveVolume)
    }

    @Test
    fun tokenizesQuotesEscapesAndOptionalExecutable() {
        assertEquals(
            listOf("-i", "input clip.mp4", "-metadata", "title=Sam's clip", "out.mp4"),
            CommandLineTokenizer.tokenize(
                "ffmpeg -i 'input clip.mp4' -metadata \"title=Sam's clip\" out.mp4",
                "ffmpeg",
            ),
        )
    }

    @Test
    fun rejectsUnterminatedQuotes() {
        assertFails { CommandLineTokenizer.tokenize("-i 'broken") }
    }

    private companion object {
        const val BUNNY_PATH = "big-buck-bunny-1s.mp4"
    }
}
