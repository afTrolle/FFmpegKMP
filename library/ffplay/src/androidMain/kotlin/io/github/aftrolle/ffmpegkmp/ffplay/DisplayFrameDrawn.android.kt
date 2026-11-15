// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

// Choreographer runs a whole display frame, its callbacks, layout and drawing, as one main-thread message, so a message
// posted from one of its callbacks runs once the frame is drawn. A frame clock on another thread, as in tests, is left to
// refresh in its callback.
internal actual fun displayFrameDrawn(): Deferred<Unit>? {
    if (Looper.myLooper() !== Looper.getMainLooper()) return null
    val drawn = CompletableDeferred<Unit>()
    mainHandler.post { drawn.complete(Unit) }
    return drawn
}
