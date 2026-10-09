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

/** Takes ownership of [handle], one `ffmpegkmp_frame` reference. */
internal class JavaCppFrame(internal val handle: ffmpegkmp_frame) : NativeFrame {
    override val width: Int
    override val height: Int
    override val format: NativeFrameFormat?
    override val mappable: Boolean
    override val pixelBuffer: Any? = null

    init {
        val info = ffmpegkmp_frame_info()
        try {
            bridge.ffmpegkmp_frame_info_init(info)
            requireFrameSuccess(bridge.ffmpegkmp_frame_get_info(handle, info), "describe a frame")
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
            requireFrameSuccess(bridge.ffmpegkmp_frame_map(handle, planes), "map a frame")
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
            requireFrameSuccess(bridge.ffmpegkmp_frame_convert_to(handle, native, destination), "convert a frame")
        }
        return JavaCppFrame(destination)
    }

    override fun convertInto(target: NativeFrame) {
        val destination = requireNotNull(target as? JavaCppFrame) { "Frames of another binding cannot be converted into" }
        requireFrameSuccess(bridge.ffmpegkmp_frame_convert_into(destination.handle, handle), "convert a frame")
    }

    override fun <R> useWritablePixels(block: (address: Long, rowBytes: Int) -> R): R {
        val planes = ffmpegkmp_frame_planes()
        try {
            bridge.ffmpegkmp_frame_planes_init(planes)
            requireFrameSuccess(bridge.ffmpegkmp_frame_map_writable(handle, planes), "map a frame for writing")
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
        bridge.ffmpegkmp_frame_unref(handle)
        handle.setNull()
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
        NativeFrameStatistics(buffers = statistics.buffers(), bytes = statistics.bytes())
    } finally {
        statistics.close()
    }
}

/** Takes ownership of a reference the bridge handed out, or returns null for none. */
internal fun ffmpegkmp_frame?.toNativeFrame(): NativeFrame? = this?.takeUnless(ffmpegkmp_frame::isNull)?.let(::JavaCppFrame)

@InternalFFmpegKmpApi
public actual fun allocateNativeFrame(format: NativeFrameFormat, width: Int, height: Int): NativeFrame {
    JavaCppBridgeLoader.load()
    val frame = ffmpegkmp_frame()
    format.withNative { native ->
        requireFrameSuccess(bridge.ffmpegkmp_frame_pool_get(null, native, width, height, frame), "allocate a ${width}x$height frame")
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
