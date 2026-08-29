// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFPLAYKMP_OUTPUT_PROTECTED_CONTENT
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFPLAYKMP_OUTPUT_TONE_MAP_HDR_TO_SDR
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFPLAYKMP_OUTPUT_ZERO_COPY
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFPLAYKMP_PLATFORM_FRAME_CV_PIXEL_BUFFER
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_configuration
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_configuration_default
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_pixel_format_name
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_output_capabilities
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_output_capabilities_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_cancel
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_clear_output
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_create
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_destroy
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_get_snapshot
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_pause
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_play
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_prepare
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_reset_cancel
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_seek
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_set_io_callback
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_set_output
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_set_platform_video_frame_callback
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_set_video_frame_callback
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_stop
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_snapshot
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_snapshot_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_video_frame
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_platform_video_frame
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction

@InternalFFmpegKmpApi
public actual fun createPlatformPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: (NativeVideoFrame) -> Unit,
    platformFrame: (NativePlatformVideoFrame) -> Boolean,
): NativePlayerBridge = NativeCInteropPlayerBridge(configuration, update, frame, platformFrame)

private class NativeCInteropPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: (NativeVideoFrame) -> Unit,
    platformFrame: (NativePlatformVideoFrame) -> Boolean,
) : NativePlayerBridge {
    private val callbackState = NativePlayerCallbackState(update, frame, platformFrame)
    private val callbackReference = StableRef.create(callbackState)
    private val player = memScoped {
        val nativeConfiguration = alloc<ffplaykmp_configuration>()
        ffplaykmp_configuration_default(nativeConfiguration.ptr)
        nativeConfiguration.decoder_preference = configuration.decoderPreference.ordinal.toUInt()
        ffplaykmp_player_create(
            nativeConfiguration.ptr,
            staticCFunction(::receiveNativePlayerState),
            callbackReference.asCPointer(),
        )
    } ?: run {
        callbackReference.dispose()
        throw NativeBridgeUnavailableException("FFmpegKMP native player allocation failed")
    }
    private var closed = false

    init {
        ffplaykmp_player_set_io_callback(
            player,
            staticCFunction(::receiveNativePlayerIo),
            callbackReference.asCPointer(),
        )
        ffplaykmp_player_set_video_frame_callback(
            player,
            staticCFunction(::receiveNativeVideoFrame),
            callbackReference.asCPointer(),
        )
        ffplaykmp_player_set_platform_video_frame_callback(
            player,
            staticCFunction(::receiveNativePlatformVideoFrame),
            callbackReference.asCPointer(),
        )
    }

    override fun prepare(source: NativePlayerSource): Int {
        checkOpen()
        callbackState.mounts = source.mounts.mapIndexed { index, mount ->
            index.toLong() + 1L to NativeMountedResource(
                resource = mount.resource,
                replayableSource = true,
            )
        }.toMap()
        val mountedInput = source.mounts.indexOfFirst { it.path == source.input }
            .takeIf { it >= 0 }
            ?.let { protocolUrl(it.toLong() + 1L, source.input) }
            ?: source.input
        return ffplaykmp_player_prepare(
            player,
            mountedInput,
            if (source.requireSecurePath) FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH else 0u,
        ).also { result -> if (result < 0) callbackState.mounts = emptyMap() }
    }

    override fun resetCancellation() {
        checkOpen()
        // Join the outgoing worker before a subsequent prepare reuses its mount ids.
        ffplaykmp_player_reset_cancel(player)
    }

    override fun setOutput(capabilities: NativePlayerOutputCapabilities): Int = memScoped {
        checkOpen()
        val nativeCapabilities = alloc<ffplaykmp_output_capabilities>()
        ffplaykmp_output_capabilities_init(nativeCapabilities.ptr)
        nativeCapabilities.flags = capabilities.toFlags()
        ffplaykmp_player_set_output(player, nativeCapabilities.ptr)
    }

    override fun clearOutput() {
        checkOpen()
        ffplaykmp_player_clear_output(player)
    }

    override fun play(): Int = checkedCall { ffplaykmp_player_play(player) }
    override fun pause(): Int = checkedCall { ffplaykmp_player_pause(player) }
    override fun seek(positionUs: Long): Int = checkedCall {
        ffplaykmp_player_seek(player, positionUs)
    }
    override fun stop(): Int = checkedCall {
        ffplaykmp_player_stop(player).also { if (it == 0) callbackState.mounts = emptyMap() }
    }
    override fun cancel() {
        if (!closed) ffplaykmp_player_cancel(player)
    }

    override fun snapshot(): NativePlayerSnapshot = memScoped {
        checkOpen()
        val nativeSnapshot = alloc<ffplaykmp_snapshot>()
        ffplaykmp_snapshot_init(nativeSnapshot.ptr)
        val result = ffplaykmp_player_get_snapshot(player, nativeSnapshot.ptr)
        check(result == 0) { "Native player snapshot failed with error $result" }
        nativeSnapshot.toNativeSnapshot()
    }

    override fun close() {
        if (closed) return
        closed = true
        callbackState.mounts = emptyMap()
        ffplaykmp_player_destroy(player)
        callbackReference.dispose()
    }

    private inline fun checkedCall(block: () -> Int): Int {
        checkOpen()
        return block()
    }

    private fun checkOpen() = check(!closed) { "The native player bridge is closed" }
}

private class NativePlayerCallbackState(
    val update: (NativePlayerSnapshot) -> Unit,
    val frame: (NativeVideoFrame) -> Unit,
    val platformFrame: (NativePlatformVideoFrame) -> Boolean,
) {
    var mounts: Map<Long, NativeMountedResource> = emptyMap()
}

