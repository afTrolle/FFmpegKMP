// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
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
import androidx.compose.ui.unit.toSize
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.PixelLayout
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
 * Frames are converted at their own size unless [targetSize] says what size the image is drawn at:
 * then at the step of 16 of the frame's size that covers it, so a tile of a 4K source holds tile-sized
 * bitmaps. [displaySize] stays the frame's whatever size the bitmaps are.
 *
 * [update] converts into the bitmap that is not on screen, then shows it, so the one being drawn
 * is never written: [update] may run off the drawing thread, as when a preview collects its frames
 * on `Dispatchers.Default` and the UI thread never converts. An export calls [update], then
 * `ComposeFrameRenderer.render`. Updates run one at a time. A drawing still under way two updates
 * later would see its bitmap written; one update per drawn frame never does. [close] frees both.
 *
 * On Android a frame in GPU memory, from `VideoOutput.GpuBuffers`, is shown with no copy and no
 * bitmap of its own: [update] wraps its `HardwareBuffer` anew, since a wrap of a buffer shows
 * only the frame the buffer held when it was made, and draws its crop. A wrap shows its frame only
 * while the frame is open, so the image retains the frames of its last two updates, the one on
 * screen and the one a drawing may still be using, and closes each once two more updates have
 * come. With a decoder's ring of three, that leaves room for the frame `frames()` decodes ahead.
 * Such frames need a GPU canvas: drawing one onto a software canvas, such as
 * `ComposeFrameRenderer`'s software path, fails.
 *
 * A 10-bit PQ or HLG buffer draws through a shader of the renderer's own, as linear light with 1.0
 * at 203 nits, as the same frame from memory does on an `RgbaF16` canvas. It is not tone mapped, so
 * draw it on an `RgbaF16` canvas, and take an SDR export's HDR sources from `Memory(canvasFormat)`,
 * which tone maps.
 *
 * A player's Canvas output uses it another way, [prepare] then [present]: each frame is converted
 * as the player queues it, ahead of its time, into a free bitmap of a ring of `bitmaps`, and
 * presenting it flips to that bitmap. A frame queued while the ring has no bitmap free stays as it
 * was decoded and is converted as it is presented, on the presenting thread: into the bitmap that
 * left the screen last, or, with a ring of one, over the picture on screen. With `keepsPrevious`
 * the frame that left the screen last keeps its bitmap, or its GPU buffer, until the next one
 * leaves, for a drawing still under way; without it that goes back to the ring at once. One image
 * is used one way or the other, not both.
 */
