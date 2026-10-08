// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import io.github.aftrolle.ffmpegkmp.ffprobe.FFprobeClient
import io.github.aftrolle.ffmpegkmp.ffprobe.ProbeSideData
import io.github.aftrolle.ffmpegkmp.ffprobe.ProbeStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.use

/**
 * Decode → encode → decode and probe round trips against the real bridge, on the JVM and
 * Kotlin/Native. The default builds encode H.264 and HEVC only in hardware (VideoToolbox on
 * macOS), so a host without the encoder a test needs skips it, saying so.
 */
class MediaWriterSystemTest {
    private val fileSystem = FileSystem.SYSTEM
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "media-writer-${Random.nextLong().toULong()}"

    @BeforeTest
    fun createDirectory() {
        fileSystem.createDirectories(directory)
    }

    @AfterTest
    fun cleanUp() {
        fileSystem.deleteRecursively(directory)
    }

    @Test
    fun anSdrRoundTripKeepsTheSizeFramesTimestampsAndColour() = runBlocking {
        val config = VideoEncoderConfig(128, 128, FrameRate(30))
        if (!encodes(config)) return@runBlocking
        val output = directory / "sdr.mp4"
        val result = transcode("cfr-30-h264-128.mp4", output, config)
        assertEquals(150, result.videoFrames)
        assertTrue(assertNotNull(result.bytes) > 1_000, "${result.bytes} bytes")
        assertClose(5.seconds, result.duration, 40.milliseconds, "duration")

        VideoDecoder.open(MediaSource(output.toString())).use { decoder ->
            assertEquals(128, decoder.info.width)
            assertEquals(128, decoder.info.height)
            assertEquals(8, decoder.info.bitDepth)
            assertEquals(HdrType.SDR, decoder.info.hdrType)
            val frames = decoder.frames().map { frame -> frame.use { it.number() to it.pts } }.toList()
            assertEquals((0 until 150).toList(), frames.map { it.first })
            frames.forEach { (number, pts) -> assertClose(number.seconds / 30, pts, 1.milliseconds, "pts of frame $number") }
        }
        val stream = probeVideo(output)
        assertEquals("bt709", stream.colorPrimaries)
        assertEquals("bt709", stream.colorTransfer)
        assertEquals("tv", stream.colorRange)
    }

    @Test
    fun aTenBitSdrRoundTripKeepsBt709AtTenBits() = runBlocking {
        val config = VideoEncoderConfig(128, 128, FrameRate(30), VideoCodec.HEVC, DynamicRange.SDR, bitDepth = 10)
        if (!encodes(config)) return@runBlocking
        val output = directory / "sdr-10.mp4"
        val result = MediaWriter.open(MediaOutput.File(output.toString())).use { writer ->
            val track = writer.addVideoTrack(config)
            assertEquals(FrameFormat(PixelLayout.RGBA_1010102, FrameColor.Srgb), track.canvasFormat)
            assertTrue(track.inputFormat.layout in setOf(PixelLayout.P010, PixelLayout.YUV420P10), "${track.inputFormat}")
            assertEquals(FrameColor.Bt709, track.inputFormat.color)
            decoder("cfr-30-h264-128.mp4", VideoOutput.Memory(track.canvasFormat)).use { decoder ->
                decoder.frames(until = 1.seconds).collect { track.write(it) }
            }
            writer.finish()
        }
        assertEquals(30, result.videoFrames)
        VideoDecoder.open(MediaSource(output.toString())).use { decoder ->
            assertEquals(10, decoder.info.bitDepth)
            assertEquals(HdrType.SDR, decoder.info.hdrType)
            assertEquals((0 until 30).toList(), decoder.frames().map { frame -> frame.use { it.number() } }.toList())
        }
        val stream = probeVideo(output)
        assertEquals("bt709", stream.colorPrimaries)
        assertEquals("bt709", stream.colorTransfer)
        assertEquals("tv", stream.colorRange)
        assertEquals("Main 10", stream.profile)
        assertTrue(stream.pixelFormat.orEmpty().contains("10"), "${stream.pixelFormat}")
        assertTrue(stream.sideData.none { it.type == "Mastering display metadata" }, "SDR carries no mastering display")
    }

