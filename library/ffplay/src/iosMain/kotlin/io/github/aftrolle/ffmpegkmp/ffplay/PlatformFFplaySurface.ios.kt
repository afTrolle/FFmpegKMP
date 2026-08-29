// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.UIKitView
import io.github.aftrolle.ffmpegkmp.bindings.NativePlatformVideoFrame
import io.github.aftrolle.ffmpegkmp.bindings.NativePlatformVideoFrameKind
import io.github.aftrolle.ffmpegkmp.bindings.NativeVideoFrame
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.microseconds
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVQueuedSampleBufferRenderingStatusFailed
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.AVFoundation.enqueueSampleBuffer
import platform.AVFoundation.flush
import platform.AVFoundation.flushAndRemoveImage
import platform.AVFoundation.status
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreGraphics.CGRectZero
import platform.CoreMedia.CMSampleBufferCreateReadyWithImageBuffer
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMSampleTimingInfo
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMVideoFormatDescriptionCreateForImageBuffer
import platform.CoreMedia.CMVideoFormatDescriptionRefVar
import platform.CoreMedia.kCMTimeInvalid
import platform.CoreMedia.kCMSampleAttachmentKey_DisplayImmediately
import platform.CoreVideo.CVImageBufferRef
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
internal actual fun PlatformFFplaySurface(
    player: FFplayPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    backgroundColor: Color,
) {
    if (player.configuration.outputPreference == FFplayOutputPreference.COMPOSE_CANVAS) {
        ComposeCanvasFFplaySurface(player, modifier, contentScale, backgroundColor)
        return
    }

    val output = remember(player) { IOSSampleBufferOutput() }
    val softwareFrame by output.frames.collectAsState()
    val playback by player.snapshot.collectAsState()
    output.updateVideoInfo(playback.video)

    LaunchedEffect(output, playback.video) {
        player.attachOutput(output)
    }

    DisposableEffect(player, output) {
        player.attachOutput(output)
        onDispose {
            player.detachOutput(output)
            output.release()
        }
    }

    Box(modifier) {
        UIKitView(
            factory = {
                SampleBufferVideoView().also(output::attach)
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.backgroundColor = UIColor.colorWithRed(
                    red = backgroundColor.red.toDouble(),
                    green = backgroundColor.green.toDouble(),
                    blue = backgroundColor.blue.toDouble(),
                    alpha = backgroundColor.alpha.toDouble(),
                )
                view.videoGravity = contentScale.toVideoGravity()
            },
            onRelease = { view -> output.detach(view) },
        )

        // The overlay is transparent while VideoToolbox is active. If hardware
        // negotiation falls back to software, scheduled RGBA frames remain visible.
        softwareFrame?.let { frame ->
            Canvas(Modifier.fillMaxSize()) {
                drawFrame(frame, contentScale)
            }
        }
    }
}

private class IOSSampleBufferOutput : FFplayVideoOutput {
    override val kind: FFplayRendererKind = FFplayRendererKind.NATIVE_SURFACE
    override val frames = MutableStateFlow<FFplayFrame?>(null)
    override val capabilities: FFplayOutputCapabilities
        get() {
            val directPresentation = videoInfo.hasIdentityDisplayTransform()
            return FFplayOutputCapabilities(
                hardwareFrameImport = directPresentation,
                softwareFrameUpload = true,
                zeroCopy = directPresentation,
                hdrTransfers = if (directPresentation) setOf("PQ", "HLG") else emptySet(),
                colorSpaces = if (directPresentation) {
                    setOf("sRGB", "Display P3", "BT.2020")
                } else {
                    setOf("sRGB")
                },
                toneMapHdrToSdr = true,
            )
        }

    private var view: SampleBufferVideoView? = null
    private var videoInfo: FFplayVideoInfo? = null

    fun updateVideoInfo(videoInfo: FFplayVideoInfo?) {
        this.videoInfo = videoInfo
    }

    fun attach(view: SampleBufferVideoView) {
        this.view = view
    }

    fun detach(view: SampleBufferVideoView) {
        if (this.view === view) {
            this.view = null
            view.flush()
        }
    }

    override fun submit(frame: FFplayFrame): Boolean {
        frames.value = frame
        return true
    }

