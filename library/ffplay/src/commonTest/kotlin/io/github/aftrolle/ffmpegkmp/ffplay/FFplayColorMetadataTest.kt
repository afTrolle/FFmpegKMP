// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import kotlin.test.Test
import kotlin.test.assertEquals

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
