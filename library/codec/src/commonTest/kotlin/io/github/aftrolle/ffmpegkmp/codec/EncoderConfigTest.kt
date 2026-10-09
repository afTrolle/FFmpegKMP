// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EncoderConfigTest {
    @Test
    fun theBitDepthDefaultsToTheDynamicRangesAndDecidesTheCanvas() {
        val sdr = VideoEncoderConfig(64, 64, FrameRate(30), VideoCodec.HEVC)
        assertEquals(FrameFormat.Rgba8, sdr.canvasFormat)
        assertEquals(0, sdr.toNative().bitDepth)
        // Null follows the range, so a copy into HDR takes HDR's 10 bits.
        val hdr = sdr.copy(dynamicRange = DynamicRange.HDR10)
        assertEquals(FrameFormat.RgbaF16, hdr.canvasFormat)
        val tenBitSdr = VideoEncoderConfig(64, 64, FrameRate(30), VideoCodec.AV1, bitDepth = 10)
        assertEquals(FrameFormat(PixelLayout.RGBA_1010102, FrameColor.Srgb), tenBitSdr.canvasFormat)
        assertEquals(10, tenBitSdr.toNative().bitDepth)
    }

    @Test
    fun theRangeThatKeepsASourceFollowsItsTransferNotItsSignalling() {
        fun range(color: FrameColor, metadata: HdrMetadata? = null) = DynamicRange.of(VideoInfo(64, 64, color = color, hdrMetadata = metadata))

        assertEquals(DynamicRange.SDR, range(FrameColor.Bt709))
        assertEquals(DynamicRange.HDR10, range(FrameColor.Bt2020Pq))
        assertEquals(DynamicRange.HLG, range(FrameColor.Bt2020Hlg))
        assertEquals(DynamicRange.HLG, range(FrameColor.Bt2020Hlg, HdrMetadata(dolbyVision = true)))
        assertEquals(DynamicRange.HDR10, range(FrameColor.Bt2020Pq, HdrMetadata(dolbyVision = true, hdr10Plus = true)))
        assertEquals(DynamicRange.SDR, range(FrameColor.Bt709, HdrMetadata(dolbyVision = true)))
    }

    @Test
    fun aSourcesMetadataPassesIntoAnyConfigAndOnlyHdr10TakesIt() {
        val source = HdrMetadata(MasteringDisplay.DisplayP3At1000Nits, ContentLightMetadata(1000, 400), dolbyVision = true)
        val hlg = VideoEncoderConfig(64, 64, FrameRate(30), VideoCodec.HEVC, DynamicRange.HLG, hdrMetadata = source)
        assertNull(hlg.toNative().hdrMetadata)
        val sdr = VideoEncoderConfig(64, 64, FrameRate(30), hdrMetadata = source)
        assertNull(sdr.toNative().hdrMetadata)
        val hdr10 = hlg.copy(dynamicRange = DynamicRange.HDR10).toNative().hdrMetadata
        assertEquals(1000.0, hdr10?.masteringDisplay?.maxLuminance)
        assertEquals(1000 to 400, hdr10?.contentLight)
        assertNull(hlg.copy(dynamicRange = DynamicRange.HDR10, hdrMetadata = HdrMetadata(dolbyVision = true)).toNative().hdrMetadata)
    }

    @Test
    fun hdrTakesTenBitsAndTenBitsTakeHevcOrAv1() {
        assertFailsWith<IllegalArgumentException> {
            VideoEncoderConfig(64, 64, FrameRate(30), VideoCodec.HEVC, DynamicRange.HLG, bitDepth = 8)
        }
        assertFailsWith<IllegalArgumentException> { VideoEncoderConfig(64, 64, FrameRate(30), VideoCodec.H264, bitDepth = 10) }
        assertFailsWith<IllegalArgumentException> { VideoEncoderConfig(64, 64, FrameRate(30), VideoCodec.HEVC, bitDepth = 12) }
        assertFailsWith<IllegalArgumentException> { VideoEncoderConfig(64, 64, FrameRate(30), VideoCodec.H264, DynamicRange.HDR10) }
    }

    @Test
    fun combineTakesTheMainMasteringDisplayAndTheHighestLightLevels() {
        val brighter = MasteringDisplay.DisplayP3At1000Nits.copy(maxLuminance = 4000.0)
        val main = HdrMetadata(MasteringDisplay.DisplayP3At1000Nits, ContentLightMetadata(800, 400))
        val inset = HdrMetadata(brighter, ContentLightMetadata(1000, 200))
        assertEquals(
            HdrMetadata(MasteringDisplay.DisplayP3At1000Nits, ContentLightMetadata(1000, 400)),
            HdrMetadata.combine(main, inset),
        )
        assertEquals(main, HdrMetadata.combine(main))
        // Null sources, and levels a source leaves out, add nothing.
        assertEquals(
            HdrMetadata(MasteringDisplay.DisplayP3At1000Nits, ContentLightMetadata(800, 400)),
            HdrMetadata.combine(main, null, HdrMetadata(contentLight = ContentLightMetadata(maxFrameAverageLightLevel = 100))),
        )
        // Without a main mastering display, the composite has none, whatever the others carry.
        assertEquals(HdrMetadata(contentLight = ContentLightMetadata(1000, 200)), HdrMetadata.combine(null, inset))
        assertEquals(
            HdrMetadata(contentLight = ContentLightMetadata(null, 200)),
            HdrMetadata.combine(HdrMetadata(), HdrMetadata(contentLight = ContentLightMetadata(maxFrameAverageLightLevel = 200))),
        )
        assertNull(HdrMetadata.combine(null, null))
        assertNull(HdrMetadata.combine(HdrMetadata(), HdrMetadata(contentLight = ContentLightMetadata())))
    }
}
