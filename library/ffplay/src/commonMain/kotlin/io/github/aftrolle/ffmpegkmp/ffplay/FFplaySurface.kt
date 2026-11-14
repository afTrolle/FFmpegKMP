// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * pixels, the picture is rendered at as [targetSize], upright as `FrameImage.targetSize` takes it,
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
    targetSize: IntSize? = null,
) {
    require(targetSize == null || (targetSize.width >= 0 && targetSize.height >= 0)) {
        "A targetSize is not negative: $targetSize"
    }
    PlatformFFplaySurface(player, modifier, contentScale, backgroundColor, targetSize)
}

@Composable
internal expect fun PlatformFFplaySurface(
    player: FFplayPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    backgroundColor: Color,
    targetSize: IntSize?,
)

/**
 * The Compose Canvas output. [playerClockNanos] reads the clock the player runs on, where the
 * composition's frame times are on it too (Android: `System.nanoTime`, as Choreographer's): frames
 * are then chosen at the display frame's own time. Null, or a frame time far from that clock, as
 * from a test clock, chooses them at the time the refresh runs. [hardwareFrames] makes, where the
 * platform can, the way a hardware decoder's frames reach the canvas from GPU memory.
 */
@Composable
internal fun ComposeCanvasFFplaySurface(
    player: FFplayPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    backgroundColor: Color,
    targetSize: IntSize?,
    playerClockNanos: (() -> Long)? = null,
    hardwareFrames: (() -> CanvasHardwareFrames)? = null,
) {
    val output = remember(player) { ComposeCanvasOutput(player, playerClockNanos, hardwareFrames?.invoke()) }
    output.targetSize = targetSize
    DisposableEffect(player, output) {
        player.attachOutput(output)
        onDispose {
            player.detachOutput(output)
            output.close()
        }
    }
    LaunchedEffect(output) {
        launch { output.redrawWhileStill() }
        launch { output.negotiateWithStream() }
        output.driveRefresh()
    }
    Canvas(modifier) {
        drawRect(backgroundColor)
        drawFrameImage(output.image, contentScale)
        canvasFrameProbe?.drawn(player, output.image.shown)
        output.drawnIn(size, contentScale)
    }
}

/**
 * Test-only: told of each frame a player's Canvas surface takes and of each drawing of it, so that a
 * device measurement (`PlaybackTimingDeviceTest`) can tell in which display frame a video frame was
 * first drawn. Calls come on the player's thread and on the drawing thread; they must be quick.
 */
@InternalFFmpegKmpApi
internal interface CanvasFrameProbe {
    /** [player]'s frame at [pts], converted as it was queued, is being presented on its Canvas output. */
    fun submitting(player: FFplayPlayer, pts: Duration)

    /** The frame at [pts] was presented: drawings that show [shown] show it. Null when the output did not take it. */
    fun submitted(player: FFplayPlayer, pts: Duration, shown: FrameImage.Shown?)

    /** A drawing of [player]'s surface showed [shown], null for none. */
    fun drawn(player: FFplayPlayer, shown: FrameImage.Shown?)
}

/** The probe every Canvas surface tells, for tests; null, the default, costs one read per frame and per drawing. */
@InternalFFmpegKmpApi
@Volatile
internal var canvasFrameProbe: CanvasFrameProbe? = null

/**
 * How a hardware decoder's frames reach a Canvas output from GPU memory, with no copy: Android 14's
 * `ImageReader`. While the output [takes] a stream, it offers the player's hardware decoder its
 * [surface] and has the player render each frame into it as it is queued; [inGpuMemory] then turns
 * the frame into the buffer it was rendered into, which the canvas draws and the GPU scales.
 */
internal interface CanvasHardwareFrames : AutoCloseable {
    /** The surface a hardware decoder renders into: the output's platform target while it [takes] the stream. */
    val surface: Any

    /** Whether a hardware decoder's frames of [video] are drawn from GPU memory, looking as the conversion would. */
    fun takes(video: VideoInfo): Boolean

    /**
     * [frame] in GPU memory, taking it: for a hardware decoder's frame, rendered into [surface], a
     * frame of the buffer it was rendered into, once it is there, waiting for it on the calling
     * thread; [frame] itself for one in memory. Throws, having closed [frame], when the buffer does
     * not come.
     */
    fun inGpuMemory(frame: VideoFrame, video: VideoInfo?): VideoFrame

    /**
     * For a source of one buffer, which is the one on screen while [frame] is queued: takes
     * [frame], a hardware decoder's rendered into [surface], and returns what gives its frame in GPU
     * memory once the buffer on screen went back, as it is presented. Null, [frame] left alone, for
     * a frame in memory and where [inGpuMemory] takes frames as they are queued.
     */
    fun later(frame: VideoFrame, video: VideoInfo?): (() -> VideoFrame)? = null

    /** The most hardware frames queued at once of a player that queues [frameQueueSize], each holding a buffer. */
    fun queueLimit(frameQueueSize: Int): Int = frameQueueSize
}

