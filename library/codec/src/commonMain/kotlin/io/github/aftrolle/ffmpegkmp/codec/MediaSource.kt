// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.core.CommandIo

/**
 * A media input for the decoders and players: a path, a URL, or the path of an input mounted in
 * [io], as for an `ffmpeg -i` argument.
 */
public data class MediaSource(
    val input: String,
    val io: CommandIo = CommandIo.Empty,
    /**
     * Use [ContentProtection.REQUIRE_SECURE_PATH] for DRM content whose decoded pixels must remain
     * in a platform-protected decoder/surface path. Such sources never fall back to Canvas.
     */
    val protection: ContentProtection = ContentProtection.CLEAR_OR_AUTO_DETECT,
) {
    init {
        require(input.isNotBlank()) { "The media input must not be blank" }
        require('\u0000' !in input) { "The media input must not contain NUL" }
    }
}

/** Whether a [MediaSource]'s decoded pixels may leave a platform-protected path. */
public enum class ContentProtection {
    CLEAR_OR_AUTO_DETECT,
    REQUIRE_SECURE_PATH,
}
