// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(ExperimentalWasmJsInterop::class, io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.time.Duration.Companion.microseconds
import kotlinx.coroutines.await
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer

/**
 * Encodes video with WebCodecs and audio with FFmpeg's AAC encoder in a worker of its own, where
 * FFmpeg's muxer writes the output into memory; [NativeMediaWriter.finish] then writes it to the
 * mounted resource. The browser encodes SDR only, from RGBA8, BGRA8, NV12 and 4:2:0 frames, each
 * copied once into a buffer the worker takes over.
 */
@InternalFFmpegKmpApi
public actual suspend fun createPlatformMediaWriter(
    output: NativeWriterOutput,
    container: NativeContainer,
    fastStart: Boolean,
    timeoutMicros: Long,
): NativeMediaWriter {
    val mounted = output as? NativeWriterOutput.Mounted ?: throw NativeBridgeUnavailableException(
        "The browser has no file system to write '${(output as NativeWriterOutput.Path).path}' to: write to a stream or a handle",
    )
    val worker = BrowserWorkerCalls.start()
    val writer = BrowserMediaWriter(worker, mounted, container, timeoutMicros)
    try {
        writer.call(track = null) { worker.request(openMessage(output.url(), container.ordinal, fastStart, timeoutMicros.toDouble())) }
    } catch (failure: Throwable) {
        worker.terminate()
        throw failure
    }
    return writer
}

/** The WebCodecs encoder the browser has for [config], which it asks without opening one; null when it has none. */
@InternalFFmpegKmpApi
public actual suspend fun platformVideoEncoderFor(config: NativeVideoEncoderConfig): NativeVideoTrackInfo? {
    val encoderConfig = webEncoderConfig(config, annexB = false) ?: return null
    if (!webCodecsEncodes(encoderConfig).awaitTrue()) return null
    return webTrackInfo(encoderConfig)
}

