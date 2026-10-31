// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import android.graphics.Paint
import android.os.Build
import android.view.Display
import android.view.Surface
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toAndroidRectF
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

@Composable
internal actual fun PlatformFFplaySurface(
    player: FFplayPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    backgroundColor: Color,
) {
    if (player.configuration.outputPreference == FFplayOutputPreference.COMPOSE_CANVAS) {
        ComposeCanvasFFplaySurface(player, modifier, contentScale, backgroundColor)
        return
    }

    val secureOutputRequired by player.secureOutputRequired.collectAsState()
    // Only the stream's display metadata matters here; the full snapshot changes every frame.
    val video by remember(player) { player.snapshot.map { it.video }.distinctUntilChanged() }
        .collectAsState(player.snapshot.value.video)
    val hostView = LocalView.current
    // One output per player: presentation changes are applied to it, not by replacing it, since
    // the external surface below is bound to the output it was created with.
    val output = remember(player) { AndroidSurfaceOutput(contentScale, backgroundColor.toArgb()) }
    output.updatePresentation(contentScale, backgroundColor.toArgb())
    output.updateVideoInfo(video)
    remember(output, hostView.display) { output.updateDisplayCapabilities(hostView.display) }

    LaunchedEffect(output, video, contentScale) {
        // Re-negotiate once stream display metadata is known, and when the scale decides whether
        // direct presentation is possible. Transformed streams deliberately use the software path
        // because a raw MediaCodec Surface cannot apply FFmpeg's matrix.
        player.attachOutput(output)
    }

    DisposableEffect(player, output) {
        onDispose {
            player.detachOutput(output)
            output.detachSurface()
        }
    }

    key(player) {
        AndroidExternalSurface(
            modifier = modifier,
            isOpaque = true,
            isSecure = secureOutputRequired,
        ) {
            onSurface { surface, width, height ->
                output.attachSurface(surface, width, height)
                player.attachOutput(output)
                surface.onChanged { changedWidth, changedHeight ->
                    output.resize(changedWidth, changedHeight)
                }
                surface.onDestroyed {
                    player.detachOutput(output)
                    output.detachSurface(this)
                }
            }
        }
    }
}

/**
 * Android's native-surface tier. MediaCodec frames are presented directly by the native player;
 * CPU-readable frames arrive here only when automatic negotiation selected the software fallback.
 */
internal class AndroidSurfaceOutput(
    contentScale: ContentScale,
    backgroundArgb: Int,
) : FFplayVideoOutput {
    private val lock = Any()
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var contentScale = contentScale
    private var backgroundArgb = backgroundArgb
    private var videoInfo: VideoInfo? = null
    private var displayHdrTransfers: Set<ColorTransfer> = emptySet()
    private var displayColorSpaces: Set<ColorPrimaries> = setOf(ColorPrimaries.BT709)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** The bitmap each software frame is converted into before it is drawn onto [surface], at the size it is drawn at. */
    private var bitmap: FrameBitmap? = null
    private val sizing = BitmapSizing()

    /** The size of [bitmap], for tests; null before the first software frame. */
    internal val bitmapSize: IntSize?
        get() = synchronized(lock) { bitmap?.let { IntSize(it.width, it.height) } }

    override val kind: FFplayRendererKind = FFplayRendererKind.NATIVE_SURFACE
    override val capabilities: FFplayOutputCapabilities
        get() = synchronized(lock) {
            val directSurfacePresentation = contentScale == ContentScale.Fit &&
                videoInfo.hasIdentityDisplayTransform()
            FFplayOutputCapabilities(
                hardwareFrameImport = directSurfacePresentation,
                softwareFrameUpload = true,
                zeroCopy = directSurfacePresentation,
                hdrTransfers = if (directSurfacePresentation) displayHdrTransfers else emptySet(),
                colorSpaces = if (directSurfacePresentation) displayColorSpaces else setOf(ColorPrimaries.BT709),
                protectedContent = false,
                toneMapHdrToSdr = true,
            )
        }
    override val platformTarget: Any?
        get() = synchronized(lock) { surface }

    fun updatePresentation(contentScale: ContentScale, backgroundArgb: Int) = synchronized(lock) {
        this.contentScale = contentScale
        this.backgroundArgb = backgroundArgb
    }

    fun updateVideoInfo(videoInfo: VideoInfo?) = synchronized(lock) {
        this.videoInfo = videoInfo
    }

    @Suppress("DEPRECATION") // Required for the module's API 24-35 display capability range.
    fun updateDisplayCapabilities(display: Display?) = synchronized(lock) {
        val hdrTypes = display?.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        displayHdrTransfers = buildSet {
            if (Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes ||
                (Build.VERSION.SDK_INT >= 29 &&
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdrTypes) ||
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes
            ) {
                add(ColorTransfer.PQ)
            }
            if (Display.HdrCapabilities.HDR_TYPE_HLG in hdrTypes) add(ColorTransfer.HLG)
        }
        displayColorSpaces = buildSet {
            add(ColorPrimaries.BT709)
            if (hdrTypes.isNotEmpty()) add(ColorPrimaries.BT2020)
            if (Build.VERSION.SDK_INT >= 26 && display?.isWideColorGamut == true) add(ColorPrimaries.DISPLAY_P3)
        }
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) = synchronized(lock) {
        this.surface = surface
        this.width = width
        this.height = height
    }

    fun resize(width: Int, height: Int) = synchronized(lock) {
        this.width = width
        this.height = height
    }

    fun detachSurface(expected: Surface? = null) = synchronized(lock) {
        if (expected != null && surface !== expected) return@synchronized
        surface = null
        bitmap?.close()
        bitmap = null
    }

    override fun submitNative(frame: VideoFrame, video: VideoInfo?): Boolean = frame.use {
        synchronized(lock) { draw(it) }
    }

    /** Converts [frame] and draws it onto the surface; the caller holds [lock]. */
    private fun draw(frame: VideoFrame): Boolean {
        val target = surface?.takeIf(Surface::isValid) ?: return false
        if (width <= 0 || height <= 0) return false
        val display = displaySize(frame.width, frame.height, frame.sampleAspectRatio, frame.rotationDegrees)
        val destination = contentScale.place(display, Size(width.toFloat(), height.toFloat()))
        val size = sizing.sizeFor(frame, IntSize(destination.width.roundToInt(), destination.height.roundToInt()))
        // The frame is drawn before this returns, so one bitmap serves every frame of its size and format.
        val format = frame.bitmapFormat()
        val converted = bitmap?.takeIf { it.width == size.width && it.height == size.height && it.format == format }
            ?.apply { write(frame) }
            ?: frame.toFrameBitmap(format, size).also {
                bitmap?.close()
                bitmap = it
            }
        val pixels = converted.image.asAndroidBitmap()
        val turn = uprightTurn(frame.rotationDegrees)
        val canvas = try {
            target.lockCanvas(null)
        } catch (_: Throwable) {
            return false
        }
        try {
            canvas.drawColor(backgroundArgb)
            canvas.save()
            canvas.rotate(turn, destination.center.x, destination.center.y)
            canvas.drawBitmap(pixels, null, destination.beforeTurn(turn).toAndroidRectF(), paint)
            canvas.restore()
        } finally {
            target.unlockCanvasAndPost(canvas)
        }
        return true
    }

    override fun discard() = Unit
}

private fun VideoInfo?.hasIdentityDisplayTransform(): Boolean =
    this != null && rotationDegrees.normalizedRotation() == 0f &&
        kotlin.math.abs(sampleAspectRatioValue() - 1.0) < 0.000_001
