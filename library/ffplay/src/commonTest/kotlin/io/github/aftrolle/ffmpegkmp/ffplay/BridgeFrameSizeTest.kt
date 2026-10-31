// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a Canvas output tells a bridge that converts its frames, the browser's worker, as it draws. */
class BridgeFrameSizeTest {
    private val told = mutableListOf<IntSize>()
    private val frameSize = BridgeFrameSize().apply { bind { width, height -> told += IntSize(width, height) } }

    private fun draw(area: Size, video: IntSize = FOUR_K, rotationDegrees: Double = 0.0, scale: ContentScale = ContentScale.Fit) {
        val display = displaySize(video.width, video.height, 1.0, rotationDegrees)
        frameSize.drawnIn(video, rotationDegrees, display, area, scale)
    }

    @Test
    fun theStepOfTheVideoThatCoversThePlacedPictureIsToldOncePerChange() {
        draw(Size(1920f, 1080f))
        draw(Size(1920f, 1080f))
        // Still step 8, and a step down only two or more below it.
        draw(Size(1900f, 1070f))
        draw(Size(1500f, 844f))
        assertEquals(listOf(IntSize(1920, 1080)), told)
        draw(Size(960f, 540f))
        // Up a step at once.
        draw(Size(1000f, 562f))
        assertEquals(listOf(IntSize(1920, 1080), IntSize(960, 540), IntSize(1200, 675)), told)
    }

    @Test
    fun aFittedPictureTakesTheVideosAspectWhateverTheArea() {
        // Fitted into a tall area the picture is 960 wide: step 4.
        draw(Size(960f, 2000f))
        assertEquals(listOf(IntSize(960, 540)), told)
        // Cropped into it, the whole picture is 3556 × 2000: step 15.
        draw(Size(960f, 2000f), scale = ContentScale.Crop)
        // Not scaled at all, in any area: the video's own size.
        draw(Size(960f, 2000f), scale = ContentScale.None)
        assertEquals(listOf(IntSize(960, 540), IntSize(3600, 2025), FOUR_K), told)
    }

    @Test
    fun aTurnedVideoIsToldInItsOwnOrientation() {
        // 96 × 64 turned a quarter is 64 × 96 upright; drawn at 32 × 48, its pixels are 48 × 32: step 8.
        draw(Size(32f, 48f), video = IntSize(96, 64), rotationDegrees = 90.0)
        assertEquals(listOf(IntSize(48, 32)), told)
    }

    @Test
    fun nothingIsToldUnboundAndABindTellsAgain() {
        draw(Size(1920f, 1080f))
        frameSize.bind(null)
        draw(Size(960f, 540f))
        frameSize.bind { width, height -> told += IntSize(width, height) }
        draw(Size(960f, 540f))
        assertEquals(listOf(IntSize(1920, 1080), IntSize(960, 540)), told)
        // Before a layout, nothing.
        draw(Size.Zero)
        assertEquals(2, told.size)
    }

    private companion object {
        val FOUR_K = IntSize(3840, 2160)
    }
}
