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
