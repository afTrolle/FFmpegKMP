// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [frameFit]: the sizes, cut and turn [VideoDecoder.extractFrame] uses. */
class FrameFitTest {
    @Test
    fun rotationsTurnTheOtherWayInQuarterTurns() {
        // FFmpeg reports the rotation anticlockwise; showing it upright turns clockwise by its negative.
        assertEquals(0, uprightQuarterTurns(0.0))
        assertEquals(3, uprightQuarterTurns(90.0))
        assertEquals(2, uprightQuarterTurns(180.0))
        assertEquals(2, uprightQuarterTurns(-180.0))
        assertEquals(1, uprightQuarterTurns(-90.0))
        assertEquals(1, uprightQuarterTurns(270.0))
        assertEquals(0, uprightQuarterTurns(360.0))
        // Between quarter turns, the nearest; exactly half way, as roundToInt rounds -rotation / 90 (a half up).
        assertEquals(3, uprightQuarterTurns(89.0))
        assertEquals(3, uprightQuarterTurns(91.0))
        assertEquals(0, uprightQuarterTurns(30.0))
        assertEquals(0, uprightQuarterTurns(45.0))
        assertEquals(3, uprightQuarterTurns(135.0))
        assertEquals(0, uprightQuarterTurns(Double.NaN))
    }

    @Test
    fun noSizeIsTheUprightSourceAtItsDisplaySize() {
        assertEquals(FrameFit(FrameSize(1920, 1080), null, 0, FrameSize(1920, 1080)), fit(1920, 1080))
        assertEquals(FrameFit(FrameSize(1920, 1080), null, 3, FrameSize(1080, 1920)), fit(1920, 1080, rotation = 90.0))
        assertEquals(FrameFit(FrameSize(1920, 1080), null, 2, FrameSize(1920, 1080)), fit(1920, 1080, rotation = 180.0))
        assertEquals(FrameFit(FrameSize(1920, 1080), null, 1, FrameSize(1080, 1920)), fit(1920, 1080, rotation = -90.0))
        // Anamorphic: PAL 16:9 and 4:3, the width stretched by the sample aspect ratio, rounded.
        assertEquals(FrameFit(FrameSize(1024, 576), null, 0, FrameSize(1024, 576)), fit(720, 576, aspect = 64.0 / 45))
        assertEquals(FrameFit(FrameSize(768, 576), null, 0, FrameSize(768, 576)), fit(720, 576, aspect = 16.0 / 15))
        assertEquals(FrameFit(FrameSize(1024, 576), null, 3, FrameSize(576, 1024)), fit(720, 576, aspect = 64.0 / 45, rotation = 90.0))
        // An absurd aspect is bounded at 16, and an unknown one is square.
        assertEquals(FrameSize(16 * 720, 576), fit(720, 576, aspect = 1e9).size)
        assertEquals(FrameSize(45, 576), fit(720, 576, aspect = 1e-9).size)
        assertEquals(FrameSize(720, 576), fit(720, 576, aspect = 0.0).size)
        assertEquals(FrameSize(720, 576), fit(720, 576, aspect = Double.NaN).size)
    }

    @Test
    fun fitKeepsTheWholePictureInTheBox() {
        assertEquals(FrameFit(FrameSize(256, 144), null, 0, FrameSize(256, 144)), fit(1920, 1080, FrameSize(256, 256)))
        assertEquals(FrameFit(FrameSize(256, 144), null, 0, FrameSize(256, 144)), fit(1920, 1080, FrameSize(300, 144)))
        // Portrait, from a rotated landscape coding: decoded sideways at the turned size.
        assertEquals(FrameFit(FrameSize(256, 144), null, 3, FrameSize(144, 256)), fit(1920, 1080, FrameSize(256, 256), rotation = 90.0))
        assertEquals(FrameFit(FrameSize(256, 144), null, 1, FrameSize(144, 256)), fit(1920, 1080, FrameSize(256, 256), rotation = 270.0))
        assertEquals(FrameFit(FrameSize(256, 144), null, 2, FrameSize(256, 144)), fit(1920, 1080, FrameSize(256, 256), rotation = 180.0))
        // A portrait coding.
        assertEquals(FrameFit(FrameSize(144, 256), null, 0, FrameSize(144, 256)), fit(1080, 1920, FrameSize(256, 256)))
        // Anamorphic 720x576 at 16:9 shows 1024x576.
        assertEquals(FrameFit(FrameSize(256, 144), null, 0, FrameSize(256, 144)), fit(720, 576, FrameSize(256, 256), aspect = 64.0 / 45))
    }

