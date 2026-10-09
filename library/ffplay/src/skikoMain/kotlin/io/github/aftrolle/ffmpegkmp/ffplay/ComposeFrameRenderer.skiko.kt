// SPDX-License-Identifier: Apache-2.0
// Compiled into the JVM, Kotlin/Native and web targets, which all draw through Skia.
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.aftrolle.ffmpegkmp.bindings.allocateNativeFrame
import io.github.aftrolle.ffmpegkmp.codec.FrameFormat
import io.github.aftrolle.ffmpegkmp.codec.PixelLayout
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoTrack
import io.github.aftrolle.ffmpegkmp.codec.toNative
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

/**
 * Composes [content] and draws it straight into frames FFmpeg owns, in [format], for an export:
 * `track.write(renderer.render(frame.pts, value))`. Each [render] composes the content with
 * [value] at [time], drives the frame clock from [time], so animations land exactly on each
 * frame, and draws into a new pooled frame, which the caller owns.
 *
 * [format] is an RGB layout, because Skia draws RGB: [PixelLayout.RGBA8] or [PixelLayout.BGRA8] in
 * sRGB or Display P3, [PixelLayout.RGBA_1010102] for 10-bit SDR, and [PixelLayout.RGBA_F16] in
 * linear extended sRGB for HDR, where the shapes and text Compose draws sit at reference white,
 * 203 nits, and drawn HDR images keep their highlights. A [VideoTrack]'s `config.canvasFormat` is the
 * one its dynamic range calls for; [VideoTrack.write] converts it into the encoder's format once.
 * Pixels are premultiplied: transparency comes out as if drawn over black.
 *
 * Each renderer composes and draws on a thread of its own, so several can render at once; one
 * renderer renders one frame at a time. It uses Compose's own scene, as `ImageComposeScene` does
 * inside, and falls back to `ImageComposeScene` itself where the Compose the app resolves lacks
 * that API. In the browser each frame is drawn in the page's Skia memory and copied once into the
 * frame, which there is RGBA8 only.
 */
public class ComposeFrameRenderer<T> internal constructor(
    public val width: Int,
    public val height: Int,
    public val format: FrameFormat,
    public val density: Density,
    route: Route,
    private val content: @Composable (T) -> Unit,
) : AutoCloseable {
    public constructor(
        width: Int,
        height: Int,
        format: FrameFormat = FrameFormat.Rgba8,
        density: Density = Density(1f),
        content: @Composable (T) -> Unit,
    ) : this(width, height, format, density, Route.AUTO, content)

    /** Takes the size and the canvas format from [track]. */
    public constructor(
        track: VideoTrack,
        density: Density = Density(1f),
        content: @Composable (T) -> Unit,
    ) : this(track.config.width, track.config.height, track.config.canvasFormat, density, content)

    internal enum class Route { AUTO, SCENE, IMAGE_SCENE }

    init {
        require(width > 0 && height > 0) { "The size must be positive: ${width}x$height" }
        require(format.layout in RGB_LAYOUTS) { "Compose draws RGB layouts only, not ${format.layout}" }
        requireNotNull(format.skiaColorSpace()) { "Skia has no canvas space for ${format.color}" }
    }

    private val info = ImageInfo(width, height, format.skiaColorType(), ColorAlphaType.PREMUL, format.skiaColorSpace())
    private val thread = Dispatchers.Default.limitedParallelism(1)
    private val current = mutableStateOf<Any?>(Unset)
    private var requestedRoute = route
    private var scene: RenderScene? = null
    private var closed = false

    /** The route in use, once the first frame has been rendered. */
    internal val activeRoute: Route? get() = scene?.route

    /** Composes [value] at [time] and draws it into a new pooled frame of [format], shown from [time]. */
    public suspend fun render(time: Duration, value: T): VideoFrame = withContext(thread) {
        check(!closed) { "The renderer is closed" }
        current.value = value
        Snapshot.sendApplyNotifications()
        val scene = scene ?: openScene().also { scene = it }
        val frame = VideoFrame.of(allocateNativeFrame(format.toNative(), width, height), pts = time, duration = Duration.ZERO)
        try {
            frame.useNative { native ->
                drawIntoFrame(checkNotNull(native), info) { canvas ->
                    // Pooled memory holds an earlier frame: what the content leaves uncovered is transparent black.
                    canvas.clear(0)
                    scene.draw(canvas, time.inWholeNanoseconds)
                }
            }
        } catch (failure: Throwable) {
            frame.close()
            throw failure
        }
        frame
    }

    /** Frees the composition. Call it once the last [render] has returned. */
    override fun close() {
        if (closed) return
        closed = true
        scene?.close()
        scene = null
    }

    private fun openScene(): RenderScene {
        val root: @Composable () -> Unit = {
            val value = current.value
            // Nothing to compose until the first render has given a value.
            @Suppress("UNCHECKED_CAST")
            if (value !== Unset) content(value as T)
        }
        if (requestedRoute != Route.IMAGE_SCENE) {
            try {
                return openComposeScene(width, height, density, thread, root)
            } catch (failure: Throwable) {
                if (requestedRoute == Route.SCENE || !isLinkageFailure(failure)) throw failure
                reportFallback(failure)
            }
        }
        return ImageRenderScene(width, height, density, thread, root)
    }

    private object Unset

    private companion object {
        val RGB_LAYOUTS = setOf(PixelLayout.RGBA8, PixelLayout.BGRA8, PixelLayout.RGBA_1010102, PixelLayout.RGBA_F16)
        var reportedFallback = false

        fun reportFallback(failure: Throwable) {
            if (reportedFallback) return
            reportedFallback = true
            println(
                "FFmpegKMP: this Compose version lacks the scene API ComposeFrameRenderer was built against " +
                    "(${failure::class.simpleName}); rendering through ImageComposeScene instead",
            )
        }
    }
}

/** A composition drawn onto a canvas at a frame time. */
internal interface RenderScene : AutoCloseable {
    val route: ComposeFrameRenderer.Route

    fun draw(canvas: Canvas, nanoTime: Long)
}

/**
 * The public route: `ImageComposeScene` composes and renders into a surface of its own, where the
 * content only records itself into a [GraphicsLayer], which is then replayed onto the frame's
 * canvas. It costs that surface, 8-bit at the scene's size, and a clear of it per frame, but no
 * copy of the frame.
 */
private class ImageRenderScene(
    private val width: Int,
    private val height: Int,
    private val density: Density,
    context: CoroutineContext,
    content: @Composable () -> Unit,
) : RenderScene {
    override val route = ComposeFrameRenderer.Route.IMAGE_SCENE
    private var layer: GraphicsLayer? = null
    private val scene = ImageComposeScene(width, height, density, context) {
        val recorded = rememberGraphicsLayer()
        layer = recorded
        Box(Modifier.fillMaxSize().drawWithContent { recorded.record { this@drawWithContent.drawContent() } }) {
            content()
        }
    }

    override fun draw(canvas: Canvas, nanoTime: Long) {
        scene.render(nanoTime).close()
        val recorded = layer ?: return
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas.asComposeCanvas(), Size(width.toFloat(), height.toFloat())) {
            drawLayer(recorded)
        }
    }

    override fun close() = scene.close()
}
