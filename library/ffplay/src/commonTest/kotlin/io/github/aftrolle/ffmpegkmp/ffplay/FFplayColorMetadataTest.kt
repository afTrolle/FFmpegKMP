// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerHdrType
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerMasteringDisplayMetadata
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerVideoInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FFplayColorMetadataTest {
    @Test
    fun displayTransformNormalizesAspectRatioAndRotation() {
        val video = FFplayVideoInfo(
            width = 720,
            height = 576,
            sampleAspectRatio = "16:15",
            rotationDegrees = -90.0,
        )

        assertEquals(16.0 / 15.0, video.sampleAspectRatioValue())
        assertEquals(270f, video.rotationDegrees.normalizedRotation())
    }

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
        assertEquals(FFplayHdrType.HDR10, video.hdrType)
        assertEquals("1000.0", assertNotNull(video.masteringDisplay).raw["maxLuminance"])
        val contentLight = assertNotNull(video.contentLight)
        assertEquals(1000, contentLight.maxContentLightLevel)
        assertEquals(400, contentLight.maxFrameAverageLightLevel)
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
    }

    @Test
    fun preservesHdrOnlyWhenTheWholeOutputPathAdvertisesSupport() {
        val video = hdr10Video()
        val capable = FFplayOutputCapabilities(
            hardwareFrameImport = true,
            hdrTransfers = setOf("PQ"),
            colorSpaces = setOf("BT.2020"),
        )

        val result = decideColorOutput(video, capable, FFplayHdrPolicy.PRESERVE_OR_TONE_MAP)

        assertEquals("BT.2020", result.sourceColorSpace)
        assertEquals("BT.2020", result.outputColorSpace)
        assertEquals(FFplayHdrResult.PRESERVED, result.hdrResult)
    }

    @Test
    fun reportsToneMappingOnlyWhenTheOutputEnablesTheNativeSdrConversion() {
        val canvas = FFplayOutputCapabilities(
            softwareFrameUpload = true,
            hdrTransfers = emptySet(),
            colorSpaces = setOf("sRGB"),
            toneMapHdrToSdr = true,
        )

        assertEquals(
            FFplayHdrResult.TONE_MAPPED,
            decideColorOutput(
                hdr10Video(),
                canvas,
                FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
            ).hdrResult,
        )
        assertEquals(
            FFplayHdrResult.TONE_MAPPED,
            decideColorOutput(hdr10Video(), canvas, FFplayHdrPolicy.FORCE_SDR).hdrResult,
        )
        assertEquals(
            FFplayHdrResult.UNSUPPORTED,
            decideColorOutput(
                hdr10Video(),
                canvas.copy(toneMapHdrToSdr = false),
                FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
            ).hdrResult,
        )
    }

    @Test
    fun sdrOutputIsNotMisreportedAsHdr() {
        val result = decideColorOutput(
            FFplayVideoInfo(
                width = 1920,
                height = 1080,
                colorPrimaries = "BT.709",
                colorTransfer = "BT.709",
            ),
            FFplayOutputCapabilities(colorSpaces = setOf("BT.709")),
            FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
        )

        assertEquals(FFplayHdrResult.NOT_HDR, result.hdrResult)
        assertEquals("BT.709", result.outputColorSpace)
    }

    @Test
    fun unknownHdrTransferIsNotClaimedAsToneMapped() {
        val unknown = hdr10Video().copy(
            colorTransfer = null,
            hdrType = FFplayHdrType.UNKNOWN_HDR,
        )

        assertEquals(
            FFplayHdrResult.UNSUPPORTED,
            decideColorOutput(
                unknown,
                FFplayOutputCapabilities(toneMapHdrToSdr = true),
                FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
            ).hdrResult,
        )
    }

    @Test
    fun displayP3SdrRequiresAColorManagedP3Output() {
        val video = FFplayVideoInfo(
            width = 1920,
            height = 1080,
            colorPrimaries = "Display P3",
            colorTransfer = "BT.709",
            hdrType = FFplayHdrType.SDR,
        )

        val p3 = decideColorOutput(
            video,
            FFplayOutputCapabilities(colorSpaces = setOf("sRGB", "Display P3")),
            FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
        )
        val srgb = decideColorOutput(
            video,
            FFplayOutputCapabilities(colorSpaces = setOf("sRGB")),
            FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
        )

        assertEquals("Display P3", p3.outputColorSpace)
        assertEquals("sRGB", srgb.outputColorSpace)
        assertEquals(FFplayHdrResult.NOT_HDR, p3.hdrResult)
        assertEquals(FFplayHdrResult.NOT_HDR, srgb.hdrResult)
    }

    @Test
    fun hlgPreservationRequiresBothBt2020AndHlgCapabilities() {
        val hlg = FFplayVideoInfo(
            width = 3840,
            height = 2160,
            colorPrimaries = "BT.2020",
            colorTransfer = "HLG",
            colorMatrix = "BT.2020 NCL",
            hdrType = FFplayHdrType.HLG,
        )

        assertEquals(
            FFplayHdrResult.PRESERVED,
            decideColorOutput(
                hlg,
                FFplayOutputCapabilities(
                    colorSpaces = setOf("BT.2020"),
                    hdrTransfers = setOf("HLG"),
                ),
                FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
            ).hdrResult,
        )
        assertEquals(
            FFplayHdrResult.UNSUPPORTED,
            decideColorOutput(
                hlg,
                FFplayOutputCapabilities(
                    colorSpaces = setOf("BT.2020"),
                    hdrTransfers = setOf("PQ"),
                ),
                FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
            ).hdrResult,
        )
    }

    private fun hdr10Video() = FFplayVideoInfo(
        width = 3840,
        height = 2160,
        colorPrimaries = "BT.2020",
        colorTransfer = "PQ",
        colorMatrix = "BT.2020 NCL",
        hdrType = FFplayHdrType.HDR10,
    )
}
