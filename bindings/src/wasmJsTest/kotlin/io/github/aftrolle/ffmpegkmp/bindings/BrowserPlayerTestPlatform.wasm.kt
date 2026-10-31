// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.await

internal actual suspend fun loadBrowserPlayerTestResource(url: String): ByteArray {
    val bytes = fetchBrowserPlayerBytes(url).await()
    return ByteArray(browserPlayerByteLength(bytes)) { index ->
        browserPlayerByteAt(bytes, index).toByte()
    }
}

internal actual fun configureBrowserPlayerTestRuntime(): Unit = js(
    """{
      globalThis.FFMPEGKMP_WORKER_URL = '/base/kotlin/ffmpegkmp-worker.mjs';
      globalThis.FFMPEGKMP_MODULE_URL = '/base/kotlin/ffmpegkmp.mjs';
    }""",
)

internal actual fun browserPlayerVideoFrameRegistrySize(): Int =
    browserPlayerRegistrySize()

internal actual fun browserSupportsWebCodecs(): Boolean = hasBrowserWebCodecs()

private fun fetchBrowserPlayerBytes(url: String): Promise<JsAny> = js(
    """fetch(url)
        .then(response => {
          if (!response.ok) throw new Error('Failed to load ' + url + ': HTTP ' + response.status);
          return response.arrayBuffer();
        })
        .then(buffer => new Uint8Array(buffer))
    """,
)

private fun browserPlayerByteLength(bytes: JsAny): Int = js("bytes.byteLength")
private fun browserPlayerByteAt(bytes: JsAny, index: Int): Int = js("bytes[index]")
private fun browserPlayerRegistrySize(): Int =
    js("globalThis.__ffmpegkmpVideoFrames?.frames?.size || 0")
private fun hasBrowserWebCodecs(): Boolean =
    js("typeof globalThis.VideoDecoder === 'function' && typeof globalThis.EncodedVideoChunk === 'function'")

internal actual fun browserVideoFrameNumber(frameId: Int): Int = js(
    """(() => {
      const registry = globalThis.__ffmpegkmpVideoFrames;
      const frame = registry ? registry.frames.get(frameId) : undefined;
      if (!frame) return -1;
      const width = frame.displayWidth;
      const height = frame.displayHeight;
      const context = new OffscreenCanvas(width, height).getContext('2d', { willReadFrequently: true });
      context.drawImage(frame, 0, 0);
      const pixels = context.getImageData(0, 0, width, height).data;
      let number = 0;
      for (let bit = 0; bit < 12; bit++) {
        const x = (bit % 6) * 16 + 8;
        const y = Math.floor(bit / 6) * 16 + 8;
        if (pixels[(y * width + x) * 4 + 1] > 128) number |= 1 << bit;
      }
      return number;
    })()""",
)
