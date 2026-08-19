// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.ExecutionEvent
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegClient
import io.github.aftrolle.ffmpegkmp.ffmpeg.FFmpegSession
import io.github.aftrolle.ffmpegkmp.ffprobe.FFprobeClient
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.write

public class StudioController(
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(StudioState())
    public val state = mutableState.asStateFlow()

    private var nextClipId = 1L
    private var renderJob: Job? = null
    private var renderSession: FFmpegSession? = null

    public fun importClips() {
        if (mutableState.value.isImporting) return
        scope.launch {
            mutableState.update { it.copy(isImporting = true, importMessage = "Choose one or more videos") }
            try {
                val files: List<PlatformFile> = FileKit.openFilePicker(
                    type = FileKitType.Video,
                    mode = FileKitMode.Multiple(maxItems = 20),
                ) ?: emptyList()
                if (files.isNotEmpty()) addFiles(files)
            } catch (failure: Throwable) {
                failure.reportToConsole("Clip import failed")
                mutableState.update { it.copy(importMessage = failure.readableMessage("Could not import clips")) }
            } finally {
                mutableState.update { it.copy(isImporting = false) }
            }
        }
    }

    private suspend fun addFiles(files: List<PlatformFile>) {
        val additions = files.map { file ->
            TimelineClip(
                id = nextClipId++,
                file = file,
                displayName = runCatching { file.name }.getOrDefault("Untitled clip"),
                sizeBytes = runCatching { file.size() }.getOrNull(),
                analysisState = ClipAnalysisState.ANALYZING,
            )
        }
        mutableState.update { current ->
            current.copy(
                clips = current.clips + additions,
                selectedClipId = current.selectedClipId ?: additions.firstOrNull()?.id,
                importMessage = "Analyzing ${additions.size} clip${if (additions.size == 1) "" else "s"}…",
            )
        }

        additions.forEachIndexed { index, clip ->
            analyzeClip(clip)
            mutableState.update {
                it.copy(importMessage = "Analyzed ${index + 1} of ${additions.size} clips")
            }
        }
    }

    private suspend fun analyzeClip(clip: TimelineClip) {
        val virtualPath = "/studio/probe-${clip.id}.${clip.displayName.safeExtension()}"
        try {
            val source = Buffer().apply { write(clip.file.readBytes()) }
            val io = CommandIo { input(virtualPath, source) }
            val client = FFprobeClient()
            val media = try {
                client.inspect(virtualPath, io = io)
            } finally {
                client.close()
            }
            val video = media.streams.firstOrNull { it.codecType == "video" }
            val audio = media.streams.firstOrNull { it.codecType == "audio" }
            val duration = listOfNotNull(media.format?.durationSeconds, video?.durationSeconds)
                .firstOrNull { it > 0.0 } ?: 10.0
            updateClip(clip.id) {
                it.copy(
                    mediaInfo = ClipMediaInfo(
                        durationSeconds = duration,
                        width = video?.width,
                        height = video?.height,
                        codec = video?.codecName,
                        frameRate = video?.averageFrameRate,
                        hasAudio = audio != null,
                        audioCodec = audio?.codecName,
                    ),
                    analysisState = ClipAnalysisState.READY,
                    trimEndSeconds = duration,
                )
            }
        } catch (failure: Throwable) {
            failure.reportToConsole("FFprobe failed for ${clip.displayName}")
            updateClip(clip.id) {
                it.copy(
                    analysisState = ClipAnalysisState.FAILED,
                    analysisError = failure.readableMessage("FFprobe is unavailable"),
                )
            }
        }
    }

    public fun selectClip(id: Long) {
        mutableState.update { it.copy(selectedClipId = id) }
    }

    public fun removeClip(id: Long) {
        mutableState.update { current ->
            val clips = current.clips.filterNot { it.id == id }
            current.copy(
                clips = clips,
                selectedClipId = if (current.selectedClipId == id) clips.firstOrNull()?.id else current.selectedClipId,
            )
        }
    }

    public fun moveClip(id: Long, offset: Int) {
        mutableState.update { current ->
            val from = current.clips.indexOfFirst { it.id == id }
            if (from < 0) return@update current
            val to = (from + offset).coerceIn(0, current.clips.lastIndex)
            if (from == to) current else {
                val reordered = current.clips.toMutableList()
                val clip = reordered.removeAt(from)
                reordered.add(to, clip)
                current.copy(clips = reordered)
            }
        }
    }

    public fun updateTrim(startSeconds: Double? = null, endSeconds: Double? = null) {
        updateSelected { clip ->
            val duration = clip.sourceDurationSeconds
            val start = (startSeconds ?: clip.trimStartSeconds).coerceIn(0.0, (duration - 0.1).coerceAtLeast(0.0))
            val end = (endSeconds ?: clip.trimEndSeconds).coerceIn(start + 0.1, duration)
            clip.copy(trimStartSeconds = start, trimEndSeconds = end)
        }
    }

    public fun updateSpeed(speed: Double) {
        updateSelected { it.copy(speed = speed.coerceIn(0.5, 2.0)) }
    }

    public fun updateVolume(volume: Double) {
        updateSelected { it.copy(volume = volume.coerceIn(0.0, 1.5)) }
    }

    public fun setCanvas(canvas: CanvasPreset) {
        mutableState.update { it.copy(canvas = canvas) }
    }

    public fun setQuality(quality: ExportQuality) {
        mutableState.update { it.copy(quality = quality) }
    }

    public fun render() {
        val snapshot = mutableState.value
        if (!snapshot.canRender) return
        renderJob = scope.launch {
            mutableState.update {
                it.copy(render = RenderState(RenderStage.PREPARING, "Mounting ${snapshot.clips.size} clips…"))
            }
            try {
                val plan = ExportCommandFactory.create(snapshot.clips, snapshot.canvas, snapshot.quality)
                val sources = snapshot.clips.map { clip -> Buffer().apply { write(clip.file.readBytes()) } }
                val rendered = Buffer()
                val io = CommandIo {
                    plan.inputPaths.zip(sources).forEach { (path, source) -> input(path, source) }
                    output(plan.outputPath, rendered)
                }
                val client = FFmpegClient()
                try {
                    val session = client.enqueue(plan.command, io)
                    renderSession = session
                    val eventJob = launch {
                        session.events.collect { event ->
                            val line = when (event) {
                                is ExecutionEvent.Log -> event.message.trim()
                                is ExecutionEvent.Output -> event.text.trim()
                            }
                            if (line.isNotEmpty()) appendLog(line)
                        }
                    }
                    mutableState.update {
                        it.copy(render = it.render.copy(stage = RenderStage.RENDERING, message = "Rendering montage…"))
                    }
                    val result = try {
                        session.await()
                    } finally {
                        eventJob.cancel()
                        renderSession = null
                        session.close()
                    }
                    if (result.cancelled) {
                        mutableState.update {
                            it.copy(render = it.render.copy(stage = RenderStage.CANCELLED, message = "Render cancelled"))
                        }
                        return@launch
                    }
                    check(result.isSuccess) {
                        "FFmpeg returned ${result.returnCode}: ${result.errorOutput.takeLast(400)}"
                    }
                } finally {
                    client.close()
                }

                mutableState.update {
                    it.copy(render = it.render.copy(stage = RenderStage.SAVING, message = "Choose where to save the montage"))
                }
                val bytes = rendered.readByteArray()
                val saved = saveRenderedVideo(bytes, "ffmpegkmp-montage.mp4")
                mutableState.update {
                    it.copy(
                        render = it.render.copy(
                            stage = if (saved) RenderStage.COMPLETE else RenderStage.IDLE,
                            message = if (saved) "Montage exported successfully" else "Save cancelled",
                        ),
                    )
                }
            } catch (_: CancellationException) {
                mutableState.update {
                    it.copy(render = it.render.copy(stage = RenderStage.CANCELLED, message = "Render cancelled"))
                }
            } catch (failure: Throwable) {
                renderSession = null
                val diagnostics = failure.reportToConsole("Montage render failed")
                mutableState.update {
                    it.copy(
                        render = it.render.copy(
                            stage = RenderStage.FAILED,
                            message = failure.readableMessage("Render failed"),
                            logs = (it.render.logs + diagnostics).takeLast(24),
                        ),
                    )
                }
            } finally {
                renderSession = null
                renderJob = null
            }
        }
    }

    public fun cancelRender() {
        renderSession?.cancel()
        if (renderSession == null) renderJob?.cancel()
    }

    public fun clearRenderMessage() {
        mutableState.update { it.copy(render = RenderState()) }
    }

    public fun dispose() {
        renderSession?.cancel()
        renderJob?.cancel()
    }

    private fun updateSelected(transform: (TimelineClip) -> TimelineClip) {
        val selectedId = mutableState.value.selectedClipId ?: return
        updateClip(selectedId, transform)
    }

    private fun updateClip(id: Long, transform: (TimelineClip) -> TimelineClip) {
        mutableState.update { current ->
            current.copy(clips = current.clips.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun appendLog(line: String) {
        mutableState.update { current ->
            current.copy(render = current.render.copy(logs = (current.render.logs + line).takeLast(24)))
        }
    }
}

private fun String.safeExtension(): String =
    substringAfterLast('.', "mp4").lowercase().filter(Char::isLetterOrDigit).take(8).ifBlank { "mp4" }

private fun Throwable.readableMessage(fallback: String): String =
    causeMessages().lastOrNull()?.take(320)?.let { "$fallback: $it" } ?: fallback

private fun Throwable.reportToConsole(context: String): List<String> {
    val causes = causeMessages().mapIndexed { index, message ->
        if (index == 0) "$context: $message" else "Caused by: $message"
    }
    println("[FFmpegKMP Studio] $context\n${stackTraceToString()}")
    return causes
}

private fun Throwable.causeMessages(): List<String> =
    generateSequence(this) { it.cause }
        .mapNotNull { failure -> failure.message?.lineSequence()?.firstOrNull()?.trim() }
        .filter(String::isNotEmpty)
        .distinct()
        .take(8)
        .toList()

internal expect suspend fun saveRenderedVideo(bytes: ByteArray, fileName: String): Boolean
