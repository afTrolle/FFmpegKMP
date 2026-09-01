// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_context
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_event_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_io_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge
import java.io.File
import okio.Buffer
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer

@InternalFFmpegKmpApi
internal fun createJavaCppExecutionBridge(): NativeExecutionBridge = JavaCppExecutionBridge()

private class JavaCppExecutionBridge : NativeExecutionBridge {
    @Volatile
    private var eventConsumer: ((NativeExecutionEvent) -> Unit)? = null

    private val callback: ffmpegkmp_event_callback
    private val ioCallback: ffmpegkmp_io_callback
    private val context: ffmpegkmp_context
    @Volatile
    private var mountedResources: Map<Long, MountedResource> = emptyMap()
    @Volatile
    private var closed = false

    init {
        JavaCppBridgeLoader.load()
        callback = object : ffmpegkmp_event_callback() {
            override fun call(opaque: Pointer?, kind: Int, level: Int, data: BytePointer?, size: Long) {
                if (data == null || size <= 0L || size > Int.MAX_VALUE) return
                val bytes = ByteArray(size.toInt())
                data.get(bytes)
                val text = bytes.decodeToString()
                eventConsumer?.invoke(
                    when (kind) {
                        bridge.FFMPEGKMP_EVENT_LOG -> NativeExecutionEvent.Log(level, text)
                        bridge.FFMPEGKMP_EVENT_STDOUT ->
                            NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDOUT, text)
                        else -> NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDERR, text)
                    },
                )
            }
        }
        ioCallback = object : ffmpegkmp_io_callback() {
            override fun call(
                opaque: Pointer?,
                resourceId: Long,
                operation: Int,
                offset: Long,
                data: BytePointer?,
                size: Long,
            ): Long = mountedResources[resourceId]
                ?.dispatch(operation, offset, data, size)
                ?: IO_FAILURE
        }
        context = bridge.ffmpegkmp_context_create(callback, null)
            ?: throw NativeBridgeUnavailableException("FFmpegKMP JavaCPP context allocation failed")
        bridge.ffmpegkmp_context_set_io_callback(context, ioCallback)
        // Android app processes have an unwritable cwd and no TMPDIR, which breaks the
        // bridge's ffprobe stdout redirect; java.io.tmpdir is always app-writable.
        System.getProperty("java.io.tmpdir")
            ?.let(::File)
            ?.takeIf(File::isDirectory)
            ?.let { bridge.ffmpegkmp_set_temp_directory(it.absolutePath) }
    }

    override suspend fun execute(
        request: NativeExecutionRequest,
        emit: (NativeExecutionEvent) -> Unit,
    ): NativeExecutionResult {
        check(!closed) { "The JavaCPP execution bridge is closed" }
        val mounts = request.mounts.mapIndexed { index, mount ->
            val id = index.toLong() + 1L
            id to MountedResource(mount.resource)
        }.toMap()
        val mountedPaths = request.mounts.mapIndexed { index, mount ->
            mount.path to protocolUrl(index.toLong() + 1L, mount.path)
        }.toMap()
        try {
            val executable = if (request.kind == NativeCommandKind.FFMPEG) "ffmpeg" else "ffprobe"
            val arguments = listOf(executable) + request.arguments.map { argument ->
                mountedPaths[argument] ?: argument
            }
            val pointers = PointerPointer<BytePointer>(*arguments.toTypedArray())
            mountedResources = mounts
            eventConsumer = emit
            val returnCode = try {
                bridge.ffmpegkmp_execute(
                    context,
                    if (request.kind == NativeCommandKind.FFMPEG) {
                        bridge.FFMPEGKMP_COMMAND_FFMPEG
                    } else {
                        bridge.FFMPEGKMP_COMMAND_FFPROBE
                    },
                    arguments.size,
                    pointers,
                )
            } finally {
                eventConsumer = null
                pointers.close()
            }
            if (returnCode == -38) {
                throw NativeBridgeUnavailableException(
                    "The FFmpegKMP JNI bridge was built without embedded fftools entry points",
                )
            }
            return NativeExecutionResult(returnCode)
        } finally {
            mountedResources = emptyMap()
        }
    }

    override fun cancel(executionId: Long) {
        if (!closed) bridge.ffmpegkmp_cancel(context)
    }

    override fun close() {
        if (closed) return
        closed = true
        eventConsumer = null
        mountedResources = emptyMap()
        bridge.ffmpegkmp_context_destroy(context)
        ioCallback.close()
        callback.close()
    }
}

