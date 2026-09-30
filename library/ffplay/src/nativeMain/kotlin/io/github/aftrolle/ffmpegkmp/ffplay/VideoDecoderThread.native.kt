// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import kotlin.native.concurrent.Worker
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal actual class VideoDecoderThread actual constructor() {
    private val worker = Worker.start(name = "FFmpegKMP VideoDecoder")

    actual fun <T> submit(block: () -> T): Deferred<T> = CompletableDeferred<T>().also { result ->
        worker.executeAfter(0L) { result.completeWith(runCatching(block)) }
    }

    // Kotlin/Native cannot interrupt a thread blocked in a host read.
    actual fun interrupt() = Unit

    actual fun finish(bound: Duration, block: () -> Unit) {
        val done = submit(block)
        worker.requestTermination(processScheduledJobs = true)
        runBlocking { withTimeoutOrNull(bound) { done.join() } }
    }
}
