// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageWriter
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Opens a [SurfaceVideoEncoder] for a track the FFmpeg encoder of [info] took, or returns null:
 * Android 14 (API 34) and later, 8-bit SDR H.264 or HEVC, or HDR10 or HLG HEVC Main10, with a
 * hardware encoder that takes Surface input.
 */
internal val openSurfaceVideoEncoder: PacketEncoderFactory = { config, info, sink, timeoutMicros ->
    val qualifies = if (config.dynamicRange == NativeDynamicRange.SDR) {
        config.bitDepth != 10 && config.codec != NativeVideoCodec.AV1
    } else {
        config.codec == NativeVideoCodec.HEVC
    }
    if (Build.VERSION.SDK_INT >= 34 && info.hardware && qualifies) SurfaceVideoEncoder.open(config, info, sink, timeoutMicros) else null
}

/**
 * A hardware `MediaCodec` encoder taking its input from a `Surface`, which an `ImageWriter` of
 * [MAX_IMAGES] images feeds with the `HardwareBuffer`s a GPU draws into: [dequeue] lends one and
 * [queue] stamps it with its pts and hands it to the encoder, so a rendered frame reaches MediaCodec
 * with no copy. An SDR track's buffers are `RGBA_8888` in sRGB; an HDR track's are `RGBA_1010102`
 * holding full-range PQ or HLG codes on BT.2020 primaries, which the data space names, so the
 * encoder's own RGB to YUV conversion makes limited-range BT.2020 of them, as it makes BT.709 of
 * sRGB. A thread of its own takes the encoded packets and gives them to the writer's packet
 * track: the codec's config buffer as the extradata, each buffer's flags as keyframes, and its
 * presentation time as pts, which are in order since the encoder is told not to reorder frames.
 *
 * It fails the way a writer's encoder does: a packet the writer refuses or an encoder that takes
 * frames without giving packets for the writer's timeout fails the next [dequeue], [queue] and
 * [finish]; the stuck encoder is released so that a [dequeue] waiting on it returns.
 */
