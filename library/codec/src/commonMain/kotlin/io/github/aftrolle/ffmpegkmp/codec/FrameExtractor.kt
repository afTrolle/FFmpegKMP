// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.ExperimentalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Extracts frames as [VideoDecoder.extractFrame] does, one at a time, through one decoder it keeps between them and
 * [VideoDecoder.reopen]s for each source: posters for a list of videos open one decoder, and while the videos are coded
 * alike, one codec, instead of a decoder, a thread and a MediaCodec session each. Calls from several coroutines queue
 * in order.
 *
 * The decoder is closed after a failed attempt, before a software retry, and once no call has come for [idle]; [close]
 * closes it too, and fails the calls after it. Its frames are the caller's, as [VideoDecoder.extractFrame]'s are.
 *
 * The browser does not extract frames yet: there each call throws [UnsupportedOperationException].
 */
@ExperimentalFFmpegKmpApi
public class FrameExtractor(private val idle: Duration = 2.seconds) : AutoCloseable {
    private val calls = Mutex()
    private val releases = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var release: Job? = null
    private val decoders = KeptDecoder()

    @Volatile
    private var closed = false

    init {
        require(!idle.isNegative()) { "The idle time must not be negative: $idle" }
    }

    /** [VideoDecoder.extractFrame]'s frame, from the decoder this extractor keeps. */
    public suspend fun extractFrame(
        source: MediaSource,
        position: Duration = Duration.ZERO,
        size: FrameSize? = null,
        scale: FrameScale = FrameScale.FIT,
        decoder: CodecSelection = CodecPreference.AUTO,
        timeout: Duration = 10.seconds,
    ): VideoFrame = extractFrameIn(source, position, size, scale, decoder, timeout) { FrameFormat.Rgba8 }

    /** [extractFrame] in the format [format] picks for the source once it is open, as `VideoDecoder.extractFrameIn` takes it. */
    @InternalFFmpegKmpApi
    public suspend fun extractFrameIn(
        source: MediaSource,
        position: Duration,
        size: FrameSize?,
        scale: FrameScale,
        decoder: CodecSelection,
        timeout: Duration,
        format: (VideoInfo) -> FrameFormat,
    ): VideoFrame = calls.withLock {
        check(!closed) { "The frame extractor is closed" }
        release?.cancel()
        try {
            extractUprightFrame(source, position, size, scale, decoder, timeout, format, decoders)
        } finally {
            if (closed) {
                withContext(NonCancellable + Dispatchers.Default) { decoders.drop() }
            } else {
                // Cancelled only under the lock, so never once it holds it.
                release = releases.launch {
                    delay(idle)
                    calls.withLock { decoders.drop() }
                }
            }
        }
    }

    override fun close() {
        closed = true
        if (calls.tryLock()) {
            try {
                dropClosed()
            } finally {
                calls.unlock()
            }
        } else {
            // A call or the idle release holds the lock, and a call may have read `closed` before it was set and
            // left the decoder to an idle release: this drops it once the lock is free, whatever its holder did.
            releases.launch { calls.withLock { dropClosed() } }
        }
    }

    /** Under the lock, once closed: no call opens a decoder after it. */
    private fun dropClosed() {
        release?.cancel()
        decoders.drop()
    }
}

/** The one decoder of a [FrameExtractor], kept between its extractions for the selection it was opened with. */
private class KeptDecoder : ExtractionDecoders {
    private var decoder: VideoDecoder? = null
    private var selection: CodecSelection? = null
    private var opening: CodecSelection? = null

    override suspend fun open(source: MediaSource, output: VideoOutput.Memory, selection: CodecSelection, timeout: Duration): VideoDecoder {
        val kept = decoder
        decoder = null
        opening = selection
        return when {
            kept == null -> VideoDecoder.open(source, output, selection, timeout)
            this.selection == selection -> kept.reopen(source, output, timeout)
            else -> {
                withContext(NonCancellable + Dispatchers.Default) { kept.close() }
                VideoDecoder.open(source, output, selection, timeout)
            }
        }
    }

    override fun finish(decoder: VideoDecoder, failed: Boolean) {
        if (failed) {
            decoder.close()
        } else {
            this.decoder = decoder
            selection = opening
        }
    }

    fun drop() {
        decoder?.close()
        decoder = null
    }
}
