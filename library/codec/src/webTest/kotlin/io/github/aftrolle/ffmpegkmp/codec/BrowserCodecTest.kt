// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okio.Buffer
import okio.FileHandle

/**
 * The browser's decoder and writer against the real worker: WebCodecs decodes and encodes, and
 * FFmpeg demuxes, muxes and encodes the audio. Each test returns early in a browser without
 * WebCodecs.
 */
class BrowserCodecTest {
    @Test
    fun eachPositionGivesTheFrameShownThere() = browserTest {
        if (!webCodecs()) return@browserTest
        open("cfr-30-h264.mp4").use { decoder ->
            assertEquals(96, decoder.info.width)
            assertEquals(64, decoder.info.height)
            assertEquals(DecoderKind.UNKNOWN, decoder.decoderKind)
            assertEquals(5.seconds, decoder.duration)
            val rate = FrameRate(30)
            for (index in listOf(0, 1, 2, 11, 12, 13, 59, 60, 149)) {
                decoder.frameAt(rate.timeOf(index.toLong())).use { frame ->
                    assertEquals(FrameFormat.Rgba8, frame.format)
                    assertEquals(index, frame.number(), "the frame at ${rate.timeOf(index.toLong())}")
                    // The stream's own times, as the native decoder gives them: 1/15360 ticks rounded to nanoseconds.
                    assertEquals(rate.timeOf(index.toLong()), frame.pts)
                    assertTrue(abs((frame.duration - rate.timeOf(1)).inWholeNanoseconds) <= 1, "duration ${frame.duration}")
                }
            }
            // Just before a frame's start is still the frame before it.
            decoder.frameAt(rate.timeOf(40) - 1.milliseconds).use { assertEquals(39, it.number()) }
            // Behind the current frame seeks back to its keyframe and decodes up to it.
            decoder.frameAt(1.seconds).use { assertEquals(30, it.number()) }
            // Past the end the last frame is held.
            decoder.frameAt(10.seconds).use { assertEquals(149, it.number()) }
        }
    }

    @Test
    fun aFrameTheCurrentOneCoversIsTheSameMemoryWithoutDecoding() = browserTest {
        if (!webCodecs()) return@browserTest
        open("cfr-30-h264.mp4").use { decoder ->
            val first = decoder.frameAt(10.milliseconds)
            val second = decoder.frameAt(20.milliseconds)
            assertEquals(0, second.number())
            assertContentEquals(first.pixels(), second.pixels())
            first.close()
            // The frames stay valid past the decoder's next call.
            decoder.frameAt(2.seconds).close()
            assertEquals(0, second.number())
            second.close()
        }
    }

    @Test
    fun framesAsDecodedKeepTheirYuvLayout() = browserTest {
        if (!webCodecs()) return@browserTest
        open("cfr-30-h264.mp4", VideoOutput.Memory()).use { decoder ->
            decoder.frameAt(FrameRate(30).timeOf(7)).use { frame ->
                val format = assertNotNull(frame.format, "WebCodecs decodes H.264 into a layout the model has")
                assertTrue(format.layout == PixelLayout.YUV420P || format.layout == PixelLayout.NV12, "$format")
                assertEquals(ColorMatrix.BT709, format.color.matrix)
                assertEquals(7, frame.number())
            }
        }
    }

    @Test
    fun theFramesFlowDeliversEachFrameOnceInOrder() = browserTest {
        if (!webCodecs()) return@browserTest
        open("cfr-24-h264.mp4").use { decoder ->
            val frames = decoder.frames(until = 2.seconds).toList()
            assertEquals((0 until 48).toList(), frames.map { it.number() })
            assertEquals((0 until 48).map { FrameRate(24).timeOf(it.toLong()) }, frames.map { it.pts })
            frames.forEach(VideoFrame::close)
        }
    }

