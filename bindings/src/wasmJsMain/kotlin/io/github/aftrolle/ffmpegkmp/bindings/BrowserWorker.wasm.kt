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

@InternalFFmpegKmpApi
public actual fun createPlatformPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: (NativeVideoFrame) -> Unit,
    platformFrame: (NativePlatformVideoFrame) -> Boolean,
): NativePlayerBridge = createBrowserPlayerBridge(configuration, update, frame, platformFrame)

internal actual fun startBrowserPlayerWorker(
    decoderPreference: Int,
    listener: BrowserPlayerWorkerListener,
): BrowserPlayerWorker {
    val onSnapshot: (String) -> Unit = listener::onSnapshot
    val onFrame: (JsAny) -> Unit = { data ->
        listener.onFrame(
            bytes = ByteArray(playerFrameSize(data)) { index -> playerFrameByte(data, index).toByte() },
            width = playerFrameWidth(data),
            height = playerFrameHeight(data),
            stride = playerFrameStride(data),
            presentationTimeUs = playerFramePresentationTime(data).toLong(),
            queueSerial = playerFrameQueueSerial(data).toUInt(),
        )
    }
    val onFailure: (String) -> Unit = listener::onFailure
    val onPlatformFrame: (JsAny) -> Boolean = { data ->
        val frameId = registerPlayerVideoFrame(playerPlatformFrame(data))
        listener.onPlatformFrame(
            frameId = frameId,
            width = playerPlatformFrameWidth(data),
            height = playerPlatformFrameHeight(data),
            presentationTimeUs = playerPlatformFramePresentationTime(data).toLong(),
            queueSerial = playerPlatformFrameQueueSerial(data).toUInt(),
        ).also { accepted -> if (!accepted) releasePlayerVideoFrame(frameId) }
    }
    return WasmBrowserPlayerWorker(
        startPlayerWorker(decoderPreference, onSnapshot, onFrame, onPlatformFrame, onFailure),
    )
}

private class WasmBrowserPlayerWorker(private val controller: JsAny) : BrowserPlayerWorker {
    override fun prepare(source: NativePlayerSource, mountBytes: Array<ByteArray>) {
        val paths = JsArray<JsAny>()
        val bytes = JsArray<JsAny>()
        source.mounts.forEachIndexed { index, mount ->
            paths[index] = mount.path.toJsString()
            bytes[index] = mountBytes[index].toJsUint8Array()
        }
        postPlayerPrepare(controller, source.input, source.requireSecurePath, paths, bytes)
    }

    override fun setOutput(flags: Int) = postPlayerCommand(controller, "player-set-output", flags, "0")
    override fun clearOutput() = postPlayerCommand(controller, "player-clear-output", 0, "0")
    override fun play() = postPlayerCommand(controller, "player-play", 0, "0")
    override fun pause() = postPlayerCommand(controller, "player-pause", 0, "0")
    override fun seek(positionUs: Long) =
        postPlayerCommand(controller, "player-seek", 0, positionUs.toString())
    override fun stop() = postPlayerCommand(controller, "player-stop", 0, "0")
    override fun cancel() = terminatePlayerWorker(controller)
    override fun close() = closePlayerWorker(controller)
}

