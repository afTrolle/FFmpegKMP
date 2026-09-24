// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.aftrolle.ffmpegkmp.player

import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

internal actual fun createPlatformAudioOutput(format: PcmFormat): PlatformAudioOutput = AudioEngineOutput(format)

internal actual val playbackDispatcher: CoroutineDispatcher = Dispatchers.IO

/**
 * Schedules decoded chunks on an [AVAudioPlayerNode]. A fixed ring of PCM buffers is reused: the
 * semaphore admits a write only once the oldest buffer has been played (or dropped by a flush),
 * so steady-state playback allocates nothing.
 */
private class AudioEngineOutput(private val format: PcmFormat) : PlatformAudioOutput {
    private val engine = AVAudioEngine()
    private val node = AVAudioPlayerNode()
    private val avFormat = requireNotNull(
        AVAudioFormat(standardFormatWithSampleRate = format.sampleRate.toDouble(), channels = format.channels.toUInt()),
    ) { "AVAudioEngine cannot play ${format.channels} channels at ${format.sampleRate} Hz" }
    // intptr_t: 32 bits on watchOS's arm64_32 and armv7k, 64 elsewhere.
    private val slots = dispatch_semaphore_create(QUEUED_BUFFERS.convert())
    private val buffers = arrayOfNulls<AVAudioPCMBuffer>(QUEUED_BUFFERS)
    private var next = 0
    private var largestWrite = 0

    init {
        engine.attachNode(node)
        engine.connect(node, engine.mainMixerNode, avFormat)
        engine.prepare()
    }

    override val latencyFrames: Int get() = QUEUED_BUFFERS * largestWrite

    override fun start() {
        if (!engine.running) check(engine.startAndReturnError(null)) { "AVAudioEngine failed to start" }
        node.play()
    }

    override fun pause() = node.pause()

    override fun flush() {
        val wasPlaying = node.playing
        // Stopping drops scheduled buffers and runs their completion handlers, freeing every slot.
        node.stop()
        if (wasPlaying) node.play()
    }

    override fun write(samples: FloatArray, frames: Int) {
        acquireSlot()
        val slot = next
        next = (next + 1) % QUEUED_BUFFERS
        val buffer = buffers[slot]?.takeIf { it.frameCapacity.toInt() >= frames }
            ?: requireNotNull(AVAudioPCMBuffer(avFormat, frames.toUInt())).also { buffers[slot] = it }
        largestWrite = maxOf(largestWrite, frames)
        buffer.frameLength = frames.toUInt()
        val planes = requireNotNull(buffer.floatChannelData)
        val channels = format.channels
        for (channel in 0 until channels) {
            val plane = requireNotNull(planes[channel])
            var source = channel
            for (frame in 0 until frames) {
                plane[frame] = samples[source]
                source += channels
            }
        }
        node.scheduleBuffer(buffer) { dispatch_semaphore_signal(slots) }
    }

    override fun drain() {
        repeat(QUEUED_BUFFERS) { acquireSlot() }
        repeat(QUEUED_BUFFERS) { dispatch_semaphore_signal(slots) }
    }

    /**
     * Waits for a free buffer. If the engine stops by itself (an audio-session interruption or a
     * route change), queued buffers never complete, so fail instead of blocking forever.
     */
    private fun acquireSlot() {
        while (dispatch_semaphore_wait(slots, dispatch_time(DISPATCH_TIME_NOW, SLOT_WAIT_NANOS)).toLong() != 0L) {
            check(engine.running) { "The audio engine stopped (interrupted or its route changed)" }
        }
    }

    override fun close() {
        node.stop()
        engine.stop()
    }
}

/** With 1,024-frame chunks, about 85 ms of audio queued ahead of the device at 48 kHz. */
private const val QUEUED_BUFFERS = 4
private const val SLOT_WAIT_NANOS = 100_000_000L
