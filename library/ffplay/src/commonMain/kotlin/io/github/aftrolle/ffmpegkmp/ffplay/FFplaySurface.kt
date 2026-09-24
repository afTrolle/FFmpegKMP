// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
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

@Composable
public fun FFplaySurface(
    player: FFplayPlayer,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    backgroundColor: Color = Color.Black,
) {
    PlatformFFplaySurface(player, modifier, contentScale, backgroundColor)
}

@Composable
internal expect fun PlatformFFplaySurface(
    player: FFplayPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    backgroundColor: Color,
)

@Composable
internal fun ComposeCanvasFFplaySurface(
    player: FFplayPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    backgroundColor: Color,
) {
    val output = remember(player) { ComposeCanvasOutput() }
    val frame by output.frames.collectAsState()
    DisposableEffect(player, output) {
        player.attachOutput(output)
        onDispose { player.detachOutput(output) }
    }
    Canvas(modifier) {
        drawRect(backgroundColor)
        frame?.let { drawFrame(it, contentScale) }
    }
}

private class ComposeCanvasOutput : FFplayVideoOutput {
    override val kind: FFplayRendererKind = FFplayRendererKind.COMPOSE_CANVAS
    val frames = MutableStateFlow<FFplayFrame?>(null)
    override val capabilities = FFplayOutputCapabilities(
        softwareFrameUpload = true,
        zeroCopy = false,
        // Wide-gamut/F16 support is promoted only by platform outputs after a
        // real display/backend capability check.
        hdrTransfers = emptySet(),
        colorSpaces = setOf("sRGB"),
        toneMapHdrToSdr = true,
    )

    override fun submit(frame: FFplayFrame): Boolean {
        frames.value = frame
        return true
    }

    override fun discard() {
        frames.value = null
    }
}

internal fun DrawScope.drawFrame(frame: FFplayFrame, contentScale: ContentScale) {
    val rotation = frame.rotationDegrees.normalizedRotation()
    val quarterTurn = rotation == 90f || rotation == 270f
    val pixelWidth = frame.image.width * frame.sampleAspectRatio.toFloat()
    val pixelHeight = frame.image.height.toFloat()
    val source = if (quarterTurn) Size(pixelHeight, pixelWidth) else Size(pixelWidth, pixelHeight)
    val scale = contentScale.computeScaleFactor(source, size)
    val destination = Size(source.width * scale.scaleX, source.height * scale.scaleY)
    val unrotatedWidth = if (quarterTurn) destination.height else destination.width
    val unrotatedHeight = if (quarterTurn) destination.width else destination.height
    val left = (size.width - unrotatedWidth) / 2f
    val top = (size.height - unrotatedHeight) / 2f
    withTransform({ rotate(rotation, center) }) {
        drawImage(
            image = frame.image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(frame.image.width, frame.image.height),
            dstOffset = IntOffset(left.toInt(), top.toInt()),
            dstSize = IntSize(unrotatedWidth.toInt(), unrotatedHeight.toInt()),
        )
    }
}

internal fun Double.normalizedRotation(): Float =
    (((this % 360.0) + 360.0) % 360.0).toFloat()
