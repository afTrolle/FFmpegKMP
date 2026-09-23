// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.player

import kotlinx.coroutines.CoroutineDispatcher

/** A platform audio sink fed from the playback thread. Only [close] may come from elsewhere. */
internal interface PlatformAudioOutput : AutoCloseable {
    /** Frames the device may still hold after [write] returns: the reported position lags by this. */
    val latencyFrames: Int

    fun start()
    fun pause()

    /** Discards queued audio (after a seek). */
    fun flush()

    /** Blocks until there is room, then queues [frames] interleaved frames of [samples]. */
    fun write(samples: FloatArray, frames: Int)

    /** Blocks until everything queued has played. */
    fun drain()
}

internal expect fun createPlatformAudioOutput(format: PcmFormat): PlatformAudioOutput

/** Where the playback loop runs; the loop blocks in [PlatformAudioOutput.write]. */
internal expect val playbackDispatcher: CoroutineDispatcher
