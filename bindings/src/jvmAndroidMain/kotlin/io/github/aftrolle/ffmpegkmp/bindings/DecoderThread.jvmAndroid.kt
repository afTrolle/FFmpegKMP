// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.completeWith

@InternalFFmpegKmpApi
public actual class DecoderThread actual constructor(name: String) {
    @Volatile
    private var thread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, name).apply { isDaemon = true }.also { thread = it }
    }

    public actual fun <T> submit(block: suspend () -> T): Deferred<T> = CompletableDeferred<T>().also { result ->
        executor.execute { block.startCoroutine(Continuation(EmptyCoroutineContext) { result.completeWith(it) }) }
    }

    // Okio's blocking reads (a Pipe's, a socket's) end with an InterruptedIOException; the
    // executor clears the interrupt before its next task.
    public actual fun interrupt() {
        thread?.interrupt()
    }

    public actual fun finish(bound: Duration, block: () -> Unit) {
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
