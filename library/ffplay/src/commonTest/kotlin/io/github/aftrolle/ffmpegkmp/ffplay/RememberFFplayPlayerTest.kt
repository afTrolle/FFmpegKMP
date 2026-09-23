// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class RememberFFplayPlayerTest {
    @Test
    fun leavingTheCompositionClosesThePlayer() = runTest {
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(UnitApplier(), recomposer)
        var player: FFplayPlayer? = null

        composition.setContent { player = rememberFFplayPlayer(FFplayConfiguration(), ::testPlayer) }
        val remembered = assertNotNull(player)
        assertEquals(FFplayState.IDLE, remembered.snapshot.value.state)

        composition.dispose()
        remembered.awaitClosed()
        recomposer.cancel()
    }

    @Test
    fun anAbandonedCompositionStillClosesThePlayer() = runTest {
        val player = testPlayer(FFplayConfiguration())

        // Compose calls this, not onForgotten, when a composition is discarded before applying.
        RememberedFFplayPlayer(player).onAbandoned()

        player.awaitClosed()
    }
}

private fun testPlayer(configuration: FFplayConfiguration) =
    FFplayPlayer(configuration, ::createInMemoryFFplayEngine, audioOpener = { null })

private suspend fun FFplayPlayer.awaitClosed() = withContext(Dispatchers.Default) {
    withTimeout(5.seconds) { snapshot.first { it.state == FFplayState.CLOSED } }
}

private class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
