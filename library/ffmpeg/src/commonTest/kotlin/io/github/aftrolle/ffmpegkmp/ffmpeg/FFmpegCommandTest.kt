// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffmpeg

import io.github.aftrolle.ffmpegkmp.core.CommandLineTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class FFmpegCommandTest {
    @Test
    fun buildsOrderedCommand() {
        val command = FFmpegCommand {
            overwrite()
            input("input clip.mp4")
            videoCodec("h264")
            audioCodec("aac")
            map("0:v:0")
            metadata("title", "Example")
            output("output.mp4")
        }

        assertEquals(
            listOf(
                "-y", "-i", "input clip.mp4", "-c:v", "h264", "-c:a", "aac",
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
}
