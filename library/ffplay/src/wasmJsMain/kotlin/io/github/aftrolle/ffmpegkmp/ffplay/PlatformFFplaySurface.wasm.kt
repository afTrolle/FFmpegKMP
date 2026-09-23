// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    ExperimentalWasmJsInterop::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.HtmlElementView
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame
import io.github.aftrolle.ffmpegkmp.bindings.NativePlatformVideoFrame
import io.github.aftrolle.ffmpegkmp.bindings.NativePlatformVideoFrameKind
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableStateFlow
import org.w3c.dom.HTMLCanvasElement

@Composable
internal actual fun PlatformFFplaySurface(
    player: FFplayPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    backgroundColor: Color,
) {
    val output = remember(player) { WasmCanvasOutput() }
    output.scaleMode = contentScale.webScaleMode()
    output.background = backgroundColor.cssColor()
    DisposableEffect(player, output) {
        player.attachOutput(output)
        onDispose { player.detachOutput(output) }
    }
    HtmlElementView(
        factory = { output.canvas },
        modifier = modifier,
        update = { canvas ->
            canvas.style.width = "100%"
            canvas.style.height = "100%"
            canvas.style.backgroundColor = output.background
        },
        onRelease = { output.discard() },
    )
}

private class WasmCanvasOutput : FFplayVideoOutput {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    override val kind = FFplayRendererKind.NATIVE_SURFACE
    override val frames = MutableStateFlow<FFplayFrame?>(null)
    override val capabilities = FFplayOutputCapabilities(
        hardwareFrameImport = webCodecsAvailable(),
        softwareFrameUpload = true,
        zeroCopy = false,
        hdrTransfers = emptySet(),
        colorSpaces = setOf("sRGB"),
        toneMapHdrToSdr = true,
    )
    var scaleMode: String = "fit"
    var background: String = "rgba(0,0,0,1)"

    override fun submit(frame: FFplayFrame): Boolean = false

    override fun submitNative(frame: NativeVideoFrame, video: FFplayVideoInfo?): Boolean {
        drawRgba(
            canvas,
            frame.rgba.toJsUint8Array(),
            frame.width,
            frame.height,
            scaleMode,
            video.sampleAspectRatioValue(),
            video?.rotationDegrees ?: 0.0,
        )
        return true
    }

    override fun submitPlatform(frame: NativePlatformVideoFrame, video: FFplayVideoInfo?): Boolean {
        if (frame.kind != NativePlatformVideoFrameKind.WEB_VIDEO_FRAME) return false
        return drawRegisteredVideoFrame(
            canvas,
            frame.handle as Int,
            scaleMode,
            video.sampleAspectRatioValue(),
            video?.rotationDegrees ?: 0.0,
        )
    }

    override fun discard() {
        clearCanvas(canvas)
    }
}

private fun ContentScale.webScaleMode(): String = when (this) {
    ContentScale.Crop -> "crop"
    ContentScale.FillBounds -> "fill"
    ContentScale.FillWidth -> "fillWidth"
    ContentScale.FillHeight -> "fillHeight"
    ContentScale.Inside -> "inside"
    ContentScale.None -> "none"
    else -> "fit"
}

private fun Color.cssColor(): String =
    "rgba(${(red * 255).toInt()},${(green * 255).toInt()},${(blue * 255).toInt()},$alpha)"

private fun ByteArray.toJsUint8Array(): JsAny {
    val result = createUint8Array(size)
    forEachIndexed { index, byte -> setUint8ArrayByte(result, index, byte.toInt() and 0xff) }
    return result
}

private fun createUint8Array(size: Int): JsAny = js("new Uint8Array(size)")
private fun setUint8ArrayByte(array: JsAny, index: Int, value: Int): Unit = js("array[index] = value")

