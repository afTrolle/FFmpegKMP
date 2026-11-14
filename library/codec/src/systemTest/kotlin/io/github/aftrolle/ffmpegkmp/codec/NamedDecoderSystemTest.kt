// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Buffer

/**
 * [NamedCodec] decoders against the real bridge: exactly the named decoder opens, with its
 * AVOptions, or opening fails saying why, with no fallback. FFmpeg's own H.264 and MPEG-4 decoders
 * are in every build, so these run on every host.
 */
class NamedDecoderSystemTest {
    @Test
    fun aNamedSoftwareDecoderOpensAndDecodesTheSameFrames() = runBlocking {
        val expected = open(H264_CLIP, CodecPreference.SOFTWARE).use { it.numbers() }
        // Its own options too: one thread, slice threading, as `ffmpeg -threads 1 -thread_type slice -c:v h264` sets them.
        for (named in listOf(NamedCodec("h264"), NamedCodec("h264", mapOf("threads" to "1", "thread_type" to "slice")))) {
            open(H264_CLIP, named).use { decoder ->
                assertEquals(DecoderKind.SOFTWARE, decoder.decoderKind, "$named")
                assertNull(decoder.decoderFallback, "$named")
                assertEquals(expected, decoder.numbers(), "$named")
            }
        }
    }

    @Test
    fun anUnknownNameFailsNamingIt() = runBlocking {
        assertFailsSaying(NamedCodec("no_such_decoder"), "this build has no decoder named 'no_such_decoder'")
    }

    @Test
    fun aDecoderOfAnotherCodecFailsNamingBoth() = runBlocking {
        // FFmpeg's MPEG-4 Part 2 decoder for an H.264 stream: no fallback to the H.264 one.
        assertFailsSaying(NamedCodec("mpeg4"), "mpeg4 decodes mpeg4, not h264")
    }

    @Test
    fun anUnknownOptionFailsNamingEachOne() = runBlocking {
        assertFailsSaying(
            NamedCodec("h264", mapOf("threads" to "1", "no_such_option" to "1", "nor_this" to "2")),
            "h264 has no option 'no_such_option', 'nor_this'",
        )
    }

    @Test
    fun anOptionThatIsNoVideoDecodingOptionOrABadValueFails() = runBlocking {
        // The bit rate is an encoding option only.
        assertFailsSaying(NamedCodec("h264", mapOf("b" to "1000000")), "'b' is no video decoding option of h264")
        assertFailsSaying(
            NamedCodec("h264", mapOf("skip_frame" to "no_such_value")),
            "h264 does not take 'no_such_value' for its option 'skip_frame'",
        )
    }

    @Test
    fun extractFrameOpensTheNamedDecoderOnceWithoutASoftwareRetry() = runBlocking {
        val expected = VideoDecoder.extractFrame(source(H264_CLIP), 0.5.seconds, decoder = CodecPreference.SOFTWARE)
            .use { it.packed(4) }
        val named = VideoDecoder.extractFrame(source(H264_CLIP), 0.5.seconds, decoder = NamedCodec("h264")).use { it.packed(4) }
        assertContentEquals(expected, named)
        val failure = assertFailsWith<VideoDecodingException> {
            VideoDecoder.extractFrame(source(H264_CLIP), 0.5.seconds, decoder = NamedCodec("mpeg4"))
        }
        assertContains(failure.message.orEmpty(), "mpeg4 decodes mpeg4, not h264")
        // One attempt: a software retry would have added the first failure as suppressed.
        assertEquals(0, failure.suppressedExceptions.size)
    }

    private suspend fun assertFailsSaying(decoder: NamedCodec, reason: String) {
        val failure = assertFailsWith<VideoDecodingException> { open(H264_CLIP, decoder).close() }
        println("As expected: ${failure.message}")
        assertContains(failure.message.orEmpty(), reason)
    }

    private suspend fun open(name: String, decoder: CodecSelection): VideoDecoder = VideoDecoder.open(source(name), decoder = decoder)

    private fun source(name: String): MediaSource {
        val bytes = readVideoDecoderFixture(name)
        return MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) })
    }

    private suspend fun VideoDecoder.numbers(): List<Int> = frames().map { frame -> frame.use { it.number() } }.toList()

    private companion object {
        const val H264_CLIP = "cfr-30-h264.mp4"
    }
}
