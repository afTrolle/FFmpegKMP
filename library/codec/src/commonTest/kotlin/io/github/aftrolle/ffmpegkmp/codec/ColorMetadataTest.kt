// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerHdrType
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
            hdrType = NativePlayerHdrType.HDR10,
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
        assertEquals("1:1", video.sampleAspectRatio)
        assertEquals(90.0, video.rotationDegrees)
        assertEquals("BT.2020", video.colorPrimaries)
        assertEquals("PQ", video.colorTransfer)
        assertEquals("BT.2020 NCL", video.colorMatrix)
        assertEquals("Limited", video.colorRange)
        assertEquals("Left", video.chromaLocation)
        assertEquals(HdrType.HDR10, video.hdrType)
        assertEquals("1000.0", assertNotNull(video.masteringDisplay).raw["maxLuminance"])
        val contentLight = assertNotNull(video.contentLight)
        assertEquals(1000, contentLight.maxContentLightLevel)
        assertEquals(400, contentLight.maxFrameAverageLightLevel)
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
        assertEquals("0.708", assertNotNull(primariesOnly.masteringDisplay).raw["redX"])
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
    fun unspecifiedColorFieldsStayUnspecified() {
        val video = NativePlayerVideoInfo(
            width = 640,
            height = 360,
            colorPrimaries = 2,
            colorTransfer = 2,
            colorSpace = 2,
            colorRange = 0,
            chromaLocation = 0,
        ).toPublicVideoInfo()

        assertNull(video.colorPrimaries)
        assertNull(video.colorTransfer)
        assertNull(video.colorMatrix)
        assertNull(video.colorRange)
        assertNull(video.chromaLocation)
        assertNull(video.masteringDisplay)
        assertNull(video.contentLight)
        assertNull(video.hdrMetadata)
    }
}
