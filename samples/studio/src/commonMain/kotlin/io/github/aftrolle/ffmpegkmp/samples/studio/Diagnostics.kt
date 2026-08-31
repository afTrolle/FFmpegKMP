// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

/** Logs a diagnostic through the platform's native log (logcat on Android, stdout elsewhere). */
internal expect fun logDiagnostic(tag: String, message: String, throwable: Throwable?)