private fun receiveNativePlatformVideoFrame(
    opaque: COpaquePointer?,
    nativeFrame: CPointer<ffplaykmp_platform_video_frame>?,
): Int {
    if (opaque == null || nativeFrame == null) return 0
    val value = nativeFrame.pointed
    val handle = value.handle ?: return 0
    val kind = when (value.kind) {
        FFPLAYKMP_PLATFORM_FRAME_CV_PIXEL_BUFFER -> NativePlatformVideoFrameKind.CV_PIXEL_BUFFER
        else -> return 0
    }
    return if (
        opaque.asStableRef<NativePlayerCallbackState>().get().platformFrame(
            NativePlatformVideoFrame(
                kind = kind,
                handle = handle,
                width = value.width,
                height = value.height,
                presentationTimeUs = value.presentation_time_us,
                queueSerial = value.queue_serial,
            ),
        )
    ) 1 else 0
}

private fun receiveNativeVideoFrame(
    opaque: COpaquePointer?,
    nativeFrame: CPointer<ffplaykmp_video_frame>?,
) {
    if (opaque == null || nativeFrame == null) return
    val value = nativeFrame.pointed
    val data = value.rgba ?: return
    val size = value.rgba_size
    if (size == 0uL || size > Int.MAX_VALUE.toULong()) return
    opaque.asStableRef<NativePlayerCallbackState>().get().frame(
        NativeVideoFrame(
            rgba = data.readBytes(size.toInt()),
            width = value.width,
            height = value.height,
            stride = value.stride,
            presentationTimeUs = value.presentation_time_us,
            queueSerial = value.queue_serial,
        ),
    )
}

private fun receiveNativePlayerState(
    opaque: COpaquePointer?,
    snapshot: CPointer<ffplaykmp_snapshot>?,
) {
    if (opaque == null || snapshot == null) return
    opaque.asStableRef<NativePlayerCallbackState>().get().update(snapshot.pointed.toNativeSnapshot())
}

private fun receiveNativePlayerIo(
    opaque: COpaquePointer?,
    resourceId: Long,
    operation: UInt,
    offset: Long,
    data: CPointer<UByteVar>?,
    size: ULong,
): Long = opaque
    ?.asStableRef<NativePlayerCallbackState>()
    ?.get()
    ?.mounts
    ?.get(resourceId)
    ?.dispatch(operation.toInt(), offset, data, size)
    ?: -1L

private fun NativePlayerOutputCapabilities.toFlags(): UInt =
    (if (hardwareFrameImport) FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT else 0u) or
        (if (softwareFrameUpload) FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD else 0u) or
        (if (zeroCopy) FFPLAYKMP_OUTPUT_ZERO_COPY else 0u) or
        (if (protectedContent) FFPLAYKMP_OUTPUT_PROTECTED_CONTENT else 0u) or
        (if (toneMapHdrToSdr) FFPLAYKMP_OUTPUT_TONE_MAP_HDR_TO_SDR else 0u)

private fun ffplaykmp_snapshot.toNativeSnapshot(): NativePlayerSnapshot {
    val flags = output_flags
    return NativePlayerSnapshot(
        state = NativePlayerState.entries[state.toInt()],
        positionUs = position_us,
        durationUs = duration_us.takeIf { it >= 0L },
        queueSerial = queue_serial,
        outputCapabilities = flags.takeIf { it != 0u }?.let {
            NativePlayerOutputCapabilities(
                hardwareFrameImport = it and FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT != 0u,
                softwareFrameUpload = it and FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD != 0u,
                zeroCopy = it and FFPLAYKMP_OUTPUT_ZERO_COPY != 0u,
                protectedContent = it and FFPLAYKMP_OUTPUT_PROTECTED_CONTENT != 0u,
                toneMapHdrToSdr = it and FFPLAYKMP_OUTPUT_TONE_MAP_HDR_TO_SDR != 0u,
            )
        },
        errorCode = last_error,
        videoWidth = video_width,
        videoHeight = video_height,
        activeDecoder = NativePlayerDecoderKind.entries[active_decoder.toInt()],
        droppedFrames = dropped_frames.toLong(),
        videoInfo = video_width.takeIf { it > 0 }?.let {
            NativePlayerVideoInfo(
                width = it,
                height = video_height,
                pixelFormat = pixel_format,
                pixelFormatName = ffplaykmp_pixel_format_name(pixel_format)?.toKString(),
                bitDepth = bit_depth,
                sampleAspectRatioNumerator = sample_aspect_ratio_num,
                sampleAspectRatioDenominator = sample_aspect_ratio_den,
                rotationDegrees = rotation_degrees,
                colorPrimaries = color_primaries,
                colorTransfer = color_transfer,
                colorSpace = color_space,
                colorRange = color_range,
                chromaLocation = chroma_location,
                hdrType = NativePlayerHdrType.entries[hdr_type.toInt()],
                masteringDisplay = if (mastering_has_primaries != 0 ||
                    mastering_has_luminance != 0
                ) {
                    NativePlayerMasteringDisplayMetadata(
                        hasPrimaries = mastering_has_primaries != 0,
                        hasLuminance = mastering_has_luminance != 0,
                        redX = mastering_red_x,
                        redY = mastering_red_y,
                        greenX = mastering_green_x,
                        greenY = mastering_green_y,
                        blueX = mastering_blue_x,
                        blueY = mastering_blue_y,
                        whiteX = mastering_white_x,
                        whiteY = mastering_white_y,
                        minLuminance = mastering_min_luminance,
                        maxLuminance = mastering_max_luminance,
                    )
                } else {
                    null
                },
                maxContentLightLevel = max_content_light_level.toInt()
                    .takeIf { content_light_present != 0 },
                maxFrameAverageLightLevel = max_frame_average_light_level.toInt()
                    .takeIf { content_light_present != 0 },
            )
        },
    )
}
