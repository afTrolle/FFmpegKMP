// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_frame
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_frame_format
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_frame_info
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_frame_planes
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_frame_statistics
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap

/** Takes ownership of [handle], one `ffmpegkmp_frame` reference. */
internal class JavaCppFrame(internal val handle: ffmpegkmp_frame) : NativeFrame {
    override val width: Int
    override val height: Int
    override val format: NativeFrameFormat?
    override val mappable: Boolean
    override val pixelBuffer: Any? = null
    private val leak = FrameLeaks.track(this, handle)

    init {
        val info = ffmpegkmp_frame_info()
        try {
            bridge.ffmpegkmp_frame_info_init(info)
            requireNative(bridge.ffmpegkmp_frame_get_info(handle, info), "describe a frame", ::NativeFrameException)
            width = info.width()
            height = info.height()
            mappable = info.mappable() != 0
            format = info.format().toNative()?.takeIf { mappable }
        } finally {
            info.close()
        }
    }

    override fun retain(): NativeFrame = JavaCppFrame(
        bridge.ffmpegkmp_frame_ref(handle)?.takeUnless(ffmpegkmp_frame::isNull)
            ?: throw NativeFrameException("Could not retain a frame", NativePlayerError.INVALID_STATE),
    )

    override fun <R> usePlanes(block: (List<NativeFramePlane>) -> R): R {
        val planes = ffmpegkmp_frame_planes()
        try {
            bridge.ffmpegkmp_frame_planes_init(planes)
            requireNative(bridge.ffmpegkmp_frame_map(handle, planes), "map a frame", ::NativeFrameException)
            try {
                return block(
                    List(planes.count()) { index ->
                        val size = planes.row_bytes(index).toLong() * planes.rows(index)
                        check(size <= Int.MAX_VALUE) { "A $size byte plane does not fit in a ByteBuffer" }
                        NativeFramePlane(
                            memory = planes.data(index).capacity(size).asByteBuffer(),
                            offset = 0,
                            size = size.toInt(),
                            rowBytes = planes.row_bytes(index),
                            rows = planes.rows(index),
                        )
                    },
                )
            } finally {
                bridge.ffmpegkmp_frame_unmap(handle)
            }
        } finally {
            planes.close()
        }
    }

    override fun convert(format: NativeFrameFormat): NativeFrame {
        val destination = ffmpegkmp_frame()
        format.withNative { native ->
            requireNative(bridge.ffmpegkmp_frame_convert_to(handle, native, destination), "convert a frame", ::NativeFrameException)
        }
        return JavaCppFrame(destination)
    }

    override fun upright(fallbackClockwiseQuarterTurns: Int, cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int): NativeFrame {
        val destination = ffmpegkmp_frame()
        requireNative(
            bridge.ffmpegkmp_frame_upright(handle, fallbackClockwiseQuarterTurns, cropX, cropY, cropWidth, cropHeight, destination),
            "turn a frame upright",
            ::NativeFrameException,
        )
        return JavaCppFrame(destination)
    }

    override fun convertInto(target: NativeFrame) {
        val destination = requireNotNull(target as? JavaCppFrame) { "Frames of another binding cannot be converted into" }
        requireNative(bridge.ffmpegkmp_frame_convert_into(destination.handle, handle), "convert a frame", ::NativeFrameException)
    }

    override fun <R> useWritablePixels(block: (address: Long, rowBytes: Int) -> R): R {
        val planes = ffmpegkmp_frame_planes()
        try {
            bridge.ffmpegkmp_frame_planes_init(planes)
            requireNative(bridge.ffmpegkmp_frame_map_writable(handle, planes), "map a frame for writing", ::NativeFrameException)
            try {
                check(planes.count() == 1) { "A ${planes.count()}-plane frame has no single packed plane to write" }
                return block(planes.data(0).address(), planes.row_bytes(0))
            } finally {
                bridge.ffmpegkmp_frame_unmap_writable(handle)
            }
        } finally {
            planes.close()
        }
    }

    override fun close() {
        leak.closed()
        bridge.ffmpegkmp_frame_unref(handle)
        handle.setNull()
    }
}

/**
 * Frees the frames the garbage collector finds unclosed, and says where each was made: a frame
 * left open holds its decoder's memory, and a MediaCodec frame holds the codec itself open after
 * the player or decoder that made it has closed. Looked for as frames are made.
 */
private object FrameLeaks {
    private val collected = ReferenceQueue<JavaCppFrame>()
    private val open: MutableSet<Leak> = ConcurrentHashMap.newKeySet()

    fun track(frame: JavaCppFrame, handle: ffmpegkmp_frame): Leak {
        while (true) (collected.poll() as Leak? ?: break).free()
        return Leak(frame, handle).also(open::add)
    }

