// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
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
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_execute
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlin.concurrent.atomics.AtomicBoolean
import okio.Buffer

@InternalFFmpegKmpApi
public actual fun createPlatformExecutionBridge(): NativeExecutionBridge = NativeCInteropExecutionBridge()

@InternalFFmpegKmpApi
private class NativeCInteropExecutionBridge : NativeExecutionBridge {
    private val callbackState = CallbackState()
    private val callbackReference = StableRef.create(callbackState)
    private val context = ffmpegkmp_context_create(
        staticCFunction(::receiveNativeEvent),
        callbackReference.asCPointer(),
    ) ?: run {
        callbackReference.dispose()
        throw NativeBridgeUnavailableException("FFmpegKMP native context allocation failed")
    }
    private var closed = false

    init {
        ffmpegkmp_context_set_io_callback(context, staticCFunction(::receiveNativeIo))
    }

    override suspend fun execute(
        request: NativeExecutionRequest,
        emit: (NativeExecutionEvent) -> Unit,
    ): NativeExecutionResult {
        check(!closed) { "The native execution bridge is closed" }
        val mounts = request.mounts.mapIndexed { index, mount ->
            (index.toLong() + 1L) to NativeMountedResource(mount.resource)
        }.toMap()
        val mountedPaths = request.mounts.mapIndexed { index, mount ->
            mount.path to protocolUrl(index.toLong() + 1L, mount.path)
        }.toMap()
        try {
            callbackState.emit = emit
            callbackState.mounts = mounts
            val executable = if (request.kind == NativeCommandKind.FFMPEG) "ffmpeg" else "ffprobe"
            val arguments = listOf(executable) + request.arguments.map { mountedPaths[it] ?: it }
            val returnCode = try {
                memScoped {
                    val nativeArguments = allocArray<CPointerVar<ByteVar>>(arguments.size)
                    arguments.forEachIndexed { index, argument -> nativeArguments[index] = argument.cstr.ptr }
                    ffmpegkmp_execute(
                        context,
                        if (request.kind == NativeCommandKind.FFMPEG) {
                            FFMPEGKMP_COMMAND_FFMPEG
                        } else {
                            FFMPEGKMP_COMMAND_FFPROBE
                        },
                        arguments.size,
                        nativeArguments,
                    )
                }
            } finally {
                callbackState.emit = null
            }
            if (returnCode == -38) {
                throw NativeBridgeUnavailableException(
                    "The FFmpegKMP bridge was built without the patched fftools entry points",
                )
            }
            return NativeExecutionResult(returnCode)
        } finally {
            callbackState.emit = null
            callbackState.mounts = emptyMap()
        }
    }

    override fun cancel(executionId: Long) {
        if (!closed) ffmpegkmp_cancel(context)
    }

    override fun close() {
        if (closed) return
        closed = true
        callbackState.emit = null
        callbackState.mounts = emptyMap()
        ffmpegkmp_context_destroy(context)
        callbackReference.dispose()
    }
}

internal fun protocolUrl(id: Long, path: String): String = "ffmpegkmp:$id${path.fileSuffix()}"

