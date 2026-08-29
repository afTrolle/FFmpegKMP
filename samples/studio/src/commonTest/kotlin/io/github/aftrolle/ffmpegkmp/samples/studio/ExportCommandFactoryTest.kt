// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import io.github.vinceglb.filekit.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportCommandFactoryTest {
    @Test
    fun createsNormalizedMultiClipConcatWithSilentAudioFallback() {
        val clips = listOf(
            clip(
                id = 1,
                name = "first.mov",
                duration = 8.0,
                hasAudio = true,
                trimStart = 1.0,
                trimEnd = 6.0,
                speed = 2.0,
            ),
            clip(
                id = 2,
                name = "second.mp4",
                duration = 4.0,
                hasAudio = false,
                trimStart = 0.0,
                trimEnd = 4.0,
                speed = 1.0,
            ),
        )

        val plan = ExportCommandFactory.create(clips, CanvasPreset.PORTRAIT, ExportQuality.BALANCED)

        assertEquals(listOf("/studio/input-0.mov", "/studio/input-1.mp4"), plan.inputPaths)
        assertTrue("scale=720:1280" in plan.filterGraph)
        assertTrue("atempo=2" in plan.filterGraph)
        assertTrue("anullsrc=r=48000:cl=stereo" in plan.filterGraph)
        assertTrue("[v0][a0][v1][a1]concat=n=2:v=1:a=1[outv][outa]" in plan.filterGraph)
        assertTrue(plan.command.arguments.containsAll(listOf("-map", "[outv]", "mpeg4", "-q:v")))
        assertTrue("-movflags" !in plan.command.arguments)
        assertTrue("+faststart" !in plan.command.arguments)
        assertTrue(plan.command.arguments.containsAll(listOf("-filter_complex_threads", "1")))
        assertEquals(
            clips.size + 1,
            plan.command.arguments.windowed(2).count { it == listOf("-threads", "1") },
        )
        assertTrue("-preset" !in plan.command.arguments)
    }

    @Test
    fun stateDurationAccountsForTrimAndSpeed() {
        val state = StudioState(
            clips = listOf(
                clip(1, "a.mp4", 10.0, true, 2.0, 8.0, 2.0),
                clip(2, "b.mp4", 5.0, true, 0.0, 5.0, 1.0),
            ),
        )

        assertEquals(8.0, state.totalDurationSeconds)
    }

    private fun clip(
        id: Long,
        name: String,
        duration: Double,
        hasAudio: Boolean,
        trimStart: Double,
        trimEnd: Double,
        speed: Double,
    ) = TimelineClip(
        id = id,
        file = testPlatformFile(name),
        displayName = name,
        sizeBytes = null,
        mediaInfo = ClipMediaInfo(duration, 1920, 1080, "h264", "30/1", hasAudio, if (hasAudio) "aac" else null),
        analysisState = ClipAnalysisState.READY,
        trimStartSeconds = trimStart,
        trimEndSeconds = trimEnd,
        speed = speed,
    )
}

internal expect fun testPlatformFile(name: String): PlatformFile