    @Test
    fun fillCoversTheBoxAndCutsTheCentre() {
        // 1920x1080 to cover 256x256: 455.1 rounds to 455, and 199 spare columns leave 99 on the left.
        assertEquals(
            FrameFit(FrameSize(455, 256), FrameRect(99, 0, 256, 256), 0, FrameSize(256, 256)),
            fit(1920, 1080, FrameSize(256, 256), FrameScale.CROP),
        )
        assertEquals(
            FrameFit(FrameSize(256, 455), FrameRect(0, 99, 256, 256), 0, FrameSize(256, 256)),
            fit(1080, 1920, FrameSize(256, 256), FrameScale.CROP),
        )
        // Upright 1080x1920 (turned three quarters clockwise from 1920x1080) to cover 256x100: decoded at 455x256,
        // which is 256x455 upright, and rows 177 until 277 of the upright picture cut, whichever way it turns.
        assertEquals(
            FrameFit(FrameSize(455, 256), FrameRect(0, 177, 256, 100), 3, FrameSize(256, 100)),
            fit(1920, 1080, FrameSize(256, 100), FrameScale.CROP, rotation = 90.0),
        )
        assertEquals(
            FrameFit(FrameSize(455, 256), FrameRect(0, 177, 256, 100), 1, FrameSize(256, 100)),
            fit(1920, 1080, FrameSize(256, 100), FrameScale.CROP, rotation = -90.0),
        )
        assertEquals(
            FrameFit(FrameSize(455, 256), FrameRect(99, 0, 256, 256), 2, FrameSize(256, 256)),
            fit(1920, 1080, FrameSize(256, 256), FrameScale.CROP, rotation = 180.0),
        )
        // A box of the picture's own aspect cuts nothing.
        assertEquals(FrameFit(FrameSize(320, 180), null, 0, FrameSize(320, 180)), fit(1920, 1080, FrameSize(320, 180), FrameScale.CROP))
    }

    @Test
    fun aBoxLargerThanThePictureNeverEnlargesIt() {
        assertEquals(FrameFit(FrameSize(96, 64), null, 0, FrameSize(96, 64)), fit(96, 64, FrameSize(1000, 1000)))
        assertEquals(FrameFit(FrameSize(96, 64), null, 3, FrameSize(64, 96)), fit(96, 64, FrameSize(1000, 1000), rotation = 90.0))
        // CROP: the largest centred part with the box's aspect.
        assertEquals(
            FrameFit(FrameSize(96, 64), FrameRect(16, 0, 64, 64), 0, FrameSize(64, 64)),
            fit(96, 64, FrameSize(1000, 1000), FrameScale.CROP),
        )
        // Larger in one direction only: 1000x500 into 800x800 cuts 500x500, 1000x500 into 2000x600 cuts 1000x300.
        assertEquals(
            FrameFit(FrameSize(1000, 500), FrameRect(250, 0, 500, 500), 0, FrameSize(500, 500)),
            fit(1000, 500, FrameSize(800, 800), FrameScale.CROP),
        )
        assertEquals(
            FrameFit(FrameSize(1000, 500), FrameRect(0, 100, 1000, 300), 0, FrameSize(1000, 300)),
            fit(1000, 500, FrameSize(2000, 600), FrameScale.CROP),
        )
        // Upright 64x96 into 200x100: 64x32 from rows 32 until 64.
        assertEquals(
            FrameFit(FrameSize(96, 64), FrameRect(0, 32, 64, 32), 3, FrameSize(64, 32)),
            fit(96, 64, FrameSize(200, 100), FrameScale.CROP, rotation = 90.0),
        )
    }

    @Test
    fun degenerateBoxesKeepAtLeastOnePixel() {
        assertEquals(FrameSize(1, 1), fit(1920, 1080, FrameSize(1, 1)).size)
        assertEquals(FrameFit(FrameSize(2, 1), null, 0, FrameSize(2, 1)), fit(1920, 1080, FrameSize(1000, 1)))
        assertEquals(FrameFit(FrameSize(100, 1), null, 0, FrameSize(100, 1)), fit(1920, 1, FrameSize(100, 100)))
        assertEquals(FrameFit(FrameSize(2, 1), FrameRect(0, 0, 1, 1), 0, FrameSize(1, 1)), fit(1920, 1080, FrameSize(1, 1), FrameScale.CROP))
        assertEquals(
            FrameFit(FrameSize(1000, 563), FrameRect(0, 281, 1000, 1), 0, FrameSize(1000, 1)),
            fit(1920, 1080, FrameSize(1000, 1), FrameScale.CROP),
        )
        assertEquals(
            FrameFit(FrameSize(1778, 1000), FrameRect(888, 0, 1, 1000), 0, FrameSize(1, 1000)),
            fit(1920, 1080, FrameSize(1, 1000), FrameScale.CROP),
        )
        // A 1x1 source stays 1x1.
        assertEquals(FrameFit(FrameSize(1, 1), null, 0, FrameSize(1, 1)), fit(1, 1, FrameSize(7, 3), FrameScale.CROP))
    }

