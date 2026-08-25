// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Extracts [ExecutionEvent.Progress] from FFmpeg's textual status output.
 *
 * Two formats are recognised, both fed line-by-line from log and stderr text:
 * - the periodic av_log status report (`frame= 12 fps=25 ... time=00:00:01.00 ... speed=1.2x`);
 * - `-progress` key=value blocks terminated by a `progress=continue|end` line.
 */
internal class ProgressParser(private val emit: (ExecutionEvent.Progress) -> Unit) {
    private val buffer = StringBuilder()
    private val block = mutableMapOf<String, String>()

    fun accept(text: String) {
        buffer.append(text)
        while (true) {
            val newline = buffer.indexOfFirst { it == '\n' || it == '\r' }
            if (newline < 0) break
            val line = buffer.substring(0, newline)
            buffer.deleteRange(0, newline + 1)
            acceptLine(line)
        }
        if (buffer.length > MAX_PENDING_LINE_CHARACTERS) {
            buffer.deleteRange(0, buffer.length - MAX_PENDING_LINE_CHARACTERS)
        }
    }

    private fun acceptLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        val statsLine = line.startsWith("frame=") && "time=" in line
        when {
            statsLine -> parseStatsLine(line)
            isProgressBlockEntry(line) -> {
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim()
                if (key == "progress") {
                    flushBlock(end = value == "end")
                } else {
                    block[key] = value
                }
            }
        }
    }

    private fun isProgressBlockEntry(line: String): Boolean {
        val key = line.substringBefore('=', missingDelimiterValue = "").trim()
        return key in progressBlockKeys && ' ' !in line
    }

    private fun flushBlock(end: Boolean) {
        val progress = ExecutionEvent.Progress(
            frame = block["frame"]?.toLongOrNull(),
            fps = block["fps"]?.toDoubleOrNull(),
            outTime = block["out_time_us"]?.toLongOrNull()?.microseconds
                ?: block["out_time_ms"]?.toLongOrNull()?.milliseconds,
            totalSizeBytes = block["total_size"]?.toLongOrNull(),
            bitrate = block["bitrate"]?.takeUnless { it == "N/A" },
            speed = block["speed"]?.removeSuffix("x")?.toDoubleOrNull(),
            end = end,
        )
        block.clear()
        emit(progress)
    }

    private fun parseStatsLine(line: String) {
        val fields = statsField.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
        emit(
            ExecutionEvent.Progress(
                frame = fields["frame"]?.toLongOrNull(),
                fps = fields["fps"]?.toDoubleOrNull(),
                outTime = (fields["time"])?.let(::parseClock),
                totalSizeBytes = (fields["size"] ?: fields["Lsize"])?.let(::parseSize),
                bitrate = fields["bitrate"]?.takeUnless { it == "N/A" },
                speed = fields["speed"]?.removeSuffix("x")?.toDoubleOrNull(),
                end = "Lsize" in fields,
            ),
        )
    }

    private fun parseClock(value: String): Duration? {
        val match = clock.matchEntire(value) ?: return null
        val (h, m, s) = match.destructured
        return h.toInt().hours + m.toInt().minutes + s.toDouble().seconds
    }

    private fun parseSize(value: String): Long? {
        val match = size.matchEntire(value) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val factor = when {
            unit.startsWith("g") -> 1024L * 1024L * 1024L
            unit.startsWith("m") -> 1024L * 1024L
            unit.startsWith("k") -> 1024L
            else -> 1L
        }
        return (amount * factor).toLong()
    }

    private companion object {
        const val MAX_PENDING_LINE_CHARACTERS = 64 * 1_024
        val statsField = Regex("""(\w+)=\s*([^\s]+)""")
        val clock = Regex("""(-?\d+):(\d{2}):(\d{2}(?:\.\d+)?)""")
        val size = Regex("""([\d.]+)\s*([A-Za-z]*)B?""")
        val progressBlockKeys = setOf(
            "frame", "fps", "bitrate", "total_size", "out_time_us", "out_time_ms",
            "out_time", "dup_frames", "drop_frames", "speed", "progress", "stream_0_0_q",
        )
    }
}
