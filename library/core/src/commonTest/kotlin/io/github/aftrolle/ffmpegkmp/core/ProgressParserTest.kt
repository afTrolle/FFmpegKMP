// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProgressParserTest {
    private fun parse(vararg chunks: String): List<ExecutionEvent.Progress> {
        val events = mutableListOf<ExecutionEvent.Progress>()
        val parser = ProgressParser(events::add)
        chunks.forEach(parser::accept)
        return events
    }

    @Test
    fun parsesPeriodicStatsLine() {
        val events = parse(
            "frame=  120 fps= 30 q=28.0 size=     512KiB time=00:00:04.00 bitrate=1048.6kbits/s speed=1.01x\r",
        )
        assertEquals(1, events.size)
        val progress = events.single()
        assertEquals(120, progress.frame)
        assertEquals(30.0, progress.fps)
        assertEquals(4.seconds, progress.outTime)
        assertEquals(512L * 1024, progress.totalSizeBytes)
        assertEquals("1048.6kbits/s", progress.bitrate)
        assertEquals(1.01, progress.speed)
        assertEquals(false, progress.end)
    }

    @Test
    fun parsesFinalStatsLineAsEnd() {
        val events = parse(
            "frame=  240 fps= 30 q=-1.0 Lsize=    1024KiB time=00:00:08.00 bitrate=1048.6kbits/s speed=1.2x\n",
        )
        assertTrue(events.single().end)
        assertEquals(8.seconds, events.single().outTime)
    }

    @Test
    fun parsesStatsLineSplitAcrossChunks() {
        val events = parse(
            "frame=  120 fps= 30 q=28.0 size=     512KiB time=00:0",
            "0:04.50 bitrate=1048.6kbits/s speed=1.01x\r",
        )
        assertEquals(1, events.size)
        assertEquals(4.seconds + 500.milliseconds, events.single().outTime)
    }

    @Test
    fun ignoresUnrelatedLogLines() {
        val events = parse(
            "Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'input.mp4':\n",
            "  Duration: 00:00:10.00, start: 0.000000, bitrate: 1200 kb/s\n",
        )
        assertEquals(0, events.size)
    }

    @Test
    fun parsesProgressBlock() {
        val events = parse(
            "frame=25\n",
            "fps=24.5\n",
            "total_size=204800\n",
            "out_time_us=1500000\n",
            "speed=0.98x\n",
            "progress=continue\n",
            "frame=50\n",
            "out_time_us=3000000\n",
            "progress=end\n",
        )
        assertEquals(2, events.size)
        assertEquals(25, events[0].frame)
        assertEquals(24.5, events[0].fps)
        assertEquals(204800, events[0].totalSizeBytes)
        assertEquals(1500.milliseconds, events[0].outTime)
        assertEquals(0.98, events[0].speed)
        assertEquals(false, events[0].end)
        assertEquals(50, events[1].frame)
        assertTrue(events[1].end)
    }

    @Test
    fun handlesNotAvailableValues() {
        val events = parse(
            "frame=    5 fps=0.0 q=0.0 size=       0KiB time=N/A bitrate=N/A speed=N/A\r",
        )
        val progress = events.single()
        assertEquals(5, progress.frame)
        assertEquals(null, progress.outTime)
        assertEquals(null, progress.bitrate)
        assertEquals(null, progress.speed)
    }
}
