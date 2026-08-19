// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegCommand

internal data class ExportPlan(
    val command: FFmpegCommand,
    val inputPaths: List<String>,
    val outputPath: String,
    val filterGraph: String,
)

internal object ExportCommandFactory {
    fun create(
        clips: List<TimelineClip>,
        canvas: CanvasPreset,
        quality: ExportQuality,
    ): ExportPlan {
        require(clips.isNotEmpty()) { "At least one clip is required" }

        val inputPaths = clips.mapIndexed { index, clip ->
            val extension = clip.fileExtension().ifBlank { "mp4" }
            "/studio/input-$index.$extension"
        }
        val outputPath = "/studio/render.mp4"
        val graph = buildFilterGraph(clips, canvas)

        val command = FFmpegCommand {
            overwrite()
            inputPaths.forEach(::input)
            complexFilter(graph)
            map("[outv]")
            map("[outa]")
            videoCodec("libx264")
            option("-preset", quality.videoPreset)
            option("-crf", quality.crf.toString())
            audioCodec("aac")
            option("-b:a", "192k")
            option("-movflags", "+faststart")
            output(outputPath, format = "mp4")
        }

        return ExportPlan(command, inputPaths, outputPath, graph)
    }

    private fun buildFilterGraph(clips: List<TimelineClip>, canvas: CanvasPreset): String {
        val chains = buildList {
            clips.forEachIndexed { index, clip ->
                val start = clip.trimStartSeconds.ffmpegNumber()
                val end = clip.trimEndSeconds.ffmpegNumber()
                val speed = clip.speed.ffmpegNumber()
                add(
                    "[$index:v:0]trim=start=$start:end=$end," +
                        "setpts=(PTS-STARTPTS)/$speed," +
                        "scale=${canvas.width}:${canvas.height}:force_original_aspect_ratio=decrease," +
                        "pad=${canvas.width}:${canvas.height}:(ow-iw)/2:(oh-ih)/2:black," +
                        "setsar=1,fps=30,format=yuv420p[v$index]",
                )

                if (clip.mediaInfo?.hasAudio == true) {
                    add(
                        "[$index:a:0]atrim=start=$start:end=$end,asetpts=PTS-STARTPTS," +
                            "atempo=$speed,aresample=48000," +
                            "aformat=sample_fmts=fltp:channel_layouts=stereo," +
                            "volume=${clip.volume.ffmpegNumber()}[a$index]",
                    )
                } else {
                    add(
                        "anullsrc=r=48000:cl=stereo," +
                            "atrim=duration=${clip.outputDurationSeconds.ffmpegNumber()}," +
                            "asetpts=PTS-STARTPTS[a$index]",
                    )
                }
            }
            val concatInputs = clips.indices.joinToString("") { "[v$it][a$it]" }
            add("${concatInputs}concat=n=${clips.size}:v=1:a=1[outv][outa]")
        }
        return chains.joinToString(";")
    }
}

private fun TimelineClip.fileExtension(): String =
    displayName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .filter { it.isLetterOrDigit() }
        .take(8)

private fun Double.ffmpegNumber(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()
