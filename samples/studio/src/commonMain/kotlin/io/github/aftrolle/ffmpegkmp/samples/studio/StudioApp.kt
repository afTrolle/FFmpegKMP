// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.aftrolle.ffmpegkmp.samples.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Ink = Color(0xFF090B10)
private val Panel = Color(0xFF11151D)
private val Raised = Color(0xFF191E28)
private val Accent = Color(0xFF8B7CFF)
private val Mint = Color(0xFF6FE7C2)
private val Warm = Color(0xFFFFB36B)

private val StudioColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Mint,
    tertiary = Warm,
    background = Ink,
    surface = Panel,
    surfaceVariant = Raised,
    onBackground = Color(0xFFF3F1FA),
    onSurface = Color(0xFFF3F1FA),
    onSurfaceVariant = Color(0xFFADB4C4),
    outline = Color(0xFF353C4B),
    error = Color(0xFFFF6F7D),
)

@Composable
public fun StudioApp() {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { StudioController(scope) }
    val state by controller.state.collectAsState()
    DisposableEffect(controller) { onDispose(controller::dispose) }

    MaterialTheme(colorScheme = StudioColors) {
        Scaffold(
            containerColor = Ink,
            topBar = { StudioTopBar(state, controller) },
            bottomBar = { RenderBar(state, controller) },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0x222C246B), Color.Transparent),
                            center = Offset(250f, 100f),
                            radius = 800f,
                        ),
                    ),
            ) {
                if (state.clips.isEmpty()) {
                    EmptyProject(state, controller)
                } else {
                    EditorWorkspace(state, controller)
                }
            }
        }
    }
}

@Composable
private fun StudioTopBar(state: StudioState, controller: StudioController) {
    Surface(color = Ink.copy(alpha = 0.94f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(Brush.linearGradient(listOf(Accent, Color(0xFFB96DFF))), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("F", fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("FFmpegKMP Studio", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    if (state.clips.isEmpty()) "Multiplatform montage lab" else
                        "${state.clips.size} clips  •  ${state.totalDurationSeconds.asTime()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            OutlinedButton(onClick = controller::importClips, enabled = !state.isImporting) {
                if (state.isImporting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Add clips")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    }
}

@Composable
private fun EmptyProject(state: StudioState, controller: StudioController) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.widthIn(max = 650.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.92f)),
            border = CardDefaults.outlinedCardBorder(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 42.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    Modifier
                        .size(86.dp)
                        .background(Accent.copy(alpha = 0.14f), CircleShape)
                        .border(1.dp, Accent.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = Accent, fontSize = 45.sp, fontWeight = FontWeight.Light)
                }
                Text("Turn clips into a story", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Pick multiple videos, trim and reorder them, choose a canvas, then render one shareable montage with FFmpegKMP.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = controller::importClips, enabled = !state.isImporting) {
                    Text(if (state.isImporting) "Opening picker…" else "Choose video clips")
                }
                state.importMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapabilityPill("Android")
                    CapabilityPill("iOS")
                    CapabilityPill("Desktop")
                    CapabilityPill("Web")
                }
            }
        }
    }
}

@Composable
private fun CapabilityPill(label: String) {
    Text(
        label,
        modifier = Modifier
            .background(Raised, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
    )
}

@Composable
private fun EditorWorkspace(state: StudioState, controller: StudioController) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        if (wide) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PreviewPanel(state, Modifier.weight(1f))
                    TimelinePanel(state, controller)
                }
                InspectorPanel(state, controller, Modifier.width(330.dp).fillMaxHeight())
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PreviewPanel(state, Modifier.fillMaxWidth().height(280.dp))
                TimelinePanel(state, controller)
                InspectorPanel(state, controller, Modifier.fillMaxWidth(), compact = true)
            }
        }
    }
}

