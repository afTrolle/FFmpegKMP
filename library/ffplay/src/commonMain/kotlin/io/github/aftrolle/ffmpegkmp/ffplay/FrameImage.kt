// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * One source's frames as an image Compose draws, in two bitmaps that [update] converts into in
 * turn. Where [toImageBitmap] allocates a bitmap a frame, which only the garbage collector frees, a
 * `FrameImage` converts a frame only when its pts differs from the last one it converted, and
 * allocates bitmaps only for the first two frames of each size and format. It takes the formats
 * [toImageBitmap] does.
 *
 * Draw it with [drawFrameImage], which turns it upright and stretches it by its sample aspect
 * ratio as it draws; [displaySize] is its size after both, for layout. Each [update] invalidates
 * whatever draws or measures it, in an export's `ComposeFrameRenderer` and in ordinary UI alike.
 *
 * [update] converts into the bitmap that is not on screen, then shows it, so the one being drawn
 * is never written: [update] may run off the drawing thread, as when a preview collects its frames
 * on `Dispatchers.Default` and the UI thread never converts. An export calls [update], then
 * `ComposeFrameRenderer.render`. Updates run one at a time. A drawing still under way two updates
 * later would see its bitmap written; one update per drawn frame never does. [close] frees both.
 *
 * On Android a frame in GPU memory, from `VideoOutput.GpuBuffers`, is shown with no copy and no
 * bitmap of its own: [update] wraps its `HardwareBuffer`, once for each of the decoder's buffers,
 * and draws its crop. A wrap shows the buffer only while its frame is open, so the image retains
 * the frames of its last two updates, the one on screen and the one a drawing may still be using,
 * and closes each once two more updates have come. With a decoder's ring of three, that leaves
 * room for the frame `frames()` decodes ahead. Such frames need a GPU canvas: drawing one onto a
 * software canvas, such as `ComposeFrameRenderer`'s software path, fails.
 */
public class FrameImage : AutoCloseable {
    /**
     * What is drawn; a new value for each update, so that reading it is what invalidates. It is
     * snapshot state, written once the bitmap it shows is complete (see [show]), so a drawing in
     * any snapshot sees a whole frame.
     */
    internal var shown: Shown? by mutableStateOf(null)
        private set

    /** Serializes updates and close, which own the fields below. */
    private val lock = FFplayOperationLock()

    /** The bitmap [shown] draws, and the other, which the next conversion writes. */
    private var front: FrameBitmap? = null
    private var back: FrameBitmap? = null
    private var convertedPts: Duration? = null
    private var closed = false

    /** The frames in GPU memory the last two updates showed, oldest first; null for an update from memory. */
    private val held = ArrayDeque<VideoFrame?>()
    private val wraps = gpuFrameWraps()

    /** Bitmaps allocated and frames converted, for tests. */
    internal var allocations: Int = 0
        private set
    internal var conversions: Int = 0
        private set

    /** GPU buffers wrapped, for tests. */
    internal val wrapCount: Int get() = wraps.count

    /**
     * The size the last frame shows at, in pixels: stretched by its sample aspect ratio, then
     * turned by its rotation. [Size.Zero] before the first [update].
     */
    public val displaySize: Size get() = shown?.displaySize ?: Size.Zero

    /**
     * Shows [frame]: converts it into the bitmap not on screen and shows that one, unless its pts
     * is the one already shown, and takes its rotation and sample aspect ratio. A frame in GPU
     * memory is shown as it is, and retained until two more updates have come. The frame stays
     * open: close it when done with it.
     *
     * Throws [IllegalStateException] for a frame without pixels this image can reach, such as one
     * rendered to a Surface, and once this image is closed.
     */
    public fun update(frame: VideoFrame): Unit = lock.withLock {
        check(!closed) { "The frame image is closed" }
        if (frame.gpuBuffer != null) return@withLock showGpuFrame(frame)
        val format = frame.bitmapFormat()
        val previous = shown
        if (front?.holds(frame, format) != true || frame.pts != convertedPts) {
            // A failed conversion leaves the bitmap on screen as it was.
            val written = back?.takeIf { it.holds(frame, format) }?.apply { write(frame) }
                ?: frame.toFrameBitmap(format).also {
                    allocations++
                    back?.close()
                }
            conversions++
            back = front
            front = written
            convertedPts = frame.pts
        } else if (previous != null && previous.rotationDegrees == frame.rotationDegrees &&
            previous.sampleAspectRatio == frame.sampleAspectRatio
        ) {
            return@withLock
        }
        val shown = checkNotNull(front)
        show(Shown(shown.image, shown.source, frame.rotationDegrees, frame.sampleAspectRatio, gpu = false))
        if (held.isNotEmpty()) hold(null)
    }

    /** Frees the bitmaps and closes the frames held. Whatever draws the image draws nothing from then on. */
    override fun close(): Unit = lock.withLock {
        if (closed) return@withLock
        closed = true
        show(null)
        front?.close()
        back?.close()
        front = null
        back = null
        held.forEach { it?.close() }
        held.clear()
        wraps.close()
    }