    @Test
    fun anHdr10RoundTripKeepsTenBitPqHighlightsAndTheMasteringDisplay() = runBlocking {
        val metadata = HdrMetadata(MasteringDisplay.DisplayP3At1000Nits, ContentLightMetadata(1000, 400))
        val config = VideoEncoderConfig(96, 64, FrameRate(10), VideoCodec.HEVC, DynamicRange.HDR10, hdrMetadata = metadata)
        if (!encodes(config)) return@runBlocking
        val output = directory / "hdr10.mp4"
        assertEquals(10, transcode("hdr10-pq.mp4", output, config).videoFrames)

        VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.RgbaF16)).use { decoder ->
            assertEquals(HdrType.HDR10, decoder.info.hdrType)
            assertEquals(10, decoder.info.bitDepth)
            assertEquals("1000.0", decoder.info.masteringDisplay?.raw?.get("maxLuminance"))
            assertEquals(1000, decoder.info.contentLight?.maxContentLightLevel)
            decoder.frameAt(0.5.seconds).use { frame ->
                // As decoded from the source: 100 nits on the left, 1000 on the right, 1.0 at 203 nits.
                assertEquals(100.0 / 203.0, frame.linear(x = 12, y = 32), 0.03)
                assertEquals(1000.0 / 203.0, frame.linear(x = 84, y = 32), 0.25)
            }
        }
        val stream = probeVideo(output)
        assertEquals("smpte2084", stream.colorTransfer)
        assertEquals("bt2020", stream.colorPrimaries)
        assertEquals("hvc1", stream.codecTag)
        assertTrue(stream.pixelFormat.orEmpty().contains("10"), "${stream.pixelFormat}")
        val sideData = stream.sideData.map { it.type }
        assertContains(sideData, "Mastering display metadata")
        assertContains(sideData, "Content light level metadata")
    }

    @Test
    fun aCompositeOfTwoHdr10SourcesTakesTheCombinedMetadata() = runBlocking {
        val config = VideoEncoderConfig(96, 64, FrameRate(10), VideoCodec.HEVC, DynamicRange.HDR10)
        if (!encodes(config)) return@runBlocking
        // A second source mastered on a brighter P3 display, with brighter highlights and a darker average.
        val inset = directory / "inset.mp4"
        val insetMetadata = HdrMetadata(
            MasteringDisplay.DisplayP3At1000Nits.copy(maxLuminance = 4000.0),
            ContentLightMetadata(2000, 300),
        )
        transcode("hdr10-pq.mp4", inset, config.copy(hdrMetadata = insetMetadata))

        val combined = HdrMetadata(hdr10PqFixtureMetadata.masteringDisplay, ContentLightMetadata(2000, 400))
        val output = directory / "composite.mp4"
        MediaWriter.open(MediaOutput.File(output.toString())).use { writer ->
            decoder("hdr10-pq.mp4", VideoOutput.Memory(config.canvasFormat)).use { main ->
                val other = VideoDecoder.open(MediaSource(inset.toString())).use { it.info }
                assertEquals(insetMetadata, other.hdrMetadata)
                val metadata = HdrMetadata.combine(main.info.hdrMetadata, other.hdrMetadata)
                assertEquals(combined, metadata)
                val track = writer.addVideoTrack(config.copy(hdrMetadata = metadata))
                main.frames().collect { track.write(it) }
            }
            writer.finish()
        }

        VideoDecoder.open(MediaSource(output.toString())).use { assertEquals(combined, it.info.hdrMetadata) }
        val sideData = probeVideo(output).sideData
        val mastering = sideData.filterIsInstance<ProbeSideData.MasteringDisplayMetadata>().single().raw
        assertEquals("35400/50000", mastering["red_x"]?.jsonPrimitive?.content)
        assertEquals("10000000/10000", mastering["max_luminance"]?.jsonPrimitive?.content)
        val light = sideData.filterIsInstance<ProbeSideData.ContentLightLevel>().single()
        assertEquals(2000, light.maxContent)
        assertEquals(400, light.maxAverage)
    }

    @Test
    fun anHdr10ExportWithoutMetadataTakesTheFirstFramesAndAnSdrSourceSitsAt203Nits() = runBlocking {
        val config = VideoEncoderConfig(96, 64, FrameRate(10), VideoCodec.HEVC, DynamicRange.HDR10)
        if (!encodes(config)) return@runBlocking
        val fromHdr = directory / "from-hdr.mp4"
        transcode("hdr10-pq.mp4", fromHdr, config)
        assertContains(probeVideo(fromHdr).sideData.map { it.type }, "Mastering display metadata")

        // SDR white is 1.0 in the canvas, and lands at 203 nits, where HDR video puts it.
        val fromSdr = directory / "from-sdr.mp4"
        transcode("cfr-30.mp4", fromSdr, config.copy(frameRate = FrameRate(30)), until = 1.seconds)
        VideoDecoder.open(MediaSource(fromSdr.toString()), VideoOutput.Memory(FrameFormat.RgbaF16)).use { decoder ->
            assertEquals(HdrType.HDR10, decoder.info.hdrType)
            decoder.frameAt(Duration.ZERO).use { frame ->
                // Frame 0 has no bits set: its code cells are black, and the digits below are white.
                val white = (0 until frame.width).maxOf { x -> frame.linear(x, y = 48, channel = 1) }
                assertEquals(1.0, white, 0.08)
            }
        }
    }

    @Test
    fun anHlgRoundTripKeepsTheHlgTransfer() = runBlocking {
        val config = VideoEncoderConfig(96, 64, FrameRate(10), VideoCodec.HEVC, DynamicRange.HLG)
        if (!encodes(config)) return@runBlocking
        val output = directory / "hlg.mp4"
        transcode("hlg.mp4", output, config)
        val stream = probeVideo(output)
        assertEquals("arib-std-b67", stream.colorTransfer)
        assertEquals("bt2020", stream.colorPrimaries)
        VideoDecoder.open(MediaSource(output.toString())).use { decoder ->
            // VideoToolbox adds Dolby Vision 8.4 metadata to HLG, which players without Dolby Vision ignore.
            assertContains(setOf(HdrType.HLG, HdrType.DOLBY_VISION), decoder.info.hdrType)
            assertEquals(10, decoder.info.bitDepth)
        }
    }

    @Test
    fun anHdr10SourceInAnSdrExportIsToneMapped() = runBlocking {
        val config = VideoEncoderConfig(96, 64, FrameRate(10))
        if (!encodes(config)) return@runBlocking
        val output = directory / "tone-mapped.mp4"
        transcode("hdr10-pq.mp4", output, config)
        val expected = decoder("hdr10-pq.mp4", VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            decoder.frameAt(Duration.ZERO).use { it.luma(x = 84, y = 32) }
        }
        VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            assertEquals(HdrType.SDR, decoder.info.hdrType)
            decoder.frameAt(Duration.ZERO).use { frame -> assertTrue(abs(frame.luma(x = 84, y = 32) - expected) <= 8) }
        }
    }

    @Test
    fun canEncodeAnswersBeforeOpeningAndAddVideoTrackFailsWithTheReason() = runBlocking<Unit> {
        // The default builds are LGPL: no libx264, so no software H.264.
        val software = VideoEncoderConfig(128, 128, FrameRate(30), encoder = EncoderPreference.SOFTWARE)
        assertFalse(MediaWriter.canEncode(software))
        MediaWriter.open(MediaOutput.File((directory / "none.mp4").toString())).use { writer ->
            val failure = assertFailsWith<MediaWritingException> { writer.addVideoTrack(software) }
            assertContains(failure.message.orEmpty(), "no encoder")
        }
        assertFailsWith<IllegalArgumentException> {
            VideoEncoderConfig(128, 128, FrameRate(30), VideoCodec.H264, DynamicRange.HDR10)
        }
    }

    @Test
    fun fragmentedMp4GoesToAStreamAndMatroskaAndMpegTsRoundTrip() = runBlocking {
        val config = VideoEncoderConfig(128, 128, FrameRate(30))
        if (!encodes(config)) return@runBlocking
        val sink = Buffer()
        MediaWriter.open(MediaOutput.Stream(sink), ContainerFormat.Mp4(fragmented = true)).use { writer ->
            val track = writer.addVideoTrack(config)
            decoder("cfr-30-h264-128.mp4", VideoOutput.Memory(track.inputFormat)).use { decoder ->
                decoder.frames(until = 1.seconds).collect { track.write(it) }
            }
            assertEquals(null, writer.finish().bytes)
        }
        val bytes = sink.readByteArray()
        VideoDecoder.open(MediaSource("stream.mp4", CommandIo { input("stream.mp4", Buffer().write(bytes)) })).use { decoder ->
            assertEquals((0 until 30).toList(), decoder.frames().map { frame -> frame.use { it.number() } }.toList())
        }
        assertFailsWith<IllegalArgumentException> { MediaWriter.open(MediaOutput.Stream(Buffer()), ContainerFormat.Mp4()) }

        for ((container, name) in listOf(ContainerFormat.Matroska to "out.mkv", ContainerFormat.MpegTs to "out.ts")) {
            val output = directory / name
            assertEquals(30, transcode("cfr-30-h264-128.mp4", output, config, until = 1.seconds, container = container).videoFrames)
            VideoDecoder.open(MediaSource(output.toString())).use { decoder ->
                assertEquals(30, decoder.frames().map { frame -> frame.use { it.number() } }.toList().size, name)
            }
        }
    }

    @Test
    fun aHandleOutputGetsItsIndexFirst() = runBlocking {
        val config = VideoEncoderConfig(128, 128, FrameRate(30))
        if (!encodes(config)) return@runBlocking
        val output = directory / "handle.mp4"
        fileSystem.openReadWrite(output).use { handle ->
            MediaWriter.open(MediaOutput.Handle(handle)).use { writer ->
                val track = writer.addVideoTrack(config)
                decoder("cfr-30-h264-128.mp4", VideoOutput.Memory(track.canvasFormat)).use { decoder ->
                    decoder.frames(until = 1.seconds).collect { track.write(it) }
                }
                writer.finish()
            }
        }
        val bytes = fileSystem.read(output) { readByteArray() }
        val moov = bytes.indexOf("moov")
        val mdat = bytes.indexOf("mdat")
        assertTrue(moov in 0 until mdat, "moov at $moov, mdat at $mdat")
        VideoDecoder.open(MediaSource(output.toString())).use { decoder ->
            assertEquals(30, decoder.frames().map { frame -> frame.use { it.number() } }.toList().size)
        }
    }

    @Test
    fun anAudioTrackIsEncodedAlongsideTheVideo() = runBlocking {
        val config = VideoEncoderConfig(128, 128, FrameRate(30))
        if (!encodes(config)) return@runBlocking
        val output = directory / "audio.mp4"
        MediaWriter.open(MediaOutput.File(output.toString())).use { writer ->
            val video = writer.addVideoTrack(config)
            val audio = writer.addAudioTrack(AudioEncoderConfig(sampleRate = 48_000, channels = 2))
            decoder("cfr-30-h264-128.mp4", VideoOutput.Memory(video.inputFormat)).use { decoder ->
                decoder.frames(until = 1.seconds).collect { video.write(it) }
            }
            val chunk = FloatArray(1_000 * 2)
            for (start in 0 until 48_000 step 1_000) {
                for (frame in 0 until 1_000) {
                    val sample = (0.25 * sin(2 * PI * 440 * (start + frame) / 48_000.0)).toFloat()
                    chunk[frame * 2] = sample
                    chunk[frame * 2 + 1] = sample
                }
                audio.write(chunk)
            }
            val result = writer.finish()
            assertEquals(48_000, result.audioFrames)
            assertEquals(30, result.videoFrames)
        }
        val streams = probe(output)
        val audio = streams.single { it.codecType == "audio" }
        assertEquals("aac", audio.codecName)
        assertEquals(48_000, audio.sampleRate)
        assertEquals(2, audio.channels)
        assertEquals(1, streams.count { it.codecType == "video" })
    }

    @Test
    fun twoWritersRunAlongsideACommand() = runBlocking {
        val config = VideoEncoderConfig(128, 128, FrameRate(30))
        if (!encodes(config)) return@runBlocking
        val source = directory / "source.mp4"
        fileSystem.write(source) { write(readVideoDecoderFixture("cfr-30.mp4")) }
        val started = TimeSource.Monotonic.markNow()
        val results = listOf(
            async(Dispatchers.Default) { transcode("cfr-30-h264-128.mp4", directory / "a.mp4", config).videoFrames },
            async(Dispatchers.Default) { transcode("cfr-30-h264-128.mp4", directory / "b.mp4", config).videoFrames },
            async(Dispatchers.Default) {
                FFmpegClient().use { client ->
                    val result = client.execute(listOf("-y", "-i", source.toString(), "-c:v", "mpeg4", (directory / "c.mp4").toString()))
                    assertTrue(result.isSuccess, result.errorOutput)
                }
                150L
            },
        ).awaitAll()
        assertEquals(listOf(150L, 150L, 150L), results)
        assertTrue(started.elapsedNow() < 60.seconds)
    }

    @Test
    fun cancellingMidWriteClosesPromptly() = runBlocking {
        val config = VideoEncoderConfig(128, 128, FrameRate(30))
        if (!encodes(config)) return@runBlocking
        val output = directory / "cancelled.mp4"
        val writer = MediaWriter.open(MediaOutput.File(output.toString()))
        val track = writer.addVideoTrack(config)
        val decoder = decoder("cfr-30-h264-128.mp4", VideoOutput.Memory(track.canvasFormat))
        val writing = launch(Dispatchers.Default) {
            decoder.frames().collect { frame ->
                track.write(frame)
                delay(5.milliseconds)
            }
        }
        delay(100.milliseconds)
        writing.cancelAndJoin()
        val closing = TimeSource.Monotonic.markNow()
        writer.close()
        decoder.close()
        assertTrue(closing.elapsedNow() < 2.seconds, "close took ${closing.elapsedNow()}")
        assertFailsWith<IllegalStateException> { track.write(decoder("cfr-30.mp4").use { it.frameAt(Duration.ZERO) }) }
        // The path is free for the next export.
        assertEquals(30, transcode("cfr-30-h264-128.mp4", output, config, until = 1.seconds).videoFrames)
    }

    /** Decodes [name] into the track's canvas format and writes every frame before [until] to [output]. */
    private suspend fun transcode(
        name: String,
        output: Path,
        config: VideoEncoderConfig,
        until: Duration = Duration.INFINITE,
        container: ContainerFormat = ContainerFormat.Mp4(),
    ): WriterResult = MediaWriter.open(MediaOutput.File(output.toString()), container).use { writer ->
        val track = writer.addVideoTrack(config)
        decoder(name, VideoOutput.Memory(track.canvasFormat)).use { decoder ->
            decoder.frames(until = until).collect { track.write(it) }
        }
        writer.finish()
    }

    private suspend fun decoder(name: String, output: VideoOutput = VideoOutput.Memory()): VideoDecoder {
        val bytes = readVideoDecoderFixture(name)
        return VideoDecoder.open(MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }), output, DecoderPreference.SOFTWARE)
    }

    private suspend fun encodes(config: VideoEncoderConfig): Boolean =
        MediaWriter.canEncode(config).also { if (!it) println("Skipped: no encoder on this host takes $config") }

    private suspend fun probe(output: Path): List<ProbeStream> = FFprobeClient().use { it.inspect(output.toString()).streams }

    private suspend fun probeVideo(output: Path): ProbeStream = probe(output).single { it.codecType == "video" }

    private fun ByteArray.indexOf(box: String): Int {
        val tag = box.encodeToByteArray()
        return (0..size - tag.size).firstOrNull { start -> tag.indices.all { this[start + it] == tag[it] } } ?: -1
    }

    private companion object {
        fun assertClose(expected: Duration, actual: Duration, tolerance: Duration, message: String) {
            assertTrue(abs((expected - actual).inWholeNanoseconds) <= tolerance.inWholeNanoseconds, "$message: expected $expected, was $actual")
        }
    }
}
