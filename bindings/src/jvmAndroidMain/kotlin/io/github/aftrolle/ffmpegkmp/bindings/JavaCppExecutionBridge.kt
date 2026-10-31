// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_context
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_event_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_io_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer

@InternalFFmpegKmpApi
public actual fun createPlatformExecutionBridge(): NativeExecutionBridge =
    CommandEngineBridge(JavaCppCommandEngine(), Dispatchers.IO)

private class JavaCppCommandEngine : CommandEngineCalls {
    @Volatile
    private var eventConsumer: ((NativeExecutionEvent) -> Unit)? = null

    private val callback: ffmpegkmp_event_callback
    private val ioCallback: ffmpegkmp_io_callback
    private val context: ffmpegkmp_context
    @Volatile
    private var mountedResources: Map<Long, MountedResource> = emptyMap()

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

    override fun attach(mounts: List<NativeMountedIo>, emit: (NativeExecutionEvent) -> Unit) {
        mountedResources = mounts.mapIndexed { index, mount -> index.toLong() + 1L to MountedResource(mount.resource) }
            .toMap()
        eventConsumer = emit
    }

    override fun detach() {
        eventConsumer = null
        mountedResources = emptyMap()
    }

    override fun resetCancel() = bridge.ffmpegkmp_context_reset_cancel(context)

    override fun cancel() = bridge.ffmpegkmp_cancel(context)

    override fun execute(kind: NativeCommandKind, arguments: List<String>): Int {
        val pointers = PointerPointer<BytePointer>(*arguments.toTypedArray())
        return try {
            bridge.ffmpegkmp_execute(
                context,
                if (kind == NativeCommandKind.FFMPEG) bridge.FFMPEGKMP_COMMAND_FFMPEG else bridge.FFMPEGKMP_COMMAND_FFPROBE,
                arguments.size,
                pointers,
            )
        } finally {
            pointers.close()
        }
    }

    override fun release() {
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
internal object JavaCppBridgeLoader {
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


/** Moves `ffmpegkmp:` protocol bytes between JavaCPP pointers and a [MountedIo]. */
internal class MountedResource(resource: NativeIoResource, replayableSource: Boolean = false) {
    private val io = MountedIo(resource, replayableSource)
    private var scratch = ByteArray(0)

    @Synchronized
    fun dispatch(operation: Int, offset: Long, data: BytePointer?, size: Long): Long = try {
        when (operation) {
            IO_OPEN -> io.open(offset.toInt())
            IO_READ -> {
                val bytes = scratch(size.checkedSize())
                val count = io.read(offset, bytes, bytes.size.coerceAtMost(size.toInt()))
                if (count > 0) (data ?: return IO_FAILURE).put(bytes, 0, count)
                count.toLong()
            }
            IO_WRITE -> {
                val length = size.checkedSize()
                val bytes = scratch(length)
                (data ?: return IO_FAILURE).get(bytes, 0, length)
                if (io.write(offset, bytes, length)) length.toLong() else IO_FAILURE
            }
            IO_SIZE -> io.size()
            IO_CLOSE -> io.close()
            else -> IO_FAILURE
        }
    } catch (_: Throwable) {
        IO_FAILURE
    }

    /** Reused across calls: FFmpeg reads in similar-sized blocks. */
    private fun scratch(size: Int): ByteArray {
        if (scratch.size < size) scratch = ByteArray(size)
        return scratch
    }
}

private fun Long.checkedSize(): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "Invalid native I/O size: $this" }
    return toInt()
}