private class BrowserMediaWriter(
    private val worker: BrowserWorkerCalls,
    private val output: NativeWriterOutput.Mounted,
    private val container: NativeContainer,
    private val timeoutMicros: Long,
) : NativeMediaWriter {
    private val timedOut = mutableSetOf<Int>()
    private val audioChannels = mutableMapOf<Int, Int>()
    private var aborted = false
    private var closed = false

    override suspend fun addVideoTrack(config: NativeVideoEncoderConfig): NativeAddedVideoTrack {
        val encoderConfig = webEncoderConfig(config, annexB = container == NativeContainer.MPEGTS)
            ?: throw NativeMediaWriterException(unsupported(config), NativePlayerError.UNSUPPORTED)
        val reply = call(track = null) {
            worker.request(
                addVideoMessage(
                    config.width, config.height, config.frameRateNumerator, config.frameRateDenominator,
                    config.codec.ordinal, config.dynamicRange.ordinal, config.preference.ordinal,
                    encoderBitRate(config).toDouble(), config.keyframeIntervalMicros.toDouble(), config.bitDepth, encoderConfig,
                ),
            )
        }
        return NativeAddedVideoTrack(replyIndex(reply), webTrackInfo(encoderConfig))
    }

    override suspend fun addAudioTrack(sampleRate: Int, channels: Int, bitRate: Long): Int {
        val index = replyIndex(call(track = null) { worker.request(addAudioMessage(sampleRate, channels, bitRate.toDouble())) })
        audioChannels[index] = channels
        return index
    }

    override suspend fun writeVideo(track: Int, frame: NativeFrame, ptsNanos: Long) {
        val format = frame.format
        val name = format?.let(::webFrameFormat) ?: throw NativeMediaWriterException(
            "The browser encodes RGBA8, BGRA8, NV12 and 4:2:0 frames, not $format",
            NativePlayerError.INVALID_ARGUMENT,
        )
        // A copy of the planes, two on Kotlin/Wasm, which the worker takes over: they lie in one array in the browser.
        val (buffer, planes) = frame.usePlanes { planes ->
            val memory = planes.first().memory as ByteArray
            require(planes.all { it.memory === memory }) { "A browser frame's planes lie in one array" }
            val start = planes.minOf { it.offset }
            val end = planes.maxOf { it.offset + it.size }
            copyToJsUint8Array(memory, start, end - start) to planes.map { (it.offset - start) to it.rowBytes }
        }
        val plane = { index: Int -> planes.getOrNull(index) ?: (0 to 0) }
        call(track) {
            worker.request(
                videoMessage(
                    track, ptsNanos.toDouble(), name, frame.width, frame.height, planes.size,
                    plane(0).first, plane(0).second, plane(1).first, plane(1).second, plane(2).first, plane(2).second,
                    format.primaries, format.transfer, format.matrix, format.range, buffer,
                ),
                transferOf(buffer),
            )
        }
    }

    override suspend fun writeAudio(track: Int, samples: FloatArray, offset: Int, frames: Int) {
        require(offset >= 0 && frames >= 0) { "Offset and frame count must not be negative" }
        val channels = audioChannels[track] ?: throw NativeMediaWriterException("Track $track is not an audio track", NativePlayerError.INVALID_ARGUMENT)
        require(offset + frames * channels <= samples.size) { "$frames frames of $channels channels do not fit from $offset" }
        val copy = copyToJsFloat32Array(samples, offset, frames * channels)
        call(track) { worker.request(audioMessage(track, copy, frames), transferOf(copy)) }
    }

    override suspend fun endTrack(track: Int) {
        call(track) { worker.request(trackMessage("writer-end", track)) }
    }

    override fun releaseTrack(track: Int) {
        if (!closed) worker.send(trackMessage("writer-release", track))
    }

    override suspend fun finish(): NativeWriterResult {
        val reply = call(track = null) { worker.request(finishMessage()) }
        val bytes = jsBufferToByteArray(finishedOutput(reply))
        writeOut(bytes)
        return NativeWriterResult(bytes = bytes.size.toLong(), durationMicros = replyDouble(reply, "durationUs").toLong())
    }

    override fun abort() {
        aborted = true
        if (!closed) worker.send(abortMessage())
    }

    override fun close() {
        if (closed) return
        closed = true
        worker.terminate()
    }

    /**
     * Runs [block] within the writer's timeout. A track that runs past it fails, as the native
     * writer's does: its calls from then on fail too.
     */
    suspend fun call(track: Int?, block: suspend () -> JsAny): JsAny {
        check(!closed) { "The media writer is closed" }
        if (aborted) throw NativeMediaWriterException("The writer was aborted", AVERROR_EXIT)
        if (track != null && track in timedOut) throw NativeMediaWriterException("Track $track timed out", NativePlayerError.TIMED_OUT)
        val bound = timeoutMicros.takeIf { it > 0 }?.microseconds
        try {
            if (bound == null) return block()
            return withTimeoutOrNull(bound) { block() } ?: run {
                track?.let(timedOut::add)
                throw NativeMediaWriterException("WebCodecs did not encode within $bound", NativePlayerError.TIMED_OUT)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NativeMediaWriterException) {
            throw failure
        } catch (failure: Throwable) {
            throw NativeMediaWriterException(failure.message ?: "The worker failed", if (aborted) AVERROR_EXIT else failure.browserErrorCode)
        }
    }

    private fun writeOut(bytes: ByteArray) {
        when (val resource = output.resource) {
            is NativeSinkResource -> {
                val buffer = Buffer().write(bytes)
                resource.sink.write(buffer, buffer.size)
                resource.sink.flush()
            }
            is NativeFileResource -> {
                resource.fileHandle.write(0L, bytes, 0, bytes.size)
                if (resource.truncate) resource.fileHandle.resize(bytes.size.toLong())
                resource.fileHandle.flush()
            }
            is NativeSourceResource -> throw NativeMediaWriterException("The output is a source", NativePlayerError.INVALID_ARGUMENT)
        }
    }
}

/** AVERROR_EXIT: the writer was aborted. */
private const val AVERROR_EXIT = -0x54495845

private fun unsupported(config: NativeVideoEncoderConfig): String = when {
    config.dynamicRange != NativeDynamicRange.SDR || config.bitDepth == 10 -> "The browser encodes 8-bit SDR only: its frames are 8-bit RGB"
    config.preference == NativePlayerDecoderPreference.REQUIRE_HARDWARE -> "WebCodecs cannot confirm a hardware encoder"
    else -> "WebCodecs has no ${config.codec} encoder for ${config.width}x${config.height}"
}

/** The track's info: WebCodecs takes RGBA8 as it is, and names no encoder of its own. */
private fun webTrackInfo(encoderConfig: JsAny): NativeVideoTrackInfo =
    NativeVideoTrackInfo(BROWSER_RGBA8, hardware = false, encoder = "webcodecs ${encoderCodec(encoderConfig)}")

/** A bit rate that suits the size and rate, as ffmpegkmp_writer.c picks one. */
private fun encoderBitRate(config: NativeVideoEncoderConfig): Long {
    if (config.bitRate > 0) return config.bitRate
    val fps = if (config.frameRateNumerator > 0) config.frameRateNumerator.toDouble() / config.frameRateDenominator else 30.0
    val bitsPerPixel = if (config.codec == NativeVideoCodec.H264) 0.1 else 0.06
    return maxOf(200_000L, (config.width.toDouble() * config.height * fps * bitsPerPixel).toLong())
}

/** The WebCodecs `VideoEncoderConfig` for [config], or null for one the browser never encodes: HDR, or a required hardware encoder. */
private fun webEncoderConfig(config: NativeVideoEncoderConfig, annexB: Boolean): JsAny? {
    if (config.dynamicRange != NativeDynamicRange.SDR || config.bitDepth == 10) return null
    if (config.preference == NativePlayerDecoderPreference.REQUIRE_HARDWARE) return null
    if (config.width <= 0 || config.height <= 0 || config.width % 2 != 0 || config.height % 2 != 0) return null
    return encoderConfig(
        config.codec.ordinal,
        config.width,
        config.height,
        if (config.frameRateNumerator > 0) config.frameRateNumerator.toDouble() / config.frameRateDenominator else 0.0,
        encoderBitRate(config).toDouble(),
        if (config.preference == NativePlayerDecoderPreference.SOFTWARE) "prefer-software" else "no-preference",
        annexB,
    )
}

/** The VideoFrame format of a frame the browser can encode, or null. */
private fun webFrameFormat(format: NativeFrameFormat): String? = when (format.layout) {
    0 -> "RGBA"
    1 -> "BGRA"
    4 -> "NV12"
    6 -> "I420"
    7 -> "I420P10"
    else -> null
}

// The level each codec needs for the size and rate: H.264 by macroblocks, HEVC and AV1 by samples.
private fun encoderConfig(
    codec: Int,
    width: Int,
    height: Int,
    fps: Double,
    bitrate: Double,
    hardwareAcceleration: String,
    annexB: Boolean,
): JsAny = js(
    """
    (() => {
      const rate = fps > 0 ? fps : 30;
      const pick = (levels, size) =>
        (levels.find(([maxSize, maxRate]) => size <= maxSize && size * rate <= maxRate) || levels[levels.length - 1])[2];
      let name;
      if (codec === 0) {
        const macroblocks = Math.ceil(width / 16) * Math.ceil(height / 16);
        const level = pick([
          [1620, 40500, 30], [3600, 108000, 31], [5120, 216000, 32], [8192, 245760, 40], [8704, 522240, 42],
          [22080, 589824, 50], [36864, 983040, 51], [36864, 2073600, 52], [139264, 4177920, 60],
          [139264, 8355840, 61], [139264, 16711680, 62],
        ], macroblocks);
        name = 'avc1.6400' + level.toString(16).padStart(2, '0');
      } else if (codec === 1) {
        const level = pick([
          [983040, 33177600, 93], [2228224, 66846720, 120], [2228224, 133693440, 123], [8912896, 267386880, 150],
          [8912896, 534773760, 153], [8912896, 1069547520, 156], [35651584, 1069547520, 180],
          [35651584, 2139095040, 183], [35651584, 4278190080, 186],
        ], width * height);
        name = 'hvc1.1.6.L' + level + '.B0';
      } else {
        const level = pick([
          [147456, 4423680, 0], [278784, 8363520, 1], [665856, 19975680, 4], [1065024, 31950720, 5],
          [2359296, 70778880, 8], [2359296, 141557760, 9], [8912896, 267386880, 12], [8912896, 534773760, 13],
          [8912896, 1069547520, 14], [35651584, 1069547520, 16], [35651584, 2139095040, 17], [35651584, 4278190080, 18],
        ], width * height);
        name = 'av01.0.' + String(level).padStart(2, '0') + 'M.08';
      }
      const config = { codec: name, width, height, bitrate, hardwareAcceleration, latencyMode: 'quality' };
      if (fps > 0) config.framerate = fps;
      if (codec === 0) config.avc = { format: annexB ? 'annexb' : 'avc' };
      if (codec === 1) config.hevc = { format: annexB ? 'annexb' : 'hevc' };
      return config;
    })()
    """,
)

private fun encoderCodec(config: JsAny): String = js("config.codec")

private fun webCodecsEncodes(config: JsAny): Promise<JsAny?> = js(
    "typeof VideoEncoder !== 'function' ? Promise.resolve(false) : VideoEncoder.isConfigSupported(config).then(support => support.supported === true, () => false)",
)

private suspend fun Promise<JsAny?>.awaitTrue(): Boolean = isTrue(await())

private fun isTrue(value: JsAny?): Boolean = js("value === true")

private fun openMessage(url: String, container: Int, fastStart: Boolean, timeoutUs: Double): JsAny =
    js("({ type: 'writer-open', url, container, fastStart, timeoutUs })")

private fun addVideoMessage(
    width: Int,
    height: Int,
    frameRateNum: Int,
    frameRateDen: Int,
    codec: Int,
    dynamicRange: Int,
    preference: Int,
    bitRate: Double,
    keyframeIntervalUs: Double,
    bitDepth: Int,
    encoderConfig: JsAny,
): JsAny = js(
    "({ type: 'writer-add-video', width, height, frameRateNum, frameRateDen, codec, dynamicRange, preference, bitRate, keyframeIntervalUs, bitDepth, encoderConfig })",
)

private fun addAudioMessage(sampleRate: Int, channels: Int, bitRate: Double): JsAny =
    js("({ type: 'writer-add-audio', sampleRate, channels, bitRate })")

// The VideoColorSpace of PixelLayout's colour codes, which the encoder converts RGB with.
private fun videoMessage(
    track: Int,
    ptsNs: Double,
    format: String,
    width: Int,
    height: Int,
    planeCount: Int,
    offset0: Int,
    stride0: Int,
    offset1: Int,
    stride1: Int,
    offset2: Int,
    stride2: Int,
    primaries: Int,
    transfer: Int,
    matrix: Int,
    range: Int,
    buffer: JsAny,
): JsAny = js(
    """
    ({
      type: 'writer-video', track, ptsNs, format, width, height, buffer: buffer.buffer,
      planes: [{ offset: offset0, stride: stride0 }, { offset: offset1, stride: stride1 }, { offset: offset2, stride: stride2 }].slice(0, planeCount),
      colorSpace: {
        primaries: ['bt709', 'bt2020', 'smpte432'][primaries] || 'bt709',
        transfer: ['iec61966-2-1', 'bt709', 'linear', 'pq', 'hlg'][transfer] || 'bt709',
        matrix: ['rgb', 'bt709', 'bt2020-ncl', 'smpte170m'][matrix] || 'bt709',
        fullRange: range === 1,
      },
    })
    """,
)

private fun audioMessage(track: Int, samples: JsAny, frames: Int): JsAny =
    js("({ type: 'writer-audio', track, samples, frames })")

private fun trackMessage(type: String, track: Int): JsAny = js("({ type, track })")

private fun finishMessage(): JsAny = js("({ type: 'writer-finish' })")

private fun abortMessage(): JsAny = js("({ type: 'writer-abort' })")

private fun transferOf(bytes: JsAny): JsAny = js("[bytes.buffer]")

private fun replyIndex(reply: JsAny): Int = js("reply.index")

private fun replyDouble(reply: JsAny, name: String): Double = js("reply[name]")

private fun finishedOutput(reply: JsAny): JsAny = js("reply.output.buffer")
