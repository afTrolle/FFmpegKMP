// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.time.Duration
import kotlinx.coroutines.Deferred

/**
 * The one thread a native decoder's calls run on, in order: MediaCodec sessions are bound to the
 * thread that created them, and a call stuck in a host read must not block the caller. Native
 * blocks never suspend, so each runs to its end on the thread. In the browser, which has no
 * threads to give, blocks run on the page's event loop, each once the one before it has ended,
 * and suspend while a worker does the work.
 */
@InternalFFmpegKmpApi
public expect class DecoderThread(name: String) {
    /** Runs [block] after the blocks submitted before it. */
    public fun <T> submit(block: suspend () -> T): Deferred<T>

    /** Wakes the running block from a blocking wait where the platform can: JVM threads are interrupted. */
    public fun interrupt()

    /** Runs [block] last and ends the thread, waiting at most [bound] for it: it still runs after a stuck block. */
    public fun finish(bound: Duration, block: () -> Unit)
}
