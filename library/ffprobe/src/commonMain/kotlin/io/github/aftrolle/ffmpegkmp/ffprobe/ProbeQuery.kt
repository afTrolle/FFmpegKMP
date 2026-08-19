// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffprobe

public data class ProbeQuery(
    val format: Boolean = true,
    val streams: Boolean = true,
    val chapters: Boolean = true,
    val programs: Boolean = true,
    val streamGroups: Boolean = true,
    val packets: Boolean = false,
    val frames: Boolean = false,
    val pixelFormats: Boolean = false,
    val programVersion: Boolean = false,
    val libraryVersions: Boolean = false,
    val data: Boolean = false,
    val entries: String? = null,
) {
    internal fun arguments(input: String): List<String> = buildList {
        addAll(listOf("-v", "error", "-of", "json"))
        if (format) add("-show_format")
        if (streams) add("-show_streams")
        if (chapters) add("-show_chapters")
        if (programs) add("-show_programs")
        if (streamGroups) add("-show_stream_groups")
        if (packets) add("-show_packets")
        if (frames) add("-show_frames")
        if (pixelFormats) add("-show_pixel_formats")
        if (programVersion) add("-show_program_version")
        if (libraryVersions) add("-show_library_versions")
        if (data) add("-show_data")
        entries?.let { addAll(listOf("-show_entries", it)) }
        add(input)
    }

    public companion object {
        public val Default: ProbeQuery = ProbeQuery()
        public val Full: ProbeQuery = ProbeQuery(
            packets = true,
            frames = true,
            pixelFormats = true,
            programVersion = true,
            libraryVersions = true,
            data = true,
        )
    }
}
