// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.concurrent.thread

/**
 * A platform player bridge whose frames reach [frame] as they are shown, refreshed by a thread of
 * its own as an output's timer refreshes it: it waits as long as each refresh says, and polls while
 * the player is not playing.
 */
internal fun refreshedPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit = {},
    frame: (NativeVideoFrame) -> Unit = { it.frame.close() },
): NativePlayerBridge = RefreshedPlayerBridge(createPlatformPlayerBridge(configuration, update, presentingFrames(frame)))

/** Refreshes [bridge] on a thread of its own until closed; closing it closes [bridge] too. */
internal class RefreshedPlayerBridge(private val bridge: NativePlayerBridge) : NativePlayerBridge by bridge {
    @Volatile
    private var running = true

    /** The thread the refreshes run on, which presents the frames playback shows. */
    val refreshThread: Thread = thread(name = "player-refresh", isDaemon = true) {
        while (running) {
            val remainingUs = bridge.refresh()
            val waitUs = if (remainingUs == NATIVE_PLAYER_REFRESH_IDLE) 2_000 else remainingUs.coerceIn(0, 10_000)
            Thread.sleep(waitUs / 1_000, ((waitUs % 1_000) * 1_000).toInt())
        }
    }

    override fun close() {
        running = false
        refreshThread.join()
        bridge.close()
    }
}
