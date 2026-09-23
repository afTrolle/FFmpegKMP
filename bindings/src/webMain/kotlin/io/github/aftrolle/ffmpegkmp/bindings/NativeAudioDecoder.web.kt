// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import okio.FileHandle

@InternalFFmpegKmpApi
public actual fun openPlatformAudioDecoder(url: String, sampleRate: Int, channels: Int): NativeAudioDecoder =
    throw NativeBridgeUnavailableException(
        "Audio decoding and playback are not available in the browser yet: the Emscripten worker " +
            "does not export the ffmpegkmp_player API.",
    )

@InternalFFmpegKmpApi
public actual fun openPlatformAudioDecoder(fileHandle: FileHandle, sampleRate: Int, channels: Int): NativeAudioDecoder =
    throw NativeBridgeUnavailableException(
        "Audio decoding and playback are not available in the browser yet: the Emscripten worker " +
            "does not export the ffmpegkmp_player API.",
    )
