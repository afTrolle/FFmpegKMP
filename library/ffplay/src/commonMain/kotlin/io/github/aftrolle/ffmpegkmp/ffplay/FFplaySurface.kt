// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/** A player that is closed when it leaves the composition. */
@Composable
public fun rememberFFplayPlayer(
    configuration: FFplayConfiguration = FFplayConfiguration(),
): FFplayPlayer = rememberFFplayPlayer(configuration, ::FFplayPlayer)

@Composable
internal fun rememberFFplayPlayer(
    configuration: FFplayConfiguration,
    create: (FFplayConfiguration) -> FFplayPlayer,
): FFplayPlayer = remember(configuration) { RememberedFFplayPlayer(create(configuration)) }.player

/**
 * Closes [player] when it is forgotten, or when the composition that created it is abandoned
 * before it applies, which an effect never observes.
 */
internal class RememberedFFplayPlayer(val player: FFplayPlayer) : RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() = release()
    override fun onAbandoned() = release()

    @OptIn(DelicateCoroutinesApi::class)
    private fun release() {
        // Cancelling native work is immediate; destroying the engine may wait for it to stop.
        player.requestClose()
        GlobalScope.launch(Dispatchers.Default) { player.close() }
    }
}

/**
 * Shows [player]'s video, placed by [contentScale] on [backgroundColor].
 *
 * Frames the surface converts itself, on a Compose canvas or where hardware decoding fell back to
 * software, are converted at the size the picture is drawn at, which is all a tile of a 4K source
 * needs. A surface cannot see a transform drawn around it, such as `Modifier.graphicsLayer` or
 * `Modifier.scale`, so a zoomed surface would show that small picture stretched: pass the size, in
 * pixels, the picture is rendered at as [resolution], upright as `FrameImage.targetSize` takes it,
 * such as its drawn size times the zoom, or the video's own size for every pixel. Frames are never
 * converted larger than the video. Null, the default, is the drawn size. Frames shown by a
 * platform's own video view, from a hardware decoder, are full size whatever this says.
 */
@Composable
public fun FFplaySurface(
    player: FFplayPlayer,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    backgroundColor: Color = Color.Black,
    resolution: IntSize? = null,
) {
    require(resolution == null || (resolution.width >= 0 && resolution.height >= 0)) {
        "A resolution is not negative: $resolution"
    }
    PlatformFFplaySurface(player, modifier, contentScale, backgroundColor, resolution)
}

@Composable
internal expect fun PlatformFFplaySurface(
    player: FFplayPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    backgroundColor: Color,
    resolution: IntSize?,
)

@Composable
internal fun ComposeCanvasFFplaySurface(
    player: FFplayPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    backgroundColor: Color,
    resolution: IntSize?,
) {
    val output = remember(player) { ComposeCanvasOutput() }
    output.resolution = resolution
    DisposableEffect(player, output) {
        player.attachOutput(output)
        onDispose {
            player.detachOutput(output)
            output.close()
        }
    }
    Canvas(modifier) {
        drawRect(backgroundColor)
        drawFrameImage(output.image, contentScale)
        output.drawnIn(size, contentScale)
    }
}

private class ComposeCanvasOutput : FFplayVideoOutput, AutoCloseable {
    override val kind: FFplayRendererKind = FFplayRendererKind.COMPOSE_CANVAS
    val image = playerFrameImage()
    override val capabilities = FFplayOutputCapabilities(
        softwareFrameUpload = true,
        zeroCopy = false,
        // Wide-gamut/F16 support is promoted only by platform outputs after a
        // real display/backend capability check.
        hdrTransfers = emptySet(),
        colorSpaces = setOf(ColorPrimaries.BT709),
        toneMapHdrToSdr = true,
    )

    /** Where bitmaps cannot scale frames, in the browser, the bridge converts them to the size they are drawn at. */
    private val frameSize = BridgeFrameSize()

    override fun submitNative(frame: VideoFrame, video: VideoInfo?): Boolean = frame.use {
        // The browser's frames come at the drawn size: layout follows the video they show.
        image.updateIfOpen(it, video?.takeUnless { bitmapsScale }?.let { info -> IntSize(info.width, info.height) })
    }

    override fun bindFrameSize(sink: ((width: Int, height: Int) -> Unit)?) {
        if (!bitmapsScale) frameSize.bind(sink)
    }

    /** The size the picture is rendered at, for frames converted at that size; null for the drawn size. */
    var resolution: IntSize?
        get() = image.targetSize
        set(value) {
            image.targetSize = value
        }

    /** Tells the bridge, where it converts frames, the size the picture is drawn at in [area]. */
    fun drawnIn(area: Size, contentScale: ContentScale) {
        val shown = image.shown ?: return
        frameSize.drawnIn(shown.frameSize, shown.rotationDegrees, shown.displaySize, area, contentScale, resolution)
    }

    override fun discard() = image.clear()

    override fun close() = image.close()
}

/**
 * The image a player's Canvas output converts its frames into, with three bitmaps rather than two:
 * the player's native thread updates it at the video's rate, which drawing does not pace, so a
 * drawing must not see its bitmap written by the second update after it began.
 */
internal fun playerFrameImage(): FrameImage = FrameImage(bitmaps = 3)

/**
 * Draws [image] placed by [contentScale] in the whole canvas; nothing before its first frame. Tells
 * the image where it is drawn, so that the next frames are converted at the size they are drawn at.
 */
internal fun DrawScope.drawFrameImage(image: FrameImage, contentScale: ContentScale) {
    image.drawnIn(size, contentScale)
    val destination = contentScale.place(image.displaySize, size)
    drawFrameImage(image, destination.topLeft, destination.size)
}

internal fun Double.normalizedRotation(): Float =
    (((this % 360.0) + 360.0) % 360.0).toFloat()