private fun configuredJniPath(): String? =
    System.getProperty("ffmpegkmp.jni.path")?.takeIf(String::isNotBlank)

/**
 * Java marks a class as erroneous after a failed static initializer. Keep the first
 * loader failure so later client instances report the useful native-linker cause
 * instead of only `Could not initialize class ...global.bridge`.
 */
private object JavaCppBridgeLoader {
    private var firstFailure: Throwable? = null
    private var loaded = false

    fun load() = synchronized(this) {
        if (loaded) return@synchronized
        firstFailure?.let(::throwUnavailable)
        try {
            val configuredPath = configuredJniPath()
            if (configuredPath != null) {
                // `platform.library.path` is a classpath resource location in
                // JavaCPP. A generated filesystem path belongs in linkpath.
                // Pass fresh properties explicitly because Loader may already
                // have cached its defaults before an application configures us.
                val properties = Loader.loadProperties(true).apply {
                    setProperty("platform.linkpath", configuredPath)
                }
                Loader.load(bridge::class.java, properties, true)
            } else {
                Loader.load(bridge::class.java)
            }
            loaded = true
        } catch (failure: Throwable) {
            firstFailure = failure
            throwUnavailable(failure)
        }
    }

    private fun throwUnavailable(failure: Throwable): Nothing {
        val detail = generateSequence(failure) { it.cause }
            .mapNotNull { it.message?.lineSequence()?.firstOrNull()?.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?: failure.toString()
        throw NativeBridgeUnavailableException(
            "Could not load the FFmpegKMP native runtime: $detail. " +
                "On JVM, run through Gradle or set ffmpegkmp.jni.path to the generated JNI libraries.",
        ).also { it.initCause(failure) }
    }
}

private fun protocolUrl(id: Long, path: String): String = "ffmpegkmp:$id${path.fileSuffix()}"

private fun String.fileSuffix(): String {
    val name = substringAfterLast('/').substringAfterLast('\\')
    val suffix = name.substringAfterLast('.', missingDelimiterValue = "")
    return if (suffix.isEmpty()) "" else ".$suffix"
}

private class MountedResource(private val resource: NativeIoResource) {
    private var truncated = false

    @Synchronized
    fun dispatch(operation: Int, offset: Long, data: BytePointer?, size: Long): Long = try {
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
            if (resource.truncate && flags and AVIO_FLAG_WRITE != 0 && !truncated) {
                resource.fileHandle.resize(0L)
                truncated = true
            }
            when (resource.access) {
                NativeIoAccess.READ -> IO_CAP_READ or IO_CAP_SEEK
                NativeIoAccess.WRITE -> IO_CAP_WRITE or IO_CAP_SEEK
                NativeIoAccess.READ_WRITE -> IO_CAP_READ or IO_CAP_WRITE or IO_CAP_SEEK
            }
        }
        is NativeSourceResource -> IO_CAP_READ
        is NativeSinkResource -> IO_CAP_WRITE
    }.toLong()

    private fun read(offset: Long, data: BytePointer, size: Int): Long {
        val bytes = ByteArray(size)
        val count = when (resource) {
            is NativeFileResource -> resource.fileHandle.read(offset, bytes, 0, size)
            is NativeSourceResource -> {
                val buffer = Buffer()
                val read = resource.source.read(buffer, size.toLong())
                if (read > 0L) buffer.read(bytes, 0, read.toInt())
                read.toInt()
            }
            is NativeSinkResource -> return IO_FAILURE
        }
        if (count <= 0) return 0L
        data.put(bytes, 0, count)
        return count.toLong()
    }

    private fun write(offset: Long, data: BytePointer, size: Int): Long {
        val bytes = ByteArray(size)
        data.get(bytes)
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
        else -> IO_FAILURE
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

private fun Long.checkedSize(): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "Invalid native I/O size: $this" }
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