@Composable
private fun PreviewPanel(state: StudioState, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.93f))) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CANVAS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.weight(1f))
                Text("${state.canvas.width} × ${state.canvas.height}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxHeight(0.9f)
                        .aspectRatio(state.canvas.width.toFloat() / state.canvas.height)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF251F48), Color(0xFF162A38), Color(0xFF101219))),
                            RoundedCornerShape(12.dp),
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    FilmPattern()
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.selectedClip?.displayName ?: "Select a clip",
                            modifier = Modifier.padding(horizontal = 24.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val info = state.selectedClip?.mediaInfo
                        Text(
                            listOfNotNull(
                                info?.width?.let { width -> info.height?.let { height -> "$width×$height" } },
                                info?.codec?.uppercase(),
                            ).joinToString("  •  ").ifBlank { "FFmpeg preview canvas" },
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilmPattern() {
    Canvas(Modifier.fillMaxSize()) {
        val gap = size.width / 8f
        repeat(9) { index ->
            drawLine(
                color = Color.White.copy(alpha = 0.055f),
                start = Offset(index * gap, 0f),
                end = Offset(index * gap - size.height * 0.25f, size.height),
                strokeWidth = 1f,
            )
        }
        drawLine(
            color = Mint.copy(alpha = 0.32f),
            start = Offset(size.width * 0.18f, size.height * 0.78f),
            end = Offset(size.width * 0.82f, size.height * 0.78f),
            strokeWidth = 3f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun TimelinePanel(state: StudioState, controller: StudioController) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.96f))) {
        Column(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("TIMELINE", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.weight(1f))
                Text("Hard cuts", color = Mint, fontSize = 11.sp)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                state.clips.forEachIndexed { index, clip ->
                    ClipCard(index, clip, clip.id == state.selectedClipId) { controller.selectClip(clip.id) }
                }
                Box(
                    Modifier
                        .width(86.dp)
                        .height(78.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .clickable(onClick = controller::importClips),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+ Add", color = Accent, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ClipCard(index: Int, clip: TimelineClip, selected: Boolean, onClick: () -> Unit) {
    val width = (135 + clip.outputDurationSeconds * 3).coerceIn(145.0, 250.0).dp
    val borderColor = if (selected) Accent else MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .width(width)
            .height(78.dp)
            .background(
                Brush.linearGradient(
                    if (index % 2 == 0) listOf(Color(0xFF322B63), Color(0xFF1A2432))
                    else listOf(Color(0xFF493125), Color(0xFF222434)),
                ),
                RoundedCornerShape(10.dp),
            )
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text("${index + 1}", color = if (selected) Accent else Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(clip.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clip.outputDurationSeconds.asTime(), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                AnalysisDot(clip.analysisState)
            }
        }
    }
}

@Composable
private fun AnalysisDot(state: ClipAnalysisState) {
    val color = when (state) {
        ClipAnalysisState.READY -> Mint
        ClipAnalysisState.FAILED -> MaterialTheme.colorScheme.error
        ClipAnalysisState.ANALYZING -> Warm
        ClipAnalysisState.WAITING -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(7.dp).background(color, CircleShape))
}

@Composable
private fun InspectorPanel(
    state: StudioState,
    controller: StudioController,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.96f))) {
        Column(
            Modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text("PROJECT", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.4.sp)
            Text("Canvas", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                CanvasPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = state.canvas == preset,
                        onClick = { controller.setCanvas(preset) },
                        label = { Text(preset.label, fontSize = 11.sp) },
                    )
                }
            }
            Text("Quality", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ExportQuality.entries.forEach { quality ->
                    FilterChip(
                        selected = state.quality == quality,
                        onClick = { controller.setQuality(quality) },
                        label = { Text(quality.label, fontSize = 11.sp) },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
            val clip = state.selectedClip
            if (clip == null) {
                Text("Select a clip to edit it", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ClipInspector(clip, state, controller, compact)
            }
        }
    }
}

@Composable
private fun ColumnScope.ClipInspector(
    clip: TimelineClip,
    state: StudioState,
    controller: StudioController,
    compact: Boolean,
) {
    Text("SELECTED CLIP", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.4.sp)
    Text(clip.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
    val infoText = when (clip.analysisState) {
        ClipAnalysisState.ANALYZING -> "Inspecting streams with FFprobe…"
        ClipAnalysisState.FAILED -> clip.analysisError ?: "Media inspection failed"
        else -> listOfNotNull(
            clip.mediaInfo?.codec?.uppercase(),
            clip.mediaInfo?.width?.let { width -> clip.mediaInfo.height?.let { "$width×$it" } },
            clip.sizeBytes?.asFileSize(),
        ).joinToString("  •  ")
    }
    Text(
        infoText,
        color = if (clip.analysisState == ClipAnalysisState.FAILED) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )

    ValueLabel("Trim in", clip.trimStartSeconds.asTime())
    Slider(
        value = clip.trimStartSeconds.toFloat(),
        onValueChange = { controller.updateTrim(startSeconds = it.toDouble()) },
        valueRange = 0f..(clip.trimEndSeconds - 0.1).coerceAtLeast(0.1).toFloat(),
    )
    ValueLabel("Trim out", clip.trimEndSeconds.asTime())
    Slider(
        value = clip.trimEndSeconds.toFloat(),
        onValueChange = { controller.updateTrim(endSeconds = it.toDouble()) },
        valueRange = (clip.trimStartSeconds + 0.1).toFloat()..clip.sourceDurationSeconds.coerceAtLeast(0.2).toFloat(),
    )
    ValueLabel("Speed", "${((clip.speed * 100).roundToInt())}%")
    Slider(value = clip.speed.toFloat(), onValueChange = { controller.updateSpeed(it.toDouble()) }, valueRange = 0.5f..2f)
    ValueLabel("Volume", "${((clip.volume * 100).roundToInt())}%")
    Slider(value = clip.volume.toFloat(), onValueChange = { controller.updateVolume(it.toDouble()) }, valueRange = 0f..1.5f)

    if (compact) Spacer(Modifier.height(8.dp)) else Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        OutlinedButton(
            onClick = { controller.moveClip(clip.id, -1) },
            enabled = state.clips.firstOrNull()?.id != clip.id,
            modifier = Modifier.weight(1f),
        ) { Text("← Earlier", fontSize = 11.sp) }
        OutlinedButton(
            onClick = { controller.moveClip(clip.id, 1) },
            enabled = state.clips.lastOrNull()?.id != clip.id,
            modifier = Modifier.weight(1f),
        ) { Text("Later →", fontSize = 11.sp) }
    }
    OutlinedButton(
        onClick = { controller.removeClip(clip.id) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text("Remove clip") }
}

@Composable
private fun ValueLabel(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RenderBar(state: StudioState, controller: StudioController) {
    val active = state.render.stage in setOf(RenderStage.PREPARING, RenderStage.RENDERING, RenderStage.SAVING)
    Surface(color = Color(0xFF0D1016)) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            if (active) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(state.render.message, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    val detail = state.render.logs.lastOrNull()
                        ?: "${state.canvas.label}  •  ${state.quality.label}  •  ${state.totalDurationSeconds.asTime()}"
                    Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (active) {
                    OutlinedButton(onClick = controller::cancelRender) { Text("Cancel") }
                } else {
                    Button(onClick = controller::render, enabled = state.canRender) {
                        Text("Render montage")
                    }
                }
            }
        }
    }
}

private fun Double.asTime(): String {
    val total = coerceAtLeast(0.0).roundToInt()
    val minutes = total / 60
    val seconds = total % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun Long.asFileSize(): String = when {
    this >= 1_000_000_000 -> "${(this / 100_000_000.0).roundToInt() / 10.0} GB"
    this >= 1_000_000 -> "${(this / 100_000.0).roundToInt() / 10.0} MB"
    this >= 1_000 -> "${(this / 100.0).roundToInt() / 10.0} KB"
    else -> "$this B"
}
