// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import cnames.structs.ffmpegkmp_frame
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_convert_into
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_convert_to
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_format
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_format_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_get_info
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_get_statistics
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_info
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_info_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_map
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_map_writable
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_pool_get
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_planes
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_planes_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_ref
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_statistics
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_statistics_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_unmap
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_unmap_writable
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_unref
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_wrap
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value

/** Takes ownership of [handle], one `ffmpegkmp_frame` reference. */
internal class CInteropFrame(internal val handle: CPointer<ffmpegkmp_frame>) : NativeFrame {
    override val width: Int
    override val height: Int
    override val format: NativeFrameFormat?
    override val mappable: Boolean
    override val pixelBuffer: Any?

    init {
        memScoped {
            val info = alloc<ffmpegkmp_frame_info>()
            ffmpegkmp_frame_info_init(info.ptr)
            requireFrameSuccess(ffmpegkmp_frame_get_info(handle, info.ptr), "describe a frame")
            width = info.width
            height = info.height
            mappable = info.mappable != 0
            format = info.format.toNative()?.takeIf { mappable }
            pixelBuffer = info.pixel_buffer
        }
    }

    override fun retain(): NativeFrame = CInteropFrame(
        ffmpegkmp_frame_ref(handle)
            ?: throw NativeFrameException("Could not retain a frame", NativePlayerError.INVALID_STATE),
    )

    override fun <R> usePlanes(block: (List<NativeFramePlane>) -> R): R = memScoped {
        val planes = alloc<ffmpegkmp_frame_planes>()
        ffmpegkmp_frame_planes_init(planes.ptr)
        requireFrameSuccess(ffmpegkmp_frame_map(handle, planes.ptr), "map a frame")
        try {
            block(
                List(planes.count) { index ->
                    val rowBytes = planes.row_bytes[index]
                    val rows = planes.rows[index]
                    val size = rowBytes.toLong() * rows
                    check(size <= Int.MAX_VALUE) { "A $size byte plane is too large" }
                    NativeFramePlane(
                        memory = checkNotNull(planes.data[index]).reinterpret<ByteVar>(),
                        offset = 0,
                        size = size.toInt(),
                        rowBytes = rowBytes,
                        rows = rows,
                    )
                },
            )
        } finally {
            ffmpegkmp_frame_unmap(handle)
        }
    }

    override fun convert(format: NativeFrameFormat): NativeFrame = memScoped {
        val destination = alloc<CPointerVar<ffmpegkmp_frame>>()
        requireFrameSuccess(
            ffmpegkmp_frame_convert_to(handle, format.toNative(this).ptr, destination.ptr),
            "convert a frame",
        )
        CInteropFrame(checkNotNull(destination.value))
    }

    override fun convertInto(target: NativeFrame) {
        val destination = requireNotNull(target as? CInteropFrame) { "Frames of another binding cannot be converted into" }
        requireFrameSuccess(ffmpegkmp_frame_convert_into(destination.handle, handle), "convert a frame")
    }

    override fun <R> useWritablePixels(block: (address: Long, rowBytes: Int) -> R): R = memScoped {
        val planes = alloc<ffmpegkmp_frame_planes>()
        ffmpegkmp_frame_planes_init(planes.ptr)
        requireFrameSuccess(ffmpegkmp_frame_map_writable(handle, planes.ptr), "map a frame for writing")
        try {
            check(planes.count == 1) { "A ${planes.count}-plane frame has no single packed plane to write" }
            block(checkNotNull(planes.data[0]).rawValue.toLong(), planes.row_bytes[0])
        } finally {
            ffmpegkmp_frame_unmap_writable(handle)
        }
    }

    override fun close() = ffmpegkmp_frame_unref(handle)
}

/** A frame over [rowBytes] × [height] bytes at [pixels], such as a bitmap's; the memory must outlive it. */
@InternalFFmpegKmpApi
public fun wrapNativeFrame(
    format: NativeFrameFormat,
    width: Int,
    height: Int,
    pixels: CPointer<ByteVar>,
    rowBytes: Int,
): NativeFrame = memScoped {
    CInteropFrame(
        ffmpegkmp_frame_wrap(format.toNative(this).ptr, width, height, pixels.reinterpret(), rowBytes)
            ?: throw NativeFrameException("Could not wrap a ${width}x$height frame", NativePlayerError.INVALID_ARGUMENT),
    )
}

@InternalFFmpegKmpApi
public actual fun allocateNativeFrame(format: NativeFrameFormat, width: Int, height: Int): NativeFrame = memScoped {
    val frame = alloc<CPointerVar<ffmpegkmp_frame>>()
    requireFrameSuccess(
        ffmpegkmp_frame_pool_get(null, format.toNative(this).ptr, width, height, frame.ptr),
        "allocate a ${width}x$height frame",
    )
    CInteropFrame(checkNotNull(frame.value))
}

@InternalFFmpegKmpApi
public actual fun nativeFrameStatistics(): NativeFrameStatistics = memScoped {
    val statistics = alloc<ffmpegkmp_frame_statistics>()
    ffmpegkmp_frame_statistics_init(statistics.ptr)
    ffmpegkmp_frame_get_statistics(statistics.ptr)
    NativeFrameStatistics(buffers = statistics.buffers.toLong(), bytes = statistics.bytes.toLong())
}

/** Takes ownership of a reference the bridge handed out, or returns null for none. */
internal fun CPointer<ffmpegkmp_frame>?.toNativeFrame(): NativeFrame? = this?.let(::CInteropFrame)

internal fun NativeFrameFormat.toNative(scope: MemScope): ffmpegkmp_frame_format = scope.alloc<ffmpegkmp_frame_format>().also {
    ffmpegkmp_frame_format_init(it.ptr)
    it.layout = layout
    it.primaries = primaries
    it.transfer = transfer
    it.matrix = matrix
    it.range = range
}

private fun ffmpegkmp_frame_format.toNative(): NativeFrameFormat? =
    layout.takeIf { it >= 0 }?.let { NativeFrameFormat(it, primaries, transfer, matrix, range) }
