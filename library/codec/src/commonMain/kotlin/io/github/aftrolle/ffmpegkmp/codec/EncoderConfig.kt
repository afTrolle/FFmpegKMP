// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.NativeDynamicRange
import io.github.aftrolle.ffmpegkmp.bindings.NativeHdrMetadata
import io.github.aftrolle.ffmpegkmp.bindings.NativeMasteringDisplay
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderPreference
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoCodec
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoEncoderConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The dynamic range an export writes, chosen once: it decides what Compose draws into, what the
 * decoder delivers for drawing, and what the encoder takes.
 */
public enum class DynamicRange {
    /** BT.709, at 8 bits or 10 ([VideoEncoderConfig.bitDepth]). HDR sources are tone mapped to it. */
    SDR,

    /** 10-bit BT.2020 PQ, with mastering-display and content-light metadata. */
    HDR10,

    /** 10-bit BT.2020 HLG. */
    HLG,
    ;

    public companion object {
        /**
         * The range that keeps a source's, by its transfer function: PQ as HDR10, HLG as HLG and
         * the rest as SDR. Dolby Vision and HDR10+ are signalling over one of those, so a Dolby
         * Vision 8.4 clip, which is HLG, stays HLG.
         */
        public fun of(info: VideoInfo): DynamicRange = when (info.color.transfer) {
            ColorTransfer.PQ -> HDR10
            ColorTransfer.HLG -> HLG
            else -> SDR
        }
    }
}

public enum class VideoCodec { H264, HEVC, AV1 }

/**
 * Which encoder a video track opens. [AUTO] prefers the platform's hardware encoder and falls back
 * to a software one where the build has one for the codec: the default LGPL builds have none for
 * H.264 or HEVC, since libx264 and libx265 are GPL. [REQUIRE_HARDWARE] and [SOFTWARE] take only
 * their kind.
 */
public enum class EncoderPreference { AUTO, REQUIRE_HARDWARE, SOFTWARE }

/** A CIE 1931 chromaticity. */
public data class Chromaticity(val x: Double, val y: Double)

/** The display HDR10 content was mastered on: its primaries, white point and luminance range in cd/m². */
public data class MasteringDisplay(
    val red: Chromaticity,
    val green: Chromaticity,
    val blue: Chromaticity,
    val whitePoint: Chromaticity,
    val minLuminance: Double,
    val maxLuminance: Double,
) {
    init {
        require(minLuminance >= 0 && maxLuminance > minLuminance) {
            "The luminance range must be positive and increasing: $minLuminance to $maxLuminance"
        }
    }

    public companion object {
        /** A Display P3 monitor with a D65 white point from 0.0001 to 1000 cd/m², as grading suites often use. */
        public val DisplayP3At1000Nits: MasteringDisplay = MasteringDisplay(
            red = Chromaticity(0.680, 0.320),
            green = Chromaticity(0.265, 0.690),
            blue = Chromaticity(0.150, 0.060),
            whitePoint = Chromaticity(0.3127, 0.3290),
            minLuminance = 0.0001,
            maxLuminance = 1000.0,
        )
    }
}

/**
 * HDR metadata: HDR10's static [masteringDisplay] and [contentLight] levels, which a writer puts
 * in the stream and the container, and the dynamic-metadata signalling a source carries. A decoded
 * source reports its own as [VideoInfo.hdrMetadata]; [combine] makes the static part for a
 * composite of several. Which HDR a picture is follows from its [FrameColor.transfer], not from
 * these flags: a Dolby Vision 8.4 clip is HLG.
 */
public data class HdrMetadata(
    val masteringDisplay: MasteringDisplay? = null,
    val contentLight: ContentLightMetadata? = null,
    /** The source signals Dolby Vision. A writer does not carry it over. */
    val dolbyVision: Boolean = false,
    /** The source carries HDR10+ dynamic metadata. A writer does not carry it over. */
    val hdr10Plus: Boolean = false,
) {
    public companion object {
        /**
         * The metadata for a composite of [main] and [others]: [main]'s mastering display, and the
         * highest MaxCLL and MaxFALL of all of them. MaxFALL is then an upper bound, not the
         * composite's own frame average. Null sources, such as SDR ones, add nothing, and the result
         * is null when nothing is known. The signalling flags are not combined.
         */
        public fun combine(main: HdrMetadata?, vararg others: HdrMetadata?): HdrMetadata? {
            val lights = (listOf(main) + others).mapNotNull { it?.contentLight }
            return hdrMetadataOrNull(
                main?.masteringDisplay,
                contentLightOrNull(
                    lights.mapNotNull { it.maxContentLightLevel }.maxOrNull(),
                    lights.mapNotNull { it.maxFrameAverageLightLevel }.maxOrNull(),
                ),
            )
        }
    }
}

internal fun hdrMetadataOrNull(
    masteringDisplay: MasteringDisplay?,
    contentLight: ContentLightMetadata?,
    dolbyVision: Boolean = false,
    hdr10Plus: Boolean = false,
): HdrMetadata? =
    HdrMetadata(masteringDisplay, contentLight, dolbyVision, hdr10Plus).takeIf { it != HdrMetadata() }

internal fun contentLightOrNull(maxContentLightLevel: Int?, maxFrameAverageLightLevel: Int?): ContentLightMetadata? =
    if (maxContentLightLevel != null || maxFrameAverageLightLevel != null) {
        ContentLightMetadata(maxContentLightLevel, maxFrameAverageLightLevel)
    } else {
        null
    }

