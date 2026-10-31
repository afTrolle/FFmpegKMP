// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_frame
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
import org.bytedeco.javacpp.Pointer

internal fun createJavaCppPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: (NativeVideoFrame) -> Unit,
    platformFrame: (NativePlatformVideoFrame) -> Boolean = { false },
    platformOutputTarget: (ffplaykmp_player, Any?, Boolean) -> Int = { _, target, _ ->
        if (target == null) 0 else NativePlayerError.UNSUPPORTED
    },
): NativePlayerBridge {
    val guard = NativeHandleGuard()
    return GuardedPlayerBridge(
        guard,
        JavaCppPlayerEngine(guard, configuration, update, frame, platformFrame, platformOutputTarget),
    )
}

private class JavaCppPlayerEngine(
    /** The bridge's guard: callbacks after close do nothing. */
    guard: NativeHandleGuard,
    configuration: NativePlayerConfiguration,
    private val update: (NativePlayerSnapshot) -> Unit,
    private val frame: (NativeVideoFrame) -> Unit,
    private val platformFrame: (NativePlatformVideoFrame) -> Boolean,
    private val platformOutputTarget: (ffplaykmp_player, Any?, Boolean) -> Int,
) : PlayerEngineCalls {
    private val stateCallback: ffplaykmp_state_callback
    private val frameCallback: ffplaykmp_video_frame_callback
    private val platformFrameCallback: ffplaykmp_platform_video_frame_callback
    private val ioCallback: ffplaykmp_io_callback
    private val player: ffplaykmp_player
    @Volatile
    private var mounts = MountTable.Empty

    init {
        JavaCppBridgeLoader.load()
        stateCallback = object : ffplaykmp_state_callback() {
            override fun call(opaque: Pointer?, snapshot: ffplaykmp_snapshot?) {
                if (snapshot != null && guard.isOpen) update(snapshot.toNativeSnapshot())
            }
        }
        frameCallback = object : ffplaykmp_video_frame_callback() {
            override fun call(opaque: Pointer?, nativeFrame: ffplaykmp_video_frame?) {
                if (nativeFrame == null || !guard.isOpen) return
                // The frame is borrowed for the callback; the receiver gets a reference of its own.
                val retained = nativeFrame.frame()?.takeUnless(ffmpegkmp_frame::isNull)
                    ?.let(bridge::ffmpegkmp_frame_ref)
                    .toNativeFrame() ?: return
                frame(
                    NativeVideoFrame(
                        frame = retained,
                        width = nativeFrame.width(),
                        height = nativeFrame.height(),
                        presentationTimeUs = nativeFrame.presentation_time_us(),
                        queueSerial = nativeFrame.queue_serial().toUInt(),
                    ),
                )
            }
        }
        platformFrameCallback = object : ffplaykmp_platform_video_frame_callback() {
            override fun call(opaque: Pointer?, nativeFrame: ffplaykmp_platform_video_frame?): Int {
                if (nativeFrame == null || !guard.isOpen) return 0
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
        ioCallback = mountedIoCallback { mounts }
        val nativeConfiguration = ffplaykmp_configuration()
        bridge.ffplaykmp_configuration_default(nativeConfiguration)
        nativeConfiguration.decoder_preference(configuration.decoderPreference.ordinal)
        nativeConfiguration.decoder_threads(configuration.decoderThreads)
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

    override fun mount(mounts: List<NativeMountedIo>) {
        this.mounts = MountTable(mounts, replayableSources = true)
    }

    override fun prepare(input: String, requireSecurePath: Boolean): Int = bridge.ffplaykmp_player_prepare(
        player,
        input,
        if (requireSecurePath) bridge.FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH else 0,
    )

    override fun resetCancel() = bridge.ffplaykmp_player_reset_cancel(player)

    override fun setOutput(flags: Int): Int {
        val nativeCapabilities = ffplaykmp_output_capabilities()
        bridge.ffplaykmp_output_capabilities_init(nativeCapabilities)
        nativeCapabilities.flags(flags)
        return try {
            bridge.ffplaykmp_player_set_output(player, nativeCapabilities)
        } finally {
            nativeCapabilities.close()
        }
    }

    override fun setPlatformOutputTarget(target: Any?, secure: Boolean): Int =
        platformOutputTarget(player, target, secure)

    override fun clearOutput() = bridge.ffplaykmp_player_clear_output(player)
    override fun play(): Int = bridge.ffplaykmp_player_play(player)
    override fun pause(): Int = bridge.ffplaykmp_player_pause(player)
    override fun seek(positionUs: Long): Int = bridge.ffplaykmp_player_seek(player, positionUs)
    override fun stop(): Int = bridge.ffplaykmp_player_stop(player)
    override fun cancel() = bridge.ffplaykmp_player_cancel(player)
    override fun setMasterClock(mediaTimeUs: Long) = bridge.ffplaykmp_player_set_master_clock(player, mediaTimeUs)

    override fun snapshot(): NativePlayerSnapshot {
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

    override fun release() {
        bridge.ffplaykmp_player_destroy(player)
        ioCallback.close()
        platformFrameCallback.close()
        frameCallback.close()
        stateCallback.close()
    }
}

internal fun ffplaykmp_snapshot.toNativeSnapshot(): NativePlayerSnapshot = nativePlayerSnapshot(
    state = state(),
    positionUs = position_us(),
    durationUs = duration_us(),
    queueSerial = queue_serial().toUInt(),
    outputFlags = output_flags(),
    errorCode = last_error(),
    videoWidth = video_width(),
    videoHeight = video_height(),
    activeDecoder = active_decoder(),
    droppedFrames = dropped_frames(),
    pixelFormat = pixel_format(),
    pixelFormatName = bridge.ffplaykmp_pixel_format_name(pixel_format())?.getString(),
    bitDepth = bit_depth(),
    sampleAspectRatioNumerator = sample_aspect_ratio_num(),
    sampleAspectRatioDenominator = sample_aspect_ratio_den(),
    rotationDegrees = rotation_degrees(),
    colorPrimaries = color_primaries(),
    colorTransfer = color_transfer(),
    colorSpace = color_space(),
    colorRange = color_range(),
    chromaLocation = chroma_location(),
    hdrFlags = hdr_flags(),
    masteringHasPrimaries = mastering_has_primaries() != 0,
    masteringHasLuminance = mastering_has_luminance() != 0,
    mastering = {
        doubleArrayOf(
            mastering_red_x(), mastering_red_y(), mastering_green_x(), mastering_green_y(),
            mastering_blue_x(), mastering_blue_y(), mastering_white_x(), mastering_white_y(),
            mastering_min_luminance(), mastering_max_luminance(),
        )
    },
    contentLightPresent = content_light_present() != 0,
    maxContentLightLevel = max_content_light_level(),
    maxFrameAverageLightLevel = max_frame_average_light_level(),
)
