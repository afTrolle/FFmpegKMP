// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio.desktop

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Buffer

class DesktopNativeRuntimeTest {
    @Test
    fun directIdeLaunchFindsAndLoadsTheGeneratedRuntime() = runBlocking {
        System.clearProperty("ffmpegkmp.jni.path")
        System.clearProperty("org.bytedeco.javacpp.platform.linkpath")

        configureDesktopNativeRuntime()

        assertTrue(System.getProperty("ffmpegkmp.jni.path").isNotBlank())
        FFmpegClient().use { client ->
            assertEquals(0, client.execute(listOf("-version")).returnCode)

            val source = Buffer().apply {
                write("P6\n2 2\n255\n".encodeToByteArray())
                write(byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1))
            }
            val rendered = Buffer()
            val result = client.execute(
                listOf("-i", "/smoke.ppm", "-c:v", "mpeg4", "-q:v", "5", "/smoke.mp4"),
                CommandIo {
                    input("/smoke.ppm", source)
                    output("/smoke.mp4", rendered)
                },
            )
            assertTrue(result.isSuccess, result.errorOutput)
            assertTrue(rendered.size > 0L)
        }
    }
}