    override fun submitNative(frame: NativeVideoFrame, video: FFplayVideoInfo?): Boolean {
        frames.value = FFplayFrame(
            image = frame.toImageBitmap(),
            presentationTime = frame.presentationTimeUs.microseconds,
            sampleAspectRatio = video.sampleAspectRatioValue(),
            rotationDegrees = video?.rotationDegrees ?: 0.0,
        )
        return true
    }

    override fun submitPlatform(frame: NativePlatformVideoFrame, video: FFplayVideoInfo?): Boolean {
        if (frame.kind != NativePlatformVideoFrameKind.CV_PIXEL_BUFFER) return false
        val target = view ?: return false
        val imageBuffer = frame.handle as? CVImageBufferRef ?: return false
        val sampleBuffer = createSampleBuffer(imageBuffer, frame.presentationTimeUs) ?: return false
        frames.value = null
        target.enqueue(sampleBuffer)
        return true
    }

    override fun discard() {
        frames.value = null
        view?.flush()
    }

    fun release() {
        discard()
        view = null
    }
}

private class SampleBufferVideoView : UIView(frame = CGRectZero.readValue()) {
    private val displayLayer = AVSampleBufferDisplayLayer()

    var videoGravity: String?
        get() = displayLayer.videoGravity
        set(value) {
            displayLayer.videoGravity = value
        }

    init {
        layer.addSublayer(displayLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        displayLayer.frame = bounds
    }

    fun enqueue(sampleBuffer: CMSampleBufferRef) {
        dispatch_async(dispatch_get_main_queue()) {
            if (displayLayer.status == AVQueuedSampleBufferRenderingStatusFailed) {
                displayLayer.flush()
            }
            displayLayer.enqueueSampleBuffer(sampleBuffer)
            CFRelease(sampleBuffer)
        }
    }

    fun flush() {
        dispatch_async(dispatch_get_main_queue()) {
            displayLayer.flushAndRemoveImage()
        }
    }
}

private fun createSampleBuffer(
    imageBuffer: CVImageBufferRef,
    presentationTimeUs: Long,
): CMSampleBufferRef? = memScoped {
    val formatDescription = alloc<CMVideoFormatDescriptionRefVar>()
    if (CMVideoFormatDescriptionCreateForImageBuffer(null, imageBuffer, formatDescription.ptr) != 0) {
        return@memScoped null
    }
    val format = formatDescription.value ?: return@memScoped null
    val timing = alloc<CMSampleTimingInfo>()
    timing.duration.copyFrom(kCMTimeInvalid)
    CMTimeMake(presentationTimeUs, 1_000_000).useContents {
        timing.presentationTimeStamp.copyFrom(this)
    }
    timing.decodeTimeStamp.copyFrom(kCMTimeInvalid)
    val sampleBuffer = alloc<CMSampleBufferRefVar>()
    val status = CMSampleBufferCreateReadyWithImageBuffer(
        allocator = null,
        imageBuffer = imageBuffer,
        formatDescription = format,
        sampleTiming = timing.ptr,
        sampleBufferOut = sampleBuffer.ptr,
    )
    CFRelease(format)
    val sample = if (status == 0) sampleBuffer.value else null
    if (sample != null) {
        // FFplay has already scheduled this frame against its playback clock.
        // Avoid reinterpreting media PTS against the host-time clock in the layer.
        val attachments = CMSampleBufferGetSampleAttachmentsArray(sample, true)
        val firstAttachment = attachments?.let { CFArrayGetValueAtIndex(it, 0) }
        CFDictionarySetValue(
            theDict = firstAttachment?.reinterpret(),
            key = kCMSampleAttachmentKey_DisplayImmediately,
            value = kCFBooleanTrue,
        )
    }
    sample
}

private fun platform.CoreMedia.CMTime.copyFrom(other: platform.CoreMedia.CMTime) {
    value = other.value
    timescale = other.timescale
    flags = other.flags
    epoch = other.epoch
}

private fun ContentScale.toVideoGravity(): String? = when (this) {
    ContentScale.Crop -> AVLayerVideoGravityResizeAspectFill
    ContentScale.FillBounds, ContentScale.FillHeight, ContentScale.FillWidth -> AVLayerVideoGravityResize
    else -> AVLayerVideoGravityResizeAspect
}

private fun FFplayVideoInfo?.hasIdentityDisplayTransform(): Boolean =
    this != null && rotationDegrees.normalizedRotation() == 0f &&
        kotlin.math.abs(sampleAspectRatioValue() - 1.0) < 0.000_001
