// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import android.util.Log
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem

/**
 * AV1 on a device with libaom built in: AUTO finds MediaCodec's AV1 decoder by codec id, where the
 * old `"<software decoder>_mediacodec"` guess looked for "libaom-av1_mediacodec" and decoded in
 * software, and [NamedCodec] opens either one exactly. The clip is encoded here with libaom, since
 * no AV1 fixture ships; 128x128, which hardware AV1 decoders take where the device has one.
 */
class NamedDecoderDeviceTest {
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "named-decoder-${Random.nextLong().toULong()}"

    init {
        FileSystem.SYSTEM.createDirectories(directory)
    }

    @AfterTest
    fun cleanUp() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    @Test
    fun av1DecodesWithMediaCodecUnderAutoAndByName() = runBlocking {
        val clip = av1Clip()
        // libaom's decoder is software, opened by name like any of FFmpeg's.
        val expected = VideoDecoder.open(MediaSource(clip), decoder = NamedCodec("libaom-av1")).use { decoder ->
            assertEquals(DecoderKind.SOFTWARE, decoder.decoderKind)
            decoder.numbers()
        }
        assertEquals((0 until FRAMES).toList(), expected)
        val support = DecoderBudget.hardwareSupport("av1", 128, 128)
        Log.i(TAG, "AV1 at 128x128: $support")
        if (support.available != true) {
            println("Skipped: this device has no hardware AV1 decoder ($support)")
            return@runBlocking
        }
        VideoDecoder.open(MediaSource(clip)).use { decoder ->
            assertEquals(DecoderKind.HARDWARE, decoder.decoderKind, "AUTO must find av1_mediacodec by codec id")
            assertEquals(null, decoder.decoderFallback)
            assertEquals(expected, decoder.numbers())
        }
        VideoDecoder.open(MediaSource(clip), decoder = NamedCodec("av1_mediacodec")).use { decoder ->
            assertEquals(DecoderKind.HARDWARE, decoder.decoderKind)
            assertEquals(expected, decoder.numbers())
        }
    }

    /** One second of the numbered fixture, encoded to AV1 by libaom at its realtime speed. */
    private suspend fun av1Clip(): String {
        val output = directory / "av1.mp4"
        val config = VideoEncoderConfig(
            128, 128, FrameRate(30), VideoCodec.AV1,
            encoder = NamedCodec("libaom-av1", mapOf("usage" to "realtime", "cpu-used" to "8")),
        )
        MediaWriter.open(MediaOutput.File(output.toString()), timeout = 10.seconds).use { writer ->
            val track = writer.addVideoTrack(config)
            val name = "cfr-30-h264-128.mp4"
            val bytes = checkNotNull(javaClass.getResourceAsStream("/video-decoder/$name")) { "Missing fixture $name" }
                .use { it.readBytes() }
            VideoDecoder.open(
                MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }),
                VideoOutput.Memory(track.config.canvasFormat),
                CodecPreference.SOFTWARE,
            ).use { decoder -> decoder.frames(until = 1.seconds).collect { track.write(it) } }
            assertEquals(FRAMES.toLong(), writer.finish().videoFrames)
        }
        return output.toString()
    }

    private suspend fun VideoDecoder.numbers(): List<Int> = frames().map { frame -> frame.use { it.number() } }.toList()

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

    private companion object {
        const val TAG = "NamedDecoderDeviceTest"
        const val FRAMES = 30
    }
}
