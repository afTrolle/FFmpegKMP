// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    ExperimentalWasmJsInterop::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffprobe

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.io.encoding.Base64
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer

class FFprobeWasmIntegrationTest {
    @Test
    fun loadsPackagedBigBuckBunnyFixture() = runTest {
        val bytes = loadResource("/base/kotlin/big-buck-bunny-1s.mp4")

        assertEquals(76_969, bytes.size)
        assertEquals("ftyp", bytes.copyOfRange(4, 8).decodeToString())
    }

    @Test
    fun readsMountedBigBuckBunnyMp4() = runTest {
        configureWasmRuntime()
        assertTrue(
            isCrossOriginIsolated(),
            "The FFmpeg Wasm runtime requires cross-origin isolation (${wasmEnvironmentSummary()})",
        )

        val inputPath = "/fixtures/big-buck-bunny-1s.mp4"
        val input = Buffer().apply { write(loadResource("/base/kotlin/big-buck-bunny-1s.mp4")) }
        val client = FFprobeClient()

        try {
            val media = client.inspect(
                input = inputPath,
                io = CommandIo { input(inputPath, input) },
            )
            val video = media.streams.firstOrNull { it.codecType == "video" }
            val duration = media.format?.durationSeconds ?: 0.0

            assertTrue(media.format?.formatName?.contains("mp4") == true)
            assertTrue(duration in 1.0..1.2, "Expected an approximately one-second clip, got $duration")
            assertNotNull(video, "FFprobe should discover the mounted video's stream")
            assertEquals("h264", video.codecName)
            assertEquals(320, video.width)
            assertEquals(176, video.height)
        } finally {
            client.close()
        }
    }
}

private suspend fun loadResource(url: String): ByteArray =
    Base64.Default.decode(fetchBase64(url).await().toString())

private fun configureWasmRuntime(): Unit = js(
    """{
      globalThis.FFMPEGKMP_WORKER_URL = '/base/kotlin/ffmpegkmp-worker.mjs';
      globalThis.FFMPEGKMP_MODULE_URL = '/base/kotlin/ffmpegkmp.mjs';
    }""",
)

private fun isCrossOriginIsolated(): Boolean = js("globalThis.crossOriginIsolated === true")

private fun wasmEnvironmentSummary(): String = js(
    """JSON.stringify({
      href: globalThis.location?.href,
      topLevel: globalThis.window === globalThis.window?.top,
      crossOriginIsolated: globalThis.crossOriginIsolated,
      policyAllowsIsolation: globalThis.document?.featurePolicy?.allowsFeature('cross-origin-isolated')
    })""",
)

private fun fetchBase64(url: String): Promise<JsString> = js(
    """fetch(url)
        .then(response => {
          if (!response.ok) throw new Error('Failed to load ' + url + ': HTTP ' + response.status);
          return response.arrayBuffer();
        })
        .then(buffer => {
          const bytes = new Uint8Array(buffer);
          let binary = '';
          const chunkSize = 0x8000;
          for (let offset = 0; offset < bytes.length; offset += chunkSize) {
            binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
          }
          return btoa(binary);
        })
    """,
)
