// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerMasteringDisplayMetadata
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerVideoInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ColorMetadataTest {
    @Test
    fun mapsHdr10StreamMetadataWithoutLosingColorInformation() {
        val video = NativePlayerVideoInfo(
            width = 3840,
            height = 2160,
            pixelFormatName = "yuv420p10le",
            bitDepth = 10,
            sampleAspectRatioNumerator = 1,
            sampleAspectRatioDenominator = 1,
            rotationDegrees = 90.0,
            colorPrimaries = 9,
            colorTransfer = 16,
            colorSpace = 9,
            colorRange = 1,
            chromaLocation = 1,
            masteringDisplay = NativePlayerMasteringDisplayMetadata(
                hasPrimaries = true,
                hasLuminance = true,
                redX = 0.68,
                redY = 0.32,
                greenX = 0.265,
                greenY = 0.69,
                blueX = 0.15,
                blueY = 0.06,
                whiteX = 0.3127,
                whiteY = 0.329,
                minLuminance = 0.005,
                maxLuminance = 1000.0,
            ),
            maxContentLightLevel = 1000,
            maxFrameAverageLightLevel = 400,
        ).toPublicVideoInfo()

        assertEquals("yuv420p10le", video.pixelFormat)
        assertEquals(10, video.bitDepth)
        assertEquals(1.0, video.sampleAspectRatio)
        assertEquals(90.0, video.rotationDegrees)
        assertEquals(FrameColor.Bt2020Pq, video.color)
        assertEquals(DynamicRange.HDR10, DynamicRange.of(video))
        assertEquals(
            HdrMetadata(
                MasteringDisplay(
                    red = Chromaticity(0.68, 0.32),
                    green = Chromaticity(0.265, 0.69),
                    blue = Chromaticity(0.15, 0.06),
                    whitePoint = Chromaticity(0.3127, 0.329),
                    minLuminance = 0.005,
                    maxLuminance = 1000.0,
                ),
                ContentLightMetadata(1000, 400),
            ),
            video.hdrMetadata,
        )
    }

    @Test
    fun aMasteringDisplayNeedsBothItsPrimariesAndItsLuminance() {
        val primaries = NativePlayerMasteringDisplayMetadata(
            hasPrimaries = true,
            redX = 0.708,
            redY = 0.292,
            greenX = 0.17,
            greenY = 0.797,
            blueX = 0.131,
            blueY = 0.046,
            whiteX = 0.3127,
            whiteY = 0.329,
        )
        val primariesOnly = NativePlayerVideoInfo(width = 64, height = 64, masteringDisplay = primaries).toPublicVideoInfo()
        assertNull(primariesOnly.hdrMetadata)

        val luminanceOnly = NativePlayerVideoInfo(
            width = 64,
            height = 64,
            masteringDisplay = NativePlayerMasteringDisplayMetadata(hasLuminance = true, minLuminance = 0.0001, maxLuminance = 1000.0),
            maxContentLightLevel = 1000,
        ).toPublicVideoInfo()
        assertEquals(HdrMetadata(contentLight = ContentLightMetadata(1000, null)), luminanceOnly.hdrMetadata)

        // A range MasteringDisplay rejects is left out rather than failing the open.
        val badLuminance = NativePlayerVideoInfo(
            width = 64,
            height = 64,
            masteringDisplay = primaries.copy(hasLuminance = true, minLuminance = 0.0, maxLuminance = 0.0),
        ).toPublicVideoInfo()
        assertNull(badLuminance.hdrMetadata)
    }

    @Test
    fun aDolbyVisionStreamIsAsHdrAsItsTransferSaysAndNoMore() {
        // An iPhone's Dolby Vision 8.4: HLG, with Dolby Vision signalling over it.
        val dolbyVision84 = NativePlayerVideoInfo(
            width = 1920,
            height = 1080,
            bitDepth = 10,
            colorPrimaries = 9,
            colorTransfer = 18,
            colorSpace = 9,
            dolbyVision = true,
        ).toPublicVideoInfo()
        assertEquals(FrameColor.Bt2020Hlg, dolbyVision84.color)
        assertEquals(HdrMetadata(dolbyVision = true), dolbyVision84.hdrMetadata)
        assertEquals(DynamicRange.HLG, DynamicRange.of(dolbyVision84))

        val dolbyVision81 = NativePlayerVideoInfo(
            width = 1920,
            height = 1080,
            colorPrimaries = 9,
            colorTransfer = 16,
            colorSpace = 9,
            dolbyVision = true,
            hdr10Plus = true,
        ).toPublicVideoInfo()
        assertEquals(DynamicRange.HDR10, DynamicRange.of(dolbyVision81))
        assertEquals(HdrMetadata(dolbyVision = true, hdr10Plus = true), dolbyVision81.hdrMetadata)

        // Signalling with no HDR transfer, as a Dolby Vision profile 5 stream without a base layer reports, is SDR.
        val unspecified = NativePlayerVideoInfo(width = 64, height = 64, dolbyVision = true).toPublicVideoInfo()
        assertEquals(DynamicRange.SDR, DynamicRange.of(unspecified))
    }

    @Test
    fun colorOutsideTheModelReadsAsTheConverterReadsIt() {
        fun color(primaries: Int = 2, transfer: Int = 2, space: Int = 2, range: Int = 0, pixelFormatName: String? = null) =
            NativePlayerVideoInfo(
                width = 64,
                height = 64,
                pixelFormatName = pixelFormatName,
                colorPrimaries = primaries,
                colorTransfer = transfer,
                colorSpace = space,
                colorRange = range,
            ).toPublicVideoInfo().color

        assertEquals(FrameColor(ColorPrimaries.DISPLAY_P3, ColorTransfer.BT709, ColorMatrix.BT709, ColorRange.LIMITED), color(primaries = 12, transfer = 1, space = 1))
        assertEquals(ColorMatrix.BT601, color(space = 6).matrix)
        assertEquals(ColorMatrix.BT601, color(space = 5).matrix)
        assertEquals(ColorMatrix.BT2020_NCL, color(space = 10).matrix)
        assertEquals(ColorTransfer.SRGB, color(transfer = 13).transfer)
        assertEquals(ColorTransfer.LINEAR, color(transfer = 8).transfer)
        assertEquals(ColorTransfer.BT709, color(transfer = 4).transfer)
        assertEquals(ColorRange.FULL, color(range = 2).range)
        assertEquals(ColorRange.FULL, color(pixelFormatName = "yuvj420p").range)
        assertEquals(FrameColor(ColorPrimaries.BT709, ColorTransfer.SRGB, ColorMatrix.RGB, ColorRange.FULL), color(space = 0, transfer = 13))
    }

    @Test
    fun anUnreportedAspectRatioIsSquare() {
        assertEquals(1.0, NativePlayerVideoInfo(width = 64, height = 64).toPublicVideoInfo().sampleAspectRatio)
        assertEquals(
            16.0 / 15.0,
            NativePlayerVideoInfo(
                width = 720,
                height = 576,
                sampleAspectRatioNumerator = 16,
                sampleAspectRatioDenominator = 15,
            ).toPublicVideoInfo().sampleAspectRatio,
        )
    }

    @Test
    fun unspecifiedColorFieldsReadAsBt709() {
        val video = NativePlayerVideoInfo(
            width = 640,
            height = 360,
            colorPrimaries = 2,
            colorTransfer = 2,
            colorSpace = 2,
            colorRange = 0,
            chromaLocation = 0,
        ).toPublicVideoInfo()

        assertEquals(FrameColor.Bt709, video.color)
        assertEquals(DynamicRange.SDR, DynamicRange.of(video))
        assertNull(video.hdrMetadata)
    }
}
