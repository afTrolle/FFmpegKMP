// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import android.os.Build
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * MediaCodec and libaom encoding on a device, read back with the decoder. The clips are 128x128:
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
            VideoDecoder.open(MediaSource(output.toString()), decoder = DecoderPreference.SOFTWARE).use { decoder ->
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
            VideoDecoder.open(MediaSource(output.toString()), VideoOutput.Memory(FrameFormat.RgbaF16), DecoderPreference.SOFTWARE).use { decoder ->
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
        val config = VideoEncoderConfig(128, 128, FrameRate(30), VideoCodec.AV1, encoder = EncoderPreference.SOFTWARE)
        assertTrue(MediaWriter.canEncode(config), "The Android standard build has libaom")
        val result = transcode(directory / "av1.mp4", config, until = 0.5.seconds)
        assertEquals(15, result.videoFrames)
        assertTrue(assertNotNull(result.bytes) > 100)
    }

    private suspend fun transcode(output: okio.Path, config: VideoEncoderConfig, until: Duration = 1.seconds): WriterResult {
        val name = "cfr-30-h264-128.mp4"
        val bytes = checkNotNull(javaClass.getResourceAsStream("/video-decoder/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
        return MediaWriter.open(MediaOutput.File(output.toString()), timeout = TIMEOUT).use { writer ->
            val track = writer.addVideoTrack(config)
            VideoDecoder.open(
                MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }),
                VideoOutput.Memory(track.canvasFormat),
                DecoderPreference.SOFTWARE,
            ).use { decoder -> decoder.frames(until = until).collect { track.write(it) } }
            writer.finish()
        }
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
    }
}
