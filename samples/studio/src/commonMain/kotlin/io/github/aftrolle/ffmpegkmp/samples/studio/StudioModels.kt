// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import io.github.vinceglb.filekit.PlatformFile

public enum class CanvasPreset(
    public val label: String,
    public val width: Int,
    public val height: Int,
) {
    LANDSCAPE("Landscape", 1280, 720),
    PORTRAIT("Portrait", 720, 1280),
    SQUARE("Square", 1080, 1080),
}

public enum class ExportQuality(
    public val label: String,
    internal val videoQuality: Int,
) {
    COMPACT("Compact", 8),
    BALANCED("Balanced", 5),
    HIGH("High", 2),
}

public data class ClipMediaInfo(
    val durationSeconds: Double,
    val width: Int?,
    val height: Int?,
    val codec: String?,
    val frameRate: String?,
    val hasAudio: Boolean,
    val audioCodec: String?,
)

public enum class ClipAnalysisState { WAITING, ANALYZING, READY, FAILED }

public data class TimelineClip(
    val id: Long,
    val file: PlatformFile,
    val displayName: String,
    val sizeBytes: Long?,
    val mediaInfo: ClipMediaInfo? = null,
    val analysisState: ClipAnalysisState = ClipAnalysisState.WAITING,
    val analysisError: String? = null,
    val trimStartSeconds: Double = 0.0,
    val trimEndSeconds: Double = 10.0,
    val speed: Double = 1.0,
    val volume: Double = 1.0,
) {
    val sourceDurationSeconds: Double
        get() = mediaInfo?.durationSeconds?.takeIf { it > 0.0 } ?: trimEndSeconds.coerceAtLeast(0.1)

    val trimmedDurationSeconds: Double
        get() = (trimEndSeconds - trimStartSeconds).coerceAtLeast(0.1)

    val outputDurationSeconds: Double
        get() = trimmedDurationSeconds / speed.coerceAtLeast(0.1)
}

public enum class RenderStage { IDLE, PREPARING, RENDERING, SAVING, COMPLETE, FAILED, CANCELLED }

public data class RenderState(
    val stage: RenderStage = RenderStage.IDLE,
    val message: String = "Ready to render",
    val logs: List<String> = emptyList(),
)

public data class StudioState(
    val clips: List<TimelineClip> = emptyList(),
    val selectedClipId: Long? = null,
    val canvas: CanvasPreset = CanvasPreset.LANDSCAPE,
    val quality: ExportQuality = ExportQuality.BALANCED,
    val isImporting: Boolean = false,
    val importMessage: String? = null,
    val render: RenderState = RenderState(),
) {
    val selectedClip: TimelineClip?
        get() = clips.firstOrNull { it.id == selectedClipId }

    val totalDurationSeconds: Double
        get() = clips.sumOf(TimelineClip::outputDurationSeconds)

    val canRender: Boolean
        get() = clips.isNotEmpty() && clips.none { it.analysisState == ClipAnalysisState.ANALYZING } &&
            render.stage !in setOf(RenderStage.PREPARING, RenderStage.RENDERING, RenderStage.SAVING)
}
