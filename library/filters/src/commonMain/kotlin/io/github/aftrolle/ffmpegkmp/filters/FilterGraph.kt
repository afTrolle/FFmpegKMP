// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.filters

import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegCommand

public class FilterPad internal constructor(internal val expression: String) {
    override fun toString(): String = expression
}

public sealed interface FilterOperation {
    public fun render(): String

    public data class Trim(val startSeconds: Double?, val endSeconds: Double?) : FilterOperation {
        override fun render(): String = options("trim", "start" to startSeconds, "end" to endSeconds)
    }

    public data class AudioTrim(val startSeconds: Double?, val endSeconds: Double?) : FilterOperation {
        override fun render(): String = options("atrim", "start" to startSeconds, "end" to endSeconds)
    }

    public data class Crop(val width: Int, val height: Int, val x: Int, val y: Int) : FilterOperation {
        override fun render(): String = "crop=$width:$height:$x:$y"
    }

    public data class Scale(val width: Int, val height: Int) : FilterOperation {
        override fun render(): String = "scale=$width:$height"
    }

    public data class Pad(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val color: String,
    ) : FilterOperation {
        override fun render(): String = "pad=$width:$height:$x:$y:$color"
    }

    public data class Rotate(val radiansExpression: String) : FilterOperation {
        override fun render(): String = "rotate=$radiansExpression"
    }

    public data class Transpose(val direction: Direction) : FilterOperation {
        public enum class Direction(internal val value: Int) {
            COUNTER_CLOCKWISE_AND_FLIP(0),
            CLOCKWISE(1),
            COUNTER_CLOCKWISE(2),
            CLOCKWISE_AND_FLIP(3),
        }

        override fun render(): String = "transpose=${direction.value}"
    }

    public data class FrameRate(val framesPerSecond: Double) : FilterOperation {
        override fun render(): String = "fps=${framesPerSecond.ffmpegNumber()}"
    }

    public data class VideoSpeed(val factor: Double) : FilterOperation {
        override fun render(): String = "setpts=PTS/${factor.ffmpegNumber()}"
    }

    public data class Volume(val factor: Double) : FilterOperation {
        override fun render(): String = "volume=${factor.ffmpegNumber()}"
    }

    public data class Fade(
        val type: Type,
        val startSeconds: Double,
        val durationSeconds: Double,
    ) : FilterOperation {
        public enum class Type(internal val value: String) { IN("in"), OUT("out") }
        override fun render(): String =
            "afade=t=${type.value}:st=${startSeconds.ffmpegNumber()}:d=${durationSeconds.ffmpegNumber()}"
    }

    public data class Tempo(val factor: Double) : FilterOperation {
        override fun render(): String = "atempo=${factor.ffmpegNumber()}"
    }

    public data class Opacity(val alpha: Double) : FilterOperation {
        override fun render(): String = "format=rgba,colorchannelmixer=aa=${alpha.ffmpegNumber()}"
    }

    public data class Overlay(val x: String, val y: String) : FilterOperation {
        override fun render(): String = "overlay=x=$x:y=$y"
    }

    public data object AlphaMask : FilterOperation {
        override fun render(): String = "alphamerge"
    }

    public data object LumaMask : FilterOperation {
        override fun render(): String = "maskedmerge"
    }

    public data class Raw(val expression: String) : FilterOperation {
        override fun render(): String = expression
    }
}

public data class FilterNode(
    val inputs: List<FilterPad>,
    val operation: FilterOperation,
    val output: FilterPad,
)

