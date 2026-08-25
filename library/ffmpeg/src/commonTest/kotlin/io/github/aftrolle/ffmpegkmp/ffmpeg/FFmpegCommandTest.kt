// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffmpeg

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
