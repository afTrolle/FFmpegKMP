// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

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
    val onFrame: (dynamic) -> Unit = { data ->
        listener.onFrame(
            bytes = playerFrameBytes(data),
            width = data.width as Int,
            height = data.height as Int,
            stride = data.stride as Int,
            presentationTimeUs = (data.presentationTimeUs as Number).toLong(),
            queueSerial = (data.queueSerial as Number).toInt().toUInt(),
        )
    }
    val onFailure: (String) -> Unit = listener::onFailure
    val onPlatformFrame: (dynamic) -> Boolean = { data ->
        val frameId = registerPlayerVideoFrame(data.frame)
        listener.onPlatformFrame(
            frameId = frameId,
            width = data.width as Int,
            height = data.height as Int,
            presentationTimeUs = (data.presentationTimeUs as Number).toLong(),
            queueSerial = (data.queueSerial as Number).toInt().toUInt(),
        ).also { accepted -> if (!accepted) releasePlayerVideoFrame(frameId) }
    }
    return JsBrowserPlayerWorker(
        startPlayerWorker(decoderPreference, onSnapshot, onFrame, onPlatformFrame, onFailure),
    )
}

private class JsBrowserPlayerWorker(private val controller: dynamic) : BrowserPlayerWorker {
    override fun prepare(source: NativePlayerSource, mountBytes: Array<ByteArray>) {
        postPlayerPrepare(
            controller,
            source.input,
            source.requireSecurePath,
            source.mounts.map(NativeMountedIo::path).toTypedArray(),
            mountBytes,
        )
    }

    override fun setOutput(flags: Int) = postPlayerCommand(controller, "player-set-output", flags, 0L)
    override fun clearOutput() = postPlayerCommand(controller, "player-clear-output", 0, 0L)
    override fun play() = postPlayerCommand(controller, "player-play", 0, 0L)
    override fun pause() = postPlayerCommand(controller, "player-pause", 0, 0L)
    override fun seek(positionUs: Long) = postPlayerCommand(controller, "player-seek", 0, positionUs)
    override fun stop() = postPlayerCommand(controller, "player-stop", 0, 0L)
    override fun cancel() = terminatePlayerWorker(controller)
    override fun close() = closePlayerWorker(controller)
}

private fun startPlayerWorker(
    decoderPreference: Int,
    onSnapshot: (String) -> Unit,
    onFrame: (dynamic) -> Unit,
    onPlatformFrame: (dynamic) -> Boolean,
    onFailure: (String) -> Unit,
): dynamic = js(
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

private fun registerPlayerVideoFrame(frame: dynamic): Int = js(
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

private fun postPlayerPrepare(
    controller: dynamic,
    input: String,
    requireSecurePath: Boolean,
    mountPaths: Array<String>,
    mountBytes: Array<ByteArray>,
): Unit = js(
    """
    {
      const mounts = mountPaths.map((path, index) => {
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
    controller: dynamic,
    type: String,
    flags: Int,
    positionUs: Long,
): Unit = js(
    "controller.post({ type, flags, positionUs: positionUs.toString() })",
)

private fun closePlayerWorker(controller: dynamic): Unit = js("controller.close()")
private fun terminatePlayerWorker(controller: dynamic): Unit = js("controller.terminate()")

private fun playerFrameBytes(data: dynamic): ByteArray = js(
    "new Int8Array(data.bytes.buffer, data.bytes.byteOffset, data.bytes.byteLength)",
)

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
