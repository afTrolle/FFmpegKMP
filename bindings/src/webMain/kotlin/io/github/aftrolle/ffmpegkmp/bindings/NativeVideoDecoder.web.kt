// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

@InternalFFmpegKmpApi
public actual fun createPlatformVideoDecoder(
    source: NativePlayerSource,
    output: NativeVideoDecoderOutput,
    decoderPreference: NativePlayerDecoderPreference,
    timeoutMicros: Long,
    surface: Any?,
): NativeVideoDecoder = throw NativeBridgeUnavailableException(
    "Frame-accurate video decoding is not available in the browser yet: the Emscripten worker " +
        "does not export the ffmpegkmp_video_decoder API.",
)