internal class SurfaceVideoEncoder private constructor(
    private val codec: MediaCodec,
    private val images: ImageWriter,
    private val sink: PacketSink,
    private val width: Int,
    private val height: Int,
    private val frameDurationNanos: Long,
    private val timeoutNanos: Long,
    private val colorTransfer: Int,
) : PacketTrackEncoder {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val drain = Thread(::drainPackets, "FFmpegKMP SurfaceVideoEncoder")

    /** Dequeued and not yet queued or released, in the order they were lent; guarded by [lock]. */
    private val lent = LinkedHashMap<NativeGpuBuffer, Image>()

    /**
     * One wrapper for each buffer the writer cycles through, by id, which stay open until the encoder is
     * released: [Image.getHardwareBuffer] makes a new one on every call, and a renderer keeps its GPU
     * state with the wrapper it was made with. Guarded by [lock].
     */
    private val wrappers = HashMap<Long, HardwareBuffer>()
    private var dequeuing = 0
    private var lastQueuedMicros = -1L
    private var inFlight = 0
    private var lastOutputNanos = 0L
    private var failure: NativeMediaWriterException? = null
    private var endOfStream = false
    private var released = false

    override fun start() = drain.start()

    override fun dequeue(): NativeGpuBuffer {
        val deadline = if (timeoutNanos > 0) System.nanoTime() + timeoutNanos else Long.MAX_VALUE
        lock.withLock {
            while (true) {
                failure?.let { throw it }
                if (released) throw NativeMediaWriterException("The encoder is released", NativePlayerError.INVALID_STATE)
                if (lent.size + dequeuing < MAX_IMAGES) break
                val left = deadline - System.nanoTime()
                if (left <= 0) {
                    throw NativeMediaWriterException(
                        "Could not take a buffer from the encoder: the caller holds all $MAX_IMAGES, and none was queued or closed in time",
                        NativePlayerError.TIMED_OUT,
                    )
                }
                changed.awaitNanos(left)
            }
            dequeuing++
        }
        val image = try {
            images.dequeueInputImage()
        } catch (cause: RuntimeException) {
            lock.withLock {
                dequeuing--
                changed.signalAll()
                throw failure ?: NativeMediaWriterException("The encoder has no buffer to draw into: ${cause.message}", NativePlayerError.INVALID_STATE)
            }
        }
        val fresh = image.hardwareBuffer
        return lock.withLock {
            dequeuing--
            if (fresh == null) {
                image.close()
                throw NativeMediaWriterException("The encoder's image has no HardwareBuffer", NativePlayerError.UNSUPPORTED)
            }
            val known = wrappers[fresh.id]?.takeUnless(HardwareBuffer::isClosed)
            val hardwareBuffer = known ?: fresh.also { wrappers[it.id] = it }
            if (hardwareBuffer !== fresh) fresh.close()
            lateinit var buffer: NativeGpuBuffer
            buffer = NativeGpuBuffer(hardwareBuffer, hardwareBuffer.id, 0, 0, width, height, colorTransfer) { give(buffer) }
            lent[buffer] = image
            buffer
        }
    }

    override fun queue(buffer: NativeGpuBuffer, ptsNanos: Long) {
        val micros = ptsNanos / 1000
        val image = lock.withLock {
            failure?.let { throw it }
            val image = lent[buffer]
                ?: throw NativeMediaWriterException(
                    "The frame was not drawn into this track's input surface, or has been queued already",
                    NativePlayerError.INVALID_ARGUMENT,
                )
            if (micros <= lastQueuedMicros) {
                throw NativeMediaWriterException(
                    "Timestamps must increase: ${ptsNanos}ns follows ${lastQueuedMicros}µs", NativePlayerError.INVALID_ARGUMENT,
                )
            }
            lent.remove(buffer)
            lastQueuedMicros = micros
            if (inFlight++ == 0) lastOutputNanos = System.nanoTime()
            changed.signalAll()
            image
        }
        try {
            image.timestamp = ptsNanos
            images.queueInputImage(image)
        } catch (cause: RuntimeException) {
            throw lock.withLock { failure } ?: NativeMediaWriterException("The encoder did not take the frame: ${cause.message}", NativePlayerError.INVALID_STATE)
        }
    }

    override fun finish(): Int {
        lock.withLock {
            failure?.let { return it.errorCode }
            if (released) return NativePlayerError.INVALID_STATE
        }
        try {
            codec.signalEndOfInputStream()
        } catch (cause: RuntimeException) {
            return NativePlayerError.INVALID_STATE
        }
        val deadline = if (timeoutNanos > 0) System.nanoTime() + timeoutNanos else Long.MAX_VALUE
        val result = lock.withLock {
            while (!endOfStream && failure == null) {
                val left = deadline - System.nanoTime()
                if (left <= 0) {
                    fail("The encoder did not end its stream: it took frames without giving packets", NativePlayerError.TIMED_OUT)
                    break
                }
                changed.awaitNanos(left)
            }
            failure?.errorCode ?: 0
        }
        release()
        return result
    }

    override fun release() {
        lock.withLock {
            if (released) return
            released = true
            changed.signalAll()
        }
        // Releasing the codec first abandons the Surface, which wakes an ImageWriter blocked in dequeueInputImage.
        runCatching(codec::release)
        if (Thread.currentThread() !== drain && drain.isAlive) drain.join(JOIN_MILLISECONDS)
        runCatching(images::close)
        lock.withLock { wrappers.values.forEach(HardwareBuffer::close) }
    }

    /** Returns [buffer] to the writer if it was never queued. */
    private fun give(buffer: NativeGpuBuffer) {
        val image = lock.withLock { lent.remove(buffer)?.also { changed.signalAll() } }
        if (image != null) runCatching(image::close)
    }

    private fun fail(message: String, code: Int) {
        if (failure == null) failure = NativeMediaWriterException(message, code)
        changed.signalAll()
    }

    private fun drainPackets() {
        val info = MediaCodec.BufferInfo()
        var config: ByteBuffer? = null
        try {
            while (true) {
                lock.withLock { if (released || failure != null) return }
                val index = codec.dequeueOutputBuffer(info, POLL_MICROSECONDS)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> checkStalled()
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (config == null) config = configOf(codec.outputFormat)
                    index >= 0 -> {
                        val output = checkNotNull(codec.getOutputBuffer(index)).range(info.offset, info.size)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            config = ByteBuffer.allocateDirect(info.size).also { it.put(output).flip() }
                        } else if (info.size > 0) {
                            val result = sink.write(
                                output, info.size, info.presentationTimeUs * 1000, frameDurationNanos,
                                info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, config?.duplicate(),
                            )
                            config = null
                            lock.withLock {
                                if (result < 0) fail("The writer did not take the packet at ${info.presentationTimeUs}µs (error $result)", result)
                                inFlight--
                                lastOutputNanos = System.nanoTime()
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            lock.withLock {
                                endOfStream = true
                                changed.signalAll()
                            }
                            return
                        }
                    }
                }
            }
        } catch (cause: RuntimeException) {
            lock.withLock {
                if (!released) fail("The encoder failed: ${cause.message}", NativePlayerError.INVALID_STATE)
            }
        }
    }

    /** Fails the encoder once frames have waited longer than the writer's timeout for a packet, and releases it. */
    private fun checkStalled() {
        val stalled = lock.withLock {
            val stalled = timeoutNanos > 0 && inFlight > 0 && System.nanoTime() - lastOutputNanos > timeoutNanos
            if (stalled) fail("The encoder took frames without giving packets", NativePlayerError.TIMED_OUT)
            stalled
        }
        if (stalled) runCatching(codec::release)
    }

    companion object {
        /** Images lent at once: the one being drawn, the ones queued, and one spare while the encoder holds its own. */
        private const val MAX_IMAGES = 4
        private const val POLL_MICROSECONDS = 10_000L
        private const val JOIN_MILLISECONDS = 1000L
        private const val VARIABLE_FRAME_RATE = 30

        /** AVCOL_TRC_IEC61966_2_1: an SDR buffer is sRGB, as a drawn Compose frame is. */
        private const val SRGB_TRANSFER = 13

        /** CTA-861.3's Static Metadata Descriptor ID for the type 1 descriptor [KEY_HDR_STATIC_INFO][MediaFormat.KEY_HDR_STATIC_INFO] carries. */
        private const val STATIC_METADATA_TYPE_1: Byte = 0

        /**
         * The encoder for [config], started, with its first buffer lent and given back to prove the GPU can draw into it;
         * null where any step fails. An HDR10 track asks for the HDR10 profile first and settles for Main10.
         */
        fun open(config: NativeVideoEncoderConfig, info: NativeVideoTrackInfo, sink: PacketSink, timeoutMicros: Long): SurfaceVideoEncoder? {
            val mime = if (config.codec == NativeVideoCodec.HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val profiles = when (config.dynamicRange) {
                NativeDynamicRange.SDR -> listOf(null)
                NativeDynamicRange.HDR10 -> listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                NativeDynamicRange.HLG -> listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            }
            for (profile in profiles) {
                val format = formatOf(config, info, mime, profile)
                for (name in hardwareEncoders(mime, format)) {
                    val codec = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
                    val encoder = runCatching { opened(codec, format, config, sink, timeoutMicros) }.getOrNull()
                    if (encoder != null) return encoder
                    runCatching(codec::release)
                }
            }
            return null
        }

        private fun opened(codec: MediaCodec, format: MediaFormat, config: NativeVideoEncoderConfig, sink: PacketSink, timeoutMicros: Long): SurfaceVideoEncoder {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            val hdr = config.dynamicRange != NativeDynamicRange.SDR
            val images = ImageWriter.Builder(surface)
                .setMaxImages(MAX_IMAGES)
                .setHardwareBufferFormat(if (hdr) HardwareBuffer.RGBA_1010102 else HardwareBuffer.RGBA_8888)
                .setUsage(HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
                .setDataSpace(
                    when (config.dynamicRange) {
                        NativeDynamicRange.SDR -> DataSpace.DATASPACE_SRGB
                        NativeDynamicRange.HDR10 -> DataSpace.DATASPACE_BT2020_PQ
                        NativeDynamicRange.HLG -> DataSpace.DATASPACE_BT2020_HLG
                    },
                )
                .build()
            try {
                images.dequeueInputImage().use { image ->
                    val buffer = checkNotNull(image.hardwareBuffer) { "The encoder's image has no HardwareBuffer" }
                    check(buffer.usage and HardwareBuffer.USAGE_GPU_COLOR_OUTPUT != 0L) { "The encoder's buffers cannot be drawn into" }
                    check(buffer.width == config.width && buffer.height == config.height) { "The encoder's buffers have another size" }
                    check(buffer.format == (if (hdr) HardwareBuffer.RGBA_1010102 else HardwareBuffer.RGBA_8888)) { "The encoder's buffers have another format" }
                    buffer.close()
                }
            } catch (failure: Throwable) {
                images.close()
                throw failure
            }
            val frameDuration = if (config.frameRateNumerator > 0) {
                TimeUnit.SECONDS.toNanos(1) * config.frameRateDenominator / config.frameRateNumerator
            } else {
                0L
            }
            val colorTransfer = when (config.dynamicRange) {
                NativeDynamicRange.SDR -> SRGB_TRANSFER
                NativeDynamicRange.HDR10 -> AVCOL_TRC_SMPTE2084
                NativeDynamicRange.HLG -> AVCOL_TRC_ARIB_STD_B67
            }
            return SurfaceVideoEncoder(codec, images, sink, config.width, config.height, frameDuration, timeoutMicros * 1000, colorTransfer)
        }

        private fun formatOf(config: NativeVideoEncoderConfig, info: NativeVideoTrackInfo, mime: String, profile: Int?): MediaFormat =
            MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (config.bitRate.takeIf { it > 0 } ?: info.bitRate).coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
                setInteger(
                    MediaFormat.KEY_FRAME_RATE,
                    if (config.frameRateNumerator > 0) Math.round(config.frameRateNumerator.toDouble() / config.frameRateDenominator).toInt() else VARIABLE_FRAME_RATE,
                )
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalMicros / 1e6f)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                when (config.dynamicRange) {
                    NativeDynamicRange.SDR -> {
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    }
                    NativeDynamicRange.HDR10 -> {
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                        config.hdrMetadata?.let { setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo(it)) }
                    }
                    NativeDynamicRange.HLG -> {
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                    }
                }
                if (profile != null) setInteger(MediaFormat.KEY_PROFILE, profile)
            }

        /**
         * [metadata] as CTA-861.3's type 1 Static Metadata Descriptor, which MediaCodec takes as
         * `KEY_HDR_STATIC_INFO`: the descriptor ID, then little-endian 16-bit fields, the red, green,
         * blue and white chromaticities in 0.00002 steps, the peak luminance in cd/m², the floor in
         * 0.0001 cd/m², MaxCLL and MaxFALL in cd/m². Fields the metadata lacks are 0, unknown.
         */
        private fun hdrStaticInfo(metadata: NativeHdrMetadata): ByteBuffer {
            val display = metadata.masteringDisplay
            fun chromaticity(value: Double?) = ((value ?: 0.0) / 0.00002).toUnsignedShort()
            return ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(STATIC_METADATA_TYPE_1)
                for (index in 0 until 3) {
                    putShort(chromaticity(display?.primariesX?.get(index)))
                    putShort(chromaticity(display?.primariesY?.get(index)))
                }
                putShort(chromaticity(display?.whitePointX))
                putShort(chromaticity(display?.whitePointY))
                putShort((display?.maxLuminance ?: 0.0).toUnsignedShort())
                putShort(((display?.minLuminance ?: 0.0) * 10000).toUnsignedShort())
                putShort((metadata.contentLight?.first ?: 0).toDouble().toUnsignedShort())
                putShort((metadata.contentLight?.second ?: 0).toDouble().toUnsignedShort())
                flip()
            }
        }

        private fun Double.toUnsignedShort(): Short = Math.round(this).coerceIn(0, 0xffff).toShort()

        private fun hardwareEncoders(mime: String, format: MediaFormat): List<String> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { codecInfo ->
                codecInfo.isEncoder && codecInfo.isHardwareAccelerated && !codecInfo.isSoftwareOnly && mime in codecInfo.supportedTypes &&
                    runCatching { codecInfo.getCapabilitiesForType(mime).isFormatSupported(format) }.getOrDefault(false)
            }.map(MediaCodecInfo::getName)

        /** The parameter sets MediaCodec put in the output format, for encoders that give them there and not as a config buffer. */
        private fun configOf(format: MediaFormat): ByteBuffer? {
            val sets = listOfNotNull(format.getByteBuffer("csd-0"), format.getByteBuffer("csd-1"))
            if (sets.isEmpty()) return null
            return ByteBuffer.allocateDirect(sets.sumOf { it.remaining() }).also { all ->
                sets.forEach { all.put(it.duplicate()) }
                all.flip()
            }
        }
    }
}

/** A view of [size] bytes from [offset]. */
private fun ByteBuffer.range(offset: Int, size: Int): ByteBuffer = duplicate().also {
    it.clear()
    it.position(offset)
    it.limit(offset + size)
}
