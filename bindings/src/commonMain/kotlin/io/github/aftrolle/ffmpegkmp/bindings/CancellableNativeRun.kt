// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Runs a blocking native entry point on [dispatcher] and ties it to the calling coroutine.
 *
 * Native code cannot observe a coroutine's cancellation, so when the caller is cancelled this
 * invokes [cancelNative] (which sets the flag the native run polls) and then waits, without
 * being interruptible itself, for the native call to return. Only then is the cancellation
 * rethrown, so a caller that resumes from a cancelled run knows the native runtime is idle.
 *
 * When cancellation arrives before [block] has been dispatched, the block never runs. The flag
 * [cancelNative] set is then left for the next run to clear before it starts.
 */
internal suspend fun <T> runCancellableNative(
    dispatcher: CoroutineDispatcher,
    cancelNative: () -> Unit,
    block: () -> T,
): T = coroutineScope {
    val run = async(dispatcher) { block() }
    try {
        run.await()
    } catch (cancellation: CancellationException) {
        cancelNative()
        withContext(NonCancellable) { run.join() }
        throw cancellation
    }
}