public class FilterGraph private constructor(
    public val nodes: List<FilterNode>,
) {
    public fun compile(): String = nodes.joinToString(";") { node ->
        node.inputs.joinToString("") { it.expression } + node.operation.render() + node.output.expression
    }

    public companion object {
        public operator fun invoke(block: Builder.() -> Unit): FilterGraph =
            Builder().apply(block).build()
    }

    public class Builder {
        private val nodes = mutableListOf<FilterNode>()
        private val labels = mutableSetOf<String>()
        private var nextLabel = 0

        public fun input(fileIndex: Int, stream: String = "v:0"): FilterPad {
            require(fileIndex >= 0) { "Input index must not be negative" }
            require(stream.isNotBlank()) { "Stream selector must not be blank" }
            return FilterPad("[$fileIndex:$stream]")
        }

        public fun trim(input: FilterPad, startSeconds: Double? = null, endSeconds: Double? = null, label: String? = null): FilterPad =
            node(listOf(input), FilterOperation.Trim(startSeconds, endSeconds), label)

        public fun audioTrim(input: FilterPad, startSeconds: Double? = null, endSeconds: Double? = null, label: String? = null): FilterPad =
            node(listOf(input), FilterOperation.AudioTrim(startSeconds, endSeconds), label)

        public fun crop(input: FilterPad, width: Int, height: Int, x: Int = 0, y: Int = 0, label: String? = null): FilterPad {
            require(width > 0 && height > 0) { "Crop dimensions must be positive" }
            return node(listOf(input), FilterOperation.Crop(width, height, x, y), label)
        }

        public fun scale(input: FilterPad, width: Int, height: Int, label: String? = null): FilterPad {
            require(width != 0 && height != 0) { "Scale dimensions must not be zero" }
            return node(listOf(input), FilterOperation.Scale(width, height), label)
        }

        public fun pad(
            input: FilterPad,
            width: Int,
            height: Int,
            x: Int = 0,
            y: Int = 0,
            color: String = "black",
            label: String? = null,
        ): FilterPad = node(listOf(input), FilterOperation.Pad(width, height, x, y, color), label)

        public fun rotate(input: FilterPad, radiansExpression: String, label: String? = null): FilterPad =
            node(listOf(input), FilterOperation.Rotate(radiansExpression), label)

        public fun transpose(input: FilterPad, direction: FilterOperation.Transpose.Direction, label: String? = null): FilterPad =
            node(listOf(input), FilterOperation.Transpose(direction), label)

        public fun frameRate(input: FilterPad, framesPerSecond: Double, label: String? = null): FilterPad =
            node(listOf(input), FilterOperation.FrameRate(framesPerSecond), label)

        public fun videoSpeed(input: FilterPad, factor: Double, label: String? = null): FilterPad {
            require(factor > 0.0) { "Video speed must be positive" }
            return node(listOf(input), FilterOperation.VideoSpeed(factor), label)
        }

        public fun volume(input: FilterPad, factor: Double, label: String? = null): FilterPad =
            node(listOf(input), FilterOperation.Volume(factor), label)

        public fun fade(
            input: FilterPad,
            type: FilterOperation.Fade.Type,
            startSeconds: Double,
            durationSeconds: Double,
            label: String? = null,
        ): FilterPad = node(listOf(input), FilterOperation.Fade(type, startSeconds, durationSeconds), label)

        public fun tempo(input: FilterPad, factor: Double, label: String? = null): FilterPad {
            require(factor in 0.5..2.0) { "A single FFmpeg atempo node supports factors from 0.5 through 2.0" }
            return node(listOf(input), FilterOperation.Tempo(factor), label)
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
                node(listOf(overlay), FilterOperation.Opacity(opacity), null)
            }
            return node(listOf(main, preparedOverlay), FilterOperation.Overlay(x, y), label)
        }

        public fun alphaMask(content: FilterPad, alpha: FilterPad, label: String? = null): FilterPad =
            node(listOf(content, alpha), FilterOperation.AlphaMask, label)

        public fun lumaMask(base: FilterPad, overlay: FilterPad, mask: FilterPad, label: String? = null): FilterPad =
            node(listOf(base, overlay, mask), FilterOperation.LumaMask, label)

        public fun raw(inputs: List<FilterPad>, expression: String, label: String? = null): FilterPad {
            require(expression.isNotBlank()) { "Raw filter expression must not be blank" }
            return node(inputs, FilterOperation.Raw(expression), label)
        }

        internal fun build(): FilterGraph = FilterGraph(nodes.toList())

        private fun node(inputs: List<FilterPad>, operation: FilterOperation, requestedLabel: String?): FilterPad {
            require(inputs.isNotEmpty()) { "A filter node requires at least one input" }
            val label = requestedLabel ?: "ffk${nextLabel++}"
            require(label.matches(Regex("[A-Za-z0-9_]+"))) { "Invalid filter label: $label" }
            require(labels.add(label)) { "Duplicate filter label: $label" }
            val output = FilterPad("[$label]")
            nodes += FilterNode(inputs, operation, output)
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

private fun Double.ffmpegNumber(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()
