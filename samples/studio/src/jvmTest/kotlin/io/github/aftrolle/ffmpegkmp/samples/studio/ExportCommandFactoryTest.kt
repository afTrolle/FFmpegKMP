// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import io.github.aftrolle.ffmpegkmp.filters.ToneMap
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
    fun createsHdr10CommandWithPerClipToneMapping() {
        val clips = listOf(
            clip(id = 1, name = "hdr.mov", duration = 6.0, hasAudio = true, trimStart = 0.0, trimEnd = 6.0, speed = 1.0, isHdr = true),
            clip(id = 2, name = "sdr.mp4", duration = 4.0, hasAudio = false, trimStart = 0.0, trimEnd = 4.0, speed = 1.0, isHdr = false),
        )

        val plan = ExportCommandFactory.create(clips, CanvasPreset.LANDSCAPE, ExportQuality.BALANCED, hdr = true)

        assertTrue(ToneMap.ToHdr10Bt2020 in plan.filterGraph)
        assertTrue(ToneMap.SdrBt709ToHdr10 in plan.filterGraph)
        assertTrue("[vcat]${ToneMap.Hdr10P010Output}[outv]" in plan.filterGraph)
        assertTrue("format=yuv420p" !in plan.filterGraph)
        assertTrue(
            plan.command.arguments.containsAll(
                listOf("-c:v", "hevc_mediacodec", "-profile:v", "main10", "-pix_fmt", "p010le"),
            ),
        )
        assertTrue("mpeg4" !in plan.command.arguments)
        assertTrue("-q:v" !in plan.command.arguments)
    }

    @Test
    fun defaultCommandStaysSdrWhenHdrIsNotRequested() {
        val clips = listOf(clip(id = 1, name = "a.mp4", duration = 4.0, hasAudio = true, trimStart = 0.0, trimEnd = 4.0, speed = 1.0))

        val plan = ExportCommandFactory.create(clips, CanvasPreset.LANDSCAPE, ExportQuality.BALANCED)

        assertTrue("hevc_mediacodec" !in plan.command.arguments)
        assertTrue("format=yuv420p" in plan.filterGraph)
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
        isHdr: Boolean = false,
    ) = TimelineClip(
        id = id,
        file = PlatformFile("/tmp/$name"),
        displayName = name,
        sizeBytes = null,
        mediaInfo = ClipMediaInfo(
            durationSeconds = duration,
            width = 1920,
            height = 1080,
            codec = "h264",
            frameRate = "30/1",
            hasAudio = hasAudio,
            audioCodec = if (hasAudio) "aac" else null,
            colorTransfer = if (isHdr) "smpte2084" else "bt709",
            isHdr = isHdr,
        ),
        analysisState = ClipAnalysisState.READY,
        trimStartSeconds = trimStart,
        trimEndSeconds = trimEnd,
        speed = speed,
    )
}
