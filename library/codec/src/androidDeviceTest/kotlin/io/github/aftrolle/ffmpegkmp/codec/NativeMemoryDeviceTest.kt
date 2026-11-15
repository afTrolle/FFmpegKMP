// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.FFmpegKmp
import io.github.aftrolle.ffmpegkmp.core.FFmpegKmpConfig
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import io.github.aftrolle.ffmpegkmp.core.MemoryLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.bytedeco.javacpp.Pointer

class NativeMemoryDeviceTest {
    @Test
    fun androidLiftsTheProcessLimitAndALateOtherLimitFailsLoudly() = runBlocking<Unit> {
        val name = "cfr-30-h264.mp4"
        val bytes = checkNotNull(javaClass.getResourceAsStream("/video-decoder/$name")).use { it.readBytes() }
        VideoDecoder.open(MediaSource(name, CommandIo { input(name, Buffer().write(bytes)) }), VideoOutput.Memory(), timeout = 10.seconds).close()

        assertEquals(0L, Pointer.maxPhysicalBytes())
        FFmpegKmp.configure(FFmpegKmpConfig(processMemoryLimit = MemoryLimit.None))
        assertFailsWith<IllegalStateException> {
            FFmpegKmp.configure(FFmpegKmpConfig(processMemoryLimit = MemoryLimit.Bytes(1L shl 30)))
        }
        FFmpegKmp.configure(FFmpegKmpConfig())
    }
}