public class FrameImage internal constructor(
    private val bitmaps: Int,
    private val keepsPrevious: Boolean = true,
) : AutoCloseable {
    /** An image of two bitmaps: enough while each update is drawn before the one after next. */
    public constructor() : this(2)

    init {
        require(bitmaps >= 1) { "A frame image needs at least one bitmap: $bitmaps" }
    }

    /**
     * What is drawn; a new value for each update, so that reading it is what invalidates. It is
     * snapshot state, written once the bitmap it shows is complete (see [show]), so a drawing in
     * any snapshot sees a whole frame.
     */
    internal var shown: Shown? by mutableStateOf(null)
        private set

    /** Serializes updates and close, which own the fields below. */
    private val lock = FFplayOperationLock()

    /** The size of the bitmaps the next conversion needs; [lock] guards it. */
    private val sizing = BitmapSizing()

    /** [targetSize], packed by [pack]: read on the updating thread without a snapshot. */
    private val target = AtomicLong(UNSET)

    /** The area a player's surface last drew this image in, packed, and how it placed it there. */
    private val drawArea = AtomicLong(UNSET)
    private val drawScale = AtomicReference<ContentScale>(ContentScale.Fit)

    /** The bitmap [shown] draws, and those shown before it, oldest first: the next conversion writes the oldest. */
    private var front: FrameBitmap? = null
    private val behind = ArrayDeque<FrameBitmap>()
    private var convertedPts: Duration? = null
    private var closed = false

    /** The frames in GPU memory the last two updates showed, oldest first; null for an update from memory. */
    private val held = ArrayDeque<VideoFrame?>()
    private val wraps = gpuFrameWraps()

    /** The ring [prepare] converts into: its bitmaps no frame holds, oldest first, and how many it has in all. */
    private val free = ArrayDeque<FrameBitmap>()
    private var ringSize = 0

    /** The prepared frame on screen, and the one shown before it, which a drawing may still be using. */
    private var onScreen: Prepared? = null
    private var previous: Prepared? = null

    /**
     * Counts changes of where a surface draws this image and of [targetSize], so that a frame on
     * screen while nothing new comes, a paused player's, can be converted again at the new size.
     */
    internal val sizeChanges: StateFlow<Int> get() = mutableSizeChanges
    private val mutableSizeChanges = MutableStateFlow(0)

    /** Bitmaps allocated and frames converted, for tests. */
    internal var allocations: Int = 0
        private set
    internal var conversions: Int = 0
        private set

    /** Frames in GPU memory wrapped, for tests. */
    internal val wrapCount: Int get() = wraps.count

    /**
     * The size the last frame shows at, in pixels: stretched by its sample aspect ratio, then
     * turned by its rotation. [Size.Zero] before the first [update].
     */
    public val displaySize: Size get() = shown?.displaySize ?: Size.Zero

    /**
     * The size, in pixels, this image is drawn at, upright as [displaySize] is; null, the default,
     * for frames converted at their own size. Each frame is then converted to the smallest of 16
     * steps of its own size, `size × step / 16`, that covers this on both axes: the frame's aspect,
     * and never larger than the frame. A target that moves allocates bitmaps only as it crosses a
     * step: at once when it grows, and only two steps or more down when it shrinks. A
     * [PixelLayout.RGBA_F16] frame, which converts pixel for pixel, and every frame in the browser
     * are converted at their own size whatever this says; there a player's frames arrive at the
     * size its surface draws them, converted by its worker.
     *
     * Setting it costs nothing and may happen on any thread, as often as a drawing does: the next
     * [update] reads it. Until then the frame on screen is drawn scaled from the size it has. A
     * player's Canvas surface sets it to `FFplaySurface`'s `resolution`; while that is null it tells
     * its image where it draws it, which this overrides.
     */
    public var targetSize: IntSize?
        get() = target.load().unpack()
        set(value) {
            require(value == null || (value.width >= 0 && value.height >= 0)) { "A target size is not negative: $value" }
            if (target.exchange(value?.pack() ?: UNSET) != (value?.pack() ?: UNSET)) mutableSizeChanges.update { it + 1 }
        }

    /**
     * Records that a player's surface draws this image in [area], placed by [contentScale]; the next
     * update converts at the size that places the frame at, unless [targetSize] is set. Two atomic
     * writes and no snapshot state, so drawing neither invalidates nor allocates.
     */
    internal fun drawnIn(area: Size, contentScale: ContentScale) {
        val packed = IntSize(area.width.roundToInt(), area.height.roundToInt()).pack()
        val scaleBefore = drawScale.exchange(contentScale)
        if (drawArea.exchange(packed) != packed || scaleBefore != contentScale) mutableSizeChanges.update { it + 1 }
    }

    /** The size [drawnIn] places [frame] at; null before a surface drew this image. */
    internal fun drawnSize(frame: VideoFrame): IntSize? {
        val area = drawArea.load().unpack() ?: return null
        val display = displaySize(frame.width, frame.height, frame.sampleAspectRatio, frame.rotationDegrees)
        val placed = drawScale.load().place(display, area.toSize()).size
        return IntSize(placed.width.roundToInt(), placed.height.roundToInt())
    }

    /**
     * Shows [frame]: converts it into the bitmap not on screen and shows that one, unless its pts
     * is the one already shown at the size [targetSize] asks for, and takes its rotation and sample
     * aspect ratio. A frame in GPU memory is shown as it is, and retained until two more updates
     * have come. The frame stays open: close it when done with it.
     *
     * Throws [IllegalStateException] for a frame without pixels this image can reach, and once this
     * image is closed.
     */
    public fun update(frame: VideoFrame) {
        check(updateIfOpen(frame)) { "The frame image is closed" }
    }

    /**
     * [update], or false once this image is closed, when a frame may still arrive from another thread.
     * [videoSize] is the size of the video [frame] shows when a bridge converted it smaller, as the
     * browser's worker converts a player's frames to the size they are drawn at: [displaySize] then
     * stays the video's.
     */
    internal fun updateIfOpen(frame: VideoFrame, videoSize: IntSize? = null): Boolean = lock.withLock {
        if (closed) return@withLock false
        if (frame.gpuBuffer != null) {
            showGpuFrame(frame)
            return@withLock true
        }
        val format = frame.bitmapFormat()
        val size = sizing.sizeFor(frame, targetSize ?: drawnSize(frame))
        val frameSize = videoSize ?: IntSize(frame.width, frame.height)
        val previous = shown
        if (front?.holds(size, format) != true || frame.pts != convertedPts) {
            // A failed conversion leaves the bitmap on screen as it was.
            val oldest = behind.firstOrNull()?.takeIf { behind.size == bitmaps - 1 }
            val written = oldest?.takeIf { it.holds(size, format) }?.apply { write(frame) }
                ?: frame.toFrameBitmap(format, size).also {
                    allocations++
                    oldest?.close()
                }
            if (oldest != null) behind.removeFirst()
            conversions++
            front?.let(behind::addLast)
            front = written
            convertedPts = frame.pts
        } else if (previous != null && previous.frameSize == frameSize &&
            previous.rotationDegrees == frame.rotationDegrees && previous.sampleAspectRatio == frame.sampleAspectRatio
        ) {
            return@withLock true
        }
        val shown = checkNotNull(front)
        show(Shown(shown.image, shown.source, frameSize, frame.rotationDegrees, frame.sampleAspectRatio, gpu = false))
        if (held.isNotEmpty()) hold(null)
        true
    }

    /** Whether a frame is shown: from the first update until [clear] or [close]. Reading it observes changes. */
    internal val showsFrame: Boolean get() = shown != null

    /** Shows nothing until the next update, keeping the bitmaps for it. */
    internal fun clear(): Unit = lock.withLock {
        if (closed || shown == null) return@withLock
        show(null)
        // A drawing may still be using what was on screen: it goes back to the ring with the next present.
        previous?.let(::retire)
        previous = onScreen?.also(::leaveScreen)
        onScreen = null
        dropPrevious()
    }

    /**
     * Makes [frame] drawable ahead of its time and takes it, for [present] to show later: converts
     * it, on the calling thread, into a bitmap of the ring that nothing shows or holds, at the size
     * [targetSize] asks for, else the size a surface last drew this image at, as [update] does. The
     * ring reuses its bitmaps while that size and the frame's format stay; one of another size is
     * replaced. A frame in GPU memory is kept as it is. [videoSize] is [updateIfOpen]'s.
     *
     * Exactly one [present] or [release] must follow. Returns null once this image is closed, having
     * closed [frame]; throws, having closed it, when it cannot be converted. With [platformHdr], a PQ or
     * HLG frame in GPU memory is shown as the platform shows HDR, rather than as linear light.
     */
    internal fun prepare(frame: VideoFrame, videoSize: IntSize? = null, platformHdr: Boolean = false): Prepared? {
        val frameSize = videoSize ?: IntSize(frame.width, frame.height)
        val claim = try {
            val format = if (frame.gpuBuffer == null) frame.bitmapFormat() else null
            lock.withLock {
                when {
                    closed -> null
                    format == null -> NO_BITMAP
                    // Every bitmap is shown or holds a frame queued before this one: converted as it is presented.
                    free.isEmpty() && ringSize >= bitmaps -> DEFERRED
                    else -> claim(sizing.sizeFor(frame, targetSize ?: drawnSize(frame)), format)
                }
            }
        } catch (failure: Throwable) {
            frame.close()
            throw failure
        }
        if (claim == null) {
            frame.close()
            return null
        }
        val bitmap = if (claim === NO_BITMAP || claim === DEFERRED) null else convert(frame, claim)
        val prepared = Prepared(frame.pts, bitmap, frame, frameSize, frame.rotationDegrees, frame.sampleAspectRatio, platformHdr)
        if (lock.withLock { closed }) {
            retire(prepared)
            return null
        }
        return prepared
    }

    /**
     * [prepare] for a frame in GPU memory that [take] gives only as it is presented, since the one
     * buffer its source has is the one on screen until then: [present] gives that one back, then
     * takes this frame, on the presenting thread. Null once this image is closed.
     */
    internal fun prepareLate(
        pts: Duration,
        frameSize: IntSize,
        rotationDegrees: Double,
        sampleAspectRatio: Double,
        platformHdr: Boolean,
        take: () -> VideoFrame,
    ): Prepared? = lock.withLock {
        if (closed) return@withLock null
        Prepared(pts, null, null, frameSize, rotationDegrees, sampleAspectRatio, platformHdr).also { it.late = take }
    }

    /**
     * Gives [prepared] what it is drawn from where [prepare] left that for now: takes a late frame,
     * or converts a deferred one. False, the frame done with, when that fails. Under [lock].
     */
    private fun materialize(prepared: Prepared): Boolean {
        val late = prepared.late
        if (late != null) {
            prepared.late = null
            // The one buffer goes back before the next frame is taken from it.
            previous?.let(::retire)
            previous = null
            onScreen?.let(::retire)
            onScreen = null
            prepared.frame = try {
                late()
            } catch (failure: Exception) {
                show(null)
                prepared.done = true
                return false
            }
            return true
        }
        val frame = prepared.frame ?: return true
        if (prepared.bitmap != null || frame.gpuBuffer != null) return true
        prepared.bitmap = try {
            val format = frame.bitmapFormat()
            val size = sizing.sizeFor(frame, targetSize ?: drawnSize(frame))
            if (free.isEmpty() && ringSize >= bitmaps) dropPrevious(always = true)
            val showing = onScreen
            val over = showing?.bitmap?.takeIf { free.isEmpty() && ringSize >= bitmaps }
            val claim = if (showing == null || over == null) {
                claim(size, format)
            } else {
                // No bitmap but the one on screen: this frame is written over it.
                showing.bitmap = null
                onScreen = null
                retire(showing)
                if (over.holds(size, format)) {
                    Claim(over, size, format)
                } else {
                    over.close()
                    allocations++
                    Claim(null, size, format)
                }
            }
            convert(frame, claim)
        } catch (failure: Exception) {
            // convert closed the frame.
            prepared.frame = null
            prepared.done = true
            return false
        }
        return true
    }

    /** Gives back the frame that left the screen last, unless it is kept for a drawing under way. Under [lock]. */
    private fun dropPrevious(always: Boolean = false) {
        if (keepsPrevious && !always) return
        previous?.let(::retire)
        previous = null
    }

    /**
     * Shows [prepared], a flip to what [prepare] made; the frame shown before it goes back to the
     * ring with the present after this one, since a drawing may still be using it. False once this
     * image is closed, or for a frame already presented or released.
     */
    internal fun present(prepared: Prepared): Boolean = lock.withLock {
        if (closed || prepared.done || prepared === onScreen || prepared === previous) {
            if (prepared !== onScreen && prepared !== previous) retire(prepared)
            return@withLock false
        }
        if (!materialize(prepared)) return@withLock false
        val bitmap = prepared.bitmap
        val shown = if (bitmap != null) {
            Shown(bitmap.image, bitmap.source, prepared.frameSize, prepared.rotationDegrees, prepared.sampleAspectRatio, gpu = false)
        } else {
            val frame = checkNotNull(prepared.frame)
            val buffer = checkNotNull(frame.gpuBuffer)
            val wrapped = wraps.wrap(frame, prepared.platformHdr)
            val source = IntRect(buffer.cropLeft, buffer.cropTop, buffer.cropRight, buffer.cropBottom)
            Shown(
                wrapped.image, source, source.size, prepared.rotationDegrees, prepared.sampleAspectRatio, gpu = true,
                wrapped.transfer,
            )
        }
        flip(prepared, shown)
        true
    }

    /** Gives back a [prepare]d frame that will not be shown: its bitmap returns to the ring, its frame is closed. */
    internal fun release(prepared: Prepared): Unit = lock.withLock {
        if (prepared !== onScreen && prepared !== previous) retire(prepared)
    }

    /**
     * Converts the frame on screen again, from the frame it was made from, at the size this image is
     * now drawn at, and flips to it: for a resize while nothing new is shown, as when paused. Does
     * nothing where that size is the one on screen, for a frame in GPU memory, which scales as it is
     * drawn, and once the frame on screen changed meanwhile. The conversion runs on the calling
     * thread. Returns whether it redrew.
     */
    internal fun redraw(): Boolean {
        var current: Prepared? = null
        var source: VideoFrame? = null
        val claim = lock.withLock {
            val showing = onScreen?.takeIf { !closed && shown != null } ?: return@withLock null
            val frame = showing.frame ?: return@withLock null
            val bitmap = showing.bitmap ?: return@withLock null
            val size = sizing.sizeFor(frame, targetSize ?: drawnSize(frame))
            if (bitmap.holds(size, bitmap.format)) return@withLock null
            current = showing
            // Presents may move it off screen, and close the frame, while this converts.
            source = frame.retain()
            claim(size, bitmap.format)
        } ?: return false
        val frame = checkNotNull(source)
        val bitmap = try {
            convert(frame, claim)
        } finally {
            frame.close()
        }
        return lock.withLock {
            val showing = checkNotNull(current)
            if (closed || onScreen !== showing || shown == null) {
                giveBack(bitmap)
                return@withLock false
            }
            val redrawn = Prepared(showing.pts, bitmap, showing.frame, showing.frameSize, showing.rotationDegrees, showing.sampleAspectRatio)
            showing.frame = null
            flip(redrawn, Shown(bitmap.image, bitmap.source, redrawn.frameSize, redrawn.rotationDegrees, redrawn.sampleAspectRatio, gpu = false))
            true
        }
    }

    /** Bitmaps the ring has: free, prepared, on screen and the one before it. For tests. */
    internal val ringBitmaps: Int get() = lock.withLock { ringSize }

    /** Frees the bitmaps and closes the frames held. Whatever draws the image draws nothing from then on. */
    override fun close(): Unit = lock.withLock {
        if (closed) return@withLock
        closed = true
        show(null)
        front?.close()
        behind.forEach { it.close() }
        front = null
        behind.clear()
        held.forEach { it?.close() }
        held.clear()
        // Prepared frames still out close their bitmaps as they come back.
        onScreen?.let(::retire)
        previous?.let(::retire)
        onScreen = null
        previous = null
        free.forEach { it.close() }
        ringSize -= free.size
        free.clear()
        wraps.close()
    }

    /** A free bitmap of the ring that holds [size] and [format]; else, with null, room for a new one. Under [lock]. */
    private fun claim(size: IntSize, format: FrameFormat): Claim {
        val fitting = free.firstOrNull { it.holds(size, format) }
        if (fitting != null) {
            free.remove(fitting)
            return Claim(fitting, size, format)
        }
        // Of another size or format: the oldest free one makes way.
        if (ringSize >= bitmaps) free.removeFirstOrNull()?.let {
            it.close()
            ringSize--
        }
        ringSize++
        allocations++
        return Claim(null, size, format)
    }

    /** [frame] converted into [claim]'s bitmap, or into a new one; a failure gives the claim back. */
    private fun convert(frame: VideoFrame, claim: Claim): FrameBitmap {
        val bitmap = try {
            claim.bitmap?.apply { write(frame) } ?: frame.toFrameBitmap(claim.format, claim.size)
        } catch (failure: Throwable) {
            lock.withLock { claim.bitmap?.let(::giveBack) ?: ringSize-- }
            frame.close()
            throw failure
        }
        lock.withLock { conversions++ }
        return bitmap
    }

    /** Shows [next], whose [shown] is ready; what was on screen becomes the previous frame, and the previous one goes back. */
    private fun flip(next: Prepared, shown: Shown) {
        previous?.let(::retire)
        previous = onScreen?.also(::leaveScreen)
        onScreen = next
        show(shown)
        dropPrevious()
    }

    /** A frame leaving the screen keeps its bitmap for a drawing still under way; a frame in memory is needed no more. */
    private fun leaveScreen(prepared: Prepared) {
        if (prepared.bitmap != null) {
            prepared.frame?.close()
            prepared.frame = null
        }
    }

    /** Done with [prepared]: its frame closed and its bitmap back in the ring. */
    private fun retire(prepared: Prepared) = lock.withLock {
        if (prepared.done) return@withLock
        prepared.done = true
        prepared.frame?.close()
        prepared.frame = null
        prepared.bitmap?.let(::giveBack)
    }

    /** Returns [bitmap] to the ring, or frees it once the image is closed or the ring has more than it keeps. */
    private fun giveBack(bitmap: FrameBitmap) {
        if (closed || ringSize > bitmaps) {
            bitmap.close()
            ringSize--
        } else {
            free.addLast(bitmap)
        }
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
        val wrapped = wraps.wrap(frame)
        if (!again) hold(frame.retain())
        // The next frame from memory converts, whatever the bitmap off screen holds.
        convertedPts = null
        val source = IntRect(buffer.cropLeft, buffer.cropTop, buffer.cropRight, buffer.cropBottom)
        show(Shown(wrapped.image, source, source.size, frame.rotationDegrees, frame.sampleAspectRatio, gpu = true, wrapped.transfer))
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

    private fun FrameBitmap.holds(size: IntSize, format: FrameFormat): Boolean =
        width == size.width && height == size.height && this.format == format

    /**
     * The [source] rectangle of [image], which holds a frame of [frameSize] at that size or smaller,
     * drawn turned and stretched; [gpu] when the image lies in GPU memory, where [transfer] says how
     * its pixels are coded: PQ and HLG ones are drawn as light.
     */
    internal class Shown(
        val image: ImageBitmap,
        val source: IntRect,
        val frameSize: IntSize,
        val rotationDegrees: Double,
        val sampleAspectRatio: Double,
        val gpu: Boolean,
        val transfer: ColorTransfer = ColorTransfer.SRGB,
    ) {
        /** The frame's, whatever size [image] holds it at, so that layout never follows the bitmaps. */
        val displaySize: Size = displaySize(frameSize.width, frameSize.height, sampleAspectRatio, rotationDegrees)
    }

    /**
     * A frame [prepare] made drawable: converted into [bitmap], one of the ring's, or for a frame in
     * GPU memory, null, the frame itself shown through a wrap of its buffer. [frameSize] is the
     * video's size, as [Shown.frameSize]; [platformHdr] is [prepare]'s.
     */
    internal class Prepared(
        val pts: Duration,
        bitmap: FrameBitmap?,
        frame: VideoFrame?,
        val frameSize: IntSize,
        val rotationDegrees: Double,
        val sampleAspectRatio: Double,
        val platformHdr: Boolean = false,
    ) {
        /**
         * The frame it was made from, kept until it leaves the screen, so that [redraw] can convert
         * it again; a frame in GPU memory until it leaves the ring, since it is what is drawn.
         * Guarded by the image's lock.
         */
        var frame: VideoFrame? = frame

        /** The ring's bitmap it is converted into; null for a frame in GPU memory, and until [present] for a deferred one. */
        var bitmap: FrameBitmap? = bitmap

        /** Takes the frame, in GPU memory, as it is presented ([prepareLate]); null otherwise and once taken. */
        var late: (() -> VideoFrame)? = null

        /** Retired: its bitmap went back to the ring. Guarded by the image's lock. */
        var done: Boolean = false
    }

    /** A bitmap [claim]ed for a conversion, or null for a new one, and what it converts to. */
    private class Claim(val bitmap: FrameBitmap?, val size: IntSize, val format: FrameFormat)

    private companion object {
        /** The claim of a frame in GPU memory, which is shown without a bitmap. */
        val NO_BITMAP = Claim(null, IntSize.Zero, FrameFormat.Rgba8)

        /** The claim of a frame queued while the ring has no bitmap free, converted as it is presented. */
        val DEFERRED = Claim(null, IntSize.Zero, FrameFormat.Rgba8)
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
    drawUpright(shown.image, shown.source, shown.rotationDegrees, Rect(topLeft, size), shown.transfer)
}

/** Draws [source] of [image] turned upright by [rotationDegrees], so that it fills [destination]. */
internal fun DrawScope.drawUpright(
    image: ImageBitmap,
    source: IntRect,
    rotationDegrees: Double,
    destination: Rect,
    transfer: ColorTransfer = ColorTransfer.SRGB,
) {
    val turn = uprightTurn(rotationDegrees)
    val target = destination.beforeTurn(turn)
    val dstOffset = IntOffset(target.left.roundToInt(), target.top.roundToInt())
    val dstSize = IntSize(target.width.roundToInt(), target.height.roundToInt())
    withTransform({ rotate(turn, destination.center) }) {
        when (transfer) {
            ColorTransfer.PQ, ColorTransfer.HLG -> drawCodedImage(image, source, dstOffset, dstSize, transfer)
            else -> drawImage(image, srcOffset = source.topLeft, srcSize = source.size, dstOffset = dstOffset, dstSize = dstSize)
        }
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

/** The steps of a frame's size that bitmaps are converted to: `size × step / BITMAP_STEPS`. */
internal const val BITMAP_STEPS = 16

/**
 * The size frames are converted to for a picture drawn at a given size: the smallest step of the
 * frame's own size that covers it on both axes, at least one and at most [BITMAP_STEPS]. A larger
 * step is taken at once, for quality; a smaller one only when it is two or more below the current
 * one, so a drawn size wobbling about a step's edge never reallocates. Its owner's lock guards it.
 */
internal class BitmapSizing {
    private var frameSize = IntSize.Zero
    private var step = BITMAP_STEPS

    /** The size to convert [frame] to when it is drawn upright at [drawn]; its own size for null. */
    fun sizeFor(frame: VideoFrame, drawn: IntSize?): IntSize =
        sizeFor(IntSize(frame.width, frame.height), frame.rotationDegrees, drawn?.takeIf { frame.scalesIntoBitmaps() })

    /** The size to convert a frame of [own] size, turned by [rotationDegrees], to when it is drawn upright at [drawn]. */
    fun sizeFor(own: IntSize, rotationDegrees: Double, drawn: IntSize?): IntSize {
        val needed = if (drawn == null) BITMAP_STEPS else own.stepCovering(drawn, rotationDegrees)
        if (own != frameSize || needed > step || needed <= step - 2) step = needed
        frameSize = own
        return own.atStep(step)
    }
}

/**
 * Tells a bridge that converts a player's frames itself, as the browser's worker does, the size an
 * output draws them at: the step of the video's size that covers the picture placed in the drawn
 * area, as [FrameImage.targetSize] takes it, in the video's own orientation. It tells only a change
 * of step, so drawing costs no message, and a size that wobbles about a step's edge none either.
 * The output's drawing thread uses it.
 */
internal class BridgeFrameSize {
    private val sizing = BitmapSizing()
    private var sink: ((width: Int, height: Int) -> Unit)? = null
    private var told: IntSize? = null

    /** Where to tell the size while an engine has the output attached; null once it is detached. */
    fun bind(sink: ((width: Int, height: Int) -> Unit)?) {
        this.sink = sink
        told = null
    }

    /**
     * Says that a picture of a [video]-sized frame turned by [rotationDegrees], [display] upright, is
     * placed by [contentScale] in [area], all in pixels, and rendered at [resolution] when it is set.
     */
    fun drawnIn(
        video: IntSize,
        rotationDegrees: Double,
        display: Size,
        area: Size,
        contentScale: ContentScale,
        resolution: IntSize? = null,
    ) {
        val sink = sink ?: return
        if (video.width <= 0 || video.height <= 0) return
        val drawn = resolution ?: run {
            if (area.width <= 0f || area.height <= 0f) return
            val placed = contentScale.place(display, area).size
            IntSize(placed.width.roundToInt(), placed.height.roundToInt())
        }
        val size = sizing.sizeFor(video, rotationDegrees, drawn)
        if (size == told) return
        told = size
        sink(size.width, size.height)
    }
}

/** The smallest step of this frame size that covers [drawn], a size drawn upright with a frame turned by [rotationDegrees]. */
private fun IntSize.stepCovering(drawn: IntSize, rotationDegrees: Double): Int {
    // Back to the frame's own orientation: a quarter turn swaps the sides.
    val turned = uprightTurn(rotationDegrees).isQuarterTurn()
    val drawnWidth = if (turned) drawn.height else drawn.width
    val drawnHeight = if (turned) drawn.width else drawn.height
    return maxOf(stepCovering(drawnWidth, width), stepCovering(drawnHeight, height))
}

private fun stepCovering(drawn: Int, own: Int): Int =
    ((drawn.toLong() * BITMAP_STEPS + own - 1) / own).coerceIn(1L, BITMAP_STEPS.toLong()).toInt()

/** This size at [step] of [BITMAP_STEPS], each side rounded up and at least one pixel. */
internal fun IntSize.atStep(step: Int): IntSize = IntSize(
    ((width.toLong() * step + BITMAP_STEPS - 1) / BITMAP_STEPS).toInt().coerceAtLeast(1),
    ((height.toLong() * step + BITMAP_STEPS - 1) / BITMAP_STEPS).toInt().coerceAtLeast(1),
)

/**
 * Whether this frame converts into a bitmap of another size: not from [PixelLayout.RGBA_F16], which
 * the converter takes into a bitmap pixel for pixel and refuses to scale, nor where the platform
 * cannot scale, as in the browser.
 */
internal fun VideoFrame.scalesIntoBitmaps(): Boolean = bitmapsScale && format?.layout != PixelLayout.RGBA_F16

private const val UNSET = -1L

private fun IntSize.pack(): Long = (width.toLong() shl 32) or (height.toLong() and 0xffffffffL)

private fun Long.unpack(): IntSize? = if (this == UNSET) null else IntSize((this ushr 32).toInt(), toInt())