    @Test
    fun aVariableFrameRateKeepsEachFramesOwnTimes() = browserTest {
        if (!webCodecs()) return@browserTest
        open("vfr-h264.mp4").use { decoder ->
            val frames = decoder.frames().toList()
            assertEquals(28, frames.size)
            frames.zipWithNext().forEach { (frame, next) -> assertEquals(next.pts, frame.pts + frame.duration) }
            // Each frame's start gives it back.
            for (frame in frames.filterIndexed { index, _ -> index % 5 == 0 }) {
                decoder.frameAt(frame.pts).use { assertEquals(frame.number(), it.number()) }
            }
            frames.forEach(VideoFrame::close)
        }
    }

    @Test
    fun aCancelledCallLeavesTheDecoderUsable() = browserTest {
        if (!webCodecs()) return@browserTest
        open("cfr-30-h264.mp4").use { decoder ->
            val far = async { decoder.frameAt(4.5.seconds) }
            yield()
            far.cancel()
            // The next call waits for the interrupted one and seeks to its own position.
            decoder.frameAt(1.seconds).use { assertEquals(30, it.number()) }
            decoder.frameAt(1.5.seconds).use { assertEquals(45, it.number()) }
        }
    }

    @Test
    fun aSizeScalesEachFrameInTheWorker() = browserTest {
        if (!webCodecs()) return@browserTest
        for (format in listOf(FrameFormat.Rgba8, FrameFormat(PixelLayout.BGRA8, FrameColor.Srgb))) {
            open("cfr-30-h264.mp4", VideoOutput.Memory(format, FrameSize(48, 32))).use { decoder ->
                assertEquals(96, decoder.info.width, "the source's size")
                for (index in listOf(0, 13, 60)) {
                    decoder.frameAt(FrameRate(30).timeOf(index.toLong())).use { frame ->
                        assertEquals(format, frame.format)
                        assertEquals(48, frame.width)
                        assertEquals(32, frame.height)
                        assertEquals(1.0, frame.sampleAspectRatio)
                        assertEquals(index, frame.number(), "$format frame $index at 48x32")
                    }
                }
            }
        }
    }

    @Test
    fun formatsWebCodecsCannotCopyIntoAreRefused() = browserTest {
        if (!webCodecs()) return@browserTest
        assertFailsWith<VideoDecodingException> { open("cfr-30-h264.mp4", VideoOutput.Memory(FrameFormat.RgbaF16)) }
        assertFailsWith<VideoDecodingException> { open("cfr-30-h264.mp4", decoder = DecoderPreference.REQUIRE_HARDWARE) }
        assertFailsWith<VideoDecodingException> { open("cfr-30-h264.mp4", VideoOutput.GpuBuffers) }
    }

    @Test
    fun anSdrExportThroughWebCodecsDecodesBackFrameForFrame() = browserTest {
        if (!webCodecs()) return@browserTest
        val config = VideoEncoderConfig(96, 64, FrameRate(30), VideoCodec.H264, DynamicRange.SDR)
        if (!MediaWriter.canEncode(config)) {
            println("Skipped: this browser's WebCodecs has no H.264 encoder")
            return@browserTest
        }
        assertTrue(!MediaWriter.canEncode(config.copy(codec = VideoCodec.HEVC, dynamicRange = DynamicRange.HDR10)), "The browser encodes SDR only")
        val output = MemoryFileHandle()
        val result = MediaWriter.open(MediaOutput.Handle(output)).use { writer ->
            val video = writer.addVideoTrack(config)
            assertEquals(FrameFormat.Rgba8, video.inputFormat)
            assertEquals(FrameFormat.Rgba8, video.config.canvasFormat)
            val audio = writer.addAudioTrack(AudioEncoderConfig(sampleRate = 48_000, channels = 2))
            open("cfr-30-h264.mp4").use { decoder ->
                decoder.frames(until = 1.seconds).collect { frame -> video.write(frame) }
            }
            audio.write(FloatArray(48_000 * 2) { sin(2 * PI * 440 * (it / 2) / 48_000).toFloat() * 0.25f })
            writer.finish()
        }
        assertEquals(30, result.videoFrames)
        assertEquals(48_000L, result.audioFrames)
        assertTrue(abs((result.duration - 1.seconds).inWholeMilliseconds) < 50, "${result.duration}")
        val bytes = output.readBytes()
        VideoDecoder.open(MediaSource("exported.mp4", CommandIo { input("exported.mp4", Buffer().write(bytes)) }), VideoOutput.Memory(FrameFormat.Rgba8)).use { decoder ->
            assertEquals(96, decoder.info.width)
            val frames = decoder.frames().toList()
            assertEquals((0 until 30).toList(), frames.map { it.number() })
            frames.forEachIndexed { index, frame ->
                assertTrue(abs((frame.pts - FrameRate(30).timeOf(index.toLong())).inWholeNanoseconds) <= 1_000_000, "frame $index at ${frame.pts}")
            }
            frames.forEach(VideoFrame::close)
        }
    }

