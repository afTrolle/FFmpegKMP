// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.player

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun createPlatformAudioOutput(format: PcmFormat): PlatformAudioOutput = JavaSoundAudioOutput(format)

internal actual val playbackDispatcher: CoroutineDispatcher = Dispatchers.IO

/**
 * Java Sound output. Lines accept 16-bit PCM on every mixer, whereas float support varies, so
 * samples are converted once, into a reused byte buffer.
 */
private class JavaSoundAudioOutput(private val pcm: PcmFormat) : PlatformAudioOutput {
    private val audioFormat = AudioFormat(pcm.sampleRate.toFloat(), 16, pcm.channels, true, false)
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(audioFormat).apply {
        // ~100 ms of device buffering keeps pause and seek responsive.
        open(audioFormat, pcm.sampleRate / 10 * audioFormat.frameSize)
    }
    private var bytes = ByteArray(0)

    override val latencyFrames: Int get() = line.bufferSize / audioFormat.frameSize

    override fun start() = line.start()

    override fun pause() = line.stop()

    override fun flush() = line.flush()

    override fun write(samples: FloatArray, frames: Int) {
        val sampleCount = frames * pcm.channels
        if (bytes.size < sampleCount * 2) bytes = ByteArray(sampleCount * 2)
        for (index in 0 until sampleCount) {
            val value = (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
        line.write(bytes, 0, sampleCount * 2)
    }

    override fun drain() = line.drain()

    override fun close() = line.close()
}
