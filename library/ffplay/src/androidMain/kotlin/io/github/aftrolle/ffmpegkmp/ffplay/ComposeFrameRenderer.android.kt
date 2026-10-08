// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareBufferRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.aftrolle.ffmpegkmp.bindings.NativeGpuBuffer
import io.github.aftrolle.ffmpegkmp.bindings.allocateNativeFrame
import io.github.aftrolle.ffmpegkmp.bindings.convertFromBitmap
import io.github.aftrolle.ffmpegkmp.bindings.convertFromHardwareBuffer
import io.github.aftrolle.ffmpegkmp.codec.DynamicRange
import io.github.aftrolle.ffmpegkmp.codec.FrameColor
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.PixelLayout
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoTrack
import io.github.aftrolle.ffmpegkmp.codec.toNative
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Composes [content] and draws it into frames FFmpeg owns, in [format], for an export:
 * `track.write(renderer.render(frame.pts, value))`.
 *
 * A composition on Android needs a window, so the renderer hosts the content in a `Presentation`
 * on a private `VirtualDisplay` of [width] × [height], which an app may create for its own content
 * without a permission. Each [render] composes [value] at [time] on a frame clock of the
 * renderer's own, then measures, lays out and draws the view itself, without waiting for the display.
 * On Android 14 (API 34) and later the view is recorded into a `RenderNode` that
 * `HardwareBufferRenderer` draws on the GPU into a `HardwareBuffer`; earlier, or where that fails,
 * it is drawn onto a software canvas over a bitmap. Either way one conversion copies the pixels
 * into a new pooled frame the caller owns.
 *
 * A renderer made for a [VideoTrack] can skip that copy. On Android 14 and later, for an 8-bit SDR H.264
 * or HEVC track, or an HDR10 or HLG HEVC track, on a hardware encoder, the first [render] turns the track
 * [VideoTrack.zeroCopy]: the GPU then draws into buffers of the encoder's own input surface, and [render] returns a
 * frame over the buffer it drew, which [VideoTrack.write] queues to the encoder with its [VideoFrame.pts], so no
 * pixel is copied or converted. Such a frame lies in GPU memory like a `GpuBuffers` one, and its buffer goes back
 * to the encoder when the frame closes, so a caller holds at most a few at a time. The renderer then draws on the
 * GPU only, and fails where it cannot. An HDR track's buffers hold 10-bit PQ or HLG codes, which the GPU makes in
 * a second pass of its own from the F16 canvas the view is drawn onto, with the same curves and matrix the
 * one-copy path's converter applies.
 *
 * [format] is [FrameFormat.Rgba8] (sRGB, `ARGB_8888`), 10-bit RGB in sRGB (`RGBA_1010102`, API 33
 * and later) for 10-bit SDR, or [FrameFormat.RgbaF16] (linear extended sRGB, `RGBA_F16`, API 26
 * and later) for HDR, where the shapes and text Compose draws sit at reference white, 203 nits. Pixels are premultiplied, and what the content leaves uncovered is
 * transparent black.
 *
 * The frame clock follows [render]'s time, as on Skiko, so `withFrameNanos`, `animate*AsState` and
 * infinite transitions land exactly on each frame, and the system's animation scale does not apply.
 * Only the first render waits for the display, until the window has attached the view, and fails
 * if that takes 5 seconds. The main thread composes and draws; the conversion runs off it.
 */