    @Test
    fun framesDrawnInPageMemoryEncodeAsARendererHandsThemOver() = browserTest {
        if (!webCodecs()) return@browserTest
        val config = VideoEncoderConfig(96, 64, FrameRate(30), VideoCodec.H264, DynamicRange.SDR)
        if (!MediaWriter.canEncode(config)) return@browserTest
        val output = MemoryFileHandle()
        MediaWriter.open(MediaOutput.Handle(output)).use { writer ->
            val track = writer.addVideoTrack(config)
            // What ComposeFrameRenderer does in the browser: a frame of the track's canvas format, drawn in place.
            for (index in 0 until 12) track.write(drawn(index, track.config.canvasFormat), FrameRate(30).timeOf(index.toLong()))
            assertEquals(12, writer.finish().videoFrames)
        }
        val bytes = output.readBytes()
        VideoDecoder.open(MediaSource("drawn.mp4", CommandIo { input("drawn.mp4", Buffer().write(bytes)) })).use { decoder ->
            assertEquals((0 until 12).toList(), decoder.frames().toList().map { frame -> frame.number().also { frame.close() } })
        }
    }

    @Test
    fun aPathOutputAndHdrAreRefusedWithTheReason() = browserTest {
        if (!webCodecs()) return@browserTest
        val failure = assertFailsWith<MediaWritingException> { MediaWriter.open(MediaOutput.File("/tmp/out.mp4")) }
        assertTrue("file system" in failure.message.orEmpty(), failure.message)
        MediaWriter.open(MediaOutput.Handle(MemoryFileHandle())).use { writer ->
            val hdr = assertFailsWith<MediaWritingException> {
                writer.addVideoTrack(VideoEncoderConfig(96, 64, FrameRate(30), VideoCodec.HEVC, DynamicRange.HDR10))
            }
            assertTrue("no encoder" in hdr.message.orEmpty(), hdr.message)
        }
    }

    // Real time: the decoder's timeouts must not fire while the worker works, as a virtual clock's would.
    private fun browserTest(block: suspend CoroutineScope.() -> Unit) = runTest(timeout = 90.seconds) {
        configureRuntime()
        withContext(Dispatchers.Default) { block() }
    }

    private suspend fun open(
        name: String,
        output: VideoOutput = VideoOutput.Memory(FrameFormat.Rgba8),
        decoder: DecoderPreference = DecoderPreference.AUTO,
    ): VideoDecoder {
        val bytes = fetchBytes("/base/kotlin/video-decoder/$name")
        return VideoDecoder.open(MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }), output, decoder, timeout = 20.seconds)
    }
}

/** A seekable in-memory output, which the browser writer fills when it finishes. */
private class MemoryFileHandle : FileHandle(readWrite = true) {
    private var bytes = ByteArray(0)

