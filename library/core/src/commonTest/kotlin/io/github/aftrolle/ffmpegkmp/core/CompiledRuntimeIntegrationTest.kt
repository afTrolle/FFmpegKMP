// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileHandle

class CompiledRuntimeIntegrationTest {
    @Test
    fun failedCommandDoesNotTruncateUnopenedOutput() = runTest {
        val initialBytes = "preserve me".encodeToByteArray()
        val output = MemoryFileHandle(readWrite = true, initialBytes)
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)

        try {
            val result = ffmpeg.execute(
                arguments = listOf("-definitely-not-an-ffmpeg-option", OUTPUT_PATH),
                io = CommandIo { output(OUTPUT_PATH, output, truncate = true) },
            )

            assertTrue(!result.isSuccess)
            assertContentEquals(initialBytes, output.snapshot())
        } finally {
            ffmpeg.close()
        }
    }

    @Test
    fun executesTinyMediaPipeline() = runTest {
        val pixels = Buffer().apply { write(ByteArray(FRAME_BYTE_COUNT)) }
        val media = MemoryFileHandle(readWrite = true)
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)
        val ffprobe = CommandRuntimeClient(CommandKind.FFPROBE)

        try {
            val encodeResult = ffmpeg.execute(
                arguments = listOf(
                    "-hide_banner",
                    "-loglevel", "error",
                    "-f", "rawvideo",
                    "-pixel_format", "rgb24",
                    "-video_size", "16x16",
                    "-i", RAW_INPUT_PATH,
                    "-frames:v", "1",
                    "-c:v", "rawvideo",
                    "-f", "nut",
                    OUTPUT_PATH,
                ),
                io = CommandIo {
                    input(RAW_INPUT_PATH, pixels)
                    readWrite(OUTPUT_PATH, media, truncate = true)
                },
            )

            assertTrue(encodeResult.isSuccess, encodeResult.errorOutput)
            val mediaBytes = media.snapshot()
            assertTrue(mediaBytes.isNotEmpty(), "FFmpeg should produce a non-empty media file")

            val probeResult = ffprobe.execute(
                arguments = listOf(
                    "-v", "error",
                    "-show_entries", "stream=codec_type,width,height",
                    "-of", "json",
                    INPUT_PATH,
                ),
                io = CommandIo { input(INPUT_PATH, MemoryFileHandle(readWrite = false, mediaBytes)) },
            )

            assertTrue(probeResult.isSuccess, probeResult.errorOutput)
            assertContains(probeResult.output, "\"codec_type\"")
        } finally {
            ffmpeg.close()
            ffprobe.close()
        }
    }

    private class MemoryFileHandle(
        readWrite: Boolean,
        initialBytes: ByteArray = ByteArray(0),
    ) : FileHandle(readWrite) {
        private var bytes = initialBytes.copyOf()

        fun snapshot(): ByteArray = bytes.copyOf()

        override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ): Int {
            if (fileOffset >= bytes.size) return -1
            val count = minOf(byteCount, bytes.size - fileOffset.toInt())
            bytes.copyInto(array, arrayOffset, fileOffset.toInt(), fileOffset.toInt() + count)
            return count
        }

        override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ) {
            val requiredSize = fileOffset + byteCount
            require(requiredSize <= Int.MAX_VALUE)
            if (requiredSize > bytes.size) bytes = bytes.copyOf(requiredSize.toInt())
            array.copyInto(bytes, fileOffset.toInt(), arrayOffset, arrayOffset + byteCount)
        }

        override fun protectedFlush() = Unit

        override fun protectedResize(size: Long) {
            require(size in 0..Int.MAX_VALUE.toLong())
            bytes = bytes.copyOf(size.toInt())
        }

        override fun protectedSize(): Long = bytes.size.toLong()

        override fun protectedClose() = Unit
    }

    private companion object {
        const val FRAME_BYTE_COUNT = 16 * 16 * 3
        const val RAW_INPUT_PATH = "frame.rgb"
        const val OUTPUT_PATH = "generated.nut"
        const val INPUT_PATH = "probe-input.nut"
    }
}
