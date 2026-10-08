// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.FrameColor
import io.github.aftrolle.ffmpegkmp.codec.HdrMetadata
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class FFplayColorMetadataTest {
    @Test
    fun displayTransformNormalizesAspectRatioAndRotation() {
        val video = VideoInfo(
            width = 720,
            height = 576,
            sampleAspectRatio = 16.0 / 15.0,
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
            hdrTransfers = setOf(ColorTransfer.PQ),
            colorSpaces = setOf(ColorPrimaries.BT2020),
        )

        val result = decideColorOutput(video, capable, FFplayHdrPolicy.PRESERVE_OR_TONE_MAP)

        assertEquals(ColorPrimaries.BT2020, result.sourceColorSpace)
        assertEquals(ColorPrimaries.BT2020, result.outputColorSpace)
        assertEquals(FFplayHdrResult.PRESERVED, result.hdrResult)
    }

    @Test
    fun reportsToneMappingOnlyWhenTheOutputEnablesTheNativeSdrConversion() {
        val canvas = FFplayOutputCapabilities(
            softwareFrameUpload = true,
            hdrTransfers = emptySet(),
            colorSpaces = setOf(ColorPrimaries.BT709),
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
            VideoInfo(width = 1920, height = 1080, color = FrameColor.Bt709),
            FFplayOutputCapabilities(colorSpaces = setOf(ColorPrimaries.BT709)),
            FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
        )

        assertEquals(FFplayHdrResult.NOT_HDR, result.hdrResult)
        assertEquals(ColorPrimaries.BT709, result.outputColorSpace)
    }

    @Test
    fun aDolbyVisionStreamIsTheHdrItsTransferIs() {
        val hlg = hlgVideo().copy(hdrMetadata = HdrMetadata(dolbyVision = true))
        val sdr = hdr10Video().copy(color = FrameColor.Bt709, hdrMetadata = HdrMetadata(dolbyVision = true))

        assertEquals(
            FFplayHdrResult.PRESERVED,
            decideColorOutput(
                hlg,
                FFplayOutputCapabilities(colorSpaces = setOf(ColorPrimaries.BT2020), hdrTransfers = setOf(ColorTransfer.HLG)),
                FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
            ).hdrResult,
        )
        assertEquals(
            FFplayHdrResult.NOT_HDR,
            decideColorOutput(sdr, FFplayOutputCapabilities(toneMapHdrToSdr = true), FFplayHdrPolicy.PRESERVE_OR_TONE_MAP).hdrResult,
        )
    }

    @Test
    fun displayP3SdrRequiresAColorManagedP3Output() {
        val video = VideoInfo(
            width = 1920,
            height = 1080,
            color = FrameColor.Bt709.copy(primaries = ColorPrimaries.DISPLAY_P3),
        )

        val p3 = decideColorOutput(
            video,
            FFplayOutputCapabilities(colorSpaces = setOf(ColorPrimaries.BT709, ColorPrimaries.DISPLAY_P3)),
            FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
        )
        val srgb = decideColorOutput(
            video,
            FFplayOutputCapabilities(colorSpaces = setOf(ColorPrimaries.BT709)),
            FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
        )

        assertEquals(ColorPrimaries.DISPLAY_P3, p3.outputColorSpace)
        assertEquals(ColorPrimaries.BT709, srgb.outputColorSpace)
        assertEquals(FFplayHdrResult.NOT_HDR, p3.hdrResult)
        assertEquals(FFplayHdrResult.NOT_HDR, srgb.hdrResult)
    }

    @Test
    fun hlgPreservationRequiresBothBt2020AndHlgCapabilities() {
        val hlg = hlgVideo()

        assertEquals(
            FFplayHdrResult.PRESERVED,
            decideColorOutput(
                hlg,
                FFplayOutputCapabilities(
                    colorSpaces = setOf(ColorPrimaries.BT2020),
                    hdrTransfers = setOf(ColorTransfer.HLG),
                ),
                FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
            ).hdrResult,
        )
        assertEquals(
            FFplayHdrResult.UNSUPPORTED,
            decideColorOutput(
                hlg,
                FFplayOutputCapabilities(
                    colorSpaces = setOf(ColorPrimaries.BT2020),
                    hdrTransfers = setOf(ColorTransfer.PQ),
                ),
                FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
            ).hdrResult,
        )
    }

    private fun hdr10Video() = VideoInfo(width = 3840, height = 2160, color = FrameColor.Bt2020Pq)

    private fun hlgVideo() = VideoInfo(width = 3840, height = 2160, color = FrameColor.Bt2020Hlg)
}
