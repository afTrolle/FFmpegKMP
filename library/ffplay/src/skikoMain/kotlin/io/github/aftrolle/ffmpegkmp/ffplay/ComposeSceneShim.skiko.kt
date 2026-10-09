// SPDX-License-Identifier: Apache-2.0
// The only file that uses Compose's internal scene API, for Compose 1.12 (FrameRecomposer and
// CanvasLayersComposeScene with measureAndLayout and draw). A Compose release that changes it needs
// a shim of its own; ComposeFrameRendererTest renders through both routes and compares them.
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.coroutines.CoroutineContext
import org.jetbrains.skia.Canvas

/**
 * The primary route: what `ImageComposeScene.render` does inside, pointed at the frame's canvas
 * instead of a surface of its own, so there is no second surface, display list or snapshot.
 * [context] is the renderer's own thread, where snapshot changes made on other threads reach the
 * scene: queued there, and run before each frame, never while it draws.
 */
internal fun openComposeScene(
    width: Int,
    height: Int,
    density: Density,
    context: CoroutineContext,
    content: @Composable () -> Unit,
): RenderScene {
    val recomposer = FrameRecomposer(context)
    val size = IntSize(width, height)
    val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = size
    }
    val scene = CanvasLayersComposeScene(
        recomposer,
        density,
        LayoutDirection.Ltr,
        size,
        object : PlatformContext.Empty() {
            override val windowInfo: WindowInfo get() = window
        },
    )
    scene.setContent(recomposer.compositionContext, content)
    return object : RenderScene {
        override val route = ComposeFrameRenderer.Route.SCENE

        override fun draw(canvas: Canvas, nanoTime: Long) {
            recomposer.performFrame(nanoTime)
            scene.measureAndLayout()
            scene.draw(canvas.asComposeCanvas())
        }

        override fun close() {
            scene.close()
            recomposer.close()
        }
    }
}
