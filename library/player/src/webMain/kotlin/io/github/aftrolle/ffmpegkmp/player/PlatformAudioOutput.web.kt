// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun createPlatformAudioOutput(format: PcmFormat): PlatformAudioOutput =
    throw UnsupportedOperationException("Audio playback is not available in the browser yet")

internal actual val playbackDispatcher: CoroutineDispatcher = Dispatchers.Default
