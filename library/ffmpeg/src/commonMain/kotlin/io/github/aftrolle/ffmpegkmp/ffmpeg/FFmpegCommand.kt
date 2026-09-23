// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffmpeg

import io.github.aftrolle.ffmpegkmp.core.AudioLevel

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

        /**
         * Selects audio from input [input] for the next output. [track] is the audio-relative
         * index (`0` is the input's first audio track, whatever its absolute stream index);
         * `null` selects every audio track. An [optional] mapping is skipped instead of failing
         * when the input has no such track.
         */
        public fun mapAudio(input: Int = 0, track: Int? = null, optional: Boolean = false) {
            require(input >= 0) { "Input index must not be negative" }
            require(track == null || track >= 0) { "Audio track index must not be negative" }
            map("$input:a" + (track?.let { ":$it" } ?: "") + if (optional) "?" else "")
        }

        /** Drops all audio from the next output (`-an`). */
        public fun disableAudio() {
            flag("-an")
        }

        /**
         * Applies [level] to audio in the next output. [outputTrack] targets one output audio
         * track (after mapping) so each track can carry its own volume or be muted while still
         * present; `null` applies it to every output audio track. This sets the stream's filter
         * chain, so don't also pass [audioFilter] for the same track: fold the volume into that
         * chain with [AudioLevel.toFilter] instead.
         */
        public fun audioLevel(level: AudioLevel, outputTrack: Int? = null) {
            option(audioOption("-filter", outputTrack), level.toFilter())
        }

        /** Audio bitrate such as `128k`, for one output audio track or (`null`) all of them. */
        public fun audioBitrate(bitrate: String, outputTrack: Int? = null) {
            require(bitrate.isNotBlank()) { "Audio bitrate must not be blank" }
            option(audioOption("-b", outputTrack), bitrate)
        }

        public fun audioSampleRate(hertz: Int, outputTrack: Int? = null) {
            require(hertz > 0) { "Audio sample rate must be positive" }
            option(audioOption("-ar", outputTrack), hertz.toString())
        }

        public fun audioChannels(count: Int, outputTrack: Int? = null) {
            require(count > 0) { "Audio channel count must be positive" }
            option(audioOption("-ac", outputTrack), count.toString())
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

        /** `-opt:a` for every output audio track, or `-opt:a:N` for audio track [outputTrack]. */
        private fun audioOption(option: String, outputTrack: Int?): String {
            require(outputTrack == null || outputTrack >= 0) { "Audio track index must not be negative" }
            return "$option:a" + (outputTrack?.let { ":$it" } ?: "")
        }

        private fun specified(option: String, streamSpecifier: String?): String =
            if (streamSpecifier.isNullOrBlank()) option else "$option:$streamSpecifier"
    }

    public companion object {
        public operator fun invoke(block: Builder.() -> Unit): FFmpegCommand =
            Builder().apply(block).build()

        public fun arguments(arguments: List<String>): FFmpegCommand = FFmpegCommand(arguments.toList())
    }
}

/** The FFmpeg `volume` filter that applies this level; muted levels render as `volume=0`. */
public fun AudioLevel.toFilter(): String = "volume=${effectiveVolume.ffmpegNumber()}"

private fun Double.ffmpegNumber(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()
