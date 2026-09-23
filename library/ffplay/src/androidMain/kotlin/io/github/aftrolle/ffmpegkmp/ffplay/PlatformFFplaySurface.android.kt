// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.Surface
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.MutableStateFlow

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
    val playback by player.snapshot.collectAsState()
    val hostView = LocalView.current
    val output = remember(player, contentScale) {
        AndroidSurfaceOutput(contentScale, backgroundColor.toArgb())
    }
    output.updatePresentation(contentScale, backgroundColor.toArgb())
    output.updateVideoInfo(playback.video)
    output.updateDisplayCapabilities(hostView.display)

    LaunchedEffect(output, playback.video) {
        // Re-negotiate once stream display metadata is known. Transformed streams deliberately
        // use the software path because a raw MediaCodec Surface cannot apply FFmpeg's matrix.
        player.attachOutput(output)
    }

    DisposableEffect(player, output) {
        onDispose {
            player.detachOutput(output)
            output.detachSurface()
        }
    }

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

/**
 * Android's native-surface tier. MediaCodec frames are presented directly by the native player;
 * CPU-readable frames arrive here only when automatic negotiation selected the software fallback.
 */
private class AndroidSurfaceOutput(
    contentScale: ContentScale,
    backgroundArgb: Int,
) : FFplayVideoOutput {
    private val lock = Any()
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var contentScale = contentScale
    private var backgroundArgb = backgroundArgb
    private var videoInfo: FFplayVideoInfo? = null
    private var displayHdrTransfers: Set<String> = emptySet()
    private var displayColorSpaces: Set<String> = setOf("sRGB")
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override val kind: FFplayRendererKind = FFplayRendererKind.NATIVE_SURFACE
    override val frames = MutableStateFlow<FFplayFrame?>(null)
    override val capabilities: FFplayOutputCapabilities
        get() = synchronized(lock) {
            val directSurfacePresentation = contentScale == ContentScale.Fit &&
                videoInfo.hasIdentityDisplayTransform()
            FFplayOutputCapabilities(
                hardwareFrameImport = directSurfacePresentation,
                softwareFrameUpload = true,
                zeroCopy = directSurfacePresentation,
                hdrTransfers = if (directSurfacePresentation) displayHdrTransfers else emptySet(),
                colorSpaces = if (directSurfacePresentation) displayColorSpaces else setOf("sRGB"),
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

    fun updateVideoInfo(videoInfo: FFplayVideoInfo?) = synchronized(lock) {
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
                add("PQ")
            }
            if (Display.HdrCapabilities.HDR_TYPE_HLG in hdrTypes) add("HLG")
        }
        displayColorSpaces = buildSet {
            add("sRGB")
            if (hdrTypes.isNotEmpty()) add("BT.2020")
            if (Build.VERSION.SDK_INT >= 26 && display?.isWideColorGamut == true) {
                add("Display P3")
                add("DCI-P3")
            }
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
        if (expected == null || surface === expected) surface = null
    }

    override fun submit(frame: FFplayFrame): Boolean = synchronized(lock) {
        val target = surface?.takeIf(Surface::isValid) ?: return false
        if (width <= 0 || height <= 0) return false
        val bitmap = frame.image.asAndroidBitmap()
        val rotation = frame.rotationDegrees.normalizedRotation()
        val quarterTurn = rotation == 90f || rotation == 270f
        val pixelWidth = bitmap.width * frame.sampleAspectRatio.toFloat()
        val pixelHeight = bitmap.height.toFloat()
        val sourceSize = if (quarterTurn) Size(pixelHeight, pixelWidth) else Size(pixelWidth, pixelHeight)
        val scale = contentScale.computeScaleFactor(sourceSize, Size(width.toFloat(), height.toFloat()))
        val renderedWidth = sourceSize.width * scale.scaleX
        val renderedHeight = sourceSize.height * scale.scaleY
        val destinationWidth = (if (quarterTurn) renderedHeight else renderedWidth).toInt()
        val destinationHeight = (if (quarterTurn) renderedWidth else renderedHeight).toInt()
        val left = (width - destinationWidth) / 2
        val top = (height - destinationHeight) / 2
        val canvas = try {
            target.lockCanvas(null)
        } catch (_: Throwable) {
            return false
        }
        try {
            canvas.drawColor(backgroundArgb)
            canvas.save()
            canvas.rotate(rotation, width / 2f, height / 2f)
            canvas.drawBitmap(
                bitmap,
                null,
                Rect(left, top, left + destinationWidth, top + destinationHeight),
                paint,
            )
            canvas.restore()
            frames.value = frame
        } finally {
            target.unlockCanvasAndPost(canvas)
        }
        true
    }

    override fun discard() = synchronized(lock) {
        frames.value = null
    }
}

private fun FFplayVideoInfo?.hasIdentityDisplayTransform(): Boolean =
    this != null && rotationDegrees.normalizedRotation() == 0f &&
        kotlin.math.abs(sampleAspectRatioValue() - 1.0) < 0.000_001
