// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffmpeg

public class FFmpegCommand private constructor(
    public val arguments: List<String>,
) {
    public class Builder {
        private val arguments = mutableListOf<String>()

        public fun overwrite(enabled: Boolean = true) {
            argument(if (enabled) "-y" else "-n")
        }

        public fun input(path: String, format: String? = null) {
            format?.let { option("-f", it) }
            option("-i", path)
        }

        public fun output(path: String, format: String? = null) {
            format?.let { option("-f", it) }
            argument(path)
        }

        public fun videoCodec(codec: String, streamSpecifier: String? = null) {
            option(specified("-c:v", streamSpecifier), codec)
        }

        public fun audioCodec(codec: String, streamSpecifier: String? = null) {
            option(specified("-c:a", streamSpecifier), codec)
        }

        public fun codec(codec: String, streamSpecifier: String? = null) {
            option(specified("-c", streamSpecifier), codec)
        }

        public fun map(stream: String) {
            option("-map", stream)
        }

        public fun metadata(key: String, value: String, streamSpecifier: String? = null) {
            require(key.isNotBlank()) { "Metadata key must not be blank" }
            option(specified("-metadata", streamSpecifier), "$key=$value")
        }

        public fun videoFilter(filter: String) {
            option("-vf", filter)
        }

        public fun audioFilter(filter: String) {
            option("-af", filter)
        }

        public fun complexFilter(filter: String) {
            option("-filter_complex", filter)
        }

        public fun option(name: String, value: String) {
            require(name.startsWith('-')) { "FFmpeg option must start with '-': $name" }
            argument(name)
            argument(value)
        }

        public fun flag(name: String) {
            require(name.startsWith('-')) { "FFmpeg flag must start with '-': $name" }
            argument(name)
        }

        public fun argument(value: String) {
            require('\u0000' !in value) { "FFmpeg arguments must not contain NUL" }
            arguments += value
        }

        public fun arguments(vararg values: String) {
            values.forEach(::argument)
        }

        internal fun build(): FFmpegCommand = FFmpegCommand(arguments.toList())

        private fun specified(option: String, streamSpecifier: String?): String =
            if (streamSpecifier.isNullOrBlank()) option else "$option:$streamSpecifier"
    }

    public companion object {
        public operator fun invoke(block: Builder.() -> Unit): FFmpegCommand =
            Builder().apply(block).build()

        public fun arguments(arguments: List<String>): FFmpegCommand = FFmpegCommand(arguments.toList())
    }
}
