// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * The video stream of an input, demuxed by FFmpeg in a Web Worker of its own and handed out packet by packet, for a caller
 * that decodes with WebCodecs on its own schedule. It never decodes or plays; [close] terminates the worker.
 *
 * [config] is a WebCodecs `VideoDecoderConfig` (codec string, avcC/hvcC `description`, coded size, colour space) without a
 * `hardwareAcceleration` preference, and [snapshot] the inspected stream: its size, rotation, aspect ratio and duration.
 * Calls must not overlap.
 */
@InternalFFmpegKmpApi
public class BrowserDemuxer private constructor(
    private val controller: JsAny,
    public val config: JsAny,
    public val snapshot: NativePlayerSnapshot,
) : AutoCloseable {
    /** Up to [count] packets in decode order as `EncodedVideoChunk`s, with whether the stream ended after them. */
    public suspend fun read(count: Int): BrowserPackets {
        val response = checkNotNull(request(controller, "demux-read", count, 0.0).await())
        return BrowserPackets(List(packetCount(response)) { chunk(response, it) }, packetsEnded(response))
    }

    /** Moves to the keyframe at or before [positionUs], measured from the input's start, so the next [read] starts there. */
    public suspend fun seek(positionUs: Long) {
        request(controller, "demux-seek", 0, positionUs.toDouble()).await()
    }

    override fun close(): Unit = terminate(controller)

    public companion object {
        /** Demuxes [bytes], a container FFmpeg recognises; [extension], such as `mp4`, is a hint. */
        public suspend fun open(bytes: ByteArray, extension: String = ""): BrowserDemuxer {
            val controller = startDemuxWorker()
            val opened = try {
                checkNotNull(openDemux(controller, copyToJsUint8Array(bytes), extension).await())
            } catch (failure: Throwable) {
                terminate(controller)
                throw failure
            }
            return BrowserDemuxer(controller, openedConfig(opened), openedSnapshot(opened).toBrowserNativePlayerSnapshot())
        }
    }
}

/** [chunks] are `EncodedVideoChunk`s; [end] is true once the stream has none after them. */
@InternalFFmpegKmpApi
public class BrowserPackets(
    public val chunks: List<JsAny>,
    public val end: Boolean,
)

/** A copy of [bytes] in a `Uint8Array` that owns its whole buffer, which a worker can take over. */
internal expect fun copyToJsUint8Array(bytes: ByteArray): JsAny

private fun startDemuxWorker(): JsAny = js(
    """
    (() => {
      const workerUrl = globalThis.FFMPEGKMP_WORKER_URL || 'ffmpegkmp-worker.mjs';
      const moduleUrl = globalThis.FFMPEGKMP_MODULE_URL || './ffmpegkmp.mjs';
      const worker = new Worker(workerUrl, { type: 'module' });
      const pending = new Map();
      let nextId = 1;
      let failure = null;
      const failAll = message => {
        failure = new Error(message);
        for (const { reject } of pending.values()) reject(failure);
        pending.clear();
      };
      worker.onmessage = ({ data }) => {
        const request = pending.get(data.id);
        if (!request) return;
        pending.delete(data.id);
        if (data.type === 'demux-failure') request.reject(new Error(data.message));
        else request.resolve(data);
      };
      worker.onerror = event => failAll(event.message || 'Could not load the FFmpegKMP demuxer worker at ' + workerUrl);
      return {
        post(message, transfers = []) {
          if (failure) return Promise.reject(failure);
          const id = nextId++;
          return new Promise((resolve, reject) => {
            pending.set(id, { resolve, reject });
            worker.postMessage({ ...message, id, moduleUrl }, transfers);
          });
        },
        terminate() {
          worker.terminate();
          failAll('The demuxer was closed');
        },
      };
    })()
    """,
)

private fun openDemux(controller: JsAny, copy: JsAny, extension: String): Promise<JsAny?> =
    js("controller.post({ type: 'demux-open', bytes: copy, extension }, [copy.buffer])")

private fun request(controller: JsAny, type: String, count: Int, positionUs: Double): Promise<JsAny?> =
    js("controller.post({ type, count, positionUs })")

private fun openedConfig(opened: JsAny): JsAny = js("opened.config")

private fun openedSnapshot(opened: JsAny): String = js("opened.snapshot")

private fun packetCount(response: JsAny): Int = js("response.packets.length")

private fun chunk(response: JsAny, index: Int): JsAny = js("new EncodedVideoChunk(response.packets[index])")

private fun packetsEnded(response: JsAny): Boolean = js("response.end === true")

private fun terminate(controller: JsAny): Unit = js("controller.terminate()")
