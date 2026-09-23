// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun createPlatformAudioOutput(format: PcmFormat): PlatformAudioOutput = AudioTrackOutput(format)

internal actual val playbackDispatcher: CoroutineDispatcher = Dispatchers.IO

/** Streams float PCM straight into an [AudioTrack]; the decoder's format needs no conversion. */
private class AudioTrackOutput(private val format: PcmFormat) : PlatformAudioOutput {
    private val track: AudioTrack

    init {
        val channelMask = when (format.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> throw IllegalArgumentException("Android has no output layout for ${format.channels} channels")
        }
        val minimum = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        require(minimum > 0) { "The device cannot play ${format.sampleRate} Hz float PCM (error $minimum)" }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minimum * 2)
            .build()
    }

    override val latencyFrames: Int get() = track.bufferSizeInFrames

    override fun start() = track.play()

    override fun pause() = track.pause()

    override fun flush() {
        // AudioTrack only discards queued data while paused or stopped.
        val wasPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING
        if (wasPlaying) track.pause()
        track.flush()
        if (wasPlaying) track.play()
    }

    override fun write(samples: FloatArray, frames: Int) {
        var written = 0
        val total = frames * format.channels
        while (written < total) {
            val count = track.write(samples, written, total - written, AudioTrack.WRITE_BLOCKING)
            check(count >= 0) { "AudioTrack write failed (error $count)" }
            if (count == 0) return
            written += count
        }
    }

    override fun drain() {
        // In streaming mode stop() plays out what is queued; play() resumes the track later.
        track.stop()
    }

    override fun close() = track.release()
}
