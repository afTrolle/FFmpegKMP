// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

@InternalFFmpegKmpApi
public actual fun createPlatformExecutionBridge(): NativeExecutionBridge =
    createBrowserExecutionBridge()

internal actual fun startBrowserWorker(
    requestJson: String,
    mountBytes: Array<ByteArray>,
    listener: BrowserWorkerListener,
): BrowserWorker {
    val onEvent: (Int, Int, String) -> Unit = listener::onEvent
    val onComplete: (Int, dynamic) -> Unit = { returnCode, outputs ->
        listener.onComplete(returnCode, outputsToBrowserWorkerOutputs(outputs))
    }
    val onFailure: (String) -> Unit = listener::onFailure
    return JsBrowserWorker(
        startWorker(requestJson, mountBytes, onEvent, onComplete, onFailure),
    )
}

private class JsBrowserWorker(private val worker: dynamic) : BrowserWorker {
    override fun terminate() {
        worker.terminate()
    }
}

private fun startWorker(
    requestJson: String,
    mountBytes: Array<ByteArray>,
    onEvent: (Int, Int, String) -> Unit,
    onComplete: (Int, dynamic) -> Unit,
    onFailure: (String) -> Unit,
): dynamic = js(
    WORKER_BOOTSTRAP,
)

private fun outputsToBrowserWorkerOutputs(outputs: dynamic): List<BrowserWorkerOutput> =
    List(outputs.length as Int) { index ->
        val output = outputs[index]
        BrowserWorkerOutput(
            path = output.path as String,
            bytes = outputBytes(output),
        )
    }

private fun outputBytes(output: dynamic): ByteArray = js(
    "new Int8Array(output.bytes.buffer, output.bytes.byteOffset, output.size)",
)

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
      request.mounts = request.mounts.map((mount, index) => {
        const source = mountBytes[index];
        const converted = {};
        converted.path = mount.path;
        converted.access = mount.access;
        converted.truncate = mount.truncate;
        converted.bytes = source;
        return converted;
      });
      worker.postMessage(
        { ...request, moduleUrl },
        request.mounts.map(mount => mount.bytes.buffer),
      );
      return worker;
    }
"""