public data class VideoEncoderConfig(
    val width: Int,
    val height: Int,
    /** Null for a variable frame rate: each frame keeps its own timestamp. */
    val frameRate: FrameRate?,
    val codec: VideoCodec = VideoCodec.H264,
    /** Decides every format along the way; see [DynamicRange]. */
    val dynamicRange: DynamicRange = DynamicRange.SDR,
    /**
     * Bits per sample, 8 or 10, or null for the dynamic range's own: 8 for SDR, 10 for HDR, which
     * takes 10 only. 10-bit SDR keeps the gradients smooth that 8 bits band, and needs HEVC or AV1.
     */
    val bitDepth: Int? = null,
    /**
     * HDR10's static part of it; other ranges ignore it, so a source's [VideoInfo.hdrMetadata] can
     * be passed as it is. Without it the writer takes the first frame's, as a decoded HDR10 source
     * carries it.
     */
    val hdrMetadata: HdrMetadata? = null,
    /** Bits per second; null for one that suits the size and rate. */
    val bitRate: Long? = null,
    val keyframeInterval: Duration = 2.seconds,
    val encoder: EncoderPreference = EncoderPreference.AUTO,
) {
    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "The size must be positive and even for 4:2:0 video: ${width}x$height"
        }
        require(bitRate == null || bitRate > 0) { "The bit rate must be positive: $bitRate" }
        require(!keyframeInterval.isNegative() && keyframeInterval.isFinite()) {
            "The keyframe interval must not be negative: $keyframeInterval"
        }
        require(bitDepth == null || bitDepth == 8 || bitDepth == 10) { "The bit depth must be 8 or 10: $bitDepth" }
        require(dynamicRange == DynamicRange.SDR || bitDepth != 8) { "$dynamicRange takes 10 bits, not 8" }
        require((dynamicRange == DynamicRange.SDR && bitDepth != 10) || codec != VideoCodec.H264) {
            "10-bit video needs a 10-bit codec: HEVC or AV1"
        }
    }

    /**
     * What to draw into and decode to for this config: [FrameFormat.Rgba8] for 8-bit SDR, 10-bit RGB
     * in sRGB for 10-bit SDR, and [FrameFormat.RgbaF16] for HDR, where SDR white sits at 1.0.
     */
    public val canvasFormat: FrameFormat
        get() = when {
            dynamicRange != DynamicRange.SDR -> FrameFormat.RgbaF16
            bitDepth == 10 -> FrameFormat(PixelLayout.RGBA_1010102, FrameColor.Srgb)
            else -> FrameFormat.Rgba8
        }
}

public data class AudioEncoderConfig(
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    /** Bits per second; null for 64 kbit/s per channel. */
    val bitRate: Long? = null,
) {
    init {
        require(sampleRate in 8_000..384_000) { "The sample rate must be between 8 kHz and 384 kHz: $sampleRate" }
        require(channels in 1..8) { "The channel count must be between 1 and 8: $channels" }
        require(bitRate == null || bitRate > 0) { "The bit rate must be positive: $bitRate" }
    }
}

private val HdrMetadata.hasStaticMetadata: Boolean get() = masteringDisplay != null || contentLight != null

internal fun VideoEncoderConfig.toNative(): NativeVideoEncoderConfig = NativeVideoEncoderConfig(
    width = width,
    height = height,
    frameRateNumerator = frameRate?.numerator ?: 0,
    frameRateDenominator = frameRate?.denominator ?: 0,
    codec = when (codec) {
        VideoCodec.H264 -> NativeVideoCodec.H264
        VideoCodec.HEVC -> NativeVideoCodec.HEVC
        VideoCodec.AV1 -> NativeVideoCodec.AV1
    },
    dynamicRange = when (dynamicRange) {
        DynamicRange.SDR -> NativeDynamicRange.SDR
        DynamicRange.HDR10 -> NativeDynamicRange.HDR10
        DynamicRange.HLG -> NativeDynamicRange.HLG
    },
    preference = when (encoder) {
        EncoderPreference.AUTO -> NativePlayerDecoderPreference.AUTO
        EncoderPreference.REQUIRE_HARDWARE -> NativePlayerDecoderPreference.REQUIRE_HARDWARE
        EncoderPreference.SOFTWARE -> NativePlayerDecoderPreference.SOFTWARE
    },
    bitRate = bitRate ?: 0L,
    keyframeIntervalMicros = keyframeInterval.inWholeMicroseconds,
    bitDepth = bitDepth ?: 0,
    hdrMetadata = hdrMetadata?.takeIf { dynamicRange == DynamicRange.HDR10 && it.hasStaticMetadata }?.let { metadata ->
        NativeHdrMetadata(
            masteringDisplay = metadata.masteringDisplay?.let { display ->
                NativeMasteringDisplay(
                    primariesX = doubleArrayOf(display.red.x, display.green.x, display.blue.x),
                    primariesY = doubleArrayOf(display.red.y, display.green.y, display.blue.y),
                    whitePointX = display.whitePoint.x,
                    whitePointY = display.whitePoint.y,
                    minLuminance = display.minLuminance,
                    maxLuminance = display.maxLuminance,
                )
            },
            contentLight = metadata.contentLight?.let {
                (it.maxContentLightLevel ?: 0) to (it.maxFrameAverageLightLevel ?: 0)
            },
        )
    },
)
