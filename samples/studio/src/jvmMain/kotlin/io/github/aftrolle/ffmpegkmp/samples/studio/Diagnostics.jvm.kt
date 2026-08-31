// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

internal actual fun logDiagnostic(tag: String, message: String, throwable: Throwable?) {
    println("[$tag] $message" + (throwable?.let { "\n${it.stackTraceToString()}" } ?: ""))
}