    @Test
    fun divisionsRoundToTheNearestPixelAHalfUp() {
        // 2 * 3 / 4 = 1.5 rounds up; 2 * 2 / 3 = 1.33 down.
        assertEquals(FrameSize(3, 2), fit(4, 2, FrameSize(3, 3)).size)
        assertEquals(FrameSize(2, 1), fit(3, 2, FrameSize(2, 2)).size)
        // 101 * 0.5 = 50.5 rounds up to 51.
        assertEquals(FrameSize(51, 100), fit(101, 100, aspect = 0.5).size)
        // Odd sizes are allowed: RGBA has no subsampling.
        assertEquals(FrameSize(255, 143), fit(1920, 1080, FrameSize(255, 255)).size)
    }

    /** What every fit keeps, over many sources, boxes, aspects and rotations. */
    @Test
    fun everyFitIsConsistent() {
        val random = Random(13)
        repeat(20_000) {
            val codedWidth = random.nextInt(1, 4000)
            val codedHeight = random.nextInt(1, 4000)
            val aspect = if (random.nextBoolean()) 1.0 else random.nextDouble(0.25, 4.0)
            val rotation = listOf(0.0, 90.0, 180.0, -90.0, 270.0).random(random)
            val box = FrameSize(random.nextInt(1, 5000), random.nextInt(1, 5000))
            val scale = FrameScale.entries.random(random)
            val name = "${codedWidth}x$codedHeight at $aspect turned $rotation into $box by $scale"
            val fit = frameFit(codedWidth, codedHeight, aspect, rotation, box, scale)
            val natural = frameFit(codedWidth, codedHeight, aspect, rotation, null, scale).size
            val sideways = fit.clockwiseQuarterTurns % 2 == 1

            // Never larger than the box, nor than the picture at its display size.
            assertTrue(fit.size.width <= box.width && fit.size.height <= box.height, name)
            assertTrue(fit.size.width <= natural.width && fit.size.height <= natural.height, name)
            assertTrue(fit.decodeSize.width <= (if (sideways) natural.height else natural.width), name)
            assertTrue(fit.decodeSize.height <= (if (sideways) natural.width else natural.height), name)

            // The cut is the result, centred in the upright picture the decoded frame turns into.
            val upright = if (sideways) FrameSize(fit.decodeSize.height, fit.decodeSize.width) else fit.decodeSize
            val crop = fit.crop ?: FrameRect(0, 0, upright.width, upright.height)
            assertTrue(crop.width in 1..upright.width && crop.height in 1..upright.height, name)
            assertEquals((upright.width - crop.width) / 2, crop.x, name)
            assertEquals((upright.height - crop.height) / 2, crop.y, name)
            assertEquals(fit.size, FrameSize(crop.width, crop.height), name)

            when (scale) {
                // The whole picture: nothing cut, and its aspect kept to within the rounding of a pixel.
                FrameScale.FIT -> {
                    assertEquals(null, fit.crop, name)
                    assertTrue(fit.size.width == box.width || fit.size.height == box.height || fit.size == natural, name)
                    assertAspect(natural, fit.size, name)
                }
                // The box, or the largest part of the picture with its aspect.
                FrameScale.CROP -> {
                    assertTrue(fit.size == box || fit.size.width == natural.width || fit.size.height == natural.height, name)
                    if (fit.size != box) assertAspect(box, fit.size, name)
                    // The scaled picture covers the result and keeps the picture's aspect.
                    val scaled = if (sideways) FrameSize(fit.decodeSize.height, fit.decodeSize.width) else fit.decodeSize
                    assertAspect(natural, scaled, name)
                }
            }
        }
    }

    /** [actual] has [expected]'s aspect to within half a pixel in each direction. */
    private fun assertAspect(expected: FrameSize, actual: FrameSize, name: String) {
        val widthFromHeight = actual.height.toDouble() * expected.width / expected.height
        val heightFromWidth = actual.width.toDouble() * expected.height / expected.width
        assertTrue(
            abs(widthFromHeight - actual.width) <= 0.5 || abs(heightFromWidth - actual.height) <= 0.5 ||
                actual.width == 1 || actual.height == 1,
            "$name: $actual is not the aspect of $expected",
        )
    }

    private fun fit(
        width: Int,
        height: Int,
        box: FrameSize? = null,
        scale: FrameScale = FrameScale.FIT,
        aspect: Double = 1.0,
        rotation: Double = 0.0,
    ): FrameFit = frameFit(width, height, aspect, rotation, box, scale)
}
