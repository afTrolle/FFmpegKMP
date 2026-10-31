// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(ExperimentalWasmJsInterop::class, io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

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

internal fun startBrowserPlayerWorker(
    configuration: NativePlayerConfiguration,
    listener: BrowserPlayerWorkerListener,
): BrowserPlayerWorker {
    val onSnapshot: (String) -> Unit = listener::onSnapshot
    val onFrame: (JsAny) -> Unit = { data ->
        listener.onFrame(
            bytes = int8ArrayToByteArray(playerFrameBytes(data)),
            width = frameWidth(data),
            height = frameHeight(data),
            stride = frameStride(data),
            presentationTimeUs = framePresentationTime(data).toLong(),
            queueSerial = frameQueueSerial(data).toUInt(),
        )
    }
    val onFailure: (String) -> Unit = listener::onFailure
    val onAudio: (String, String) -> Unit = listener::onAudioEvent
    val onPlatformFrame: (JsAny) -> Boolean = { data ->
        val frameId = registerPlayerVideoFrame(playerPlatformFrame(data))
        listener.onPlatformFrame(
            frameId = frameId,
            width = frameWidth(data),
            height = frameHeight(data),
            presentationTimeUs = framePresentationTime(data).toLong(),
            queueSerial = frameQueueSerial(data).toUInt(),
        ).also { accepted -> if (!accepted) releasePlayerVideoFrame(frameId) }
    }
    return WebBrowserPlayerWorker(
        startPlayerWorker(
            configuration.decoderPreference.ordinal,
            configuration.decoderThreads,
            configuration.codecReleaseDelayUs.toDouble(),
            onSnapshot,
            onFrame,
            onPlatformFrame,
            onFailure,
            onAudio,
        ),
    )
}

private class WebBrowserPlayerWorker(private val controller: JsAny) : BrowserPlayerWorker {
    override fun prepare(source: NativePlayerSource, mountBytes: Array<ByteArray>) {
        val mounts = emptyJsArray()
        source.mounts.forEachIndexed { index, mount ->
            pushMount(mounts, mount.path, transferToJsUint8Array(mountBytes[index]))
        }
        postPlayerPrepare(controller, source.input, source.requireSecurePath, mounts)
    }

    override fun setOutput(flags: Int) = postPlayerCommand(controller, "player-set-output", flags, 0L)
    override fun clearOutput() = postPlayerCommand(controller, "player-clear-output", 0, 0L)
    override fun setFrameSize(width: Int, height: Int) = postPlayerFrameSize(controller, width, height)
    override fun play() = postPlayerCommand(controller, "player-play", 0, 0L)
    override fun pause() = postPlayerCommand(controller, "player-pause", 0, 0L)
    override fun seek(positionUs: Long) = postPlayerCommand(controller, "player-seek", 0, positionUs)
    override fun stop() = postPlayerCommand(controller, "player-stop", 0, 0L)
    override fun cancel() = terminateWorker(controller)
    override fun post(message: JsAny, transfers: JsAny) = postPlayerMessage(controller, message, transfers)
}

internal fun startBrowserWorker(
    requestJson: String,
    mountBytes: Array<ByteArray>,
    listener: BrowserWorkerListener,
): BrowserWorker {
    val onEvent: (Int, Int, String) -> Unit = listener::onEvent
    val onComplete: (Int, JsAny) -> Unit = { returnCode, outputs ->
        listener.onComplete(returnCode, outputs.toBrowserWorkerOutputs())
    }
    val onFailure: (String) -> Unit = listener::onFailure
    val transferred = emptyJsArray()
    mountBytes.forEach { bytes -> push(transferred, transferToJsUint8Array(bytes)) }
    val worker = startWorker(requestJson, transferred, onEvent, onComplete, onFailure)
    return object : BrowserWorker {
        override fun terminate() = terminateWorker(worker)
    }
}

private fun JsAny.toBrowserWorkerOutputs(): List<BrowserWorkerOutput> =
    List(outputCount(this)) { index ->
        BrowserWorkerOutput(
            path = outputPath(this, index),
            bytes = int8ArrayToByteArray(outputBytes(this, index)),
        )
    }

