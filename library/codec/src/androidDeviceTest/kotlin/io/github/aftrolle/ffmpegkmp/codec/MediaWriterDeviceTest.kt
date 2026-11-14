// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.codec

import android.os.Build
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem

/**
 * MediaCodec and libaom encoding on a device, read back with the decoder, by the library's choice
 * and by [NamedCodec]. The clips are 128x128:
 * hardware encoders have a minimum size (96x96 on Qualcomm). The emulator's MediaCodec encoders
 * take frames without ever giving packets through FFmpeg, so there the writer's timeout is the
 * other outcome that passes.
 */
class MediaWriterDeviceTest {
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "media-writer-${Random.nextLong().toULong()}"

    init {
        FileSystem.SYSTEM.createDirectories(directory)
    }

    @AfterTest
    fun cleanUp() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    @Test
    fun mediaCodecEncodesH264ThatDecodesBackFrameForFrame() = runBlocking {
        val config = VideoEncoderConfig(128, 128, FrameRate(30))
        assertTrue(MediaWriter.canEncode(config), "MediaCodec encodes H.264 on every device")
        val output = directory / "sdr.mp4"
        timesOutOnTheEmulator {
            assertEquals(30, transcode(output, config).videoFrames)
            VideoDecoder.open(MediaSource(output.toString()), decoder = CodecPreference.SOFTWARE).use { decoder ->
                assertEquals(DynamicRange.SDR, DynamicRange.of(decoder.info))
                assertEquals(128, decoder.info.width)
                assertEquals((0 until 30).toList(), decoder.frames().map { frame -> frame.use { it.number() } }.toList())
            }
        }
    }

