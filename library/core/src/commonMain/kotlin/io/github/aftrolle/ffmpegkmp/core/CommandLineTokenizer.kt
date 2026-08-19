// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

public object CommandLineTokenizer {
    public fun tokenize(command: String, executableName: String? = null): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var tokenStarted = false

        fun finishToken() {
            if (tokenStarted) {
                result += token.toString()
                token.clear()
                tokenStarted = false
            }
        }

        command.forEachIndexed { index, character ->
            when {
                escaped -> {
                    token.append(character)
                    tokenStarted = true
                    escaped = false
                }
                character == '\\' && quote != '\'' -> {
                    escaped = true
                    tokenStarted = true
                }
                quote != null && character == quote -> quote = null
                quote == null && (character == '\'' || character == '"') -> {
                    quote = character
                    tokenStarted = true
                }
                quote == null && character.isWhitespace() -> finishToken()
                else -> {
                    token.append(character)
                    tokenStarted = true
                }
            }

            if (index == command.lastIndex) finishToken()
        }

        if (escaped) throw CommandParseException("Command ends with an incomplete escape")
        if (quote != null) throw CommandParseException("Command contains an unterminated $quote quote")

        return if (executableName != null && result.firstOrNull()?.substringAfterLast('/') == executableName) {
            result.drop(1)
        } else {
            result
        }
    }
}