    class Leak(frame: JavaCppFrame, private val handle: ffmpegkmp_frame) : PhantomReference<JavaCppFrame>(frame, collected) {
        private val madeAt = Throwable("A frame was garbage-collected without being closed; it was made here")

        fun closed() {
            open.remove(this)
            clear()
        }

        fun free() {
            if (!open.remove(this)) return
            println("FFmpegKMP: ${madeAt.stackTraceToString()}")
            bridge.ffmpegkmp_frame_unref(handle)
            handle.setNull()
        }
    }
}

/** A frame over [rowBytes] × [height] bytes at [address], such as a bitmap's; the memory must outlive it. */
@InternalFFmpegKmpApi
public fun wrapNativeFrame(format: NativeFrameFormat, width: Int, height: Int, address: Long, rowBytes: Int): NativeFrame {
    JavaCppBridgeLoader.load()
    val handle = format.withNative { native ->
        bridge.ffmpegkmp_frame_wrap(native, width, height, AddressPointer(address), rowBytes)
    }?.takeUnless(ffmpegkmp_frame::isNull)
        ?: throw NativeFrameException("Could not wrap a ${width}x$height frame", NativePlayerError.INVALID_ARGUMENT)
    return JavaCppFrame(handle)
}

@InternalFFmpegKmpApi
public actual fun nativeFrameStatistics(): NativeFrameStatistics {
    JavaCppBridgeLoader.load()
    val statistics = ffmpegkmp_frame_statistics()
    return try {
        bridge.ffmpegkmp_frame_statistics_init(statistics)
        bridge.ffmpegkmp_frame_get_statistics(statistics)
        NativeFrameStatistics(
            buffers = statistics.buffers(),
            bytes = statistics.bytes(),
            ringBuffers = statistics.ring_buffers(),
            ringBytes = statistics.ring_bytes(),
            gamutMaps = statistics.gamut_maps(),
        )
    } finally {
        statistics.close()
    }
}

/** The lattice points per side of [nativeGamutMap], `FFMPEGKMP_GAMUT_MAP_POINTS`. */
@InternalFFmpegKmpApi
public const val GAMUT_MAP_POINTS: Int = 65

/**
 * The gamut map the converter's tone map to SDR takes from [from] primaries into [to]'s
 * (`AVColorPrimaries`), for SDR codes in [transfer] (`AVColorTransferCharacteristic`), for a shader
 * that tone maps as the converter does: [GAMUT_MAP_POINTS] cubed points of red, green and blue, the
 * codes in [to]'s primaries of the colour whose codes in [from]'s are (red, green, blue) /
 * ([GAMUT_MAP_POINTS] - 1), red varying fastest, then green, then blue. Look it up tetrahedrally,
 * as the converter does. The bridge makes it on first use and keeps it.
 */
@InternalFFmpegKmpApi
public fun nativeGamutMap(from: Int, to: Int, transfer: Int): FloatArray {
    JavaCppBridgeLoader.load()
    val points = FloatArray(GAMUT_MAP_POINTS * GAMUT_MAP_POINTS * GAMUT_MAP_POINTS * 3)
    FloatPointer(points.size.toLong()).use { memory ->
        requireNative(
            bridge.ffmpegkmp_frame_gamut_map(from, to, transfer, memory, points.size),
            "make the gamut map from primaries $from into $to",
            ::NativeFrameException,
        )
        memory.get(points)
    }
    return points
}

/** Takes ownership of a reference the bridge handed out, or returns null for none. */
internal fun ffmpegkmp_frame?.toNativeFrame(): NativeFrame? = this?.takeUnless(ffmpegkmp_frame::isNull)?.let(::JavaCppFrame)

@InternalFFmpegKmpApi
public actual fun allocateNativeFrame(format: NativeFrameFormat, width: Int, height: Int): NativeFrame {
    JavaCppBridgeLoader.load()
    val frame = ffmpegkmp_frame()
    format.withNative { native ->
        requireNative(bridge.ffmpegkmp_frame_pool_get(null, native, width, height, frame), "allocate a ${width}x$height frame", ::NativeFrameException)
    }
    return JavaCppFrame(frame)
}

internal inline fun <T> NativeFrameFormat.withNative(block: (ffmpegkmp_frame_format) -> T): T {
    val native = ffmpegkmp_frame_format()
    return try {
        bridge.ffmpegkmp_frame_format_init(native)
        native.layout(layout).primaries(primaries).transfer(transfer).matrix(matrix).range(range)
        block(native)
    } finally {
        native.close()
    }
}

private fun ffmpegkmp_frame_format.toNative(): NativeFrameFormat? =
    layout().takeIf { it >= 0 }?.let { NativeFrameFormat(it, primaries(), transfer(), matrix(), range()) }

/** A pointer to memory JavaCPP did not allocate. */
private class AddressPointer(address: Long) : BytePointer() {
    init {
        this.address = address
    }
}
