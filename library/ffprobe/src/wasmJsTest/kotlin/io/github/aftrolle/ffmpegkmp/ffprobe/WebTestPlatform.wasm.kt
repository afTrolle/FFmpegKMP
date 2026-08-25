// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    ExperimentalWasmJsInterop::class,
)

package io.github.aftrolle.ffmpegkmp.ffprobe

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.await

internal actual suspend fun loadWebResource(url: String): ByteArray {
    val bytes = fetchBytes(url).await()
    return ByteArray(byteLength(bytes)) { index -> byteAt(bytes, index).toByte() }
}

internal actual fun configureWebRuntime(): Unit = js(
    """{
      globalThis.FFMPEGKMP_WORKER_URL = '/base/kotlin/ffmpegkmp-worker.mjs';
      globalThis.FFMPEGKMP_MODULE_URL = '/base/kotlin/ffmpegkmp.mjs';
    }""",
)

internal actual fun isWebRuntimeCrossOriginIsolated(): Boolean =
    isCrossOriginIsolated()

internal actual fun webEnvironmentSummary(): String = environmentSummary()

private fun isCrossOriginIsolated(): Boolean = js("globalThis.crossOriginIsolated === true")

private fun environmentSummary(): String = js(
    """JSON.stringify({
      href: globalThis.location?.href,
      topLevel: globalThis.window === globalThis.window?.top,
      crossOriginIsolated: globalThis.crossOriginIsolated,
      policyAllowsIsolation: globalThis.document?.featurePolicy?.allowsFeature('cross-origin-isolated')
    })""",
)

private fun fetchBytes(url: String): Promise<JsAny> = js(
    """fetch(url)
        .then(response => {
          if (!response.ok) throw new Error('Failed to load ' + url + ': HTTP ' + response.status);
          return response.arrayBuffer();
        })
        .then(buffer => new Uint8Array(buffer))
    """,
)

private fun byteLength(bytes: JsAny): Int = js("bytes.byteLength")
private fun byteAt(bytes: JsAny, index: Int): Int = js("bytes[index]")
