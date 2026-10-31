// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * What a worker reports to a `statistics` message: the size of its Wasm heap
 * (`WebAssembly.Memory.buffer.byteLength`), its copies out of the heap (`HEAPU8.slice`), the software frames it
 * posted and the packets it gave WebCodecs, whether it gives them straight from the heap, the player's WebCodecs
 * decoders not yet closed, how often its player opened its input, and how many inputs the worker probed (its
 * player's, decoder's and audio's alike).
 */
internal data class BrowserWorkerStatistics(
    val heapBytes: Double,
    val heapSlices: Int,
    val frames: Int,
    val packets: Int,
    val chunksFromHeap: Boolean,
    val webCodecsDecoders: Int,
    val inputOpens: Int,
    val inputProbes: Int,
)

/** Records each Worker the page starts from now on, so that a test can ask it for its statistics. */
internal fun recordBrowserWorkers(): Unit = js(
    """{
      if (!globalThis.__ffmpegkmpTestWorkers) {
        const started = [];
        const Native = globalThis.Worker;
        function Recorded(url, options) {
          const worker = new Native(url, options);
          started.push(worker);
          return worker;
        }
        Recorded.prototype = Native.prototype;
        globalThis.Worker = Recorded;
        globalThis.__ffmpegkmpTestWorkers = started;
      }
    }""",
)

/** How many Workers the page started since [recordBrowserWorkers]: the index the next one gets. */
internal fun browserWorkersStarted(): Int = js("globalThis.__ffmpegkmpTestWorkers.length")

/** The statistics of the [index]th Worker started, after the messages posted to it before. */
internal suspend fun browserWorkerStatistics(index: Int): BrowserWorkerStatistics {
    val reply = checkNotNull(requestStatistics(index).await())
    return BrowserWorkerStatistics(
        heapBytes = statisticDouble(reply, "heapBytes"),
        heapSlices = statisticDouble(reply, "heapSlices").toInt(),
        frames = statisticDouble(reply, "frames").toInt(),
        packets = statisticDouble(reply, "packets").toInt(),
        chunksFromHeap = statisticBoolean(reply, "chunksFromHeap"),
        webCodecsDecoders = statisticDouble(reply, "webCodecsDecoders").toInt(),
        inputOpens = statisticDouble(reply, "inputOpens").toInt(),
        inputProbes = statisticDouble(reply, "inputProbes").toInt(),
    )
}

// The id is no request the page's own controllers made, so they leave the reply alone.
private fun requestStatistics(index: Int): Promise<JsAny?> = js(
    """new Promise((resolve, reject) => {
      const worker = globalThis.__ffmpegkmpTestWorkers[index];
      const timer = setTimeout(() => reject(new Error('Worker ' + index + ' sent no statistics')), 10000);
      const listener = event => {
        if (!event.data || event.data.type !== 'statistics') return;
        clearTimeout(timer);
        worker.removeEventListener('message', listener);
        resolve(event.data);
      };
      worker.addEventListener('message', listener);
      worker.postMessage({ type: 'statistics', id: 'statistics' });
    })""",
)

private fun statisticDouble(reply: JsAny, name: String): Double = js("Number(reply[name])")
private fun statisticBoolean(reply: JsAny, name: String): Boolean = js("Boolean(reply[name])")
