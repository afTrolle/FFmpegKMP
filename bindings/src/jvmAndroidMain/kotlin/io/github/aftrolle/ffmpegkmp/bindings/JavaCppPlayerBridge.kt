// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_configuration
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_io_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_output_capabilities
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_platform_video_frame
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_platform_video_frame_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_player
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_snapshot
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_state_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_video_frame
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_video_frame_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer

internal fun createJavaCppPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: (NativeVideoFrame) -> Unit,
    platformFrame: (NativePlatformVideoFrame) -> Boolean = { false },
    platformOutputTarget: (ffplaykmp_player, Any?, Boolean) -> Int = { _, target, _ ->
        if (target == null) 0 else -95
    },
): NativePlayerBridge = JavaCppPlayerBridge(
    configuration,
    update,
    frame,
    platformFrame,
    platformOutputTarget,
)

private class JavaCppPlayerBridge(
    configuration: NativePlayerConfiguration,
    private val update: (NativePlayerSnapshot) -> Unit,
    private val frame: (NativeVideoFrame) -> Unit,
    private val platformFrame: (NativePlatformVideoFrame) -> Boolean,
    private val platformOutputTarget: (ffplaykmp_player, Any?, Boolean) -> Int,
) : NativePlayerBridge {
    private val stateCallback: ffplaykmp_state_callback
    private val frameCallback: ffplaykmp_video_frame_callback
    private val platformFrameCallback: ffplaykmp_platform_video_frame_callback
    private val ioCallback: ffplaykmp_io_callback
    private val player: ffplaykmp_player
    @Volatile
    private var mounts: Map<Long, MountedResource> = emptyMap()
    @Volatile
    private var closed = false

    init {
        JavaCppBridgeLoader.load()
        stateCallback = object : ffplaykmp_state_callback() {
            override fun call(opaque: Pointer?, snapshot: ffplaykmp_snapshot?) {
                if (snapshot != null && !closed) update(snapshot.toNativeSnapshot())
            }
        }
        frameCallback = object : ffplaykmp_video_frame_callback() {
            override fun call(opaque: Pointer?, nativeFrame: ffplaykmp_video_frame?) {
                if (nativeFrame == null || closed) return
                val size = nativeFrame.rgba_size()
                val data = nativeFrame.rgba()
                if (data == null || size <= 0 || size > Int.MAX_VALUE) return
                val rgba = ByteArray(size.toInt())
                data.get(rgba)
                frame(
                    NativeVideoFrame(
                        rgba = rgba,
                        width = nativeFrame.width(),
                        height = nativeFrame.height(),
                        stride = nativeFrame.stride(),
                        presentationTimeUs = nativeFrame.presentation_time_us(),
                        queueSerial = nativeFrame.queue_serial().toUInt(),
                    ),
                )
            }
        }
        platformFrameCallback = object : ffplaykmp_platform_video_frame_callback() {
            override fun call(opaque: Pointer?, nativeFrame: ffplaykmp_platform_video_frame?): Int {
                if (nativeFrame == null || closed) return 0
                val handle = nativeFrame.handle() ?: return 0
                val kind = when (nativeFrame.kind()) {
                    bridge.FFPLAYKMP_PLATFORM_FRAME_CV_PIXEL_BUFFER ->
                        NativePlatformVideoFrameKind.CV_PIXEL_BUFFER
                    else -> return 0
                }
                return if (
                    platformFrame(
                        NativePlatformVideoFrame(
                            kind = kind,
                            handle = handle,
                            width = nativeFrame.width(),
                            height = nativeFrame.height(),
                            presentationTimeUs = nativeFrame.presentation_time_us(),
                            queueSerial = nativeFrame.queue_serial().toUInt(),
                        ),
                    )
                ) 1 else 0
            }
        }
        ioCallback = object : ffplaykmp_io_callback() {
            override fun call(
                opaque: Pointer?,
                resourceId: Long,
                operation: Int,
                offset: Long,
                data: BytePointer?,
                size: Long,
            ): Long = mounts[resourceId]?.dispatch(operation, offset, data, size) ?: -1L
        }
        val nativeConfiguration = ffplaykmp_configuration()
        bridge.ffplaykmp_configuration_default(nativeConfiguration)
        nativeConfiguration.decoder_preference(configuration.decoderPreference.ordinal)
        player = bridge.ffplaykmp_player_create(nativeConfiguration, stateCallback, null)
            ?: run {
                nativeConfiguration.close()
                ioCallback.close()
                platformFrameCallback.close()
                frameCallback.close()
                stateCallback.close()
                throw NativeBridgeUnavailableException("FFmpegKMP native player allocation failed")
            }
        nativeConfiguration.close()
        bridge.ffplaykmp_player_set_io_callback(player, ioCallback, null)
        bridge.ffplaykmp_player_set_video_frame_callback(player, frameCallback, null)
        bridge.ffplaykmp_player_set_platform_video_frame_callback(
            player,
            platformFrameCallback,
            null,
        )
    }

    override fun prepare(source: NativePlayerSource): Int {
        checkOpen()
        mounts = source.mounts.mapIndexed { index, mount ->
            index.toLong() + 1L to MountedResource(
                resource = mount.resource,
                replayableSource = true,
            )
        }.toMap()
        val mountedInput = source.mounts.indexOfFirst { it.path == source.input }
            .takeIf { it >= 0 }
            ?.let { protocolUrl(it.toLong() + 1L, source.input) }
            ?: source.input
        return bridge.ffplaykmp_player_prepare(
            player,
            mountedInput,
            if (source.requireSecurePath) bridge.FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH else 0,
        ).also { result -> if (result < 0) mounts = emptyMap() }
    }

    override fun resetCancellation() {
        checkOpen()
        // Joins the outgoing worker before a subsequent prepare reuses its mount ids.
        bridge.ffplaykmp_player_reset_cancel(player)
    }

    override fun setOutput(capabilities: NativePlayerOutputCapabilities): Int {
        checkOpen()
        val nativeCapabilities = ffplaykmp_output_capabilities()
        bridge.ffplaykmp_output_capabilities_init(nativeCapabilities)
        nativeCapabilities.flags(capabilities.toFlags())
        return try {
            bridge.ffplaykmp_player_set_output(player, nativeCapabilities)
        } finally {
            nativeCapabilities.close()
        }
    }

    override fun setPlatformOutputTarget(target: Any?, secure: Boolean): Int {
        checkOpen()
        return platformOutputTarget(player, target, secure)
    }

    override fun clearOutput() {
        checkOpen()
        bridge.ffplaykmp_player_clear_output(player)
    }

    override fun play(): Int = checkedCall { bridge.ffplaykmp_player_play(player) }
    override fun pause(): Int = checkedCall { bridge.ffplaykmp_player_pause(player) }
    override fun seek(positionUs: Long): Int = checkedCall {
        bridge.ffplaykmp_player_seek(player, positionUs)
    }
    override fun stop(): Int = checkedCall {
        bridge.ffplaykmp_player_stop(player).also { if (it == 0) mounts = emptyMap() }
    }
    override fun cancel() {
        if (!closed) bridge.ffplaykmp_player_cancel(player)
    }

    override fun snapshot(): NativePlayerSnapshot {
        checkOpen()
        val nativeSnapshot = ffplaykmp_snapshot()
        bridge.ffplaykmp_snapshot_init(nativeSnapshot)
        return try {
            val result = bridge.ffplaykmp_player_get_snapshot(player, nativeSnapshot)
            check(result == 0) { "Native player snapshot failed with error $result" }
            nativeSnapshot.toNativeSnapshot()
        } finally {
            nativeSnapshot.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        mounts = emptyMap()
        bridge.ffplaykmp_player_destroy(player)
        ioCallback.close()
        platformFrameCallback.close()
        frameCallback.close()
        stateCallback.close()
    }

    private inline fun checkedCall(block: () -> Int): Int {
        checkOpen()
        return block()
    }

    private fun checkOpen() = check(!closed) { "The native player bridge is closed" }
}

private fun NativePlayerOutputCapabilities.toFlags(): Int =
    (if (hardwareFrameImport) bridge.FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT else 0) or
        (if (softwareFrameUpload) bridge.FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD else 0) or
        (if (zeroCopy) bridge.FFPLAYKMP_OUTPUT_ZERO_COPY else 0) or
        (if (protectedContent) bridge.FFPLAYKMP_OUTPUT_PROTECTED_CONTENT else 0) or
        (if (toneMapHdrToSdr) bridge.FFPLAYKMP_OUTPUT_TONE_MAP_HDR_TO_SDR else 0)

private fun ffplaykmp_snapshot.toNativeSnapshot(): NativePlayerSnapshot {
    val flags = output_flags()
    return NativePlayerSnapshot(
        state = NativePlayerState.entries[state()],
        positionUs = position_us(),
        durationUs = duration_us().takeIf { it >= 0L },
        queueSerial = queue_serial().toUInt(),
        outputCapabilities = flags.takeIf { it != 0 }?.let {
            NativePlayerOutputCapabilities(
                hardwareFrameImport = it and bridge.FFPLAYKMP_OUTPUT_HARDWARE_FRAME_IMPORT != 0,
                softwareFrameUpload = it and bridge.FFPLAYKMP_OUTPUT_SOFTWARE_FRAME_UPLOAD != 0,
                zeroCopy = it and bridge.FFPLAYKMP_OUTPUT_ZERO_COPY != 0,
                protectedContent = it and bridge.FFPLAYKMP_OUTPUT_PROTECTED_CONTENT != 0,
                toneMapHdrToSdr = it and bridge.FFPLAYKMP_OUTPUT_TONE_MAP_HDR_TO_SDR != 0,
            )
        },
        errorCode = last_error(),
        videoWidth = video_width(),
        videoHeight = video_height(),
        activeDecoder = NativePlayerDecoderKind.entries[active_decoder()],
        droppedFrames = dropped_frames(),
        videoInfo = video_width().takeIf { it > 0 }?.let {
            NativePlayerVideoInfo(
                width = it,
                height = video_height(),
                pixelFormat = pixel_format(),
                pixelFormatName = bridge.ffplaykmp_pixel_format_name(pixel_format())
                    ?.getString(),
                bitDepth = bit_depth(),
                sampleAspectRatioNumerator = sample_aspect_ratio_num(),
                sampleAspectRatioDenominator = sample_aspect_ratio_den(),
                rotationDegrees = rotation_degrees(),
                colorPrimaries = color_primaries(),
                colorTransfer = color_transfer(),
                colorSpace = color_space(),
                colorRange = color_range(),
                chromaLocation = chroma_location(),
                hdrType = NativePlayerHdrType.entries[hdr_type()],
                masteringDisplay = if (mastering_has_primaries() != 0 ||
                    mastering_has_luminance() != 0
                ) {
                    NativePlayerMasteringDisplayMetadata(
                        hasPrimaries = mastering_has_primaries() != 0,
                        hasLuminance = mastering_has_luminance() != 0,
                        redX = mastering_red_x(),
                        redY = mastering_red_y(),
                        greenX = mastering_green_x(),
                        greenY = mastering_green_y(),
                        blueX = mastering_blue_x(),
                        blueY = mastering_blue_y(),
                        whiteX = mastering_white_x(),
                        whiteY = mastering_white_y(),
                        minLuminance = mastering_min_luminance(),
                        maxLuminance = mastering_max_luminance(),
                    )
                } else {
                    null
                },
                maxContentLightLevel = max_content_light_level()
                    .takeIf { content_light_present() != 0 },
                maxFrameAverageLightLevel = max_frame_average_light_level()
                    .takeIf { content_light_present() != 0 },
            )
        },
    )
}
