// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.completeWith

internal actual class VideoDecoderThread actual constructor() {
    @Volatile
    private var thread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "FFmpegKMP VideoDecoder").apply { isDaemon = true }.also { thread = it }
    }

    actual fun <T> submit(block: () -> T): Deferred<T> = CompletableDeferred<T>().also { result ->
        executor.execute { result.completeWith(runCatching(block)) }
    }

    // Okio's blocking reads (a Pipe's, a socket's) end with an InterruptedIOException; the
    // executor clears the interrupt before its next task.
    actual fun interrupt() {
        thread?.interrupt()
    }

    actual fun finish(bound: Duration, block: () -> Unit) {
        if (Thread.currentThread() === thread) {
            executor.shutdown()
            return block()
        }
        val done = executor.submit(block)
        executor.shutdown()
        try {
            done.get(bound.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // Still queued behind a stuck call; it closes the decoder once that returns.
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }
}