    fun readBytes(): ByteArray = bytes.copyOf()

    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
        if (fileOffset >= bytes.size) return -1
        val count = minOf(byteCount, bytes.size - fileOffset.toInt())
        bytes.copyInto(array, arrayOffset, fileOffset.toInt(), fileOffset.toInt() + count)
        return count
    }

    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
        val size = fileOffset.toInt() + byteCount
        if (size > bytes.size) bytes = bytes.copyOf(size)
        array.copyInto(bytes, fileOffset.toInt(), arrayOffset, arrayOffset + byteCount)
    }

    override fun protectedResize(size: Long) {
        bytes = bytes.copyOf(size.toInt())
    }

    override fun protectedSize(): Long = bytes.size.toLong()

    override fun protectedFlush() = Unit

    override fun protectedClose() = Unit
}

/** A frame drawn as the fixture generator draws [index]: grey, with a white 16x16 cell for each set bit. */
@OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class, io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class)
private fun drawn(index: Int, format: FrameFormat): VideoFrame {
    val native = io.github.aftrolle.ffmpegkmp.bindings.allocateNativeFrame(format.toNative(), 96, 64)
    native.usePlanes { planes ->
        val plane = planes.single()
        val bytes = plane.memory as ByteArray
        for (y in 0 until 64) for (x in 0 until 96) {
            val bit = (y / 16) * 6 + x / 16
            val value = if (y < 32 && index shr bit and 1 == 1) 255 else 64
            val offset = plane.offset + y * plane.rowBytes + x * 4
            for (channel in 0 until 3) bytes[offset + channel] = value.toByte()
            bytes[offset + 3] = -1
        }
    }
    return VideoFrame.of(native, pts = Duration.ZERO, duration = Duration.ZERO)
}

/** The 12-bit number the fixture generator draws as 16x16 cells of its 96x64 frames, from green or luma, at any size. */
private fun VideoFrame.number(): Int = assertNotNull(
    usePlanes { planes ->
        val plane = planes.first()
        val pixel = when (assertNotNull(format).layout) {
            PixelLayout.RGBA8, PixelLayout.BGRA8 -> 4
            else -> 1
        }
        (0 until 12).sumOf { bit ->
            val x = ((bit % 6) * 16 + 8) * width / 96
            val y = ((bit / 6) * 16 + 8) * height / 64
            val value = plane.bytes[y * plane.rowBytes + x * pixel + (if (pixel == 4) 1 else 0)].toInt() and 0xff
            if (value > 128) 1 shl bit else 0
        }
    },
)

private fun VideoFrame.pixels(): ByteArray = assertNotNull(
    usePlanes { planes -> ByteArray(planes.single().bytes.size).also(planes.single().bytes::copyInto) },
)

private fun webCodecs(): Boolean = hasWebCodecs().also { if (!it) println("Skipped: this browser has no WebCodecs") }

private fun hasWebCodecs(): Boolean =
    js("typeof globalThis.VideoDecoder === 'function' && typeof globalThis.VideoEncoder === 'function'")

private fun configureRuntime(): Unit = js(
    "{ globalThis.FFMPEGKMP_WORKER_URL = '/base/kotlin/ffmpegkmp-worker.mjs'; globalThis.FFMPEGKMP_MODULE_URL = '/base/kotlin/ffmpegkmp.mjs'; }",
)

private suspend fun fetchBytes(url: String): ByteArray {
    val bytes = checkNotNull(fetchUint8(url).await())
    return ByteArray(byteCount(bytes)) { byteAt(bytes, it).toByte() }
}

private fun fetchUint8(url: String): Promise<JsAny?> = js(
    "fetch(url).then(response => { if (!response.ok) throw new Error('Could not load ' + url); return response.arrayBuffer(); }).then(buffer => new Uint8Array(buffer))",
)

private fun byteCount(bytes: JsAny): Int = js("bytes.length")

private fun byteAt(bytes: JsAny, index: Int): Int = js("bytes[index]")
