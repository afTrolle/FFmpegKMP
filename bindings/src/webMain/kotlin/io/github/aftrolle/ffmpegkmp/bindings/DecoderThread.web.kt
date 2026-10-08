// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@InternalFFmpegKmpApi
public actual class DecoderThread actual constructor(name: String) {
    // Blocks start at once, in the caller's frame, and queue on the lock in the order they came;
    // a failed block fails only its own Deferred.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val order = Mutex()

    public actual fun <T> submit(block: suspend () -> T): Deferred<T> =
        scope.async(start = CoroutineStart.UNDISPATCHED) { order.withLock { block() } }

    public actual fun interrupt() = Unit

    // The page cannot block to wait for it: the block runs once the blocks before it have ended.
    public actual fun finish(bound: Duration, block: () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { order.withLock { runCatching(block) } }
    }
}
