// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import kotlinx.io.Sink
import kotlinx.io.Source

public class CommandIo private constructor(
    internal val inputs: List<Input>,
    internal val outputs: List<Output>,
) {
    internal data class Input(val path: String, val source: Source)
    internal data class Output(val path: String, val sink: Sink)

    public class Builder {
        private val inputs = mutableListOf<Input>()
        private val outputs = mutableListOf<Output>()
        private val paths = mutableSetOf<String>()

        public fun input(path: String, source: Source) {
            requirePath(path)
            require(paths.add(path)) { "I/O path is already mounted: $path" }
            inputs += Input(path, source)
        }

        public fun output(path: String, sink: Sink) {
            requirePath(path)
            require(paths.add(path)) { "I/O path is already mounted: $path" }
            outputs += Output(path, sink)
        }

        internal fun build(): CommandIo = CommandIo(inputs.toList(), outputs.toList())

        private fun requirePath(path: String) {
            require(path.isNotBlank()) { "Mounted I/O path must not be blank" }
            require('\u0000' !in path) { "Mounted I/O path must not contain NUL" }
        }
    }

    public companion object {
        public val Empty: CommandIo = CommandIo(emptyList(), emptyList())

        public operator fun invoke(block: Builder.() -> Unit): CommandIo =
            Builder().apply(block).build()
    }
}
