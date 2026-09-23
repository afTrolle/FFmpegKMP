// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okio.Buffer
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FFplayProductionNativeTest {
    @Test
    fun publicPlayerDecodesARealMountedVideoThroughTheAppleBridge() = runBlocking {
        val bytes = bundledTestResource("playback-color-patches-1s.mp4")
        val output = NativeProductionOutput()
        val player = FFplayPlayer(FFplayConfiguration(decoderPreference = FFplayDecoderPreference.SOFTWARE))
        player.attachOutput(output)

        try {
            player.prepare(
                FFplaySource(
                    input = "playback-color-patches-1s.mp4",
                    io = CommandIo {
                        input("playback-color-patches-1s.mp4", Buffer().write(bytes))
                    },
                ),
            )

            assertEquals(FFplayState.READY, player.snapshot.value.state)
            assertEquals(FFplayDecoderKind.SOFTWARE, player.snapshot.value.output?.decoder)
            assertNotNull(player.snapshot.value.video)
            assertTrue(output.framesReceived > 0)
        } finally {
            player.close()
        }
    }
}

private class NativeProductionOutput : FFplayVideoOutput {
    override val kind = FFplayRendererKind.COMPOSE_CANVAS
    override val frames = MutableStateFlow<FFplayFrame?>(null)
    override val capabilities = FFplayOutputCapabilities()
    var framesReceived = 0
        private set

    override fun submit(frame: FFplayFrame): Boolean = true

    override fun submitNative(frame: NativeVideoFrame, video: FFplayVideoInfo?): Boolean {
        framesReceived++
        return true
    }

    override fun discard() {
        frames.value = null
    }
}

private fun bundledTestResource(name: String): ByteArray {
    val resourcePath = checkNotNull(NSBundle.mainBundle.resourcePath) {
        "The native test bundle has no resource path"
    }
    val data: NSData = checkNotNull(
        NSFileManager.defaultManager.contentsAtPath("$resourcePath/compose-resources/$name"),
    ) { "Missing shared playback fixture $name in $resourcePath" }
    val result = ByteArray(data.length.toInt())
    if (result.isNotEmpty()) {
        result.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    }
    return result
}
