// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.DecoderFallbackReason
import io.github.aftrolle.ffmpegkmp.codec.DecoderKind
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.CodecSelection
import io.github.aftrolle.ffmpegkmp.codec.DecoderThreadType
import io.github.aftrolle.ffmpegkmp.codec.DecoderThreads
import io.github.aftrolle.ffmpegkmp.codec.NamedCodec
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

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
     * [CodecPreference.REQUIRE_HARDWARE].
     */
    FORCE_SDR,
}

public data class FFplayConfiguration(
    /**
     * Which video decoder the player opens: a [CodecPreference] chooses one, falling back from
     * hardware to software under [CodecPreference.AUTO]; a [NamedCodec] opens exactly the decoder
     * FFmpeg knows by that name, with its options, and nothing in its place: when it does not open
     * or fails to decode, the player fails with the reason in [FFplayFailure.message]. A named
     * platform decoder (`h264_mediacodec`) renders to the native surface as the hardware one does.
     * The browser decodes with WebCodecs and fails a [NamedCodec] as unsupported.
     */
    val decoder: CodecSelection = CodecPreference.AUTO,
    val outputPreference: FFplayOutputPreference = FFplayOutputPreference.AUTO,
    val hdrPolicy: FFplayHdrPolicy = FFplayHdrPolicy.PRESERVE_OR_TONE_MAP,
    /**
     * Plays the source's audio in sync with the video (audio is the master clock). Disable for
     * silent previews; volume, mute and tracks are controlled on [FFplayPlayer].
     */
    val audio: Boolean = true,
    /**
     * The software video decoder's threads; see [DecoderThreads]. Two frame threads by default: a
     * player decodes about a frame ahead of real time rather than as fast as it can, and each frame
     * thread holds a decoded picture, so two leave the other cores, and memory, to the app and to
     * other players. [DecoderThreads.Auto] is FFmpeg's own count, one more than the cores. Frames
     * converted for the Compose Canvas are converted with as many threads. The browser ignores the
     * thread types.
     */
    val threads: DecoderThreads = DecoderThreads.Fixed(2, setOf(DecoderThreadType.FRAME)),
    /**
     * How long the player keeps its video decoder once it has nothing to decode: paused, ended,
     * waiting for an output, or showing a seek's frame. A play or seek within it uses that decoder,
     * going on from the frame on screen where it can; one after it opens a decoder and decodes from
     * the keyframe before the position. Releasing frees the decoder's frames and threads, and a
     * hardware decoder's place in [io.github.aftrolle.ffmpegkmp.codec.DecoderBudget] for other
     * players and video decoders; the frame on screen stays. [Duration.ZERO] releases it as soon as
     * nothing decodes; [Duration.INFINITE] keeps it, and its place in the budget, until the source,
     * the output or its surface changes.
     */
    val decoderIdleRelease: Duration = 2.seconds,
    /**
     * How many decoded frames the player keeps ready ahead of the one on screen, at least 1, as
     * ffplay's picture queue does: the decoder works ahead by this many, so a frame that is slow to
     * decode does not make the next one late, and frames that fall behind anyway are dropped as they
     * are shown rather than delaying the ones after them. Each slot costs one decoded frame; for a
     * hardware decoder that renders to a native surface, one of its output buffers, of which a
     * decoder that gives fewer than this many queues as many as it gives. What the Compose Canvas
     * keeps besides is [canvasFrames]. The browser's player paces its own frames and ignores it.
     */
    val frameQueueSize: Int = 3,
    /**
     * How many frames the Compose Canvas output keeps ready to draw, at least 1: bitmaps of the
     * drawn width × height × 4 bytes for frames decoded in memory, GPU buffers of the video's size
     * for a hardware decoder's (Android 14 and later). The default is one for each queued frame,
     * made drawable as it is queued, off the thread that draws, the one on screen, and the one shown
     * before it, which a drawing under way may still read.
     *
     * Fewer saves memory, and gives those up in that order: below [frameQueueSize] + 2, the frame
     * shown before is reused at once; below [frameQueueSize] + 1, the queued frames with no bitmap
     * are converted as they are shown, on the thread that draws, and a hardware decoder queues only
     * as many frames as there are buffers for; at 1, each frame is written over the one on screen,
     * which a drawing under way may show partly written. Other outputs ignore it.
     */
    val canvasFrames: Int = frameQueueSize + 2,
    /**
     * How far behind its clock a decoded frame may be before the player gives up showing every
     * frame and skips forward to the next keyframe, so that a decoder slower than real time stays
     * on real time; each skip is reported as [FFplayEvent.CaughtUp]. [Duration.INFINITE] never
     * skips: playback then falls behind its audio and its clock for as long as decoding is slow.
     * The browser's player keeps 500 ms.
     */
    val catchUpLag: Duration = 500.milliseconds,
) {
    init {
        require(!decoderIdleRelease.isNegative()) {
            "The decoder release delay must not be negative: $decoderIdleRelease"
        }
        require(frameQueueSize >= 1) { "The frame queue holds at least one frame: $frameQueueSize" }
        require(!catchUpLag.isNegative()) { "The catch-up lag must not be negative: $catchUpLag" }
        require(canvasFrames >= 1) { "The Canvas keeps at least one frame: $canvasFrames" }
    }
}

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

public enum class FFplayRendererKind { NATIVE_SURFACE, COMPOSE_CANVAS }
public enum class FFplayHdrResult { NOT_HDR, PRESERVED, TONE_MAPPED, UNSUPPORTED }

public data class FFplayOutputInfo(
    val decoder: DecoderKind,
    val renderer: FFplayRendererKind,
    val zeroCopy: Boolean,
    /** The primaries the source is in, and those the output presents it in; null before a video is known. */
    val sourcePrimaries: ColorPrimaries? = null,
    val outputPrimaries: ColorPrimaries? = null,
    val hdrResult: FFplayHdrResult = FFplayHdrResult.NOT_HDR,
    val securePath: Boolean = false,
    /** Why [decoder] is software when the output wanted hardware; null when it is not, or none was wanted. */
    val decoderFallback: DecoderFallbackReason? = null,
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
    /** Hardware decoding was wanted and software decodes instead, for [reason]; mirrored by [FFplayOutputInfo.decoderFallback]. */
    public data class DecoderFallback(
        override val message: String,
        val reason: DecoderFallbackReason = DecoderFallbackReason.FAILED,
    ) : FFplayEvent
    /** The decoder fell more than [FFplayConfiguration.catchUpLag] behind and playback skipped forward, to about [position]. */
    public data class CaughtUp(override val message: String, val position: Duration) : FFplayEvent
    public data class RendererFallback(override val message: String) : FFplayEvent
    public data class SurfaceLost(override val message: String) : FFplayEvent
    public data class ProtectionRequired(override val message: String) : FFplayEvent
    public data class Fatal(override val message: String, val cause: Throwable? = null) : FFplayEvent
}
