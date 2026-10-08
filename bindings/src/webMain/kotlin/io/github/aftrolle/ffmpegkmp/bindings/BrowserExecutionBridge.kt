// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okio.Buffer

internal interface BrowserWorker {
    fun terminate()
}

internal data class BrowserWorkerOutput(
    val path: String,
    val bytes: ByteArray,
)

internal interface BrowserWorkerListener {
    fun onEvent(kind: Int, level: Int, text: String)
    fun onComplete(returnCode: Int, outputs: List<BrowserWorkerOutput>)
    fun onFailure(message: String)
}

internal expect fun startBrowserWorker(
    requestJson: String,
    mountBytes: Array<ByteArray>,
    listener: BrowserWorkerListener,
): BrowserWorker

internal fun createBrowserExecutionBridge(): NativeExecutionBridge = BrowserWorkerExecutionBridge()

/**
 * Runs each execution in a Worker of its own, so several can run at once; they are tracked by
 * execution id.
 */
private class BrowserWorkerExecutionBridge : NativeExecutionBridge {
    private val active = mutableMapOf<Long, ActiveExecution>()
    private var closed = false

    override suspend fun execute(
        request: NativeExecutionRequest,
        emit: (NativeExecutionEvent) -> Unit,
    ): NativeExecutionResult = suspendCancellableCoroutine { continuation ->
        check(!closed) { "The browser execution bridge is closed" }
        check(request.id !in active) { "Execution ${request.id} is already running" }

        val execution = ActiveExecution(continuation)
        active[request.id] = execution
        execution.worker = try {
            startBrowserWorker(
                requestJson = request.toWorkerJson().toString(),
                mountBytes = request.readMountBytes(),
                listener = listener(request, execution, emit),
            )
        } catch (failure: Throwable) {
            active.remove(request.id)
            throw failure
        }
        continuation.invokeOnCancellation {
            if (active[request.id] === execution) active.remove(request.id)?.worker?.terminate()
        }
    }

    private fun cancelActive(executionId: Long) {
        active.remove(executionId)?.let { execution ->
            execution.worker?.terminate()
            execution.resume(Result.success(NativeExecutionResult(returnCode = CANCELLED_RETURN_CODE)))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        active.keys.toList().forEach(::cancelActive)
    }

    /** Ignores whatever [execution]'s worker sends after it was cancelled or completed. */
    private fun listener(
        request: NativeExecutionRequest,
        execution: ActiveExecution,
        emit: (NativeExecutionEvent) -> Unit,
    ) = object : BrowserWorkerListener {
        override fun onEvent(kind: Int, level: Int, text: String) {
            if (active[request.id] !== execution) return
            emit(kind.toNativeEvent(level, text))
        }

        override fun onComplete(returnCode: Int, outputs: List<BrowserWorkerOutput>) {
            if (active[request.id] !== execution) return
            val result = runCatching {
                outputs.writeTo(request.mounts)
                NativeExecutionResult(returnCode)
            }.recoverCatching { failure ->
                throw NativeBridgeUnavailableException(
                    "Invalid response from the FFmpegKMP Web Worker: ${failure.message}",
                )
            }
            active.remove(request.id)
            execution.resume(result)
        }

        override fun onFailure(message: String) {
            if (active[request.id] !== execution) return
            active.remove(request.id)
            execution.resume(
                Result.failure(
                    NativeBridgeUnavailableException(message.ifEmpty { "The FFmpegKMP Web Worker failed" }),
                ),
            )
        }
    }
}

private class ActiveExecution(private val continuation: CancellableContinuation<NativeExecutionResult>) {
    var worker: BrowserWorker? = null

    fun resume(result: Result<NativeExecutionResult>) {
        if (continuation.isActive) continuation.resumeWith(result)
    }
}

internal fun NativeExecutionRequest.toWorkerJson(): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("execute"))
    put("id", JsonPrimitive(id.toString()))
    put("kind", JsonPrimitive(if (kind == NativeCommandKind.FFMPEG) "ffmpeg" else "ffprobe"))
    put("arguments", JsonArray(arguments.map(::JsonPrimitive)))
    put("mounts", buildJsonArray {
        mounts.forEach { mount ->
            val resource = mount.resource
            add(buildJsonObject {
                put("path", JsonPrimitive(mount.path))
                put("access", JsonPrimitive(resource.accessName()))
                put(
                    "truncate",
                    JsonPrimitive(resource is NativeFileResource && resource.truncate),
                )
            })
        }
    })
}

internal fun NativeExecutionRequest.readMountBytes(): Array<ByteArray> =
    mounts.map { mount ->
        when (val resource = mount.resource) {
            is NativeFileResource -> if (resource.access != NativeIoAccess.WRITE || !resource.truncate) {
                val buffer = Buffer()
                resource.fileHandle.read(0L, buffer, resource.fileHandle.size())
                buffer.readByteArray()
            } else {
                ByteArray(0)
            }
            is NativeSourceResource -> {
                val buffer = Buffer()
                while (resource.source.read(buffer, 8_192L) != -1L) {
                    // Drain into one temporary array whose backing buffer is transferred to the worker.
                }
                buffer.readByteArray()
            }
            is NativeSinkResource -> ByteArray(0)
        }
    }.toTypedArray()

private fun Int.toNativeEvent(level: Int, text: String): NativeExecutionEvent =
    when (this) {
        0 -> NativeExecutionEvent.Log(level, text)
        1 -> NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDOUT, text)
        else -> NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDERR, text)
    }

private fun List<BrowserWorkerOutput>.writeTo(mounts: List<NativeMountedIo>) {
    val outputsByPath = associateBy(BrowserWorkerOutput::path)
    mounts.forEach { mount ->
        outputsByPath[mount.path]?.bytes?.let { bytes ->
            when (val resource = mount.resource) {
                is NativeFileResource -> {
                    resource.fileHandle.resize(bytes.size.toLong())
                    resource.fileHandle.write(0L, bytes, 0, bytes.size)
                }
                is NativeSinkResource -> {
                    val buffer = Buffer().write(bytes)
                    resource.sink.write(buffer, buffer.size)
                }
                is NativeSourceResource -> Unit
            }
        }
    }
}

private fun NativeIoResource.accessName(): String = when (this) {
    is NativeFileResource -> when (access) {
        NativeIoAccess.READ -> "read"
        NativeIoAccess.WRITE -> "write"
        NativeIoAccess.READ_WRITE -> "readWrite"
    }
    is NativeSourceResource -> "read"
    is NativeSinkResource -> "write"
}

private const val CANCELLED_RETURN_CODE = 255
