// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(ExperimentalWasmJsInterop::class, io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * A Web Worker of its own running `ffmpegkmp-worker.mjs`, for one decoder or writer. [request]
 * resolves with the worker's reply and throws a `-failure` reply as a [BrowserWorkerFailure]
 * carrying its bridge or FFmpeg error code; [send] posts without waiting for one.
 */
internal class BrowserWorkerCalls private constructor(private val controller: JsAny) {
    suspend fun request(message: JsAny, transfers: JsAny = emptyTransfers()): JsAny {
        val reply = checkNotNull(post(controller, message, transfers).await())
        if (isFailure(reply)) throw BrowserWorkerFailure(failureCode(reply), failureMessage(reply))
        return reply
    }

    fun send(message: JsAny) = sendOnly(controller, message)

    /** Ends the worker; requests still waiting fail. */
    fun terminate() = terminate(controller)

    companion object {
        fun start(): BrowserWorkerCalls = BrowserWorkerCalls(startWorker())
    }
}

internal class BrowserWorkerFailure(val code: Int, message: String) : Exception(message)

/** A worker failure's code: the reply's own, or [NativePlayerError.IO] for a worker that failed or was ended. */
internal val Throwable.browserErrorCode: Int get() = (this as? BrowserWorkerFailure)?.code ?: NativePlayerError.IO

private fun startWorker(): JsAny = js(
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
        request.resolve(data);
      };
      worker.onerror = event => failAll(event.message || 'Could not load the FFmpegKMP worker at ' + workerUrl);
      return {
        post(message, transfers) {
          if (failure) return Promise.reject(failure);
          const id = nextId++;
          return new Promise((resolve, reject) => {
            pending.set(id, { resolve, reject });
            worker.postMessage({ ...message, id, moduleUrl }, transfers);
          });
        },
        send(message) {
          if (!failure) worker.postMessage({ ...message, moduleUrl });
        },
        terminate() {
          worker.terminate();
          failAll('The FFmpegKMP worker was closed');
        },
      };
    })()
    """,
)


private fun post(controller: JsAny, message: JsAny, transfers: JsAny): Promise<JsAny?> =
    js("controller.post(message, transfers)")

private fun sendOnly(controller: JsAny, message: JsAny): Unit = js("controller.send(message)")

private fun terminate(controller: JsAny): Unit = js("controller.terminate()")

private fun isFailure(reply: JsAny): Boolean = js("typeof reply.type === 'string' && reply.type.endsWith('-failure')")

private fun failureCode(reply: JsAny): Int = js("typeof reply.code === 'number' ? reply.code : -1005")

private fun failureMessage(reply: JsAny): String = js("String(reply.message)")