/**
 * Converts each frame as the player queues it, on the player's session thread, into a free bitmap
 * of its image's ring at the size the picture is drawn at then, and shows it with a flip; refreshes
 * the player once per display frame from the composition's frame clock.
 *
 * With [hardware], a stream it takes is decoded in hardware where the player can: the decoder
 * renders each frame into the hardware's surface as it is queued, and the output keeps the frame's
 * GPU buffer instead of a bitmap, drawn as it is. Until the stream is known, and for one it does not
 * take, frames come in memory as without it; which one the player uses shows in its
 * [FFplayOutputInfo]: a hardware decoder with zero copy, or a software one.
 */
internal class ComposeCanvasOutput(
    private val player: FFplayPlayer,
    private val playerClockNanos: (() -> Long)?,
    private val hardware: CanvasHardwareFrames? = null,
) : FFplayVideoOutput, AutoCloseable {
    override val kind: FFplayRendererKind = FFplayRendererKind.COMPOSE_CANVAS
    val image = FrameImage(
        bitmaps = player.configuration.canvasFrames,
        keepsPrevious = player.configuration.canvasFrames >= canvasRingSize(player.configuration.frameQueueSize),
    )

    /**
     * Whether hardware frames are taken, for the stream last negotiated for: offered before the
     * stream is known only where hardware decoding is required, which the conversion cannot give.
     */
    @Volatile
    private var takesHardware = hardware != null && player.configuration.decoder == CodecPreference.REQUIRE_HARDWARE

    override val capabilities: FFplayOutputCapabilities
        get() {
            val gpu = takesHardware
            return FFplayOutputCapabilities(
                hardwareFrameImport = gpu,
                softwareFrameUpload = true,
                zeroCopy = gpu,
                // Wide-gamut/F16 support is promoted only by platform outputs after a
                // real display/backend capability check.
                hdrTransfers = emptySet(),
                colorSpaces = setOf(ColorPrimaries.BT709),
                toneMapHdrToSdr = true,
                prepareHardwareFrames = gpu,
                hardwareFrameLimit = if (gpu) hardware?.queueLimit(player.configuration.frameQueueSize) ?: 0 else 0,
            )
        }

    override val platformTarget: Any? get() = hardware?.surface?.takeIf { takesHardware }

    /**
     * Learns the stream's [video]: true when that changes whether hardware frames are taken, and the
     * player must negotiate with this output again.
     */
    fun negotiatesFor(video: VideoInfo?): Boolean {
        if (hardware == null || video == null) return false
        val wanted = hardware.takes(video)
        if (wanted == takesHardware) return false
        takesHardware = wanted
        return true
    }

    /** Negotiates again whenever the stream the player plays changes what this output takes. */
    suspend fun negotiateWithStream() {
        if (hardware == null) return
        player.snapshot.map { it.video }.distinctUntilChanged().collect { video ->
            if (negotiatesFor(video)) player.attachOutput(this)
        }
    }

    /** Where bitmaps cannot scale frames, in the browser, the bridge converts them to the size they are drawn at. */
    private val frameSize = BridgeFrameSize()

    override val preparesFrames: Boolean get() = true

    override fun prepareNative(frame: VideoFrame, video: VideoInfo?): FFplayPreparedFrame? {
        val pts = frame.pts
        val size = IntSize(frame.width, frame.height)
        val rotation = frame.rotationDegrees
        val sampleAspectRatio = frame.sampleAspectRatio
        val later = hardware?.later(frame, video)
        if (later != null) {
            return image.prepareLate(pts, size, rotation, sampleAspectRatio, video?.let(SdrToneMap::of), later)?.let(::CanvasFrame)
        }
        val drawable = hardware?.inGpuMemory(frame, video) ?: frame
        // PQ and HLG from GPU memory are tone mapped as they are drawn, as the conversion would.
        val toneMap = if (drawable.gpuBuffer != null) video?.let(SdrToneMap::of) else null
        // The browser's frames come at the drawn size: layout follows the video they show.
        val prepared = image.prepare(
            drawable,
            video?.takeUnless { bitmapsScale }?.let { info -> IntSize(info.width, info.height) },
            toneMap,
        ) ?: return null
        return CanvasFrame(prepared)
    }

    override fun submitNative(frame: VideoFrame, video: VideoInfo?): Boolean =
        prepareNative(frame, video)?.present() ?: false

    /** A frame converted into [prepared]'s bitmap; the probe sees it handed over and shown as it is presented. */
    private inner class CanvasFrame(private val prepared: FrameImage.Prepared) : FFplayPreparedFrame {
        override fun present(): Boolean {
            val probe = canvasFrameProbe
            probe?.submitting(player, prepared.pts)
            return image.present(prepared).also { accepted -> probe?.submitted(player, prepared.pts, if (accepted) image.shown else null) }
        }

        override fun release() = image.release(prepared)
    }

    override val drivesRefresh: Boolean get() = true
    private val refresh = MutableStateFlow<FFplayRefreshDriver?>(null)

    override fun bindRefresh(driver: FFplayRefreshDriver?) {
        refresh.value = driver
    }

    /**
     * Refreshes the player once per display frame of the composition's frame clock while it plays,
     * at the frame's time where that is on the player's clock, the frame becoming visible one
     * display frame later; waits, asking for no frames, while it does not play. Runs while the
     * surface is composed and attached.
     */
    suspend fun driveRefresh() {
        val interval = DisplayFrameInterval()
        refresh.collectLatest { driver ->
            if (driver == null) return@collectLatest
            while (true) {
                driver.awaitFrames()
                withFrameNanos { frameTimeNanos ->
                    val periodNanos = interval.next(frameTimeNanos)
                    val clockNanos = playerClockNanos?.invoke()
                    val nowUs = if (clockNanos != null && abs(clockNanos - frameTimeNanos) < CLOCK_AGREEMENT_NANOS) {
                        frameTimeNanos / 1_000
                    } else {
                        -1L
                    }
                    // A followed clock is asked about the frame's own time, the same for every player in it.
                    driver.refresh(nowUs, periodNanos / 1_000, frameTimeNanos.nanoseconds)
                }
            }
        }
    }

    /**
     * Converts the frame on screen again, off the drawing thread, when the picture's size changes
     * while the player does not play, or when it stops playing on a frame converted at another size.
     * While it plays, frames queued before a resize are drawn scaled until newer ones, converted at
     * the new size, replace them.
     */
    suspend fun redrawWhileStill() {
        val states = player.snapshot.map { it.state }.distinctUntilChanged()
        combine(image.sizeChanges, states) { _, state -> state }.collectLatest { state ->
            if (state == FFplayState.PLAYING) return@collectLatest
            withContext(Dispatchers.Default) { image.redraw() }
        }
    }

    override fun bindFrameSize(sink: ((width: Int, height: Int) -> Unit)?) {
        if (!bitmapsScale) frameSize.bind(sink)
    }

    /** The size the picture is rendered at, for frames converted at that size; null for the drawn size. */
    var targetSize: IntSize?
        get() = image.targetSize
        set(value) {
            image.targetSize = value
        }

    /** Tells the bridge, where it converts frames, the size the picture is drawn at in [area]. */
    fun drawnIn(area: Size, contentScale: ContentScale) {
        val shown = image.shown ?: return
        frameSize.drawnIn(shown.frameSize, shown.rotationDegrees, shown.displaySize, area, contentScale, targetSize)
    }

    override fun discard() = image.clear()

    /** Frees the bitmaps; the hardware's buffers go back as the frames that hold them close. */
    override fun close() {
        image.close()
        hardware?.close()
    }
}

