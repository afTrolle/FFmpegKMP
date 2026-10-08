// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.DecoderKind
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.DecoderThreads
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/** Whether a source's decoded pixels may leave a platform-protected path. */
public enum class FFplayContentProtection {
    CLEAR_OR_AUTO_DETECT,

    /**
     * For DRM content whose decoded pixels must remain in a platform-protected decoder and surface
     * path. Such sources never fall back to Canvas.
     */
    REQUIRE_SECURE_PATH,
}

public enum class FFplayOutputPreference { AUTO, NATIVE_SURFACE, COMPOSE_CANVAS }
public enum class FFplayHdrPolicy {
    /** Shows HDR as HDR where the whole output path can, and tone maps it elsewhere. */
    PRESERVE_OR_TONE_MAP,

    /**
     * Always shows SDR. An HDR source that a direct surface would present as HDR is decoded to
     * software frames and tone mapped instead, so it fails under
     * [DecoderPreference.REQUIRE_HARDWARE].
     */
    FORCE_SDR,
}

public data class FFplayConfiguration(
    val decoderPreference: DecoderPreference = DecoderPreference.AUTO,
    val outputPreference: FFplayOutputPreference = FFplayOutputPreference.AUTO,
    val hdrPolicy: FFplayHdrPolicy = FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
    /**
     * Plays the source's audio in sync with the video (audio is the master clock). Disable for
     * silent previews; volume, mute and tracks are controlled on [FFplayPlayer].
     */
    val audio: Boolean = true,
    /** The software video decoder's threads; see [DecoderThreads]. */
    val threads: DecoderThreads = DecoderThreads.Auto,
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

public enum class FFplayRendererKind { NATIVE_SURFACE, GPU_TEXTURE, COMPOSE_CANVAS }
public enum class FFplayHdrResult { NOT_HDR, PRESERVED, TONE_MAPPED, UNSUPPORTED }

public data class FFplayOutputInfo(
    val decoder: DecoderKind,
    val renderer: FFplayRendererKind,
    val zeroCopy: Boolean,
    /** The primaries the source is in, and those the output presents it in; null before a video is known. */
    val sourceColorSpace: ColorPrimaries? = null,
    val outputColorSpace: ColorPrimaries? = null,
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
    val video: VideoInfo? = null,
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
