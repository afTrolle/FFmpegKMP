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
) {
    init {
        require(input.isNotBlank()) { "The media input must not be blank" }
        require('\u0000' !in input) { "The media input must not contain NUL" }
    }
}