    @Test
    fun anHdr10HevcExportKeepsTenBitsAndPutsSdrWhiteAt203Nits() = runBlocking {
        val config = VideoEncoderConfig(
            128, 128, FrameRate(30), VideoCodec.HEVC, DynamicRange.HDR10,
            hdrMetadata = HdrMetadata(MasteringDisplay.DisplayP3At1000Nits, ContentLightMetadata(1000, 400)),
        )
        if (Build.VERSION.SDK_INT < 33 || !MediaWriter.canEncode(config)) {
            println("Skipped: no HEVC Main10 HDR10 encoder on this device (API ${Build.VERSION.SDK_INT})")
            return@runBlocking
        }
        val output = directory / "hdr10.mp4"
        timesOutOnTheEmulator {
            assertEquals(30, transcode(output, config).videoFrames)
            VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.RgbaF16), CodecPreference.SOFTWARE).use { decoder ->
                assertEquals(DynamicRange.HDR10, DynamicRange.of(decoder.info))
                assertEquals(10, decoder.info.bitDepth)
                assertNotNull(decoder.info.hdrMetadata?.masteringDisplay)
                decoder.frameAt(Duration.ZERO).use { frame ->
                    val white = (0 until frame.width).maxOf { x -> frame.half(x, y = 48) }
                    assertEquals(1.0, white, 0.1)
                }
            }
        }
    }

    @Test
    fun libaomEncodesAv1InSoftware() = runBlocking {
        val config = VideoEncoderConfig(128, 128, FrameRate(30), VideoCodec.AV1, encoder = CodecPreference.SOFTWARE)
        assertTrue(MediaWriter.canEncode(config), "The Android standard build has libaom")
        val result = transcode(directory / "av1.mp4", config, until = 0.5.seconds)
        assertEquals(15, result.videoFrames)
        assertTrue(assertNotNull(result.bytes) > 100)
    }

    /**
     * Codec2's software AVC encoder gives no packet after FFmpeg's global-header pass (a dummy
     * frame to the end of stream, then a flush), so the writer opens MediaCodec without one and
     * takes the parameter sets from the first packet: this export timing out is that regression.
     */
    @Test
    fun h264PinnedToAndroidsSoftwareEncoderDecodesBackAndReportsIt() = runBlocking {
        if (Build.VERSION.SDK_INT < 29) return@runBlocking println("Skipped: Codec2's c2.android.avc.encoder is Android 10 and later")
        val config = VideoEncoderConfig(128, 128, FrameRate(30), encoder = SOFTWARE_AVC)
        assertTrue(MediaWriter.canEncode(config), "Every Android 10 device has Google's software AVC encoder")
        val output = directory / "software-avc.mp4"
        timesOutOnTheEmulator {
            var track: VideoTrack? = null
            assertEquals(30, transcode(output, config) { track = it }.videoFrames)
            assertEquals("h264_mediacodec", assertNotNull(track).encoderName)
            // Android's own answer for the pinned component, not the MediaCodec wrapper's kind.
            assertFalse(assertNotNull(track).isHardware)
            VideoDecoder.open(MediaSource(output.toString()), decoder = CodecPreference.SOFTWARE).use { decoder ->
                assertEquals((0 until 30).toList(), decoder.frames().map { frame -> frame.use { it.number() } }.toList())
            }
        }
    }

    /**
     * A measurement, not a check of the options' effect: it encodes with libaom's defaults and
     * with [REALTIME_AV1]'s options and prints both times. That the options reached libaom is
     * what the host tests show for an encoder's options; here only the track's report is asserted.
     */
    @Test
    fun measureLibaomWithAndWithoutRealtimeOptions() = runBlocking {
        val default = VideoEncoderConfig(128, 128, FrameRate(30), VideoCodec.AV1, encoder = NamedCodec("libaom-av1"))
        val realtime = default.copy(encoder = REALTIME_AV1)
        assertTrue(MediaWriter.canEncode(realtime), "The Android standard build has libaom")
        var track: VideoTrack? = null
        val defaultStarted = TimeSource.Monotonic.markNow()
        assertEquals(30, transcode(directory / "av1-default.mp4", default).videoFrames)
        val defaultTime = defaultStarted.elapsedNow()
        val realtimeStarted = TimeSource.Monotonic.markNow()
        assertEquals(30, transcode(directory / "av1-realtime.mp4", realtime) { track = it }.videoFrames)
        val realtimeTime = realtimeStarted.elapsedNow()
        println("libaom-av1, 30 frames of 128x128: $defaultTime with its defaults, $realtimeTime with usage=realtime cpu-used=8")
        assertEquals("libaom-av1", assertNotNull(track).encoderName)
        assertFalse(assertNotNull(track).isHardware)
    }

    /**
     * The export an app writes with no chain API in the library: its own series of tries, each
     * asked with canEncode first and taken if its track opens and encodes.
     */
    @Test
    fun aCallersSeriesOfEncodersTakesTheFirstThatWorks() = runBlocking {
        if (Build.VERSION.SDK_INT < 29) return@runBlocking println("Skipped: Codec2's c2.android.avc.encoder is Android 10 and later")
        val base = VideoEncoderConfig(128, 128, FrameRate(30))
        val tries = listOf(
            base.copy(codec = VideoCodec.HEVC, encoder = CodecPreference.REQUIRE_HARDWARE),
            base.copy(codec = VideoCodec.H264, encoder = CodecPreference.REQUIRE_HARDWARE),
            base.copy(codec = VideoCodec.H264, encoder = SOFTWARE_AVC),
            base.copy(codec = VideoCodec.AV1, encoder = REALTIME_AV1),
        )
        timesOutOnTheEmulator {
            // Which try a device takes depends on its encoders; that one of them works does not.
            val chosen = assertNotNull(exportWithTheFirstThatWorks(tries, directory / "series.mp4"))
            println("The series took ${chosen.codec} ${chosen.encoder}")
            // The software tries work as the series' later steps, as they do where no hardware encoder opens.
            assertEquals(tries[2], exportWithTheFirstThatWorks(tries.drop(2), directory / "series-software.mp4"))
            assertEquals(tries[3], exportWithTheFirstThatWorks(tries.drop(3), directory / "series-av1.mp4"))
        }
    }

    /**
     * The Surface-input path keeps a pinned encoder: the input surface opens that component, or
     * the track keeps FFmpeg's encoder of that name and takes frames from memory. It never opens
     * the default hardware encoder instead. Undrawn surface buffers still make frames to count.
     */
    @Test
    fun theSurfaceInputPathKeepsAPinnedEncoder() = runBlocking {
        if (Build.VERSION.SDK_INT < 34) return@runBlocking println("Skipped: the encoder's input surface is Android 14 and later")
        val config = VideoEncoderConfig(128, 128, FrameRate(30), encoder = SOFTWARE_AVC)
        val output = directory / "surface-pinned.mp4"
        timesOutOnTheEmulator {
            MediaWriter.open(MediaOutput.File(output.toString()), timeout = TIMEOUT).use { writer ->
                val track = writer.addVideoTrack(config)
                val zeroCopy = track.openInputSurface()
                if (zeroCopy) {
                    println("The surface path ran: ${track.inputSurfaceEncoderName} took the input surface")
                    // The component MediaCodec opened for the surface, by its own name.
                    assertEquals("c2.android.avc.encoder", track.inputSurfaceEncoderName)
                    for (index in 0 until 10) {
                        val buffer = track.dequeueFrameBuffer()
                        track.write(VideoFrame.of(buffer, 1.seconds * index / 30, 1.seconds / 30, 128, 128))
                    }
                } else {
                    println("The memory path ran: h264_mediacodec with codec_name=c2.android.avc.encoder takes frames from memory")
                    assertEquals(null, track.inputSurfaceEncoderName)
                    writeFixture(track, until = 1.seconds / 3)
                }
                assertEquals(10, writer.finish().videoFrames)
                assertEquals("h264_mediacodec", track.encoderName)
            }
            VideoDecoder.open(MediaSource(output.toString()), decoder = CodecPreference.SOFTWARE).use { decoder ->
                assertEquals(10, decoder.frames().map { frame -> frame.use { it.pts } }.toList().size)
            }
        }

        // An encoder MediaCodec's own API cannot open gets no surface: its frames reach libaom from memory.
        MediaWriter.open(MediaOutput.File((directory / "surface-av1.mp4").toString()), timeout = TIMEOUT).use { writer ->
            val track = writer.addVideoTrack(config.copy(codec = VideoCodec.AV1, encoder = REALTIME_AV1))
            assertFalse(track.openInputSurface())
            writeFixture(track, until = 1.seconds / 3)
            assertEquals(10, writer.finish().videoFrames)
        }

        // A component the device does not have fails the track rather than opening another.
        val missing = config.copy(encoder = NamedCodec("h264_mediacodec", mapOf("codec_name" to "c2.no.such.encoder")))
        assertFalse(MediaWriter.canEncode(missing))
        MediaWriter.open(MediaOutput.File((directory / "surface-missing.mp4").toString()), timeout = TIMEOUT).use { writer ->
            val failure = assertFailsWith<MediaWritingException> { writer.addVideoTrack(missing) }
            // The reason, after the config the message quotes with the component's name in it.
            val reason = failure.message.orEmpty().substringAfter("$missing: ")
            println("As expected: $reason")
            assertContains(reason, "h264_mediacodec")
        }
    }

    /** Writes the fixture with the first config of [tries] that this device can encode and that works; null when none does. */
    private suspend fun exportWithTheFirstThatWorks(tries: List<VideoEncoderConfig>, output: okio.Path): VideoEncoderConfig? {
        for (config in tries) {
            if (!MediaWriter.canEncode(config)) {
                println("Not on this device: ${config.codec} ${config.encoder}")
                continue
            }
            try {
                transcode(output, config)
                return config
            } catch (failure: MediaWritingException) {
                println("${config.codec} ${config.encoder} failed, trying the next: ${failure.message}")
            }
        }
        return null
    }

    private suspend fun transcode(
        output: okio.Path,
        config: VideoEncoderConfig,
        until: Duration = 1.seconds,
        added: (VideoTrack) -> Unit = {},
    ): WriterResult = MediaWriter.open(MediaOutput.File(output.toString()), timeout = TIMEOUT).use { writer ->
        val track = writer.addVideoTrack(config).also(added)
        writeFixture(track, until)
        writer.finish()
    }

    private suspend fun writeFixture(track: VideoTrack, until: Duration) {
        val name = "cfr-30-h264-128.mp4"
        val bytes = checkNotNull(javaClass.getResourceAsStream("/video-decoder/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
        VideoDecoder.open(
            MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }),
            VideoOutput.Memory(track.config.canvasFormat),
            CodecPreference.SOFTWARE,
        ).use { decoder -> decoder.frames(until = until).collect { track.write(it) } }
    }

    /** Runs [block]; on the emulator a writer that timed out, within the timeout, passes too. */
    private suspend fun timesOutOnTheEmulator(block: suspend () -> Unit) {
        if (Build.HARDWARE != "ranchu") return block()
        val started = TimeSource.Monotonic.markNow()
        try {
            block()
        } catch (failure: MediaWritingException) {
            assertTrue("timed out" in failure.message.orEmpty(), "Unexpected $failure")
            assertTrue(started.elapsedNow() < TIMEOUT * 3, "Timing out took ${started.elapsedNow()}")
            println("The emulator's encoder timed out: ${failure.message}")
        }
    }

    /** The 12-bit frame number the fixture generator draws as 16x16 cells in the top rows. */
    private fun VideoFrame.number(): Int = assertNotNull(
        usePlanes { planes ->
            val plane = planes.first()
            (0 until 12).sumOf { bit ->
                val x = (bit % 6) * 16 + 8
                val y = (bit / 6) * 16 + 8
                if (plane.bytes[y * plane.rowBytes + x].toInt() and 0xff > 128) 1 shl bit else 0
            }
        },
    )

    /** Green of an RGBA_F16 pixel. */
    private fun VideoFrame.half(x: Int, y: Int): Double = assertNotNull(
        usePlanes { planes ->
            val plane = planes.single()
            val offset = y * plane.rowBytes + (x * 4 + 1) * 2
            val bits = (plane.bytes[offset].toInt() and 0xff) or (plane.bytes[offset + 1].toInt() and 0xff shl 8)
            val exponent = bits shr 10 and 0x1f
            val magnitude = if (exponent == 0) (bits and 0x3ff) / 1024.0 / 16384.0 else (1 + (bits and 0x3ff) / 1024.0) * Math.pow(2.0, exponent - 15.0)
            if (bits and 0x8000 != 0) -magnitude else magnitude
        },
    )

    private companion object {
        val TIMEOUT = 5.seconds

        /** Google's software AVC encoder, Codec2's since Android 10, through FFmpeg's MediaCodec wrapper. */
        val SOFTWARE_AVC = NamedCodec("h264_mediacodec", mapOf("codec_name" to "c2.android.avc.encoder"))

        /** libaom at its realtime speed, which the library never sets of its own. */
        val REALTIME_AV1 = NamedCodec("libaom-av1", mapOf("usage" to "realtime", "cpu-used" to "8"))
    }
}
