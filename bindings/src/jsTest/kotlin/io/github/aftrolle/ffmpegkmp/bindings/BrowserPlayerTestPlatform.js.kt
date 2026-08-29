// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.js.Promise
import kotlinx.coroutines.await

internal actual suspend fun loadBrowserPlayerTestResource(url: String): ByteArray =
    fetchBrowserPlayerBytes(url).await()

internal actual fun configureBrowserPlayerTestRuntime() {
    js("""{
      globalThis.FFMPEGKMP_WORKER_URL = '/base/kotlin/ffmpegkmp-worker.mjs';
      globalThis.FFMPEGKMP_MODULE_URL = '/base/kotlin/ffmpegkmp.mjs';
    }""")
}

internal actual fun browserPlayerVideoFrameRegistrySize(): Int =
    js("globalThis.__ffmpegkmpVideoFrames ? globalThis.__ffmpegkmpVideoFrames.frames.size : 0")

internal actual fun browserSupportsWebCodecs(): Boolean =
    js("typeof globalThis.VideoDecoder === 'function' && typeof globalThis.EncodedVideoChunk === 'function'")

private fun fetchBrowserPlayerBytes(url: String): Promise<ByteArray> = js(
    """fetch(url)
        .then(response => {
          if (!response.ok) throw new Error('Failed to load ' + url + ': HTTP ' + response.status);
          return response.arrayBuffer();
        })
        .then(buffer => new Int8Array(buffer))
    """,
)