private fun startPlayerWorker(
    decoderPreference: Int,
    onSnapshot: (String) -> Unit,
    onFrame: (JsAny) -> Unit,
    onPlatformFrame: (JsAny) -> Boolean,
    onFailure: (String) -> Unit,
): JsAny = js(
    """
    (() => {
      const workerUrl = globalThis.FFMPEGKMP_WORKER_URL || 'ffmpegkmp-worker.mjs';
      const moduleUrl = globalThis.FFMPEGKMP_MODULE_URL || './ffmpegkmp.mjs';
      const worker = new Worker(workerUrl, { type: 'module' });
      const pending = [];
      let ready = false;
      let closed = false;
      const controller = {
        worker,
        moduleUrl,
        post(message, transfers = []) {
          if (closed) return;
          if (!ready && message.type !== 'player-init') pending.push([message, transfers]);
          else worker.postMessage({ ...message, moduleUrl }, transfers);
        },
        close() {
          if (closed) return;
          closed = true;
          if (ready) worker.postMessage({ type: 'player-close', moduleUrl });
          else worker.terminate();
        },
        terminate() {
          if (closed) return;
          closed = true;
          pending.splice(0);
          worker.terminate();
        },
      };
      worker.onmessage = ({ data }) => {
        if (data.type === 'player-ready') {
          ready = true;
          for (const [message, transfers] of pending.splice(0)) {
            worker.postMessage({ ...message, moduleUrl }, transfers);
          }
        } else if (data.type === 'player-snapshot') onSnapshot(data.snapshot);
        else if (data.type === 'player-frame') onFrame(data);
        else if (data.type === 'player-diagnostic') console.warn(data.message);
        else if (data.type === 'player-video-frame') {
          if (!onPlatformFrame(data)) data.frame.close();
        }
        else if (data.type === 'player-failure') onFailure(data.message || 'Browser playback failed');
      };
      worker.onerror = event => {
        onFailure(event.message || `Could not load the FFmpegKMP player worker at ${'$'}{workerUrl}`);
      };
      controller.post({ type: 'player-init', decoderPreference });
      return controller;
    })()
    """,
)

private fun postPlayerPrepare(
    controller: JsAny,
    input: String,
    requireSecurePath: Boolean,
    mountPaths: JsArray<JsAny>,
    mountBytes: JsArray<JsAny>,
): Unit = js(
    """
    {
      const mounts = Array.from(mountPaths).map((path, index) => {
        const mount = {};
        mount.path = path;
        mount.bytes = mountBytes[index];
        return mount;
      });
      controller.post(
        { type: 'player-prepare', input, requireSecurePath, mounts },
        mounts.map(mount => mount.bytes.buffer),
      );
    }
    """,
)

private fun postPlayerCommand(
    controller: JsAny,
    type: String,
    flags: Int,
    positionUs: String,
): Unit = js("controller.post({ type, flags, positionUs })")

private fun closePlayerWorker(controller: JsAny): Unit = js("controller.close()")
private fun terminatePlayerWorker(controller: JsAny): Unit = js("controller.terminate()")
private fun playerFrameSize(data: JsAny): Int = js("data.bytes.byteLength")
private fun playerFrameByte(data: JsAny, index: Int): Int = js("data.bytes[index]")
private fun playerFrameWidth(data: JsAny): Int = js("data.width")
private fun playerFrameHeight(data: JsAny): Int = js("data.height")
private fun playerFrameStride(data: JsAny): Int = js("data.stride")
private fun playerFramePresentationTime(data: JsAny): Double = js("data.presentationTimeUs")
private fun playerFrameQueueSerial(data: JsAny): Int = js("data.queueSerial")
private fun playerPlatformFrame(data: JsAny): JsAny = js("data.frame")
private fun playerPlatformFrameWidth(data: JsAny): Int = js("data.width")
private fun playerPlatformFrameHeight(data: JsAny): Int = js("data.height")
private fun playerPlatformFramePresentationTime(data: JsAny): Double = js("data.presentationTimeUs")
private fun playerPlatformFrameQueueSerial(data: JsAny): Int = js("data.queueSerial")
private fun registerPlayerVideoFrame(frame: JsAny): Int = js(
    """
    (() => {
      const registry = globalThis.__ffmpegkmpVideoFrames ||
        (globalThis.__ffmpegkmpVideoFrames = { nextId: 1, frames: new Map() });
      const id = registry.nextId++;
      registry.frames.set(id, frame);
      return id;
    })()
    """,
)
private fun releasePlayerVideoFrame(frameId: Int): Unit = js(
    """
    {
      const registry = globalThis.__ffmpegkmpVideoFrames;
      if (!registry) return;
      const frame = registry.frames.get(frameId);
      registry.frames.delete(frameId);
      if (frame) frame.close();
    }
    """,
)

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
