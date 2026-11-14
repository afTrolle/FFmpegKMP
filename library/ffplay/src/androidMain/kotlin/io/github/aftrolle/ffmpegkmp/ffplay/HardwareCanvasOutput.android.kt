// SPDX-License-Identifier: Apache-2.0
@file:OptIn(InternalFFmpegKmpApi::class, io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_PRI_BT2020
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_PRI_BT709
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_PRI_SMPTE432
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_TRC_ARIB_STD_B67
import io.github.aftrolle.ffmpegkmp.bindings.AVCOL_TRC_SMPTE2084
import io.github.aftrolle.ffmpegkmp.bindings.GpuBufferImages
import io.github.aftrolle.ffmpegkmp.codec.ColorPrimaries
import io.github.aftrolle.ffmpegkmp.codec.ColorTransfer
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi

/**
 * A Canvas output's hardware frames on Android 14 (API 34) and later: the player's MediaCodec
 * decoder renders each frame, as it is queued, into an `ImageReader` of the output's own, the same
 * [GpuBufferImages] a `VideoOutput.GpuBuffers` decoder uses, stamped with the frame's presentation
 * time in nanoseconds, and [inGpuMemory] waits on the session thread for the image that carries it.
 * No thread of its own: the reader's listener only wakes the wait.
 *
 * The reader holds as many images as the player's [FFplayConfiguration.canvasFrames]: by default the
 * frames queued, the one on screen, and the one before it, which a drawing already recorded may
 * still read. MediaCodec renders into buffers of the reader's queue on top of those, so its own
 * frames in flight need no image here. With fewer, the image before the one on screen goes back at
 * once and the player queues only as many frames as the rest hold ([queueLimit]); with one, a frame
 * waits rendered in the reader's queue until it is shown, when the image on screen goes back and
 * its own is taken ([later]).
 */
internal class ImageReaderCanvasFrames(private val frames: Int) : CanvasHardwareFrames {
    private val images = GpuBufferImages(frames)

    override val surface: Any get() = images.surface

    /**
     * BT.709 SDR, shown as coded as the conversion shows it, and PQ and HLG, tone mapped as they are
     * drawn as the conversion maps them ([SdrToneMap]). Not SDR of wider primaries: HWUI's mapping of
     * a Display P3 wrap into the canvas's sRGB differs from the conversion's, by 16 8-bit codes on a
     * Samsung S25 where the picture is flat, so those frames come converted in memory.
     */
    override fun takes(video: VideoInfo): Boolean = video.color.isHdr || video.color.primaries == ColorPrimaries.BT709

    override fun queueLimit(frameQueueSize: Int): Int = minOf(frameQueueSize, maxOf(frames - 1, 1))

    override fun inGpuMemory(frame: VideoFrame, video: VideoInfo?): VideoFrame {
        // In memory: a software decoder's, before the stream is known or where MediaCodec has none.
        if (frame.useNative { it == null || it.mappable }) return frame
        return rendered(frame, video)()
    }

    override fun later(frame: VideoFrame, video: VideoInfo?): (() -> VideoFrame)? =
        if (frames > 1 || frame.useNative { it == null || it.mappable }) null else rendered(frame, video)

    /** Takes [frame], which the player rendered into the reader, and returns what waits for its image and gives it. */
    private fun rendered(frame: VideoFrame, video: VideoInfo?): () -> VideoFrame {
        val pts = frame.pts
        val duration = frame.duration
        val width = frame.width
        val height = frame.height
        val rotation = frame.rotationDegrees
        val sampleAspectRatio = frame.sampleAspectRatio
        // The player rendered MediaCodec's buffer already; the frame that named it is spent.
        frame.close()
        val color = video?.color
        return {
            val gpu = images.take(
                timestampNanos = pts.inWholeMicroseconds * 1000,
                width = width,
                height = height,
                colorTransfer = when (color?.transfer) {
                    ColorTransfer.PQ -> AVCOL_TRC_SMPTE2084
                    ColorTransfer.HLG -> AVCOL_TRC_ARIB_STD_B67
                    else -> AVCOL_TRC_BT709
                },
                colorPrimaries = when (color?.primaries) {
                    ColorPrimaries.BT2020 -> AVCOL_PRI_BT2020
                    ColorPrimaries.DISPLAY_P3 -> AVCOL_PRI_SMPTE432
                    else -> AVCOL_PRI_BT709
                },
                deadline = System.nanoTime() + BUFFER_WAIT_NANOS,
            )
            VideoFrame.of(gpu, pts, duration, width, height, rotation, sampleAspectRatio)
        }
    }

    override fun close() = images.close()
}

/**
 * How long PREPARE waits for MediaCodec's render to reach the reader: well past a frame at any
 * rate, short enough that a buffer that never comes fails the frame instead of the session.
 */
private const val BUFFER_WAIT_NANOS = 500_000_000L

/** `AVCOL_TRC_BT709`: SDR shown as coded. */
private const val AVCOL_TRC_BT709 = 1
