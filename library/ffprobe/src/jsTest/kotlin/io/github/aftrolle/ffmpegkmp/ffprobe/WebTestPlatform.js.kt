// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffprobe

import kotlin.js.Promise
import kotlinx.coroutines.await

internal actual suspend fun loadWebResource(url: String): ByteArray =
    fetchBytes(url).await()

internal actual fun configureWebRuntime() {
    js("""{
      globalThis.FFMPEGKMP_WORKER_URL = '/base/kotlin/ffmpegkmp-worker.mjs';
      globalThis.FFMPEGKMP_MODULE_URL = '/base/kotlin/ffmpegkmp.mjs';
    }""")
}

internal actual fun isWebRuntimeCrossOriginIsolated(): Boolean =
    js("globalThis.crossOriginIsolated === true")

internal actual fun webEnvironmentSummary(): String = js(
    """JSON.stringify({
      href: globalThis.location && globalThis.location.href,
      topLevel: !globalThis.window || globalThis.window === globalThis.window.top,
      crossOriginIsolated: globalThis.crossOriginIsolated,
      policyAllowsIsolation: globalThis.document && globalThis.document.featurePolicy &&
        globalThis.document.featurePolicy.allowsFeature('cross-origin-isolated')
    })""",
)

private fun fetchBytes(url: String): Promise<ByteArray> = js(
    """fetch(url)
        .then(response => {
          if (!response.ok) throw new Error('Failed to load ' + url + ': HTTP ' + response.status);
          return response.arrayBuffer();
        })
        .then(buffer => new Int8Array(buffer))
    """,
)