public class ComposeFrameRenderer<T> internal constructor(
    context: Context,
    public val width: Int,
    public val height: Int,
    public val format: FrameFormat,
    public val density: Density,
    private val gpu: Boolean,
    private val content: @Composable (T) -> Unit,
) : AutoCloseable {
    public constructor(
        context: Context,
        width: Int,
        height: Int,
        format: FrameFormat = FrameFormat.Rgba8,
        density: Density = Density(1f),
        content: @Composable (T) -> Unit,
    ) : this(context, width, height, format, density, gpu = true, content)

    /**
     * Takes the size and the canvas format from [track]. Before Android 13, which has no 10-bit
     * bitmaps, a 10-bit SDR track gets an [FrameFormat.Rgba8] canvas, which the track converts.
     */
    public constructor(
        context: Context,
        track: VideoTrack,
        density: Density = Density(1f),
        content: @Composable (T) -> Unit,
    ) : this(
        context,
        track.config.width,
        track.config.height,
        track.config.canvasFormat.takeIf { it != RGBA_1010102 || Build.VERSION.SDK_INT >= 33 } ?: FrameFormat.Rgba8,
        density,
        content,
    ) {
        this.track = track
    }

    init {
        require(width > 0 && height > 0) { "The size must be positive: ${width}x$height" }
        require(format == FrameFormat.Rgba8 || format == FrameFormat.RgbaF16 || format == RGBA_1010102) {
            "On Android Compose draws into Rgba8, 10-bit sRGB or RgbaF16 frames, not $format"
        }
        require(format != FrameFormat.RgbaF16 || Build.VERSION.SDK_INT >= 26) {
            "RGBA_F16 canvases need Android 8.0 (API 26); this is API ${Build.VERSION.SDK_INT}"
        }
        require(format != RGBA_1010102 || Build.VERSION.SDK_INT >= 33) {
            "RGBA_1010102 canvases need Android 13 (API 33); this is API ${Build.VERSION.SDK_INT}"
        }
    }

    private val context = context.applicationContext
    private val current = mutableStateOf<Any?>(Unset)
    private var host: Host? = null
    private var bitmap: Bitmap? = null
    private var gpuCanvas: Any? = null

    /** The shader encoding the F16 canvas into an HDR track's buffers, made for the first frame that needs it. */
    private var encodeShader: Any? = null

    /** The track whose encoder's input surface to draw into, where it offers one; set for a renderer made for a track. */
    private var track: VideoTrack? = null

    /** Whether frames go into [track]'s input surface; known once the first frame has asked the track. */
    private var surfaceOpen: Boolean? = null

    /** The canvases of the input surface's buffers, each keeping its renderer and display list, by the buffer it was made for. */
    private val surfaceCanvases = HashMap<HardwareBuffer, GpuCanvas>()
    private var gpuFailed = false
    private var closed = false

    /** Whether the last frame was drawn on the GPU. */
    internal var drewOnGpu: Boolean = false
        private set

    /**
     * Composes [value] at [time] and draws it into a new pooled frame of [format], shown from [time]; for a
     * [VideoTrack.zeroCopy] track, into a frame over the encoder's own buffer, which has no pixels in memory.
     */
    public suspend fun render(time: Duration, value: T): VideoFrame = copy(time, draw(time, value))

    /**
     * Composes [value] at [time] and draws it, on the GPU into a HardwareBuffer, waiting for the GPU to finish, or
     * onto the software canvas's bitmap: what [copy] then copies. For a [VideoTrack.zeroCopy] track the buffer
     * is the encoder's, taken from it first, and what [copy] wraps. [render] is the two; the export measurement
     * times them apart.
     */
    internal suspend fun draw(time: Duration, value: T): Any {
        val buffer = if (opensSurface()) withContext(Dispatchers.Default) { checkNotNull(track).dequeueFrameBuffer() } else null
        return try {
            withContext(Dispatchers.Main) {
                check(!closed) { "The renderer is closed" }
                current.value = value
                val host = host ?: Host(context, width, height, density, root).also { host = it }
                host.awaitWindow()
                awaitPendingMainMessages()
                host.performFrame(time.inWholeNanoseconds)
                buffer?.also { drawIntoSurface(host.view, it) } ?: drawOnGpu(host.view) ?: drawInSoftware(host.view)
            }
        } catch (failure: Throwable) {
            buffer?.release()
            throw failure
        }
    }

    /**
     * Copies what [draw] drew into a new pooled frame of [format], shown from [time]; a frame over the encoder's
     * buffer where it drew into that, which copies nothing.
     */
    internal suspend fun copy(time: Duration, drawn: Any): VideoFrame {
        if (drawn is NativeGpuBuffer) return VideoFrame.of(drawn, pts = time, duration = Duration.ZERO, width, height)
        return withContext(Dispatchers.Default) {
            val frame = VideoFrame.of(allocateNativeFrame(format.toNative(), width, height), pts = time, duration = Duration.ZERO)
            try {
                frame.useNative { native ->
                    if (drawn is Bitmap) convertFromBitmap(drawn, checkNotNull(native)) else convertGpuCanvas(drawn, checkNotNull(native))
                }
            } catch (failure: Throwable) {
                frame.close()
                throw failure
            }
            frame
        }
    }

    /** Frees the composition and its display. Call it once the last [render] has returned. */
    override fun close() {
        if (closed) return
        closed = true
        val host = host
        val canvases = surfaceCanvases.values.toList() + listOfNotNull(gpuCanvas as? AutoCloseable)
        this.host = null
        gpuCanvas = null
        surfaceCanvases.clear()
        // The Presentation belongs to the main thread.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            host?.close()
            canvases.forEach(AutoCloseable::close)
        }
    }

    /**
     * Whether frames go into the track's input surface, asking the track once: an 8-bit SDR or an HDR renderer on
     * the GPU, on Android 14 and later, for a track that offers one.
     */
    private suspend fun opensSurface(): Boolean = surfaceOpen ?: (
        gpu && Build.VERSION.SDK_INT >= 34 && (format == FrameFormat.Rgba8 || format == FrameFormat.RgbaF16) &&
            track?.openInputSurface() == true
        ).also { surfaceOpen = it }

    /**
     * Draws the view on the GPU into the encoder's [buffer], waiting for the GPU to finish. An HDR track's buffer
     * takes PQ or HLG codes, which HWUI cannot be told to write, so the view is drawn onto the F16 canvas first and
     * [ENCODE_SHADER] turns that into the codes.
     */
    private suspend fun drawIntoSurface(view: View, buffer: NativeGpuBuffer) {
        check(Build.VERSION.SDK_INT >= 34)
        val hardwareBuffer = buffer.handle as HardwareBuffer
        val canvas = surfaceCanvases.getOrPut(hardwareBuffer) {
            surfaceCanvases.values.removeAll { canvas -> canvas.buffer.isClosed.also { if (it) canvas.close() } }
            GpuCanvas(hardwareBuffer, width, height, ColorSpace.get(ColorSpace.Named.SRGB))
        }
        if (hardwareBuffer.format == HardwareBuffer.RGBA_1010102) {
            val scene = ownGpuCanvas()
            scene.draw(view)
            canvas.draw(scene.buffer, encodeShader())
        } else {
            canvas.draw(view)
        }
        drewOnGpu = true
    }

    @RequiresApi(34)
    private fun ownGpuCanvas(): GpuCanvas = (gpuCanvas as GpuCanvas?) ?: GpuCanvas.allocate(width, height, format).also { gpuCanvas = it }

    @RequiresApi(34)
    private fun encodeShader(): RuntimeShader = (encodeShader as RuntimeShader?) ?: RuntimeShader(ENCODE_SHADER).apply {
        setFloatUniform("hlg", if (checkNotNull(track).config.dynamicRange == DynamicRange.HLG) 1f else 0f)
    }.also { encodeShader = it }

    /** The GPU's canvas once drawn into, or null where it is not available or has failed. */
    private suspend fun drawOnGpu(view: View): Any? {
        if (!gpu || gpuFailed || Build.VERSION.SDK_INT < 34) return null
        return try {
            val canvas = ownGpuCanvas()
            canvas.draw(view)
            drewOnGpu = true
            canvas
        } catch (failure: Exception) {
            // The software canvas draws the same formats; stay on it.
            gpuFailed = true
            println("FFmpegKMP: ComposeFrameRenderer could not draw on the GPU (${failure.message}); drawing in software")
            null
        }
    }

    private fun drawInSoftware(view: View): Bitmap {
        val target = bitmap ?: createBitmap().also { bitmap = it }
        target.eraseColor(0)
        view.draw(Canvas(target))
        drewOnGpu = false
        return target
    }

    private fun convertGpuCanvas(canvas: Any, frame: io.github.aftrolle.ffmpegkmp.bindings.NativeFrame) {
        check(Build.VERSION.SDK_INT >= 34)
        convertFromHardwareBuffer((canvas as GpuCanvas).buffer, frame)
    }

    private val root: @Composable () -> Unit = {
        val value = current.value
        @Suppress("UNCHECKED_CAST")
        if (value !== Unset) content(value as T)
    }

    private fun createBitmap(): Bitmap = when (format) {
        FrameFormat.RgbaF16 ->
            Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16, true, ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
        RGBA_1010102 -> Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_1010102, true, ColorSpace.get(ColorSpace.Named.SRGB))
        else -> Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private object Unset
}

