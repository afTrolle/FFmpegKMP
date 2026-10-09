// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderKind
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderPreference
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi

/**
 * Which video decoder to open. [AUTO] prefers the platform's hardware decoder and falls back to
 * FFmpeg's software one; [REQUIRE_HARDWARE] fails where no hardware decoder takes the source.
 */
public enum class DecoderPreference { AUTO, REQUIRE_HARDWARE, SOFTWARE }

/** The video decoder that actually took a source; [UNKNOWN] before one has opened. */
public enum class DecoderKind { UNKNOWN, HARDWARE, SOFTWARE }

/**
 * How many threads a software video decoder decodes with. The frames and their timestamps are the
 * same for every count.
 *
 * A [VideoDecoder] decodes each video with one decoder and one frame in progress, on FFmpeg's slice
 * threads, and the count is the budget for every thread working on its frames: converting them, on
 * any decoder, takes at most as many. Slice threads split what the stream lets them: HEVC with
 * wavefront rows, x265's default, keeps most of its speed, while H.264 written as one slice a frame
 * decodes as if on one thread. They make no difference to hardware decoders or libaom.
 *
 * Playback keeps FFmpeg's frame threading as well, as `ffplay` does, since it shows one video in
 * real time. Frame threading decodes several frames at once: the decoder reads a few packets
 * further ahead before a frame comes out, and keeps about one frame in flight per thread, roughly
 * 24 MB each for 4K 10-bit video.
 */
public sealed interface DecoderThreads {
    /** FFmpeg's automatic count, one more than the CPU cores, capped at 8 threads. */
    public data object Auto : DecoderThreads

    /** Exactly [count] threads. `Fixed(1)` decodes one frame at a time, on no extra thread. */
    public data class Fixed(val count: Int) : DecoderThreads {
        init {
            require(count > 0) { "The decoder thread count must be positive: $count" }
        }
    }
}

/** The preference as the bindings take it. */
@InternalFFmpegKmpApi
public fun DecoderPreference.toNative(): NativePlayerDecoderPreference = when (this) {
    DecoderPreference.AUTO -> NativePlayerDecoderPreference.AUTO
    DecoderPreference.REQUIRE_HARDWARE -> NativePlayerDecoderPreference.REQUIRE_HARDWARE
    DecoderPreference.SOFTWARE -> NativePlayerDecoderPreference.SOFTWARE
}

/** `decoder_threads` in the bridge: 0 is FFmpeg's automatic count, capped there. */
@InternalFFmpegKmpApi
public fun DecoderThreads.toNative(): Int = when (this) {
    DecoderThreads.Auto -> 0
    is DecoderThreads.Fixed -> count
}

/** The decoder kind the bindings report, as the public API names it. */
@InternalFFmpegKmpApi
public fun NativePlayerDecoderKind.toPublic(): DecoderKind = when (this) {
    NativePlayerDecoderKind.HARDWARE -> DecoderKind.HARDWARE
    NativePlayerDecoderKind.SOFTWARE -> DecoderKind.SOFTWARE
    NativePlayerDecoderKind.UNKNOWN -> DecoderKind.UNKNOWN
}
