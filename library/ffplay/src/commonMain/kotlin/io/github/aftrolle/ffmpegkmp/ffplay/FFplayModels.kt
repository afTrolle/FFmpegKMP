// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.core.CommandIo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

public data class FFplaySource(
    val input: String,
    val io: CommandIo = CommandIo.Empty,
    /**
     * Use [REQUIRE_SECURE_PATH] for DRM content whose decoded pixels must remain in a
     * platform-protected decoder/surface path. Such sources never fall back to Canvas.
     */
    val protection: FFplayContentProtection = FFplayContentProtection.CLEAR_OR_AUTO_DETECT,
) {
    init {
        require(input.isNotBlank()) { "FFplay input must not be blank" }
        require('\u0000' !in input) { "FFplay input must not contain NUL" }
    }
}

public enum class FFplayContentProtection {
    CLEAR_OR_AUTO_DETECT,
    REQUIRE_SECURE_PATH,
}

public enum class FFplayDecoderPreference { AUTO, REQUIRE_HARDWARE, SOFTWARE }
public enum class FFplayOutputPreference { AUTO, NATIVE_SURFACE, COMPOSE_CANVAS }
public enum class FFplayHdrPolicy { PRESERVE_OR_TONE_MAP, FORCE_SDR }

public data class FFplayConfiguration(
    val decoderPreference: FFplayDecoderPreference = FFplayDecoderPreference.AUTO,
    val outputPreference: FFplayOutputPreference = FFplayOutputPreference.AUTO,
    val hdrPolicy: FFplayHdrPolicy = FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
)

public enum class FFplayState {
    IDLE,
    PREPARING,
    WAITING_FOR_OUTPUT,
    READY,
    PLAYING,
    PAUSED,
    SEEKING,
    ENDED,
    STOPPED,
    FAILED,
    CLOSED,
}

public enum class FFplayHdrType { SDR, HDR10, HLG, HDR10_PLUS, DOLBY_VISION, UNKNOWN_HDR }
public enum class FFplayDecoderKind { UNKNOWN, HARDWARE, SOFTWARE }
public enum class FFplayRendererKind { NATIVE_SURFACE, GPU_TEXTURE, COMPOSE_CANVAS }
public enum class FFplayHdrResult { NOT_HDR, PRESERVED, TONE_MAPPED, UNSUPPORTED }

public data class FFplayMasteringDisplayMetadata(
    val raw: Map<String, String> = emptyMap(),
)

public data class FFplayContentLightMetadata(
    val maxContentLightLevel: Int? = null,
    val maxFrameAverageLightLevel: Int? = null,
)

public data class FFplayVideoInfo(
    val width: Int,
    val height: Int,
    val sampleAspectRatio: String? = null,
    val rotationDegrees: Double = 0.0,
    val pixelFormat: String? = null,
    val bitDepth: Int? = null,
    val colorPrimaries: String? = null,
    val colorTransfer: String? = null,
    val colorMatrix: String? = null,
    val colorRange: String? = null,
    val chromaLocation: String? = null,
    val hdrType: FFplayHdrType = FFplayHdrType.SDR,
    val masteringDisplay: FFplayMasteringDisplayMetadata? = null,
    val contentLight: FFplayContentLightMetadata? = null,
) {
    init {
        require(width > 0 && height > 0) { "Video dimensions must be positive" }
    }
}

public data class FFplayOutputInfo(
    val decoder: FFplayDecoderKind,
    val renderer: FFplayRendererKind,
    val zeroCopy: Boolean,
    val sourceColorSpace: String? = null,
    val outputColorSpace: String? = null,
    val hdrResult: FFplayHdrResult = FFplayHdrResult.NOT_HDR,
    val securePath: Boolean = false,
)

public data class FFplayFailure(
    val message: String,
    val cause: Throwable? = null,
)

public data class FFplaySnapshot(
    val state: FFplayState = FFplayState.IDLE,
    val position: Duration = ZERO,
    val duration: Duration? = null,
    val seekable: Boolean = false,
    val video: FFplayVideoInfo? = null,
    val output: FFplayOutputInfo? = null,
    val droppedFrames: Long = 0,
    val failure: FFplayFailure? = null,
)

public sealed interface FFplayEvent {
    public val message: String

    public data class Warning(override val message: String) : FFplayEvent
    public data class DecoderFallback(override val message: String) : FFplayEvent
    public data class RendererFallback(override val message: String) : FFplayEvent
    public data class SurfaceLost(override val message: String) : FFplayEvent
    public data class ProtectionRequired(override val message: String) : FFplayEvent
    public data class Fatal(override val message: String, val cause: Throwable? = null) : FFplayEvent
}
