// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.native.concurrent.Worker
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

@InternalFFmpegKmpApi
public actual class DecoderThread actual constructor(name: String) {
    private val worker = Worker.start(name = name)

    public actual fun <T> submit(block: suspend () -> T): Deferred<T> = CompletableDeferred<T>().also { result ->
        worker.executeAfter(0L) { block.startCoroutine(Continuation(EmptyCoroutineContext) { result.completeWith(it) }) }
    }

    // Kotlin/Native cannot interrupt a thread blocked in a host read.
    public actual fun interrupt() = Unit

    public actual fun finish(bound: Duration, block: () -> Unit) {
        val done = submit { block() }
        worker.requestTermination(processScheduledJobs = true)
        runBlocking { withTimeoutOrNull(bound) { done.join() } }
    }
}