private fun emptyJsArray(): JsAny = js("[]")

private fun push(array: JsAny, value: JsAny): Unit = js("array.push(value)")

private fun pushMount(mounts: JsAny, path: String, bytes: JsAny): Unit = js("mounts.push({ path, bytes })")

private fun startPlayerWorker(
    decoderPreference: Int,
    decoderThreads: Int,
    codecReleaseDelayUs: Double,
    onSnapshot: (String) -> Unit,
    onFrame: (JsAny) -> Unit,
    onPlatformFrame: (JsAny) -> Boolean,
    onFailure: (String) -> Unit,
    onAudio: (String, String) -> Unit,
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
        else if (data.type === 'player-audio') onAudio(data.event, data.payload);
      };
      worker.onerror = event => {
        onFailure(event.message || `Could not load the FFmpegKMP player worker at ${'$'}{workerUrl}`);
      };
      controller.post({ type: 'player-init', decoderPreference, decoderThreads, codecReleaseDelayUs });
      return controller;
    })()
    """,
)

private fun postPlayerMessage(controller: JsAny, message: JsAny, transfers: JsAny): Unit =
    js("controller.post(message, transfers)")

private fun postPlayerPrepare(controller: JsAny, input: String, requireSecurePath: Boolean, mounts: JsAny): Unit = js(
    "controller.post({ type: 'player-prepare', input, requireSecurePath, mounts }, mounts.map(mount => mount.bytes.buffer))",
)

// A Long reaches JavaScript as a string on both targets: Kotlin/JS has no BigInt Long by default.
private fun postPlayerCommand(controller: JsAny, type: String, flags: Int, positionUs: Long): Unit =
    postPlayerCommand(controller, type, flags, positionUs.toString())

private fun postPlayerCommand(controller: JsAny, type: String, flags: Int, positionUs: String): Unit =
    js("controller.post({ type, flags, positionUs })")

private fun postPlayerFrameSize(controller: JsAny, width: Int, height: Int): Unit =
    js("controller.post({ type: 'player-frame-size', width, height })")

private fun terminateWorker(controller: JsAny): Unit = js("controller.terminate()")

private fun playerFrameBytes(data: JsAny): JsAny =
    js("new Int8Array(data.bytes.buffer, data.bytes.byteOffset, data.bytes.byteLength)")

private fun frameWidth(data: JsAny): Int = js("data.width")
private fun frameHeight(data: JsAny): Int = js("data.height")
private fun frameStride(data: JsAny): Int = js("data.stride")
private fun framePresentationTime(data: JsAny): Double = js("data.presentationTimeUs")

// A queue serial is unsigned 32-bit; `| 0` hands it over as the Int with the same bits.
private fun frameQueueSerial(data: JsAny): Int = js("data.queueSerial | 0")

private fun playerPlatformFrame(data: JsAny): JsAny = js("data.frame")

/** Keeps a WebCodecs `VideoFrame` for the page's renderer to draw by id, in a registry it shares. */
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

private fun startWorker(
    requestJson: String,
    mountBytes: JsAny,
    onEvent: (Int, Int, String) -> Unit,
    onComplete: (Int, JsAny) -> Unit,
    onFailure: (String) -> Unit,
): JsAny = js(
    """
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
      // Kotlin/JS re-emits this code and drops the parentheses an arrow returning an object literal needs.
      request.mounts = request.mounts.map((mount, index) => {
        const converted = {};
        converted.path = mount.path;
        converted.access = mount.access;
        converted.truncate = mount.truncate;
        converted.bytes = mountBytes[index];
        return converted;
      });
      worker.postMessage(
        { ...request, moduleUrl },
        request.mounts.map(mount => mount.bytes.buffer),
      );
      return worker;
    }
    """,
)

private fun outputCount(outputs: JsAny): Int = js("outputs.length")
private fun outputPath(outputs: JsAny, index: Int): String = js("outputs[index].path")
private fun outputBytes(outputs: JsAny, index: Int): JsAny =
    js("new Int8Array(outputs[index].bytes.buffer, outputs[index].bytes.byteOffset, outputs[index].size)")