    /** Shows [frame], which lies in GPU memory, through a wrap of its buffer. */
    private fun showGpuFrame(frame: VideoFrame) {
        val buffer = checkNotNull(frame.gpuBuffer)
        val previous = shown
        val last = held.lastOrNull()
        val again = previous != null && last != null && last.gpuBuffer === buffer && last.pts == frame.pts
        if (again && previous.rotationDegrees == frame.rotationDegrees && previous.sampleAspectRatio == frame.sampleAspectRatio) {
            return
        }
        val image = wraps.wrap(frame)
        if (!again) hold(frame.retain())
        // The next frame from memory converts, whatever the bitmap off screen holds.
        convertedPts = null
        val source = IntRect(buffer.cropLeft, buffer.cropTop, buffer.cropRight, buffer.cropBottom)
        show(Shown(image, source, frame.rotationDegrees, frame.sampleAspectRatio, gpu = true))
    }

    /** Keeps [frame] as the last update's, and closes the one from two updates before it. */
    private fun hold(frame: VideoFrame?) {
        held.addLast(frame)
        while (held.size > 2) held.removeFirst()?.close()
    }

    /**
     * Writes [shown] in a snapshot of its own. Applying it tells every observer, such as each
     * renderer's scene, of the change before this returns, on this thread. Written in the global
     * snapshot instead, from a thread that draws nothing, the change would reach a scene only once
     * some thread sent the apply notifications, and a renderer sending them while another is
     * still delivering them can draw before its scene has seen the change.
     */
    private fun show(value: Shown?) = Snapshot.withMutableSnapshot { shown = value }

    private fun FrameBitmap.holds(frame: VideoFrame, format: FrameFormat): Boolean =
        width == frame.width && height == frame.height && this.format == format

    /** The [source] rectangle of [image], drawn turned and stretched; [gpu] when the image lies in GPU memory. */
    internal class Shown(
        val image: ImageBitmap,
        val source: IntRect,
        val rotationDegrees: Double,
        val sampleAspectRatio: Double,
        val gpu: Boolean,
    ) {
        val displaySize: Size = displaySize(source.width, source.height, sampleAspectRatio, rotationDegrees)
    }
}

/**
 * Draws [image] upright, turned by its rotation about the centre of the rectangle at [topLeft] of
 * [size], and stretched to fill it. Give the rectangle the aspect of [FrameImage.displaySize] to
 * show the picture undistorted: its sample aspect ratio is then applied too. Turning and
 * stretching happen as Compose draws, which costs nothing on the GPU. Draws nothing before the
 * image's first update. A frame in GPU memory needs a GPU canvas: on a software one this throws
 * [IllegalStateException].
 */
public fun DrawScope.drawFrameImage(
    image: FrameImage,
    topLeft: Offset = Offset.Zero,
    size: Size = Size(this.size.width - topLeft.x, this.size.height - topLeft.y),
) {
    val shown = image.shown ?: return
    if (shown.gpu) checkDrawsGpuImages()
    drawUpright(shown.image, shown.source, shown.rotationDegrees, Rect(topLeft, size))
}

/** Draws [source] of [image] turned upright by [rotationDegrees], so that it fills [destination]. */
internal fun DrawScope.drawUpright(image: ImageBitmap, source: IntRect, rotationDegrees: Double, destination: Rect) {
    val turn = uprightTurn(rotationDegrees)
    val target = destination.beforeTurn(turn)
    withTransform({ rotate(turn, destination.center) }) {
        drawImage(
            image = image,
            srcOffset = source.topLeft,
            srcSize = source.size,
            dstOffset = IntOffset(target.left.roundToInt(), target.top.roundToInt()),
            dstSize = IntSize(target.width.roundToInt(), target.height.roundToInt()),
        )
    }
}

/**
 * The size a [width] × [height] frame shows at: stretched by [sampleAspectRatio], then turned by
 * [rotationDegrees]. An unknown aspect ratio, zero, counts as square.
 */
internal fun displaySize(width: Int, height: Int, sampleAspectRatio: Double, rotationDegrees: Double): Size {
    val pixelWidth = width * (sampleAspectRatio.takeIf { it > 0.0 && it.isFinite() } ?: 1.0).toFloat()
    val pixelHeight = height.toFloat()
    return if (uprightTurn(rotationDegrees).isQuarterTurn()) Size(pixelHeight, pixelWidth) else Size(pixelWidth, pixelHeight)
}

/** [display] scaled by this into [container] and centred in it. */
internal fun ContentScale.place(display: Size, container: Size): Rect {
    if (display.width <= 0f || display.height <= 0f) return Rect.Zero
    val scale = computeScaleFactor(display, container)
    val size = Size(display.width * scale.scaleX, display.height * scale.scaleY)
    return Rect(Offset((container.width - size.width) / 2f, (container.height - size.height) / 2f), size)
}

/**
 * The clockwise turn, in 0 until 360 degrees, that shows a frame upright. FFmpeg reports a
 * frame's display rotation anticlockwise (`av_display_rotation_get`), and Compose, Android's
 * canvas and the browser's turn clockwise.
 */
internal fun uprightTurn(rotationDegrees: Double): Float = (-rotationDegrees).normalizedRotation()

/**
 * Where a frame's pixels go so that, turned clockwise by [turn] about this rectangle's centre,
 * they fill it: the same rectangle, its sides swapped for a quarter turn.
 */
internal fun Rect.beforeTurn(turn: Float): Rect {
    if (!turn.isQuarterTurn()) return this
    return Rect(Offset(center.x - height / 2f, center.y - width / 2f), Size(height, width))
}

private fun Float.isQuarterTurn(): Boolean = this == 90f || this == 270f
