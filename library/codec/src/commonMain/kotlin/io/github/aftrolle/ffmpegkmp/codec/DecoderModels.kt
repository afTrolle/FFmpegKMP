// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderFallback
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderKind
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderPreference
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi

/**
 * Which codec implementation to open: a [CodecPreference] lets the library choose among the ones
 * it knows, and a [NamedCodec] names exactly one, in FFmpeg's own terms.
 */
public sealed interface CodecSelection

/**
 * Which video decoder or encoder to open. [AUTO] prefers the platform's hardware codec and falls
 * back to a software one: FFmpeg's decoders, or an encoder where the build has one for the codec
 * (the default LGPL builds have none for H.264 or HEVC, since libx264 and libx265 are GPL).
 * [REQUIRE_HARDWARE] and [SOFTWARE] take only their kind.
 */
public enum class CodecPreference : CodecSelection { AUTO, REQUIRE_HARDWARE, SOFTWARE }

/**
 * FFmpeg's own names: the codec implementation to open, as `ffmpeg -c:v` takes it, and the
 * AVOptions to open it with, as `ffmpeg` takes them after it. Exactly that implementation opens,
 * with no other tried in its place, or the open fails saying why: an unknown [name], one for
 * another codec, an option it does not recognise or a value it does not take.
 *
 * A MediaCodec component is chosen with the MediaCodec encoders' `codec_name` option:
 * `NamedCodec("h264_mediacodec", mapOf("codec_name" to "c2.android.avc.encoder"))` is Android's
 * own software AVC encoder. libaom's speed is its own options:
 * `NamedCodec("libaom-av1", mapOf("usage" to "realtime", "cpu-used" to "8"))`. Trying several in
 * turn is the caller's choice, with `MediaWriter.canEncode` to ask first.
 *
 * It keeps a copy of [options], so a map the caller changes later does not change it; two are
 * equal when their names and options are.
 */
public class NamedCodec(
    public val name: String,
    options: Map<String, String> = emptyMap(),
) : CodecSelection {
    /** A copy of the options passed in, which later changes to that map do not reach. */
    public val options: Map<String, String> = options.toMap()

    init {
        require(name.isNotBlank()) { "The codec name must not be blank: '$name'" }
        require('\u0000' !in name) { "The codec name must not hold a NUL character: '$name'" }
        require(this.options.keys.none(String::isEmpty)) { "Option names must not be empty: ${this.options}" }
        require(this.options.all { (key, value) -> '\u0000' !in key && '\u0000' !in value }) {
            "Options must not hold NUL characters: ${this.options}"
        }
    }

    override fun equals(other: Any?): Boolean = other is NamedCodec && name == other.name && options == other.options

    override fun hashCode(): Int = 31 * name.hashCode() + options.hashCode()

    override fun toString(): String = "NamedCodec(name=$name, options=$options)"
}

/** The video decoder that actually took a source; [UNKNOWN] before one has opened. */
public enum class DecoderKind { UNKNOWN, HARDWARE, SOFTWARE }

/** Why a video that was to be decoded in hardware, under [CodecPreference.AUTO], is decoded in software. */
public enum class DecoderFallbackReason {
    /** The platform has no hardware decoder for the stream. */
    UNSUPPORTED,

    /** The hardware decoder failed to open or to decode; the source stays in software. */
    FAILED,

    /**
     * Every hardware decoder [DecoderBudget] allows was open when this one opened. A player asks
     * the budget again the next time it opens a decoder (after its idle release, or for a new
     * output), except on an Android Surface its software frames were drawn on, which MediaCodec can
     * no longer take: there it stays in software until the Surface changes.
     */
    BUDGET,
}

/**
 * How a software video decoder spreads its work over threads: FFmpeg's `thread_type`. Hardware
 * decoders and libaom take neither.
 */
public enum class DecoderThreadType {
    /**
     * Frame threading: each thread decodes a frame of its own, so several are in progress at once.
     * It speeds up every codec FFmpeg has it for (H.264, HEVC, VP9, AV1's native decoder and more)
     * however the stream was written, and costs memory and latency: each frame thread holds one
     * decoded picture (about 24 MB for 4K 10-bit video), and the decoder reads a packet further
     * ahead per thread before a frame comes out, also after each seek.
     */
    FRAME,

    /**
     * Slice threading: the threads share one frame, so one frame is in progress. They split what
     * the stream lets them: HEVC with wavefront rows, x265's default, keeps most of its speed,
     * while H.264 written as one slice a frame decodes as if on one thread.
     */
    SLICE,
}

