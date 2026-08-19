// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_context
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_event_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge
import java.io.File
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
    private val context: ffmpegkmp_context
    @Volatile
    private var closed = false

    init {
        configuredJniPath()?.let { path ->
            System.setProperty("org.bytedeco.javacpp.platform.library.path", path)
        }
        Loader.load(bridge::class.java)
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
        context = bridge.ffmpegkmp_context_create(callback, null)
            ?: throw NativeBridgeUnavailableException("FFmpegKMP JavaCPP context allocation failed")
    }

    override suspend fun execute(
        request: NativeExecutionRequest,
        emit: (NativeExecutionEvent) -> Unit,
    ): NativeExecutionResult {
        check(!closed) { "The JavaCPP execution bridge is closed" }
        val staging = if (request.inputs.isNotEmpty() || request.outputPaths.isNotEmpty()) {
            createStagingDirectory(request.id)
        } else {
            null
        }
        val stagedPaths = mutableMapOf<String, File>()
        try {
            request.inputs.forEachIndexed { index, input ->
                val file = File(staging, "input-$index${input.path.fileSuffix()}")
                file.writeBytes(input.bytes)
                stagedPaths[input.path] = file
            }
            request.outputPaths.forEachIndexed { index, path ->
                stagedPaths[path] = File(staging, "output-$index${path.fileSuffix()}")
            }

            val executable = if (request.kind == NativeCommandKind.FFMPEG) "ffmpeg" else "ffprobe"
            val arguments = listOf(executable) + request.arguments.map { argument ->
                stagedPaths[argument]?.absolutePath ?: argument
            }
            val pointers = PointerPointer<BytePointer>(*arguments.toTypedArray())
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
            val outputs = request.outputPaths.mapNotNull { path ->
                val file = stagedPaths.getValue(path)
                if (file.isFile) path to file.readBytes() else null
            }.toMap()
            return NativeExecutionResult(returnCode, outputs)
        } finally {
            staging?.deleteRecursively()
        }
    }

    override fun cancel(executionId: Long) {
        if (!closed) bridge.ffmpegkmp_cancel(context)
    }

    override fun close() {
        if (closed) return
        closed = true
        eventConsumer = null
        bridge.ffmpegkmp_context_destroy(context)
        callback.close()
    }
}

private fun configuredJniPath(): String? =
    System.getProperty("ffmpegkmp.jni.path")?.takeIf(String::isNotBlank)

private fun createStagingDirectory(executionId: Long): File {
    val marker = File.createTempFile("ffmpegkmp-$executionId-", ".staging")
    check(marker.delete() && marker.mkdir()) { "Could not create FFmpegKMP staging directory at $marker" }
    return marker
}

private fun String.fileSuffix(): String {
    val name = substringAfterLast('/').substringAfterLast('\\')
    val suffix = name.substringAfterLast('.', missingDelimiterValue = "")
    return if (suffix.isEmpty()) "" else ".$suffix"
}