/**
 * The image a player's Canvas output converts its frames into, with three bitmaps rather than two:
 * the player's native thread updates it at the video's rate, which drawing does not pace, so a
 * drawing must not see its bitmap written by the second update after it began.
 */
internal fun playerFrameImage(): FrameImage = FrameImage(bitmaps = 3)

/**
 * The frames a Canvas output keeps drawable unless [FFplayConfiguration.canvasFrames] says
 * otherwise, for a player that queues [frameQueueSize] frames: one for each queued frame, converted
 * as it is queued, the one on screen, and the one shown before it, which a drawing may still be
 * using.
 */
internal fun canvasRingSize(frameQueueSize: Int): Int = frameQueueSize + 2

/**
 * A display's frame period, from the times of the frames a frame clock gives: the shortest gap of
 * the last [WINDOW], so that a missed frame does not lengthen it and a faster display shortens it
 * within that many frames; 60 Hz's before two frames came.
 */
internal class DisplayFrameInterval {
    private val gaps = LongArray(WINDOW)
    private var count = 0
    private var slot = 0
    private var last = Long.MIN_VALUE

    /** Takes the frame at [frameTimeNanos] and returns the period, in nanoseconds. */
    fun next(frameTimeNanos: Long): Long {
        val gap = frameTimeNanos - last
        last = frameTimeNanos
        if (gap in 1..MAX_GAP_NANOS) {
            gaps[slot] = gap
            slot = (slot + 1) % WINDOW
            if (count < WINDOW) count++
        }
        if (count == 0) return DEFAULT_NANOS
        var shortest = Long.MAX_VALUE
        for (index in 0 until count) shortest = minOf(shortest, gaps[index])
        return shortest
    }

    private companion object {
        const val WINDOW = 16
        const val DEFAULT_NANOS = 16_666_667L

        /** Longer is a pause, not a display frame. */
        const val MAX_GAP_NANOS = 100_000_000L
    }
}

/** How close a frame time must be to the player's clock to be taken as a time on it. */
private const val CLOCK_AGREEMENT_NANOS = 1_000_000_000L

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
