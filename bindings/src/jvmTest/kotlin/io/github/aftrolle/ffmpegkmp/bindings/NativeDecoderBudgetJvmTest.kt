// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Buffer

/**
 * The process's hardware decoder budget, through the real player on macOS, where a Canvas output
 * wants VideoToolbox under AUTO. Each test sets the limit relative to the places already taken, and
 * puts the limit back.
 */
class NativeDecoderBudgetJvmTest {
    private var limit = 0
    private var held = 0

    @BeforeTest
    fun saveLimit() {
        limit = nativeDecoderBudget().hardwareDecoders
        held = nativeDecoderBudget().inUse
    }

    @AfterTest
    fun restoreLimit() = setNativeDecoderBudget(limit)

    @Test
    fun aBudgetOfTwoGivesTwoOfFourPlayersHardware() {
        if (!isMac()) return
        setNativeDecoderBudget(held + 2)
        val players = (1..4).map { player(NativePlayerConfiguration(NativePlayerDecoderPreference.AUTO, codecReleaseDelayUs = KEEP)) }
        try {
            players.forEach { assertEquals(0, it.prepare(hardwareSource())) }
            val snapshots = players.map { it.snapshot() }
            assertEquals(2, snapshots.count { it.activeDecoder == NativePlayerDecoderKind.HARDWARE && it.decoderFallback == NativePlayerDecoderFallback.NONE })
            assertEquals(2, snapshots.count { it.activeDecoder == NativePlayerDecoderKind.SOFTWARE && it.decoderFallback == NativePlayerDecoderFallback.BUDGET })
            assertEquals(held + 2, nativeDecoderBudget().inUse)
        } finally {
            players.forEach { it.close() }
        }
        assertEquals(held, nativeDecoderBudget().inUse, "places held after the players closed")
    }

    /** A player the budget sent to software decodes as any software player does: its frames as decoded, no download, no new memory. */
    @Test
    fun aBudgetFallbackAddsNoCopies() {
        if (!isMac()) return
        setNativeDecoderBudget(held + 1)
        val frames = CopyOnWriteArrayList<NativeVideoFrame>()
        player(NativePlayerConfiguration(NativePlayerDecoderPreference.AUTO, codecReleaseDelayUs = KEEP)).use { holder ->
            assertEquals(0, holder.prepare(hardwareSource()))
            assertEquals(NativePlayerDecoderKind.HARDWARE, holder.snapshot().activeDecoder)
            player(NativePlayerConfiguration(NativePlayerDecoderPreference.AUTO, codecReleaseDelayUs = KEEP), frames).use { bridge ->
                assertEquals(0, bridge.prepare(hardwareSource()))
                assertEquals(NativePlayerDecoderFallback.BUDGET, bridge.snapshot().decoderFallback)
                frames.closeAll()
                val before = nativePlayerStatistics()
                val memory = nativeFrameStatistics()
                assertEquals(0, bridge.play())
                frames.awaitCount(12)
                assertEquals(0, bridge.pause())
                frames.forEach { frame -> frame.frame.use { assertEquals(YUV420P, it.format?.layout, "a frame as decoded") } }
                frames.clear()
                assertEquals(before.downloads, nativePlayerStatistics().downloads, "downloads of software frames")
                assertEquals(memory.buffers, nativeFrameStatistics().buffers, "frame buffers allocated")
            }
        }
    }

    /**
     * An idle player's release gives its place back, so a later player decodes in hardware, and a
     * player the budget sent to software asks again when it next opens a decoder.
     */
    @Test
    fun anIdleReleaseGivesThePlaceBack() {
        if (!isMac()) return
        setNativeDecoderBudget(held + 1)
        val releasing = NativePlayerConfiguration(NativePlayerDecoderPreference.AUTO, codecReleaseDelayUs = 1_000_000)
        player(releasing).use { first ->
            player(releasing).use { second ->
                assertEquals(0, first.prepare(hardwareSource()))
                assertEquals(NativePlayerDecoderKind.HARDWARE, first.snapshot().activeDecoder)
                val opened = nativePlayerStatistics()
                assertEquals(0, second.prepare(hardwareSource()))
                assertEquals(NativePlayerDecoderFallback.BUDGET, second.snapshot().decoderFallback)

                // Both go idle; their releases close both codecs and give the hardware place back.
                awaitCodecClose(opened, 2)
                assertEquals(held, nativeDecoderBudget().inUse)
                assertEquals(0, second.seek(500_000))
                assertEquals(NativePlayerDecoderKind.HARDWARE, second.snapshot().activeDecoder)
                assertEquals(NativePlayerDecoderFallback.NONE, second.snapshot().decoderFallback)
                assertEquals(held + 1, nativeDecoderBudget().inUse)
            }
        }
        assertEquals(held, nativeDecoderBudget().inUse)
    }

    @Test
    fun requireHardwareFailsWithBusyWhenTheBudgetIsFull() {
        if (!isMac()) return
        setNativeDecoderBudget(held + 1)
        player(NativePlayerConfiguration(NativePlayerDecoderPreference.AUTO, codecReleaseDelayUs = KEEP)).use { holder ->
            assertEquals(0, holder.prepare(hardwareSource()))
            player(NativePlayerConfiguration(NativePlayerDecoderPreference.REQUIRE_HARDWARE)).use { bridge ->
                assertEquals(NativePlayerError.BUSY, bridge.prepare(hardwareSource()))
                assertEquals(NativePlayerState.FAILED, bridge.snapshot().state)
                assertEquals(NativePlayerError.BUSY, bridge.snapshot().errorCode)
            }
            assertEquals(held + 1, nativeDecoderBudget().inUse)
        }
    }

    @Test
    fun aNegativeLimitIsRejected() {
        val failure = runCatching { setNativeDecoderBudget(-1) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "$failure")
        assertEquals(limit, nativeDecoderBudget().hardwareDecoders)
    }

    /** A player with a Canvas-style output attached, its frames going to [frames] (closed at once when null). */
    private fun player(
        configuration: NativePlayerConfiguration,
        frames: MutableList<NativeVideoFrame>? = null,
    ): NativePlayerBridge = createPlatformPlayerBridge(
        configuration,
        update = {},
        frame = { frame -> if (frames != null) frames += frame else frame.frame.close() },
    ).also { assertEquals(0, it.setOutput(NativePlayerOutputCapabilities())) }

    private fun hardwareSource(): NativePlayerSource {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/hardware-h264.mp4")) { "Missing hardware-h264.mp4" }.use { it.readBytes() }
        return NativePlayerSource(
            input = "hardware-h264.mp4",
            mounts = listOf(NativeMountedIo("hardware-h264.mp4", NativeSourceResource(Buffer().write(bytes)))),
        )
    }

    private fun isMac() = System.getProperty("os.name").contains("Mac", ignoreCase = true)
}

private const val KEEP = -1L
private const val YUV420P = 6

private fun MutableList<NativeVideoFrame>.closeAll() {
    forEach { it.frame.close() }
    clear()
}

private fun List<NativeVideoFrame>.awaitCount(count: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (size < count) {
        assertTrue(System.nanoTime() < deadline, "$size frames within 3 s, not $count")
        Thread.sleep(5)
    }
}

private fun awaitCodecClose(before: NativePlayerStatistics, count: Long) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (nativePlayerStatistics().codecCloses < before.codecCloses + count) {
        assertTrue(System.nanoTime() < deadline, "no codec closed within 5 s")
        Thread.sleep(10)
    }
}
