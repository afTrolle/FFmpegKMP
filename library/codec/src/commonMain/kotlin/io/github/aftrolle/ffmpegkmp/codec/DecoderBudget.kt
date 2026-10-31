// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.nativeDecoderBudget
import io.github.aftrolle.ffmpegkmp.bindings.setNativeDecoderBudget

/**
 * How many hardware video decoders the process keeps open at once, shared by every [VideoDecoder]
 * and every `FFplayPlayer`.
 *
 * A hardware decoder takes a place when it opens and gives it back when it closes: a
 * [VideoDecoder] at [VideoDecoder.close], a player once it has been idle for its
 * `releaseDecoderWhenPaused` grace, or when it stops or closes. A decoder that finds every place
 * taken does not wait: under [CodecPreference.AUTO] it decodes in software and reports
 * [DecoderFallbackReason.BUDGET], under [CodecPreference.REQUIRE_HARDWARE] it fails.
 *
 * Hardware decoders are few and do not degrade gracefully: past what the device sustains, a
 * platform either refuses a new one (a Galaxy S25 Ultra opens eight 4K MediaCodec decoders and
 * fails the ninth) or gives every open one a share too small to play (VideoToolbox plays fourteen
 * 4K H.264 streams at full rate on an M4 Pro and sixteen at under 5 frames a second each). Past
 * the limit software decoders take the rest, on the CPU.
 */
public object DecoderBudget {
    /**
     * The most hardware decoders open at once, 0 for no limit. Defaults to 8 on macOS and other
     * desktops, 4 on Android and iOS. Setting it closes nothing already open; the next decoders
     * see it. The browser keeps the value and applies it to nothing: its player decodes in
     * software, and WebCodecs manages its own decoders.
     */
    public var hardwareDecoders: Int
        get() = nativeDecoderBudget().hardwareDecoders
        set(value) {
            require(value >= 0) { "The hardware decoder budget must not be negative: $value" }
            setNativeDecoderBudget(value)
        }

    /** How many hardware decoders are open now, by players and video decoders together; 0 in the browser. */
    public val hardwareDecodersInUse: Int
        get() = nativeDecoderBudget().inUse
}
