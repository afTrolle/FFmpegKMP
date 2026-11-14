// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import io.github.aftrolle.ffmpegkmp.ffprobe.FFprobeClient
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import okio.Path

/**
 * [NamedCodec] encoders against the real bridge: exactly the named encoder opens, with its
 * AVOptions, or the track fails saying why. The default host builds have no software H.264, HEVC
 * or AV1 encoder (libx264 and libx265 are GPL, libaom is Android's only), so the tests that encode
 * name VideoToolbox's H.264 encoder, which AUTO also picks on macOS: an option's visible effect
 * (the profile) is what shows the named encoder took the track with its options. A host without
 * it skips those tests, saying so. The failures that need no encoder run everywhere, and every
 * failure is asserted on the reason, not on the config the message also quotes.
 */
class NamedEncoderSystemTest {
    private val fileSystem = FileSystem.SYSTEM
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "named-encoder-${Random.nextLong().toULong()}"

    @BeforeTest
    fun createDirectory() {
        fileSystem.createDirectories(directory)
    }

    @AfterTest
    fun cleanUp() {
        fileSystem.deleteRecursively(directory)
    }

    @Test
    fun aNamedEncoderEncodesWithItsOptionsAndTheTrackReportsIt() = runBlocking {
        // VideoToolbox's software implementation, at a profile AUTO's VideoToolbox (High) does not write.
        val config = config(NamedCodec(VIDEOTOOLBOX_H264, mapOf("require_sw" to "1", "profile" to "main")))
        if (!encodes(config)) return@runBlocking
        val output = directory / "named.mp4"
        MediaWriter.open(MediaOutput.File(output.toString())).use { writer ->
            val track = writer.addVideoTrack(config)
            assertEquals(VIDEOTOOLBOX_H264, track.encoderName)
            // A platform codec wrapper reports hardware: FFmpeg does not tell which implementation runs behind it.
            assertTrue(track.isHardware)
            write(track, frames = 30)
            assertEquals(30, writer.finish().videoFrames)
        }
        assertEquals("Main", probeVideo(output).profile)
        VideoDecoder.open(MediaSource(output.toString())).use { decoder ->
            assertEquals((0 until 30).toList(), decoder.frames().map { frame -> frame.use { it.number() } }.toList())
        }
    }

    @Test
    fun anOptionOverridesWhatTheConfigLeavesToTheEncoder() = runBlocking {
        val baseline = config(NamedCodec(VIDEOTOOLBOX_H264, mapOf("profile" to "baseline")))
        val high = config(NamedCodec(VIDEOTOOLBOX_H264, mapOf("profile" to "high")))
        if (!encodes(baseline) || !encodes(high)) return@runBlocking
        assertContains(profileOf(baseline, directory / "baseline.mp4"), "Baseline")
        assertEquals("High", profileOf(high, directory / "high.mp4"))
    }

    @Test
    fun anUnknownNameFailsNamingIt() = runBlocking {
        assertFails(config(NamedCodec("no_such_encoder")), "this build has no encoder named 'no_such_encoder'")
    }

    @Test
    fun anEncoderOfAnotherCodecFailsNamingBoth() = runBlocking {
        // FFmpeg's own MPEG-4 Part 2 encoder is in every build, and is no H.264 encoder.
        assertFails(config(NamedCodec("mpeg4")), "mpeg4 encodes mpeg4, not h264")
    }

    @Test
    fun anUnknownOptionFailsNamingIt() = runBlocking {
        if (!encodes(config(NamedCodec(VIDEOTOOLBOX_H264)))) return@runBlocking
        assertFails(
            config(NamedCodec(VIDEOTOOLBOX_H264, mapOf("profile" to "main", "no_such_option" to "1"))),
            "$VIDEOTOOLBOX_H264 has no option 'no_such_option'",
        )
    }

    @Test
    fun anOptionThatIsNoVideoEncodingOptionFails() = runBlocking {
        if (!encodes(config(NamedCodec(VIDEOTOOLBOX_H264)))) return@runBlocking
        // The audio sample rate: an encoding option, but not a video one.
        assertFails(config(NamedCodec(VIDEOTOOLBOX_H264, mapOf("ar" to "48000"))), "'ar' is no video encoding option of $VIDEOTOOLBOX_H264")
    }

    @Test
    fun anInvalidOptionValueFailsNamingTheOption() = runBlocking {
        if (!encodes(config(NamedCodec(VIDEOTOOLBOX_H264)))) return@runBlocking
        assertFails(
            config(NamedCodec(VIDEOTOOLBOX_H264, mapOf("profile" to "no_such_profile"))),
            "does not take 'no_such_profile' for its option 'profile'",
        )
    }

