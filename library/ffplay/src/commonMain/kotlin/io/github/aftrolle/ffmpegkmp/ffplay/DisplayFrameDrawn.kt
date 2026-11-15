// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import kotlinx.coroutines.Deferred

/**
 * Called from a display frame's callback of the composition's frame clock: completes once that frame
 * is drawn, so that a frame shown after it is first drawn by the next one. Null where that is not
 * known, and a Canvas output then refreshes its player in the callback, on the drawing thread.
 */
internal expect fun displayFrameDrawn(): Deferred<Unit>?
