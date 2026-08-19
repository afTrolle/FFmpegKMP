// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public interface ExecutionSession<out R> : AutoCloseable {
    public val id: Long
    public val arguments: List<String>
    public val state: StateFlow<SessionState>
    public val events: Flow<ExecutionEvent>

    public suspend fun await(): R
    public fun cancel()
    public suspend fun cancelAndJoin()
}