/**
 * The GPU draws a recorded view, or another buffer through a shader, into [buffer] in [colorSpace], frame after
 * frame; a buffer it is given stays its lender's to close.
 */
@RequiresApi(34)
private class GpuCanvas(
    val buffer: HardwareBuffer,
    private val width: Int,
    private val height: Int,
    private val colorSpace: ColorSpace,
    private val ownsBuffer: Boolean = false,
) : AutoCloseable {
    private val node = RenderNode("FFmpegKMP ComposeFrameRenderer").apply { setPosition(0, 0, width, height) }
    private val renderer = HardwareBufferRenderer(buffer).apply { setContentRoot(node) }
    private val paint = Paint()

    /**
     * The wraps of the buffers drawn through a shader, the last two kept: a wrap made once would go on showing its
     * first frame, as the decode side found, since HWUI caches the texture it makes of a hardware bitmap, and the
     * display list may still name the previous wrap.
     */
    private val wraps = ArrayDeque<Bitmap>(3)

    suspend fun draw(view: View) = render { canvas ->
        // The buffer holds the last frame: what the content leaves uncovered is transparent black.
        canvas.drawColor(0, BlendMode.CLEAR)
        view.draw(canvas)
    }

    /**
     * Fills the buffer with [shader] over [scene], labelled sRGB so that, drawn into an sRGB canvas, Skia hands the
     * shader the scene's values as they are and writes its output back untouched.
     */
    suspend fun draw(scene: HardwareBuffer, shader: RuntimeShader) {
        val wrap = checkNotNull(Bitmap.wrapHardwareBuffer(scene, ColorSpace.get(ColorSpace.Named.SRGB))) { "Could not wrap the scene in a bitmap" }
        wraps.addLast(wrap)
        while (wraps.size > 2) wraps.removeFirst().recycle()
        shader.setInputShader("scene", BitmapShader(wrap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        paint.shader = shader
        render { canvas -> canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint) }
    }

    private suspend fun render(record: (Canvas) -> Unit) {
        val canvas = node.beginRecording()
        try {
            record(canvas)
        } finally {
            node.endRecording()
        }
        val result = suspendCancellableCoroutine { continuation ->
            renderer.obtainRenderRequest().setColorSpace(colorSpace).draw({ it.run() }) { continuation.resume(it) }
        }
        check(result.status == HardwareBufferRenderer.RenderResult.SUCCESS) { "the GPU could not draw the frame (${result.status})" }
        result.fence.use { fence -> check(fence.await(java.time.Duration.ofSeconds(5))) { "the GPU did not finish the frame" } }
    }

    override fun close() {
        renderer.close()
        node.discardDisplayList()
        wraps.forEach(Bitmap::recycle)
        wraps.clear()
        if (ownsBuffer) buffer.close()
    }

    companion object {
        /** A canvas over a new buffer of [format], which it closes with itself. */
        fun allocate(width: Int, height: Int, format: FrameFormat) = GpuCanvas(
            HardwareBuffer.create(
                width,
                height,
                when (format) {
                    FrameFormat.RgbaF16 -> HardwareBuffer.RGBA_FP16
                    RGBA_1010102 -> HardwareBuffer.RGBA_1010102
                    else -> HardwareBuffer.RGBA_8888
                },
                1,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN,
            ),
            width, height,
            ColorSpace.get(if (format == FrameFormat.RgbaF16) ColorSpace.Named.LINEAR_EXTENDED_SRGB else ColorSpace.Named.SRGB),
            ownsBuffer = true,
        )
    }
}

/**
 * The F16 canvas's linear light, 1.0 at 203 nits, to the full-range PQ or HLG codes an HDR track's BT.2020 buffer
 * holds: the inverse of the decode shader in `VideoFrameImage.android.kt`, with the same matrix the other way round.
 * The scene is premultiplied over transparent black, so its RGB is the light and the output is opaque. HLG takes
 * the inverse of BT.2100's OOTF for a 1000-nit display before its OETF, as the one-copy path's converter does; the
 * log's argument is floored since `mix` keeps a NaN from the branch it discards.
 */
private val ENCODE_SHADER = """
uniform shader scene;
uniform float hlg;

half4 main(float2 coord) {
    float3 light = max(float3(scene.eval(coord).rgb), 0.0);
    float3 wide = ${Bt2020Matrices.agsl(Bt2020Matrices.fromSrgb, "light")};
    float3 code;
    if (hlg > 0.5) {
        float3 display = min(wide * (203.0 / 1000.0), 1.0);
        float luminance = dot(display, float3(0.2627, 0.6780, 0.0593));
        float3 sceneLight = luminance > 0.0 ? min(display * pow(luminance, -1.0 / 6.0), 1.0) : float3(0.0);
        code = mix(
            sqrt(3.0 * sceneLight),
            0.17883277 * log(max(12.0 * sceneLight - 0.28466892, 1e-6)) + 0.55991073,
            step(float3(1.0 / 12.0), sceneLight)
        );
    } else {
        float3 signal = pow(min(wide * (203.0 / 10000.0), 1.0), float3(0.1593017578125));
        code = pow((0.8359375 + 18.8515625 * signal) / (1.0 + 18.6875 * signal), float3(78.84375));
    }
    return half4(half3(clamp(code, 0.0, 1.0)), 1.0);
}
"""

/**
 * Lets the main looper run what was posted before this frame. Compose posts a draw invalidation to the main handler
 * when snapshot state such as [FrameImage.update] changes off the main thread, as an ordinary message;
 * `Dispatchers.Main` dispatches [draw] as an asynchronous one, which a Choreographer sync barrier lets run first, so
 * without this the frame would record the previous drawing. A plain post of our own queues behind the invalidation.
 */
private suspend fun awaitPendingMainMessages() = suspendCancellableCoroutine { continuation ->
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    val runnable = Runnable { continuation.resume(Unit) }
    handler.post(runnable)
    continuation.invokeOnCancellation { handler.removeCallbacks(runnable) }
}

private suspend fun awaitFrame() = suspendCancellableCoroutine { continuation ->
    val callback = Choreographer.FrameCallback { continuation.resume(Unit) }
    Choreographer.getInstance().postFrameCallback(callback)
    continuation.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
}

/**
 * The window the composition lives in: a Presentation on a private virtual display, with the owners Compose needs.
 *
 * The composition has a recomposer of its own, on a frame clock [performFrame] drives, as Skiko's scene does, so
 * animations follow the renderer's time rather than the Choreographer's. Its coroutines run on the main thread
 * through [MainQueue], which [performFrame] runs to the end, so a frame is composed before it returns.
 */
private class Host(context: Context, private val width: Int, private val height: Int, density: Density, content: @Composable () -> Unit) :
    LifecycleOwner, SavedStateRegistryOwner {
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
        // The window is hidden, so the display shows nothing; drain whatever it hands over all the same.
        setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, android.os.Handler(android.os.Looper.getMainLooper()))
    }
    private val display: VirtualDisplay = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
        "FFmpegKMP ComposeFrameRenderer",
        width,
        height,
        (density.density * 160).roundToInt(),
        reader.surface,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
    )
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val presentation = Presentation(context, display.display)
    private val queue = MainQueue()
    private val clock = BroadcastFrameClock()
    private val job = Job()
    private val recomposer = Recomposer(queue + job)
    val view = ComposeView(presentation.context)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        CoroutineScope(queue + clock + job).launch { recomposer.runRecomposeAndApplyChanges() }
        savedState.performRestore(Bundle())
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setParentCompositionContext(recomposer)
        view.setContent(content)
        presentation.setContentView(view, ViewGroup.LayoutParams(width, height))
        presentation.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            // The renderer draws the view itself; the window drawing it too onto the display would only cost time.
            decor.visibility = View.INVISIBLE
        }
        presentation.show()
    }

    /**
     * Waits, the first time only, for the window to attach the view, which then composes. A window that has not
     * attached it within [WINDOW_TIMEOUT] never will, as when the app may not show a Presentation.
     */
    suspend fun awaitWindow() {
        withTimeoutOrNull(WINDOW_TIMEOUT) { while (!view.hasComposition) awaitFrame() }
            ?: throw IllegalStateException(
                "ComposeFrameRenderer's window did not attach its view within $WINDOW_TIMEOUT: the Presentation on " +
                    "its virtual display was not shown",
            )
    }

    /**
     * Composes the changes since the last frame at [nanoTime], runs the frame's animations and effects, and measures
     * and lays out the view, ready to draw.
     */
    fun performFrame(nanoTime: Long) {
        Snapshot.sendApplyNotifications()
        queue.flush()
        clock.sendFrame(nanoTime)
        queue.flush()
        Snapshot.sendApplyNotifications()
        if (view.isLayoutRequested || !view.isLaidOut) {
            view.measure(exactly(width), exactly(height))
            view.layout(0, 0, width, height)
        }
    }

    fun close() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        presentation.dismiss()
        recomposer.cancel()
        job.cancel()
        display.release()
        reader.close()
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}

/**
 * The composition's coroutines, on the main thread: queued, and run by [flush] or by the main looper, whichever comes
 * first, as Skiko's `FrameRecomposer` runs its own.
 */
private class MainQueue : CoroutineDispatcher() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(tasks) { tasks.addLast(block) }
        handler.post(::flush)
    }

    /** Runs every queued task, and those they queue in turn. Call it on the main thread. */
    fun flush() {
        while (true) (synchronized(tasks) { tasks.removeFirstOrNull() } ?: return).run()
    }
}

/** How long the first render waits for the renderer's window. */
private val WINDOW_TIMEOUT = 5.seconds

/** 10-bit RGB in sRGB: a 10-bit SDR track's canvas. */
private val RGBA_1010102 = FrameFormat(PixelLayout.RGBA_1010102, FrameColor.Srgb)
