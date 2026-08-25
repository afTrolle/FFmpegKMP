// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package io.github.aftrolle.ffmpegkmp.ffprobe

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Buffer

class FFprobeWebIntegrationTest {
    @Test
    fun loadsPackagedBigBuckBunnyFixture() = runTest {
        val bytes = loadWebResource("/base/kotlin/big-buck-bunny-1s.mp4")

        assertTrue(bytes.size > 8)
        assertEquals("ftyp", bytes.copyOfRange(4, 8).decodeToString())
    }

    @Test
    fun readsMountedBigBuckBunnyMp4() = runTest {
        configureWebRuntime()
        assertTrue(
            isWebRuntimeCrossOriginIsolated(),
            "The FFmpeg Wasm runtime requires cross-origin isolation (${webEnvironmentSummary()})",
        )

        val inputPath = "/fixtures/big-buck-bunny-1s.mp4"
        val input = Buffer().apply { write(loadWebResource("/base/kotlin/big-buck-bunny-1s.mp4")) }
        val client = FFprobeClient()

        try {
            val media = client.inspect(
                input = inputPath,
                io = CommandIo { input(inputPath, input) },
            )
            val video = media.streams.firstOrNull { it.codecType == "video" }
            val duration = media.format?.durationSeconds ?: 0.0

            assertTrue(media.format?.formatName?.contains("mp4") == true)
            assertTrue(duration in 1.0..1.2, "Expected an approximately one-second clip, got $duration")
            assertNotNull(video, "FFprobe should discover the mounted video's stream")
            assertEquals("h264", video.codecName)
            assertEquals(320, video.width)
            assertEquals(176, video.height)
        } finally {
            client.close()
        }
    }

    @Test
    fun writesMountedMp4UsingTransferredBufferAndLogicalSize() = runTest {
        configureWebRuntime()
        val inputPath = "/fixtures/big-buck-bunny-1s.mp4"
        val outputPath = "/results/remuxed.mp4"
        val input = Buffer().apply { write(loadWebResource("/base/kotlin/big-buck-bunny-1s.mp4")) }
        val output = Buffer()
        val client = FFmpegClient()

        try {
            val result = client.execute(
                arguments = listOf(
                    "-i", inputPath,
                    "-map", "0",
                    "-c", "copy",
                    "-f", "mp4",
                    outputPath,
                ),
                io = CommandIo {
                    input(inputPath, input)
                    output(outputPath, output)
                },
            )
            val bytes = output.readByteArray()

            assertEquals(0, result.returnCode, result.errorOutput)
            assertTrue(bytes.size > 8)
            assertEquals("ftyp", bytes.copyOfRange(4, 8).decodeToString())
        } finally {
            client.close()
        }
    }
}

internal expect suspend fun loadWebResource(url: String): ByteArray
internal expect fun configureWebRuntime()
internal expect fun isWebRuntimeCrossOriginIsolated(): Boolean
internal expect fun webEnvironmentSummary(): String
