// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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

/** How long closing a decoder or writer waits for its thread to run the close queued behind a stuck call. */
@InternalFFmpegKmpApi
public val DECODER_THREAD_CLOSE_BOUND: Duration = 1.seconds

/**
 * Starts a [DecoderThread] called [name] and runs [open] on it to its end, even when the caller is
 * cancelled: what it opens would otherwise leak. When [open] fails, the thread ends without
 * waiting and the failure is rethrown.
 */
@InternalFFmpegKmpApi
public suspend fun <T> openOnDecoderThread(name: String, open: suspend (DecoderThread) -> T): Pair<DecoderThread, T> {
    val thread = DecoderThread(name)
    val opened = try {
        withContext(NonCancellable) { thread.submit { open(thread) }.await() }
    } catch (failure: Throwable) {
        thread.finish(Duration.ZERO) {}
        throw failure
    }
    return thread to opened
}