    @Test
    fun anOptionThatChangesWhatTheWriterReliesOnFails() = runBlocking {
        if (!encodes(config(NamedCodec(VIDEOTOOLBOX_H264)))) return@runBlocking
        // MP4 needs the global header the writer asks for; this option takes it away.
        assertFails(
            config(NamedCodec(VIDEOTOOLBOX_H264, mapOf("flags" to "-global_header"))),
            "an option of $VIDEOTOOLBOX_H264 changes the global header the container needs",
        )
        // The size is no option an encoder takes: FFmpeg marks video_size for no encoder, so it fails earlier.
        assertFails(
            config(NamedCodec(VIDEOTOOLBOX_H264, mapOf("video_size" to "64x64"))),
            "'video_size' is no video encoding option of $VIDEOTOOLBOX_H264",
        )
    }

    @Test
    fun canEncodeAnswersForNamedEncoders() = runBlocking {
        assertFalse(MediaWriter.canEncode(config(NamedCodec("no_such_encoder"))))
        assertFalse(MediaWriter.canEncode(config(NamedCodec("mpeg4"))))
        if (!encodes(config(NamedCodec(VIDEOTOOLBOX_H264)))) return@runBlocking
        assertTrue(MediaWriter.canEncode(config(NamedCodec(VIDEOTOOLBOX_H264, mapOf("profile" to "main")))))
        assertFalse(MediaWriter.canEncode(config(NamedCodec(VIDEOTOOLBOX_H264, mapOf("no_such_option" to "1")))))
        assertFalse(MediaWriter.canEncode(config(NamedCodec(VIDEOTOOLBOX_H264, mapOf("profile" to "no_such_profile")))))
        // An H.264 encoder for an HEVC config.
        assertFalse(MediaWriter.canEncode(config(NamedCodec(VIDEOTOOLBOX_H264)).copy(codec = VideoCodec.HEVC)))
    }

    @Test
    fun aNamedCodecTakesNoBlankNameOrEmptyOptionNameAndKeepsItsOwnOptions() {
        assertFailsWith<IllegalArgumentException> { NamedCodec(" ") }
        assertFailsWith<IllegalArgumentException> { NamedCodec("libaom-av1", mapOf("" to "1")) }
        val options = mutableMapOf("cpu-used" to "8")
        val named = NamedCodec("libaom-av1", options)
        options["cpu-used"] = "1"
        assertEquals(mapOf("cpu-used" to "8"), named.options)
        assertEquals(NamedCodec("libaom-av1", mapOf("cpu-used" to "8")), named)
    }

    private fun config(encoder: CodecSelection): VideoEncoderConfig = VideoEncoderConfig(128, 128, FrameRate(30), encoder = encoder)

    private suspend fun assertFails(config: VideoEncoderConfig, vararg words: String) {
        assertFalse(MediaWriter.canEncode(config))
        MediaWriter.open(MediaOutput.File((directory / "failed.mp4").toString())).use { writer ->
            val failure = assertFailsWith<MediaWritingException> { writer.addVideoTrack(config) }
            // The reason only: the message quotes the config, the encoder's name and options with it.
            val reason = failure.message.orEmpty().substringAfter("$config: ")
            println("As expected: $reason")
            words.forEach { assertContains(reason, it) }
        }
    }

    private suspend fun probeVideo(output: Path) =
        FFprobeClient().use { probe -> probe.inspect(output.toString()).streams.single { it.codecType == "video" } }

    private suspend fun profileOf(config: VideoEncoderConfig, output: Path): String {
        MediaWriter.open(MediaOutput.File(output.toString())).use { writer ->
            write(writer.addVideoTrack(config), frames = 10)
            writer.finish()
        }
        return FFprobeClient().use { probe -> probe.inspect(output.toString()).streams.single { it.codecType == "video" }.profile.orEmpty() }
    }

    private suspend fun write(track: VideoTrack, frames: Int) {
        val name = "cfr-30-h264-128.mp4"
        val bytes = readVideoDecoderFixture(name)
        VideoDecoder.open(MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }), VideoOutput.Memory(track.config.canvasFormat), CodecPreference.SOFTWARE)
            .use { decoder -> decoder.frames(until = 1.seconds * frames / 30).collect { track.write(it) } }
    }

    private suspend fun encodes(config: VideoEncoderConfig): Boolean =
        MediaWriter.canEncode(config).also { if (!it) println("Skipped: no encoder on this host takes $config") }

    private companion object {
        const val VIDEOTOOLBOX_H264 = "h264_videotoolbox"
    }
}
