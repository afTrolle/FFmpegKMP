// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    @Test
    fun streamSelectionDoesNotLeakIntoTheNextProbe() = runTest {
        val media = audioVideoMedia()
        val ffprobe = CommandRuntimeClient(CommandKind.FFPROBE)

        try {
            val videoOnly = ffprobe.probe(media, "-select_streams", "v:0", "-show_streams")
            assertFalse("\"audio\"" in videoOnly, videoOnly)

            val everything = ffprobe.probe(media, "-show_streams")
            assertContains(everything, "\"video\"")
            assertContains(everything, "\"audio\"")
        } finally {
            ffprobe.close()
        }
    }

    @Test
    fun entrySelectionDoesNotLeakIntoTheNextProbe() = runTest {
        val media = audioVideoMedia()
        val ffprobe = CommandRuntimeClient(CommandKind.FFPROBE)

        try {
            val selected = ffprobe.probe(media, "-show_entries", "stream=codec_type")
            assertFalse("\"codec_name\"" in selected, selected)

            val full = ffprobe.probe(media, "-show_format", "-show_streams")
            assertContains(full, "\"codec_name\"")
            assertContains(full, "\"format_name\"")

            val selectedAgain = ffprobe.probe(media, "-show_entries", "stream=codec_type")
            assertFalse("\"codec_name\"" in selectedAgain, selectedAgain)
            assertFalse("\"format_name\"" in selectedAgain, selectedAgain)
        } finally {
            ffprobe.close()
        }
    }

    @Test
    fun largeSourceInputReachesFFmpegIntact() = runTest {
        // Okio moves a Source into a Buffer segment by segment (8 KiB each); a native read spans
        // several of them, and every byte must arrive, not just the first segment's.
        val input = ByteArray(LARGE_INPUT_BYTE_COUNT) { index -> (index * 31 + index / 8_192).toByte() }
        val output = Buffer()
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)

        try {
            val result = ffmpeg.execute(
                arguments = listOf(
                    "-hide_banner", "-loglevel", "error",
                    "-f", "u8", "-ar", "8000", "-ac", "1", "-i", LARGE_INPUT_PATH,
                    "-c:a", "pcm_u8", "-f", "u8", LARGE_OUTPUT_PATH,
                ),
                io = CommandIo {
                    input(LARGE_INPUT_PATH, Buffer().write(input))
                    output(LARGE_OUTPUT_PATH, output)
                },
            )

            assertTrue(result.isSuccess, result.errorOutput)
            assertContentEquals(input, output.readByteArray())
        } finally {
            ffmpeg.close()
        }
    }

    @Test
    fun stagingGivesAPlainSinkRealSeekableStorage() = runTest {
        if (!stagingSupportedOnThisPlatform) return@runTest
        val pixels = Buffer().apply { write(ByteArray(FRAME_BYTE_COUNT)) }
        val rendered = Buffer()
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)

        try {
            val result = ffmpeg.execute(
                arguments = listOf(
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", "16x16",
                    "-i", RAW_INPUT_PATH,
                    "-frames:v", "1", "-c:v", "mpeg4",
                    MP4_OUTPUT_PATH,
                ),
                io = CommandIo {
                    input(RAW_INPUT_PATH, pixels)
                    output(MP4_OUTPUT_PATH, rendered, Staging())
                },
            )

            assertTrue(result.isSuccess, result.errorOutput)
            assertTrue(rendered.size > 0L)
            assertEquals("ftyp", rendered.snapshot().substring(4, 8).utf8())
        } finally {
            ffmpeg.close()
        }
    }

    @Test
    fun plainSinkWithoutStagingFailsForAFormatThatNeedsToSeek() = runTest {
        // Browser mounts are buffered whole in the worker, so even a plain Sink is seekable there.
        if (!stagingSupportedOnThisPlatform) return@runTest
        // Demonstrates Staging is load-bearing, not a no-op: the exact same command that
        // succeeds above fails without it, because a plain Sink mount is non-seekable and
        // the default (non-fragmented) MP4 muxer needs to seek to patch its header.
        val pixels = Buffer().apply { write(ByteArray(FRAME_BYTE_COUNT)) }
        val rendered = Buffer()
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)

        try {
            val result = ffmpeg.execute(
                arguments = listOf(
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", "16x16",
                    "-i", RAW_INPUT_PATH,
                    "-frames:v", "1", "-c:v", "mpeg4",
                    MP4_OUTPUT_PATH,
                ),
                io = CommandIo {
                    input(RAW_INPUT_PATH, pixels)
                    output(MP4_OUTPUT_PATH, rendered)
                },
            )

            assertTrue(!result.isSuccess)
        } finally {
            ffmpeg.close()
        }
    }

    @Test
    fun stagingFailsLoudlyWhenASuccessfulCommandNeverWroteTheStagedMount() = runTest {
        if (!stagingSupportedOnThisPlatform) return@runTest
        val pixels = Buffer().apply { write(ByteArray(FRAME_BYTE_COUNT)) }
        val media = MemoryFileHandle(readWrite = true)
        val neverWritten = Buffer()
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)

        try {
            val outcome = runCatching {
                ffmpeg.execute(
                    arguments = listOf(
                        "-hide_banner", "-loglevel", "error",
                        "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", "16x16",
                        "-i", RAW_INPUT_PATH,
                        "-frames:v", "1", "-c:v", "rawvideo", "-f", "nut", OUTPUT_PATH,
                    ),
                    io = CommandIo {
                        input(RAW_INPUT_PATH, pixels)
                        readWrite(OUTPUT_PATH, media, truncate = true)
                        // Mounted with staging but never referenced in the arguments above:
                        // ffmpeg returns 0 without ever writing to it.
                        output(UNUSED_PATH, neverWritten, Staging())
                    },
                )
            }

            assertTrue(outcome.isFailure)
            val failure = outcome.exceptionOrNull()
            val emptyOutputFailure = generateSequence(failure) { it.cause }
                .filterIsInstance<StagedOutputEmptyException>()
                .firstOrNull()
            assertTrue(
                emptyOutputFailure != null,
                "Expected a StagedOutputEmptyException in the cause chain of: $failure",
            )
            assertContains(emptyOutputFailure.message.orEmpty(), UNUSED_PATH)
        } finally {
            ffmpeg.close()
        }
    }

    @Test
    fun stagingCopiesNothingWhenAnyStagedMountFailsVerification() = runTest {
        if (!stagingSupportedOnThisPlatform) return@runTest
        val pixels = Buffer().apply { write(ByteArray(FRAME_BYTE_COUNT)) }
        val rendered = Buffer()
        val neverWritten = Buffer()
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)

        try {
            val outcome = runCatching {
                ffmpeg.execute(
                    arguments = listOf(
                        "-hide_banner", "-loglevel", "error", "-y",
                        "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", "16x16",
                        "-i", RAW_INPUT_PATH,
                        "-frames:v", "1", "-c:v", "mpeg4",
                        MP4_OUTPUT_PATH,
                    ),
                    io = CommandIo {
                        input(RAW_INPUT_PATH, pixels)
                        output(MP4_OUTPUT_PATH, rendered, Staging())
                        // Mounted with staging but never referenced in the arguments above.
                        output(UNUSED_PATH, neverWritten, Staging())
                    },
                )
            }

            assertTrue(outcome.isFailure)
            // FFmpeg DID successfully write MP4_OUTPUT_PATH's staged temp file, but because
            // UNUSED_PATH's staged mount failed verification, nothing should have been copied
            // to EITHER sink: a "failed" command must never leave one sink populated.
            assertEquals(0L, rendered.size)
            assertEquals(0L, neverWritten.size)
        } finally {
            ffmpeg.close()
        }
    }

    @Test
    fun overwriteFlagDoesNotLeakIntoTheNextCommand() = runTest {
        // fftools refuses to overwrite only the files it opens itself, through its file protocol.
        val path = hostFilePath("ffmpegkmp-overwrite-${Random.nextLong(Long.MAX_VALUE)}.u8") ?: return@runTest
        val written = ByteArray(AUDIO_BYTE_COUNT) { 1 }
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)

        try {
            writeHostFile(path, "existing".encodeToByteArray())
            val overwritten = ffmpeg.execute(
                arguments = copyAudio(path, "-y"),
                io = CommandIo { input(AUDIO_INPUT_PATH, Buffer().write(written)) },
            )
            assertTrue(overwritten.isSuccess, overwritten.errorOutput)
            assertContentEquals(written, readHostFile(path))

            val refused = ffmpeg.execute(
                arguments = copyAudio(path),
                io = CommandIo { input(AUDIO_INPUT_PATH, Buffer().write(ByteArray(AUDIO_BYTE_COUNT) { 2 })) },
            )
            assertContains(refused.errorOutput, "already exists")
            assertContentEquals(written, readHostFile(path))
        } finally {
            ffmpeg.close()
            deleteHostFile(path)
        }
    }

    @Test
    fun copyTimestampsDoesNotLeakIntoTheNextCommand() = runTest {
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)

        try {
            // -ss before -i seeks the input; -copyts keeps the seeked timestamps instead of
            // shifting the output to start at zero.
            assertEquals(4_000L, ffmpeg.firstTimestampAfterSeeking("-copyts"))
            assertEquals(0L, ffmpeg.firstTimestampAfterSeeking())
        } finally {
            ffmpeg.close()
        }
    }

    @Test
    fun abortOnDoesNotLeakIntoTheNextCommand() = runTest {
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)

        try {
            val aborted = ffmpeg.execute(
                arguments = copyNothing("-abort_on", "empty_output"),
                io = CommandIo {
                    input(AUDIO_INPUT_PATH, Buffer())
                    output(LARGE_OUTPUT_PATH, Buffer())
                },
            )
            assertFalse(aborted.isSuccess, "-abort_on empty_output should fail an empty output")

            val allowed = ffmpeg.execute(
                arguments = copyNothing(),
                io = CommandIo {
                    input(AUDIO_INPUT_PATH, Buffer())
                    output(LARGE_OUTPUT_PATH, Buffer())
                },
            )
            assertTrue(allowed.isSuccess, allowed.errorOutput)
        } finally {
            ffmpeg.close()
        }
    }

    @Test
    fun cancelledCommandEndsPromptly() = runTest {
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)

        try {
            val session = ffmpeg.enqueue(realtimeAudio(), realtimeAudioIo(seconds = 60))
            val result = withContext(Dispatchers.Default) {
                // Once it reports its statistics, fftools is transcoding: only cancelling ends it early.
                withTimeout(30.seconds) { session.events.first(::isStatsReport) }
                val cancelled = TimeSource.Monotonic.markNow()
                session.cancel()
                val result = withTimeout(10.seconds) { session.await() }
                assertTrue(cancelled.elapsedNow() < 5.seconds, "Cancelling took ${cancelled.elapsedNow()}")
                result
            }
            assertTrue(result.cancelled)
            assertEquals(SessionState.CANCELLED, session.state.value)
        } finally {
            ffmpeg.close()
        }
    }

    @Test
    fun commandsFromOneClientOverlapWhereThereAreSeveralLanes() = runTest {
        if (platformCommandLanes < 2) return@runTest
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)

        try {
            val first = ffmpeg.enqueue(realtimeAudio(), realtimeAudioIo(seconds = 4))
            val second = ffmpeg.enqueue(realtimeAudio(), realtimeAudioIo(seconds = 4))
            withContext(Dispatchers.Default) {
                withTimeout(30.seconds) { second.events.first(::isStatsReport) }
            }
            // The second command is transcoding while the first, started before it, still runs.
            assertEquals(SessionState.RUNNING, first.state.value)
            assertTrue(first.await().isSuccess)
            assertTrue(second.await().isSuccess)
        } finally {
            ffmpeg.close()
        }
    }

    /** FFmpeg's periodic statistics line, which for audio-only output starts with `size=`. */
    private fun isStatsReport(event: ExecutionEvent): Boolean =
        event is ExecutionEvent.Log && "time=" in event.message && "speed=" in event.message

    private fun copyAudio(output: String, vararg options: String) = listOf(
        "-hide_banner", "-loglevel", "error", *options,
        "-f", "u8", "-ar", "8000", "-ac", "1", "-i", AUDIO_INPUT_PATH,
        "-c:a", "pcm_u8", "-f", "u8", output,
    )

    private fun copyNothing(vararg options: String) = listOf(
        "-hide_banner", "-loglevel", "error", *options,
        "-f", "u8", "-ar", "8000", "-ac", "1", "-i", AUDIO_INPUT_PATH,
        "-c", "copy", "-f", "u8", LARGE_OUTPUT_PATH,
    )

    /** -re reads the input at its own rate, so the command runs as long as its audio lasts. */
    private fun realtimeAudio() = listOf(
        "-hide_banner", "-re", "-f", "u8", "-ar", "8000", "-ac", "1", "-i", AUDIO_INPUT_PATH,
        "-f", "null", "-",
    )

    private fun realtimeAudioIo(seconds: Int) = CommandIo {
        input(AUDIO_INPUT_PATH, Buffer().write(ByteArray(seconds * AUDIO_BYTE_COUNT)))
    }

    /** Seeks 0.5 s into a second of 8 kHz audio and returns the first packet's timestamp. */
    private suspend fun CommandRuntimeClient.firstTimestampAfterSeeking(vararg options: String): Long {
        val packets = Buffer()
        val result = execute(
            arguments = listOf(
                "-hide_banner", "-loglevel", "error", *options,
                "-ss", "0.5", "-f", "u8", "-ar", "8000", "-ac", "1", "-i", AUDIO_INPUT_PATH,
                "-c", "copy", "-f", "framecrc", FRAME_CRC_PATH,
            ),
            io = CommandIo {
                input(AUDIO_INPUT_PATH, Buffer().write(ByteArray(AUDIO_BYTE_COUNT)))
                output(FRAME_CRC_PATH, packets)
            },
        )
        assertTrue(result.isSuccess, result.errorOutput)
        // framecrc lines are "stream, dts, pts, duration, size, crc" in the stream's time base.
        val firstPacket = packets.readUtf8().lineSequence().first { it.isNotBlank() && !it.startsWith("#") }
        return firstPacket.split(',')[1].trim().toLong()
    }

    private suspend fun audioVideoMedia(): ByteArray {
        val media = MemoryFileHandle(readWrite = true)
        val ffmpeg = CommandRuntimeClient(CommandKind.FFMPEG)
        try {
            val result = ffmpeg.execute(
                arguments = listOf(
                    "-hide_banner", "-loglevel", "error",
                    "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", "16x16",
                    "-i", RAW_INPUT_PATH,
                    "-f", "u8", "-ar", "8000", "-ac", "1", "-i", AUDIO_INPUT_PATH,
                    "-map", "0:v", "-map", "1:a",
                    // The browser's fixed thread pool cannot hold a frame-thread encoder per core
                    // beside two inputs' demux, decode and filter threads.
                    "-frames:v", "1", "-c:v", "rawvideo", "-threads", "1", "-c:a", "pcm_u8",
                    "-f", "nut", OUTPUT_PATH,
                ),
                io = CommandIo {
                    input(RAW_INPUT_PATH, Buffer().write(ByteArray(FRAME_BYTE_COUNT)))
                    input(AUDIO_INPUT_PATH, Buffer().write(ByteArray(AUDIO_BYTE_COUNT)))
                    readWrite(OUTPUT_PATH, media, truncate = true)
                },
            )
            assertTrue(result.isSuccess, result.errorOutput)
        } finally {
            ffmpeg.close()
        }
        return media.snapshot()
    }

    private suspend fun CommandRuntimeClient.probe(media: ByteArray, vararg options: String): String {
        val result = execute(
            arguments = listOf("-v", "error", *options, "-of", "json", INPUT_PATH),
            io = CommandIo { input(INPUT_PATH, MemoryFileHandle(readWrite = false, media)) },
        )
        assertTrue(result.isSuccess, result.errorOutput)
        return result.output
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
        const val AUDIO_BYTE_COUNT = 8_000
        const val LARGE_INPUT_BYTE_COUNT = 128 * 1_024
        const val LARGE_INPUT_PATH = "large.u8"
        const val LARGE_OUTPUT_PATH = "large-copy.u8"
        const val RAW_INPUT_PATH = "frame.rgb"
        const val AUDIO_INPUT_PATH = "tone.u8"
        const val OUTPUT_PATH = "generated.nut"
        const val INPUT_PATH = "probe-input.nut"
        const val MP4_OUTPUT_PATH = "generated.mp4"
        const val UNUSED_PATH = "unused.nut"
        const val FRAME_CRC_PATH = "packets.crc"
    }
}