private fun drawRgba(
    canvas: HTMLCanvasElement,
    rgba: JsAny,
    width: Int,
    height: Int,
    scaleMode: String,
    sampleAspectRatio: Double,
    rotationDegrees: Double,
): Unit = js(
    """
    {
      const ratio = globalThis.devicePixelRatio || 1;
      const targetWidth = Math.max(1, Math.round((canvas.clientWidth || width) * ratio));
      const targetHeight = Math.max(1, Math.round((canvas.clientHeight || height) * ratio));
      if (canvas.width !== targetWidth) canvas.width = targetWidth;
      if (canvas.height !== targetHeight) canvas.height = targetHeight;
      const context = canvas.getContext('2d', { alpha: false, colorSpace: 'srgb' });
      if (!context) return;
      if (!canvas.__ffplayScratch) canvas.__ffplayScratch = document.createElement('canvas');
      const scratch = canvas.__ffplayScratch;
      if (scratch.width !== width) scratch.width = width;
      if (scratch.height !== height) scratch.height = height;
      const scratchContext = scratch.getContext('2d', { alpha: false, colorSpace: 'srgb' });
      const bytes = new Uint8ClampedArray(rgba.buffer, rgba.byteOffset, width * height * 4);
      scratchContext.putImageData(new ImageData(bytes, width, height), 0, 0);
      const normalizedRotation = ((rotationDegrees % 360) + 360) % 360;
      const quarterTurn = normalizedRotation === 90 || normalizedRotation === 270;
      const displayWidth = quarterTurn ? height : width * sampleAspectRatio;
      const displayHeight = quarterTurn ? width * sampleAspectRatio : height;
      let sx = targetWidth / displayWidth;
      let sy = targetHeight / displayHeight;
      let scale;
      if (scaleMode === 'fill') {
        sx = targetWidth / width;
        sy = targetHeight / height;
      } else {
        if (scaleMode === 'crop') scale = Math.max(sx, sy);
        else if (scaleMode === 'fillWidth') scale = sx;
        else if (scaleMode === 'fillHeight') scale = sy;
        else if (scaleMode === 'none') scale = 1;
        else if (scaleMode === 'inside') scale = Math.min(1, Math.min(sx, sy));
        else scale = Math.min(sx, sy);
        sx = sy = scale;
      }
      const renderedWidth = Math.max(1, Math.round(displayWidth * sx));
      const renderedHeight = Math.max(1, Math.round(displayHeight * sy));
      const drawWidth = quarterTurn ? renderedHeight : renderedWidth;
      const drawHeight = quarterTurn ? renderedWidth : renderedHeight;
      context.clearRect(0, 0, targetWidth, targetHeight);
      context.save();
      context.translate(targetWidth / 2, targetHeight / 2);
      context.rotate(normalizedRotation * Math.PI / 180);
      context.drawImage(scratch, -drawWidth / 2, -drawHeight / 2, drawWidth, drawHeight);
      context.restore();
    }
    """,
)

private fun clearCanvas(canvas: HTMLCanvasElement): Unit = js(
    "{ const context = canvas.getContext('2d'); if (context) context.clearRect(0, 0, canvas.width, canvas.height); }",
)

private fun webCodecsAvailable(): Boolean = js(
    "typeof VideoDecoder === 'function' && typeof EncodedVideoChunk === 'function'",
)

private fun drawRegisteredVideoFrame(
    canvas: HTMLCanvasElement,
    frameId: Int,
    scaleMode: String,
    sampleAspectRatio: Double,
    rotationDegrees: Double,
): Boolean = js(
    """
    (() => {
      const registry = globalThis.__ffmpegkmpVideoFrames;
      const frame = registry ? registry.frames.get(frameId) : null;
      if (registry) registry.frames.delete(frameId);
      if (!frame) return false;
      try {
        const ratio = globalThis.devicePixelRatio || 1;
        const width = frame.displayWidth || frame.codedWidth;
        const height = frame.displayHeight || frame.codedHeight;
        const normalizedRotation = ((rotationDegrees % 360) + 360) % 360;
        const quarterTurn = normalizedRotation === 90 || normalizedRotation === 270;
        const displayWidth = quarterTurn ? height : width * sampleAspectRatio;
        const displayHeight = quarterTurn ? width * sampleAspectRatio : height;
        const targetWidth = Math.max(1, Math.round((canvas.clientWidth || width) * ratio));
        const targetHeight = Math.max(1, Math.round((canvas.clientHeight || height) * ratio));
        if (canvas.width !== targetWidth) canvas.width = targetWidth;
        if (canvas.height !== targetHeight) canvas.height = targetHeight;
        const context = canvas.getContext('2d', { alpha: false });
        if (!context) return false;
        let sx = targetWidth / displayWidth;
        let sy = targetHeight / displayHeight;
        let scale;
        if (scaleMode !== 'fill') {
          if (scaleMode === 'crop') scale = Math.max(sx, sy);
          else if (scaleMode === 'fillWidth') scale = sx;
          else if (scaleMode === 'fillHeight') scale = sy;
          else if (scaleMode === 'none') scale = 1;
          else if (scaleMode === 'inside') scale = Math.min(1, Math.min(sx, sy));
          else scale = Math.min(sx, sy);
          sx = sy = scale;
        }
        const renderedWidth = Math.max(1, Math.round(displayWidth * sx));
        const renderedHeight = Math.max(1, Math.round(displayHeight * sy));
        const drawWidth = quarterTurn ? renderedHeight : renderedWidth;
        const drawHeight = quarterTurn ? renderedWidth : renderedHeight;
        context.clearRect(0, 0, targetWidth, targetHeight);
        context.save();
        context.translate(targetWidth / 2, targetHeight / 2);
        context.rotate(normalizedRotation * Math.PI / 180);
        context.drawImage(frame, -drawWidth / 2, -drawHeight / 2, drawWidth, drawHeight);
        context.restore();
        return true;
      } finally {
        frame.close();
      }
    })()
    """,
)
