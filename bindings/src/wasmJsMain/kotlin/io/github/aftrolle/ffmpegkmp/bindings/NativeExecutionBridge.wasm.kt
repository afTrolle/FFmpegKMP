// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    ExperimentalWasmJsInterop::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.io.encoding.Base64
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer

@InternalFFmpegKmpApi
public actual fun createPlatformExecutionBridge(): NativeExecutionBridge = WasmWorkerExecutionBridge()

private class WasmWorkerExecutionBridge : NativeExecutionBridge {
    private var activeId: Long? = null
    private var activeWorker: JsAny? = null
    private var activeContinuation: CancellableContinuation<NativeExecutionResult>? = null
    private var closed = false

    override suspend fun execute(
        request: NativeExecutionRequest,
        emit: (NativeExecutionEvent) -> Unit,
    ): NativeExecutionResult = suspendCancellableCoroutine { continuation ->
        check(!closed) { "The browser Wasm execution bridge is closed" }
        check(activeWorker == null) { "The browser Wasm execution bridge is already executing" }

        activeId = request.id
        activeContinuation = continuation
        val worker = startWorker(request.toWorkerJson().toString()) { message ->
            if (activeId != request.id) return@startWorker
            try {
                val value = Json.parseToJsonElement(message).jsonObject
                when (value.string("type")) {
                    "event" -> emit(value.toNativeEvent())
                    "complete" -> {
                        value.writeOutputsTo(request.mounts)
                        completeActive(request.id, value.toNativeResult())
                    }
                    "failure" -> {
                        failActive(
                            request.id,
                            NativeBridgeUnavailableException(
                                value.string("message") ?: "The FFmpegKMP Web Worker failed",
                            ),
                        )
                    }
                }
            } catch (failure: Throwable) {
                failActive(
                    request.id,
                    NativeBridgeUnavailableException(
                        "Invalid response from the FFmpegKMP Web Worker: ${failure.message}",
                    ),
                )
            }
        }
        activeWorker = worker
        continuation.invokeOnCancellation {
            if (activeId == request.id) {
                terminateWorker(worker)
                takeActive()
            }
        }
    }

    override fun cancel(executionId: Long) {
        if (activeId != executionId) return
        activeWorker?.let(::terminateWorker)
        completeActive(executionId, NativeExecutionResult(returnCode = CANCELLED_RETURN_CODE))
    }

    override fun close() {
        if (closed) return
        closed = true
        activeWorker?.let(::terminateWorker)
        activeId?.let { executionId ->
            completeActive(executionId, NativeExecutionResult(returnCode = CANCELLED_RETURN_CODE))
        }
    }

    private fun completeActive(executionId: Long, result: NativeExecutionResult) {
        if (activeId != executionId) return
        val continuation = takeActive()
        if (continuation?.isActive == true) continuation.resume(result)
    }

    private fun failActive(executionId: Long, failure: Throwable) {
        if (activeId != executionId) return
        val continuation = takeActive()
        if (continuation?.isActive == true) continuation.resumeWith(Result.failure(failure))
    }

    private fun takeActive(): CancellableContinuation<NativeExecutionResult>? {
        val continuation = activeContinuation
        activeContinuation = null
        activeId = null
        activeWorker = null
        return continuation
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
            val bytes = when (resource) {
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
                        // Drain the stream into the worker's transferable buffer.
                    }
                    buffer.readByteArray()
                }
                is NativeSinkResource -> ByteArray(0)
            }
            add(buildJsonObject {
                put("path", JsonPrimitive(mount.path))
                put("access", JsonPrimitive(resource.accessName()))
                put(
                    "truncate",
                    JsonPrimitive(resource is NativeFileResource && resource.truncate),
                )
                put("base64", JsonPrimitive(Base64.Default.encode(bytes)))
            })
        }
    })
}

private fun JsonObject.toNativeEvent(): NativeExecutionEvent {
    val text = Base64.Default.decode(string("base64").orEmpty()).decodeToString()
    return when (jsonPrimitive("kind").int) {
        0 -> NativeExecutionEvent.Log(jsonPrimitive("level").int, text)
        1 -> NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDOUT, text)
        else -> NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDERR, text)
    }
}

private fun JsonObject.toNativeResult(): NativeExecutionResult = NativeExecutionResult(
    returnCode = jsonPrimitive("returnCode").int,
)

private fun JsonObject.writeOutputsTo(mounts: List<NativeMountedIo>) {
    val decoded = (get("outputs")?.jsonArray ?: JsonArray(emptyList())).associate { element ->
        val output = element.jsonObject
        output.string("path").orEmpty() to Base64.Default.decode(output.string("base64").orEmpty())
    }
    mounts.forEach { mount ->
        decoded[mount.path]?.let { bytes ->
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

private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.content
private fun JsonObject.jsonPrimitive(name: String): JsonPrimitive = getValue(name).jsonPrimitive

private fun startWorker(requestJson: String, callback: (String) -> Unit): JsAny = js(
    """
    {
      const request = JSON.parse(requestJson);
      const workerUrl = globalThis.FFMPEGKMP_WORKER_URL || 'ffmpegkmp-worker.mjs';
      const moduleUrl = globalThis.FFMPEGKMP_MODULE_URL || './ffmpegkmp.mjs';
      const worker = new Worker(workerUrl, { type: 'module' });
      const fromBase64 = value => Uint8Array.from(atob(value), char => char.charCodeAt(0));
      const toBase64 = bytes => {
        let binary = '';
        const chunk = 0x8000;
        for (let offset = 0; offset < bytes.length; offset += chunk) {
          binary += String.fromCharCode(...bytes.subarray(offset, offset + chunk));
        }
        return btoa(binary);
      };
      worker.onmessage = ({ data }) => {
        if (data.bytes) {
          data.base64 = toBase64(new Uint8Array(data.bytes));
          delete data.bytes;
        }
        if (data.outputs) {
          data.outputs = data.outputs.map(output => ({
            path: output.path,
            base64: toBase64(new Uint8Array(output.bytes)),
          }));
        }
        callback(JSON.stringify(data));
        if (data.type === 'complete' || data.type === 'failure') worker.terminate();
      };
      worker.onerror = event => {
        const location = event.filename
          ? ' (' + event.filename + ':' + (event.lineno || 0) + ':' + (event.colno || 0) + ')'
          : '';
        const detail = event.message ||
          'Could not load the FFmpegKMP Web Worker at ' + workerUrl;
        callback(JSON.stringify({
          type: 'failure',
          id: request.id,
          message: detail + location + '; module URL: ' + moduleUrl,
        }));
        worker.terminate();
      };
      worker.onmessageerror = () => {
        callback(JSON.stringify({
          type: 'failure',
          id: request.id,
          message: 'Could not decode a message from ' + workerUrl + '; module URL: ' + moduleUrl,
        }));
        worker.terminate();
      };
      request.mounts = request.mounts.map(mount => ({
        path: mount.path,
        access: mount.access,
        truncate: mount.truncate,
        bytes: fromBase64(mount.base64),
      }));
      worker.postMessage(
        { ...request, moduleUrl },
        request.mounts.map(mount => mount.bytes.buffer),
      );
      return worker;
    }
    """,
)

private fun terminateWorker(worker: JsAny): Unit = js("worker.terminate()")

private const val CANCELLED_RETURN_CODE = 255
