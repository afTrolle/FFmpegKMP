// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.completeWith

internal actual class VideoDecoderThread actual constructor() {
    actual fun <T> submit(block: () -> T): Deferred<T> = CompletableDeferred<T>().apply { completeWith(runCatching(block)) }

    actual fun interrupt() = Unit

    actual fun finish(bound: Duration, block: () -> Unit) = block()
}
