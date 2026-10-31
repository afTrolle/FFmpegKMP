// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.UnsafeNumber::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFMPEGKMP_COMMAND_FFMPEG
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFMPEGKMP_COMMAND_FFPROBE
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFMPEGKMP_EVENT_LOG
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFMPEGKMP_EVENT_STDOUT
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_cancel
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_context_create
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_context_set_io_callback
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_context_destroy
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_context_reset_cancel
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_execute
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.posix.memcpy

@InternalFFmpegKmpApi
public actual fun createPlatformExecutionBridge(): NativeExecutionBridge =
    CommandEngineBridge(NativeCInteropCommandEngine(), Dispatchers.IO)

private class NativeCInteropCommandEngine : CommandEngineCalls {
    private val callbackState = CallbackState()
    private val callbackReference = StableRef.create(callbackState)
    private val context = ffmpegkmp_context_create(
        staticCFunction(::receiveNativeEvent),
        callbackReference.asCPointer(),
    ) ?: run {
        callbackReference.dispose()
        throw NativeBridgeUnavailableException("FFmpegKMP native context allocation failed")
    }

    init {
        ffmpegkmp_context_set_io_callback(context, staticCFunction(::receiveNativeIo))
    }

    override fun attach(mounts: List<NativeMountedIo>, emit: (NativeExecutionEvent) -> Unit) {
        callbackState.mounts = MountTable(mounts)
        callbackState.emit = emit
    }

    override fun detach() {
        callbackState.emit = null
        callbackState.mounts = MountTable.Empty
    }

    override fun resetCancel() = ffmpegkmp_context_reset_cancel(context)

    override fun cancel() = ffmpegkmp_cancel(context)

    override fun execute(kind: NativeCommandKind, arguments: List<String>): Int {
        val arena = Arena()
        return try {
            val nativeArguments = arena.allocArray<CPointerVar<ByteVar>>(arguments.size)
            arguments.forEachIndexed { index, argument -> nativeArguments[index] = argument.cstr.getPointer(arena) }
            ffmpegkmp_execute(
                context,
                if (kind == NativeCommandKind.FFMPEG) FFMPEGKMP_COMMAND_FFMPEG else FFMPEGKMP_COMMAND_FFPROBE,
                arguments.size,
                nativeArguments,
            )
        } finally {
            arena.clear()
        }
    }

    override fun release() {
        ffmpegkmp_context_destroy(context)
        callbackReference.dispose()
    }
}

private class CallbackState {
    var emit: ((NativeExecutionEvent) -> Unit)? = null
    var mounts = MountTable.Empty
}

private fun receiveNativeEvent(
    opaque: COpaquePointer?,
    kind: UInt,
    level: Int,
    data: CPointer<UByteVar>?,
    size: ULong,
) {
    if (opaque == null || data == null || size == 0uL) return
    val emit = opaque.asStableRef<CallbackState>().get().emit ?: return
    val text = data.readBytes(size.toInt()).decodeToString()
    emit(
        when (kind) {
            FFMPEGKMP_EVENT_LOG -> NativeExecutionEvent.Log(level, text)
            FFMPEGKMP_EVENT_STDOUT -> NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDOUT, text)
            else -> NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDERR, text)
        },
    )
}

private fun receiveNativeIo(
    opaque: COpaquePointer?,
    resourceId: Long,
    operation: UInt,
    offset: Long,
    data: CPointer<UByteVar>?,
    size: ULong,
): Long {
    val state = opaque?.asStableRef<CallbackState>()?.get() ?: return IO_FAILURE
    return state.mounts.dispatch(resourceId, operation.toInt(), offset, data, size)
}

/** Serves one `ffplaykmp_io_callback` call, moving bytes between [data] and the table's resources. */
internal fun MountTable.dispatch(
    resourceId: Long,
    operation: Int,
    offset: Long,
    data: CPointer<UByteVar>?,
    size: ULong,
): Long = dispatch(
    resourceId,
    operation,
    offset,
    size = if (size > Long.MAX_VALUE.toULong()) -1L else size.toLong(),
    copyOut = { bytes, count -> if (count > 0) bytes.usePinned { memcpy(checkNotNull(data), it.addressOf(0), count.convert()) } },
    copyIn = { bytes, count -> if (count > 0) bytes.usePinned { memcpy(it.addressOf(0), checkNotNull(data), count.convert()) } },
)

/** An `ffplaykmp_io_callback` whose opaque is a [StableRef] to the [MountTable] it serves. */
internal fun receiveMountedIo(
    opaque: COpaquePointer?,
    resourceId: Long,
    operation: UInt,
    offset: Long,
    data: CPointer<UByteVar>?,
    size: ULong,
): Long = runCatching {
    opaque?.asStableRef<MountTable>()?.get()?.dispatch(resourceId, operation.toInt(), offset, data, size)
}.getOrNull() ?: IO_FAILURE