/**
 * How many threads a software video decoder decodes with, and how: FFmpeg's two knobs,
 * `thread_count` and `thread_type`. The frames and their timestamps are the same for every choice.
 *
 * The count is a video's budget for every thread working on its frames: converting them, on any
 * decoder, takes at most as many. With [DecoderThreadType.FRAME] the decoder holds one decoded
 * picture per thread, so memory grows with the count; with [DecoderThreadType.SLICE] alone it holds
 * one frame in progress whatever the count. Where both are allowed FFmpeg takes frame threading
 * for codecs that have it, and slice threading for the rest.
 */
public sealed interface DecoderThreads {
    /**
     * FFmpeg's own choice, as `ffmpeg -threads 0`: one thread more than the CPU cores, at most 16,
     * of frame threads where the codec has them and slice threads elsewhere. Fastest, and with
     * frame threads it holds a decoded picture per thread: give a [Fixed] count, or
     * `setOf(DecoderThreadType.SLICE)`, to bound memory. The browser caps the count at 8, its
     * worker's thread pool.
     */
    public data object Auto : DecoderThreads

    /**
     * Exactly [count] threads of the [types] allowed, as `ffmpeg -threads count` with the codec
     * context's `thread_type` set. `Fixed(1)` decodes one frame at a time, on no extra thread.
     * The default allows both, as `ffmpeg` and `ffplay` do; `types = setOf(DecoderThreadType.SLICE)`
     * keeps one frame in progress, which bounds memory to one picture whatever the count.
     */
    public data class Fixed(
        val count: Int,
        val types: Set<DecoderThreadType> = setOf(DecoderThreadType.FRAME, DecoderThreadType.SLICE),
    ) : DecoderThreads {
        init {
            require(count > 0) { "The decoder thread count must be positive: $count" }
            require(types.isNotEmpty()) { "At least one decoder thread type must be allowed" }
        }
    }
}

/** The preference as the bindings take it. */
@InternalFFmpegKmpApi
public fun CodecPreference.toNative(): NativePlayerDecoderPreference = when (this) {
    CodecPreference.AUTO -> NativePlayerDecoderPreference.AUTO
    CodecPreference.REQUIRE_HARDWARE -> NativePlayerDecoderPreference.REQUIRE_HARDWARE
    CodecPreference.SOFTWARE -> NativePlayerDecoderPreference.SOFTWARE
}

/** `decoder_threads` in the bridge: 0 is FFmpeg's automatic count. */
@InternalFFmpegKmpApi
public fun DecoderThreads.toNative(): Int = when (this) {
    DecoderThreads.Auto -> 0
    is DecoderThreads.Fixed -> count
}

/**
 * The codec context's `thread_type` as the bridge takes it, FF_THREAD_FRAME (1) and FF_THREAD_SLICE
 * (2) bits; 0 for [DecoderThreads.Auto], FFmpeg's default of both.
 */
@InternalFFmpegKmpApi
public fun DecoderThreads.toNativeThreadType(): Int = when (this) {
    DecoderThreads.Auto -> 0
    is DecoderThreads.Fixed -> types.fold(0) { bits, type ->
        bits or when (type) {
            DecoderThreadType.FRAME -> 1
            DecoderThreadType.SLICE -> 2
        }
    }
}

/** The fallback reason the bindings report, as the public API names it; null for none. */
@InternalFFmpegKmpApi
public fun NativePlayerDecoderFallback.toPublic(): DecoderFallbackReason? = when (this) {
    NativePlayerDecoderFallback.NONE -> null
    NativePlayerDecoderFallback.UNSUPPORTED -> DecoderFallbackReason.UNSUPPORTED
    NativePlayerDecoderFallback.FAILED -> DecoderFallbackReason.FAILED
    NativePlayerDecoderFallback.BUDGET -> DecoderFallbackReason.BUDGET
}

/** The decoder kind the bindings report, as the public API names it. */
@InternalFFmpegKmpApi
public fun NativePlayerDecoderKind.toPublic(): DecoderKind = when (this) {
    NativePlayerDecoderKind.HARDWARE -> DecoderKind.HARDWARE
    NativePlayerDecoderKind.SOFTWARE -> DecoderKind.SOFTWARE
    NativePlayerDecoderKind.UNKNOWN -> DecoderKind.UNKNOWN
}
