// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public interface ExecutionSession<out R> : AutoCloseable {
    public val id: Long
    public val arguments: List<String>
    public val state: StateFlow<SessionState>

    /**
     * This session's live log, output and progress events, completing once the session has ended.
     * A collector receives the most recent event from before it subscribed and everything after;
     * one that falls more than [CommandRuntimeLimits.maxPendingNativeEvents] events behind loses
     * the oldest. The [ExecutionResult] retains the complete capture either way.
     */
    public val events: Flow<ExecutionEvent>

    public suspend fun await(): R
    public fun cancel()
    public suspend fun cancelAndJoin()
}
