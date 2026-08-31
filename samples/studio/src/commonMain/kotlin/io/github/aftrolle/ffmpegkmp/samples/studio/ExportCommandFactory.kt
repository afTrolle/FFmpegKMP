// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegCommand
import io.github.aftrolle.ffmpegkmp.filters.ToneMap

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
        hdr: Boolean = false,
    ): ExportPlan {
        require(clips.isNotEmpty()) { "At least one clip is required" }

        val inputPaths = clips.mapIndexed { index, clip ->
            val extension = clip.fileExtension().ifBlank { "mp4" }
            "/studio/input-$index.$extension"
        }
        val outputPath = "/studio/render.mp4"
        val graph = buildFilterGraph(clips, canvas, hdr)

        val command = FFmpegCommand {
            overwrite()
            // Browser Wasm must synchronously acquire pthreads from its fixed
            // Emscripten pool. Explicit limits also keep the demo predictable
            // on native targets instead of multiplying threads per clip.
            option("-filter_complex_threads", "1")
            inputPaths.forEach { path ->
                option("-threads", "1")
                input(path)
            }
            complexFilter(graph)
            map("[outv]")
            map("[outa]")
            if (hdr) {
                // Android-only: HEVC Main10 HDR10 via FFmpegKMP's MediaCodec P010/HDR10
                // overlay. The profile must be explicit; it is not inferred from pixel
                // format or color metadata. See docs/bindings.md.
                videoCodec("hevc_mediacodec")
                option("-profile:v", "main10")
                option("-pix_fmt", "p010le")
                option("-b:v", HDR_BITRATE)
            } else {
                // The standard FFmpegKMP profile intentionally avoids GPL-only
                // libx264. MPEG-4 Part 2 is built into FFmpeg on every sample target.
                videoCodec("mpeg4")
                option("-q:v", quality.videoQuality.toString())
            }
            audioCodec("aac")
            option("-b:a", "192k")
            option("-threads", "1")
            output(outputPath, format = "mp4")
        }

        return ExportPlan(command, inputPaths, outputPath, graph)
    }

    // Not wired to ExportQuality: hevc_mediacodec's bitrate-mode scale doesn't map onto
    // the mpeg4 -q:v scale ExportQuality was designed for. A flat rate keeps the demo simple.
    private const val HDR_BITRATE = "12M"

    private fun buildFilterGraph(clips: List<TimelineClip>, canvas: CanvasPreset, hdr: Boolean): String {
        val chains = buildList {
            clips.forEachIndexed { index, clip ->
                val start = clip.trimStartSeconds.ffmpegNumber()
                val end = clip.trimEndSeconds.ffmpegNumber()
                val speed = clip.speed.ffmpegNumber()
                // In HDR mode each clip is tone-mapped into the same HDR10 (BT.2020/PQ)
                // signal before concatenation: a genuinely HDR source keeps its absolute
                // luminance, an SDR source is promoted to the 203-nit reference white.
                val colorStage = when {
                    !hdr -> "format=yuv420p"
                    clip.mediaInfo?.isHdr == true -> ToneMap.ToHdr10Bt2020
                    else -> ToneMap.SdrBt709ToHdr10
                }
                add(
                    "[$index:v:0]trim=start=$start:end=$end," +
                        "setpts=(PTS-STARTPTS)/$speed," +
                        "scale=${canvas.width}:${canvas.height}:force_original_aspect_ratio=decrease," +
                        "pad=${canvas.width}:${canvas.height}:(ow-iw)/2:(oh-ih)/2:black," +
                        "setsar=1,fps=30,$colorStage[v$index]",
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
            if (hdr) {
                add("${concatInputs}concat=n=${clips.size}:v=1:a=1[vcat][outa]")
                // Re-stamps p010le + BT.2020/PQ frame metadata once after concat, in case
                // the concat filter doesn't preserve it end to end from every clip's tone map.
                add("[vcat]${ToneMap.Hdr10P010Output}[outv]")
            } else {
                add("${concatInputs}concat=n=${clips.size}:v=1:a=1[outv][outa]")
            }
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
