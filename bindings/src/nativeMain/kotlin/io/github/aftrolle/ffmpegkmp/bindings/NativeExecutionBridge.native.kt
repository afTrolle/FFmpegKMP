// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFMPEGKMP_COMMAND_FFMPEG
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFMPEGKMP_COMMAND_FFPROBE
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFMPEGKMP_EVENT_LOG
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFMPEGKMP_EVENT_STDOUT
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_cancel
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_context_create
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
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

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

    override suspend fun execute(
        request: NativeExecutionRequest,
        emit: (NativeExecutionEvent) -> Unit,
    ): NativeExecutionResult {
        check(!closed) { "The native execution bridge is closed" }
        val staging = NativeCommandStaging(request)
        try {
            callbackState.emit = emit
            val executable = if (request.kind == NativeCommandKind.FFMPEG) "ffmpeg" else "ffprobe"
            val arguments = listOf(executable) + request.arguments.map(staging::resolve)
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
            staging.writeOutputs()
            return NativeExecutionResult(returnCode)
        } finally {
            callbackState.emit = null
            staging.close()
        }
    }

    override fun cancel(executionId: Long) {
        if (!closed) ffmpegkmp_cancel(context)
    }

    override fun close() {
        if (closed) return
        closed = true
        callbackState.emit = null
        ffmpegkmp_context_destroy(context)
        callbackReference.dispose()
    }
}

private class NativeCommandStaging(request: NativeExecutionRequest) : AutoCloseable {
    private val root: Path? = if (request.inputs.isEmpty() && request.outputs.isEmpty()) {
        null
    } else {
        Path(
            SystemTemporaryDirectory,
            "ffmpegkmp-${request.id}-${Random.nextLong().toString(16)}",
        ).also { SystemFileSystem.createDirectories(it, mustCreate = true) }
    }
    private val paths = mutableMapOf<String, Path>()
    private val outputs = request.outputs

    init {
        request.inputs.forEachIndexed { index, input ->
            val path = Path(root!!, "input-$index${input.path.fileSuffix()}")
            SystemFileSystem.sink(path).buffered().use { sink -> sink.transferFrom(input.source) }
            paths[input.path] = path
        }
        request.outputs.forEachIndexed { index, output ->
            paths[output.path] = Path(root!!, "output-$index${output.path.fileSuffix()}")
        }
    }

    fun resolve(argument: String): String = paths[argument]?.toString() ?: argument

    fun writeOutputs() {
        outputs.forEach { output ->
            val path = paths.getValue(output.path)
            if (SystemFileSystem.exists(path)) {
                SystemFileSystem.source(path).buffered().use { it.transferTo(output.sink) }
            }
        }
    }

    override fun close() {
        paths.values.toSet().forEach { path ->
            if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
        }
        root?.let { path ->
            if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
        }
    }
}

private fun String.fileSuffix(): String {
    val name = substringAfterLast('/').substringAfterLast('\\')
    val suffix = name.substringAfterLast('.', missingDelimiterValue = "")
    return if (suffix.isEmpty()) "" else ".$suffix"
}

private class CallbackState {
    var emit: ((NativeExecutionEvent) -> Unit)? = null
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
