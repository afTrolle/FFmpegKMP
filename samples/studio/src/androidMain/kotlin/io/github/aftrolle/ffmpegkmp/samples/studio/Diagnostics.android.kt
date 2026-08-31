// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import android.util.Log

internal actual fun logDiagnostic(tag: String, message: String, throwable: Throwable?) {
    Log.e(tag, message, throwable)
}
