// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.UnsafeNumber::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
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
import kotlin.concurrent.atomics.AtomicBoolean

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
        callbackState.mounts = mounts.mapIndexed { index, mount ->
            (index.toLong() + 1L) to NativeMountedResource(mount.resource)
        }.toMap()
        callbackState.emit = emit
    }

    override fun detach() {
        callbackState.emit = null
        callbackState.mounts = emptyMap()
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
    var mounts: Map<Long, NativeMountedResource> = emptyMap()
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

/** Moves `ffmpegkmp:` protocol bytes between native memory and a [MountedIo]. */
internal class NativeMountedResource(resource: NativeIoResource, replayableSource: Boolean = false) {
    private val io = MountedIo(resource, replayableSource)
    private var scratch = ByteArray(0)

    /** Serializes dispatch like the JVM's @Synchronized: player worker threads share the cache. */
    private val busy = AtomicBoolean(false)

    fun dispatch(operation: Int, offset: Long, data: CPointer<UByteVar>?, size: ULong): Long {
        while (!busy.compareAndSet(expectedValue = false, newValue = true)) {
            // Contention is rare and short: only concurrent reads of one mount.
        }
        return try {
            when (operation) {
                IO_OPEN -> io.open(offset.toInt())
                IO_READ -> {
                    val length = size.checkedSize()
                    val bytes = scratch(length)
                    val count = io.read(offset, bytes, length)
                    if (count > 0) {
                        val target = data ?: return IO_FAILURE
                        bytes.usePinned { pinned -> memcpy(target, pinned.addressOf(0), count.convert()) }
                    }
                    count.toLong()
                }
                IO_WRITE -> {
                    val length = size.checkedSize()
                    val bytes = (data ?: return IO_FAILURE).readBytes(length)
                    if (io.write(offset, bytes, length)) length.toLong() else IO_FAILURE
                }
                IO_SIZE -> io.size()
                IO_CLOSE -> io.close()
                else -> IO_FAILURE
            }
        } catch (_: Throwable) {
            IO_FAILURE
        } finally {
            busy.store(false)
        }
    }

    /** Reused across calls: FFmpeg reads in similar-sized blocks. */
    private fun scratch(size: Int): ByteArray {
        if (scratch.size < size) scratch = ByteArray(size)
        return scratch
    }
}

private fun receiveNativeIo(
    opaque: COpaquePointer?,
    resourceId: Long,
    operation: Int,
    offset: Long,
    data: CPointer<UByteVar>?,
    size: ULong,
): Long {
    val state = opaque?.asStableRef<CallbackState>()?.get() ?: return IO_FAILURE
    return state.mounts[resourceId]?.dispatch(operation, offset, data, size) ?: IO_FAILURE
}

private fun ULong.checkedSize(): Int {
    require(this <= Int.MAX_VALUE.toULong()) { "Invalid native I/O size: $this" }
    return toInt()
}

