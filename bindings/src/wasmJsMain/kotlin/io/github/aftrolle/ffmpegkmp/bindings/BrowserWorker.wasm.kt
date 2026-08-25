// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    ExperimentalWasmJsInterop::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray

@InternalFFmpegKmpApi
public actual fun createPlatformExecutionBridge(): NativeExecutionBridge =
    createBrowserExecutionBridge()

internal actual fun startBrowserWorker(
    requestJson: String,
    mountBytes: Array<ByteArray>,
    listener: BrowserWorkerListener,
): BrowserWorker {
    val transferableMounts = JsArray<JsAny>()
    mountBytes.forEachIndexed { index, bytes ->
        transferableMounts[index] = bytes.toJsUint8Array()
    }
    val onEvent: (Int, Int, String) -> Unit = listener::onEvent
    val onComplete: (Int, JsAny) -> Unit = { returnCode, outputs ->
        listener.onComplete(returnCode, outputs.toBrowserWorkerOutputs())
    }
    val onFailure: (String) -> Unit = listener::onFailure
    return WasmBrowserWorker(
        startWorker(requestJson, transferableMounts, onEvent, onComplete, onFailure),
    )
}

private class WasmBrowserWorker(private val worker: JsAny) : BrowserWorker {
    override fun terminate() = terminateWorker(worker)
}

private fun startWorker(
    requestJson: String,
    mountBytes: JsArray<JsAny>,
    onEvent: (Int, Int, String) -> Unit,
    onComplete: (Int, JsAny) -> Unit,
    onFailure: (String) -> Unit,
): JsAny = js(
    WORKER_BOOTSTRAP,
)

private fun terminateWorker(worker: JsAny): Unit = js("worker.terminate()")

private fun ByteArray.toJsUint8Array(): JsAny {
    val result = createUint8Array(size)
    forEachIndexed { index, byte -> setUint8ArrayByte(result, index, byte.toInt() and 0xff) }
    return result
}

private fun JsAny.toBrowserWorkerOutputs(): List<BrowserWorkerOutput> =
    List(outputCount(this)) { outputIndex ->
        val size = outputSize(this, outputIndex)
        BrowserWorkerOutput(
            path = outputPath(this, outputIndex),
            bytes = ByteArray(size) { byteIndex ->
                outputByte(this, outputIndex, byteIndex).toByte()
            },
        )
    }

private fun createUint8Array(size: Int): JsAny = js("new Uint8Array(size)")
private fun setUint8ArrayByte(array: JsAny, index: Int, value: Int): Unit = js("array[index] = value")
private fun outputCount(outputs: JsAny): Int = js("outputs.length")
private fun outputPath(outputs: JsAny, index: Int): String = js("outputs[index].path")
private fun outputSize(outputs: JsAny, index: Int): Int = js("outputs[index].size")
private fun outputByte(outputs: JsAny, outputIndex: Int, byteIndex: Int): Int =
    js("outputs[outputIndex].bytes[byteIndex]")

private const val WORKER_BOOTSTRAP: String = """
    {
      const request = JSON.parse(requestJson);
      const workerUrl = globalThis.FFMPEGKMP_WORKER_URL || 'ffmpegkmp-worker.mjs';
      const moduleUrl = globalThis.FFMPEGKMP_MODULE_URL || './ffmpegkmp.mjs';
      const worker = new Worker(workerUrl, { type: 'module' });
      worker.onmessage = ({ data }) => {
        if (data.type === 'event') onEvent(data.kind, data.level, data.text);
        if (data.type === 'complete') onComplete(data.returnCode, data.outputs || []);
        if (data.type === 'failure') onFailure(data.message || 'The FFmpegKMP Web Worker failed');
        if (data.type === 'complete' || data.type === 'failure') worker.terminate();
      };
      worker.onerror = event => {
        const location = event.filename
          ? ' (' + event.filename + ':' + (event.lineno || 0) + ':' + (event.colno || 0) + ')'
          : '';
        const detail = event.message ||
          'Could not load the FFmpegKMP Web Worker at ' + workerUrl;
        onFailure(detail + location + '; module URL: ' + moduleUrl);
        worker.terminate();
      };
      worker.onmessageerror = () => {
        onFailure('Could not decode a message from ' + workerUrl + '; module URL: ' + moduleUrl);
        worker.terminate();
      };
      request.mounts = request.mounts.map((mount, index) => ({
        path: mount.path,
        access: mount.access,
        truncate: mount.truncate,
        bytes: mountBytes[index],
      }));
      worker.postMessage(
        { ...request, moduleUrl },
        request.mounts.map(mount => mount.bytes.buffer),
      );
      return worker;
    }
"""