private fun String.fileSuffix(): String {
    val name = substringAfterLast('/').substringAfterLast('\\')
    val suffix = name.substringAfterLast('.', missingDelimiterValue = "")
    return if (suffix.isEmpty()) "" else ".$suffix"
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

internal class NativeMountedResource(
    private val resource: NativeIoResource,
    private val replayableSource: Boolean = false,
) {
    private val truncated = AtomicBoolean(false)
    private val sourceCache = Buffer()
    private var sourceExhausted = false

    fun dispatch(operation: Int, offset: Long, data: CPointer<UByteVar>?, size: ULong): Long = try {
        when (operation) {
            IO_OPEN -> open(offset.toInt())
            IO_READ -> read(offset, data ?: return IO_FAILURE, size.checkedSize())
            IO_WRITE -> write(offset, data ?: return IO_FAILURE, size.checkedSize())
            IO_SIZE -> size()
            IO_CLOSE -> close()
            else -> IO_FAILURE
        }
    } catch (_: Throwable) {
        IO_FAILURE
    }

    private fun open(flags: Int): Long = when (resource) {
        is NativeFileResource -> {
            if (
                resource.truncate &&
                flags and AVIO_FLAG_WRITE != 0 &&
                truncated.compareAndSet(expectedValue = false, newValue = true)
            ) {
                resource.fileHandle.resize(0L)
            }
            when (resource.access) {
                NativeIoAccess.READ -> IO_CAP_READ or IO_CAP_SEEK
                NativeIoAccess.WRITE -> IO_CAP_WRITE or IO_CAP_SEEK
                NativeIoAccess.READ_WRITE -> IO_CAP_READ or IO_CAP_WRITE or IO_CAP_SEEK
            }
        }
        is NativeSourceResource -> IO_CAP_READ or if (replayableSource) IO_CAP_SEEK else 0
        is NativeSinkResource -> IO_CAP_WRITE
    }.toLong()

    private fun read(offset: Long, data: CPointer<UByteVar>, size: Int): Long {
        val bytes = ByteArray(size)
        val count = when (resource) {
            is NativeFileResource -> resource.fileHandle.read(offset, bytes, 0, size)
            is NativeSourceResource -> if (replayableSource) {
                fillSourceCache(offset + size)
                val available = (sourceCache.size - offset).coerceIn(0, size.toLong()).toInt()
                if (available > 0) {
                    val copy = Buffer()
                    sourceCache.copyTo(copy, offset, available.toLong())
                    copy.readExactly(bytes, available)
                }
                available
            } else {
                val buffer = Buffer()
                val read = resource.source.read(buffer, size.toLong())
                if (read > 0L) buffer.readExactly(bytes, read.toInt())
                read.toInt()
            }
            is NativeSinkResource -> return IO_FAILURE
        }
        if (count <= 0) return 0L
        repeat(count) { index -> data[index] = bytes[index].toUByte() }
        return count.toLong()
    }

    private fun write(offset: Long, data: CPointer<UByteVar>, size: Int): Long {
        val bytes = data.readBytes(size)
        when (resource) {
            is NativeFileResource -> resource.fileHandle.write(offset, bytes, 0, size)
            is NativeSinkResource -> {
                val buffer = Buffer().write(bytes)
                resource.sink.write(buffer, size.toLong())
            }
            is NativeSourceResource -> return IO_FAILURE
        }
        return size.toLong()
    }

    private fun size(): Long = when (resource) {
        is NativeFileResource -> resource.fileHandle.size()
        is NativeSourceResource -> if (replayableSource) {
            fillSourceCache(requiredSize = null)
            sourceCache.size
        } else {
            IO_FAILURE
        }
        else -> IO_FAILURE
    }

    private fun fillSourceCache(requiredSize: Long?) {
        val source = (resource as? NativeSourceResource)?.source ?: return
        while (!sourceExhausted && (requiredSize == null || sourceCache.size < requiredSize)) {
            val request = requiredSize
                ?.minus(sourceCache.size)
                ?.coerceAtMost(32_768L)
                ?: 32_768L
            if (source.read(sourceCache, request) <= 0L) sourceExhausted = true
        }
    }

    private fun close(): Long {
        when (resource) {
            is NativeFileResource -> if (resource.access != NativeIoAccess.READ) resource.fileHandle.flush()
            is NativeSinkResource -> resource.sink.flush()
            is NativeSourceResource -> Unit
        }
        return 0L
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

private const val IO_OPEN = 0
private const val IO_READ = 1
private const val IO_WRITE = 2
private const val IO_SIZE = 3
private const val IO_CLOSE = 4
private const val IO_CAP_READ = 1
private const val IO_CAP_WRITE = 2
private const val IO_CAP_SEEK = 4
private const val AVIO_FLAG_WRITE = 2
private const val IO_FAILURE = -1L
