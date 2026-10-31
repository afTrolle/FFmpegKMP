// SPDX-License-Identifier: Apache-2.0
@file:OptIn(InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.filters

import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegCommand
import io.github.aftrolle.ffmpegkmp.ffmpeg.ffmpegNumber
import io.github.aftrolle.ffmpegkmp.ffmpeg.toFilter

public class FilterPad internal constructor(internal val expression: String) {
    override fun toString(): String = expression
}

/** The direction of a `transpose` filter. */
public enum class TransposeDirection(internal val value: Int) {
    COUNTER_CLOCKWISE_AND_FLIP(0),
    CLOCKWISE(1),
    COUNTER_CLOCKWISE(2),
    CLOCKWISE_AND_FLIP(3),
}

/** Which input's length an `amix` output takes. */
public enum class AudioMixDuration(internal val value: String) {
    LONGEST("longest"),
    SHORTEST("shortest"),
    FIRST("first"),
}

/** Whether an `afade` fades in or out. */
public enum class FadeType(internal val value: String) { IN("in"), OUT("out") }

public class FilterGraph private constructor(
    private val nodes: List<String>,
) {
    public fun compile(): String = nodes.joinToString(";")

    public companion object {
        public operator fun invoke(block: Builder.() -> Unit): FilterGraph =
            Builder().apply(block).build()
    }

    public class Builder {
        private val nodes = mutableListOf<String>()
        private val labels = mutableSetOf<String>()
        private var nextLabel = 0

        public fun input(fileIndex: Int, stream: String = "v:0"): FilterPad {
            require(fileIndex >= 0) { "Input index must not be negative" }
            require(stream.isNotBlank()) { "Stream selector must not be blank" }
            return FilterPad("[$fileIndex:$stream]")
        }

        /** Audio track [track] of input [fileIndex], by audio-relative index (`a:N`). */
        public fun audioTrack(fileIndex: Int, track: Int = 0): FilterPad {
            require(track >= 0) { "Audio track index must not be negative" }
            return input(fileIndex, "a:$track")
        }

        public fun trim(input: FilterPad, startSeconds: Double? = null, endSeconds: Double? = null, label: String? = null): FilterPad =
            node(listOf(input), options("trim", "start" to startSeconds, "end" to endSeconds), label)

        public fun audioTrim(input: FilterPad, startSeconds: Double? = null, endSeconds: Double? = null, label: String? = null): FilterPad =
            node(listOf(input), options("atrim", "start" to startSeconds, "end" to endSeconds), label)

        public fun crop(input: FilterPad, width: Int, height: Int, x: Int = 0, y: Int = 0, label: String? = null): FilterPad {
            require(width > 0 && height > 0) { "Crop dimensions must be positive" }
            return node(listOf(input), "crop=$width:$height:$x:$y", label)
        }

        public fun scale(input: FilterPad, width: Int, height: Int, label: String? = null): FilterPad {
            require(width != 0 && height != 0) { "Scale dimensions must not be zero" }
            return node(listOf(input), "scale=$width:$height", label)
        }

        public fun pad(
            input: FilterPad,
            width: Int,
            height: Int,
            x: Int = 0,
            y: Int = 0,
            color: String = "black",
            label: String? = null,
        ): FilterPad = node(listOf(input), "pad=$width:$height:$x:$y:$color", label)

        public fun rotate(input: FilterPad, radiansExpression: String, label: String? = null): FilterPad =
            node(listOf(input), "rotate=$radiansExpression", label)

        public fun transpose(input: FilterPad, direction: TransposeDirection, label: String? = null): FilterPad =
            node(listOf(input), "transpose=${direction.value}", label)

        public fun frameRate(input: FilterPad, framesPerSecond: Double, label: String? = null): FilterPad =
            node(listOf(input), "fps=${framesPerSecond.ffmpegNumber()}", label)

        public fun videoSpeed(input: FilterPad, factor: Double, label: String? = null): FilterPad {
            require(factor > 0.0) { "Video speed must be positive" }
            return node(listOf(input), "setpts=PTS/${factor.ffmpegNumber()}", label)
        }

        public fun volume(input: FilterPad, factor: Double, label: String? = null): FilterPad =
            volume(input, AudioLevel(factor), label)

        /** Applies [level]; a muted level renders `volume=0` so the track keeps its timing. */
        public fun volume(input: FilterPad, level: AudioLevel, label: String? = null): FilterPad =
            node(listOf(input), level.toFilter(), label)

        /**
         * Mixes several audio tracks into one (`amix`), each at its own [AudioLevel]. Unchanged
         * levels skip the extra `volume` node; a single track becomes just its `volume` node.
         * [normalize] is off by default so each input keeps exactly the level it was given, the
         * same sum the player produces live. Enable it to have FFmpeg scale inputs down by their
         * count instead.
         */
        public fun mixAudio(
            tracks: List<Pair<FilterPad, AudioLevel>>,
            duration: AudioMixDuration = AudioMixDuration.LONGEST,
            normalize: Boolean = false,
            label: String? = null,
        ): FilterPad {
            require(tracks.isNotEmpty()) { "An audio mix requires at least one track" }
            tracks.singleOrNull()?.let { (pad, level) -> return volume(pad, level, label) }
            val leveled = tracks.map { (pad, level) ->
                if (level == AudioLevel.Unchanged) pad else volume(pad, level, null)
            }
            val mix = "amix=inputs=${leveled.size}:duration=${duration.value}:normalize=${if (normalize) 1 else 0}"
            return node(leveled, mix, label)
        }

        public fun fade(
            input: FilterPad,
            type: FadeType,
            startSeconds: Double,
            durationSeconds: Double,
            label: String? = null,
        ): FilterPad = node(
            listOf(input),
            "afade=t=${type.value}:st=${startSeconds.ffmpegNumber()}:d=${durationSeconds.ffmpegNumber()}",
            label,
        )

        public fun tempo(input: FilterPad, factor: Double, label: String? = null): FilterPad {
            require(factor in 0.5..2.0) { "A single FFmpeg atempo node supports factors from 0.5 through 2.0" }
            return node(listOf(input), "atempo=${factor.ffmpegNumber()}", label)
        }

        public fun overlay(
            main: FilterPad,
            overlay: FilterPad,
            x: String = "0",
            y: String = "0",
            opacity: Double = 1.0,
            label: String? = null,
        ): FilterPad {
            require(opacity in 0.0..1.0) { "Overlay opacity must be between 0 and 1" }
            val preparedOverlay = if (opacity == 1.0) overlay else {
                node(listOf(overlay), "format=rgba,colorchannelmixer=aa=${opacity.ffmpegNumber()}", null)
            }
            return node(listOf(main, preparedOverlay), "overlay=x=$x:y=$y", label)
        }

        public fun alphaMask(content: FilterPad, alpha: FilterPad, label: String? = null): FilterPad =
            node(listOf(content, alpha), "alphamerge", label)

        public fun lumaMask(base: FilterPad, overlay: FilterPad, mask: FilterPad, label: String? = null): FilterPad =
            node(listOf(base, overlay, mask), "maskedmerge", label)

        public fun raw(inputs: List<FilterPad>, expression: String, label: String? = null): FilterPad {
            require(expression.isNotBlank()) { "Raw filter expression must not be blank" }
            return node(inputs, expression, label)
        }

        internal fun build(): FilterGraph = FilterGraph(nodes.toList())

        private fun node(inputs: List<FilterPad>, filter: String, requestedLabel: String?): FilterPad {
            require(inputs.isNotEmpty()) { "A filter node requires at least one input" }
            val label = requestedLabel ?: "ffk${nextLabel++}"
            require(label.matches(Regex("[A-Za-z0-9_]+"))) { "Invalid filter label: $label" }
            require(labels.add(label)) { "Duplicate filter label: $label" }
            val output = FilterPad("[$label]")
            nodes += inputs.joinToString("") { it.expression } + filter + output.expression
            return output
        }
    }
}

public fun FFmpegCommand.Builder.filterGraph(graph: FilterGraph) {
    complexFilter(graph.compile())
}

private fun options(name: String, vararg values: Pair<String, Double?>): String {
    val rendered = values.mapNotNull { (key, value) -> value?.let { "$key=${it.ffmpegNumber()}" } }
    return if (rendered.isEmpty()) name else "$name=${rendered.joinToString(":")}"
}
