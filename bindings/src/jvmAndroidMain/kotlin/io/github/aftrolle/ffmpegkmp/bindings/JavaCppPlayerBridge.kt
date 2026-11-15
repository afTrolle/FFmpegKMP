// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_frame
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_configuration
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_input_hint
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_output_capabilities
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_platform_video_frame
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_player
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_snapshot
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_statistics
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_video_frame
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.global.bridge
import org.bytedeco.javacpp.BytePointer

internal fun createJavaCppPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: NativeVideoFrameSink,
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
    private val frame: NativeVideoFrameSink,
    private val platformFrame: (NativePlatformVideoFrame) -> Boolean,
    private val platformOutputTarget: (ffplaykmp_player, Any?, Boolean) -> Int,
) : PlayerEngineCalls {
    private val callbacks: JavaCppCallbacks.Registration
    private val player: ffplaykmp_player
    @Volatile
    private var mounts = MountTable.Empty

    init {
        JavaCppBridgeLoader.load()
        callbacks = JavaCppCallbacks.register(
            state = { snapshot -> if (guard.isOpen) update(snapshot.toNativeSnapshot()) },
            videoFrame = { nativeFrame ->
                // A throw would abort the process from the player's thread, as it does on Kotlin/Native.
                runCatching {
                    dispatchVideoFrame(
                        frame,
                        nativeFrame.event(),
                        nativeFrame.token(),
                        guard.isOpen,
                        nativeFrame.display_time_us(),
                    ) {
                        // The frame is borrowed for the callback; the receiver gets a reference of its own.
                        nativeFrame.frame()?.takeUnless(ffmpegkmp_frame::isNull)
                            ?.let(bridge::ffmpegkmp_frame_ref)
                            .toNativeFrame()
                            ?.let { retained ->
                                NativeVideoFrame(
                                    frame = retained,
                                    width = nativeFrame.width(),
                                    height = nativeFrame.height(),
                                    presentationTimeUs = nativeFrame.presentation_time_us(),
                                    queueSerial = nativeFrame.queue_serial().toUInt(),
                                    token = nativeFrame.token(),
                                )
                            }
                    }
                }.getOrDefault(0)
            },
            platformFrame = platformFrame@{ nativeFrame ->
                if (!guard.isOpen) return@platformFrame 0
                val handle = nativeFrame.handle() ?: return@platformFrame 0
                val kind = when (nativeFrame.kind()) {
                    bridge.FFPLAYKMP_PLATFORM_FRAME_CV_PIXEL_BUFFER ->
                        NativePlatformVideoFrameKind.CV_PIXEL_BUFFER
                    else -> return@platformFrame 0
                }
                if (
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
            },
            mounts = { mounts },
        )
        val nativeConfiguration = ffplaykmp_configuration()
        bridge.ffplaykmp_configuration_default(nativeConfiguration)
        nativeConfiguration.decoder_preference(configuration.decoderPreference.ordinal)
        nativeConfiguration.decoder_threads(configuration.decoderThreads)
        nativeConfiguration.decoder_thread_type(configuration.decoderThreadType)
        nativeConfiguration.codec_release_delay_us(configuration.codecReleaseDelayUs)
        nativeConfiguration.frame_queue_size(configuration.frameQueueSize)
        nativeConfiguration.catch_up_lag_us(configuration.catchUpLagUs)
        // Kept until the call returns: the player copies the name and the options it keeps.
        val decoderName = configuration.decoderName?.let { BytePointer(it, Charsets.UTF_8) }
        val decoderOptions = packCodecOptions(configuration.decoderOptions)
            .takeIf(ByteArray::isNotEmpty)?.let { BytePointer(*it) }
        decoderName?.let(nativeConfiguration::decoder_name)
        decoderOptions?.let { nativeConfiguration.decoder_options(it).decoder_options_size(it.capacity().toInt()) }
        val created = bridge.ffplaykmp_player_create(nativeConfiguration, JavaCppCallbacks.state, callbacks.opaque)
        decoderName?.close()
        decoderOptions?.close()
        player = created
            ?: run {
                nativeConfiguration.close()
                callbacks.close()
                throw NativeBridgeUnavailableException("FFmpegKMP native player allocation failed")
            }
        nativeConfiguration.close()
        bridge.ffplaykmp_player_set_io_callback(player, JavaCppCallbacks.io, callbacks.opaque)
        bridge.ffplaykmp_player_set_video_frame_callback(player, JavaCppCallbacks.videoFrame, callbacks.opaque)
        bridge.ffplaykmp_player_set_platform_video_frame_callback(player, JavaCppCallbacks.platformFrame, callbacks.opaque)
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
    override fun refresh(nowUs: Long, leadUs: Long): Long = bridge.ffplaykmp_player_refresh(player, nowUs, leadUs)
    override fun refreshAt(nowUs: Long, leadUs: Long, positionNs: Long): Long =
        bridge.ffplaykmp_player_refresh_at(player, nowUs, leadUs, positionNs)
    override fun clockNowUs(): Long = bridge.ffplaykmp_clock_now_us()

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

    override fun inputHint(): NativeInputHint? {
        val hint = ffplaykmp_input_hint()
        bridge.ffplaykmp_input_hint_init(hint)
        return try {
            if (bridge.ffplaykmp_player_get_input_hint(player, hint) != 0) {
                null
            } else {
                NativeInputHint(
                    formatName = hint.format_name().string,
                    startUs = hint.start_us(),
                    durationUs = hint.duration_us(),
                    audioStreams = hint.audio_streams(),
                )
            }
        } finally {
            hint.close()
        }
    }

    override fun release() {
        bridge.ffplaykmp_player_destroy(player)
        callbacks.close()
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
    catchUps = catch_ups(),
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
    decoderFallback = decoder_fallback(),
    errorMessage = error_message().string,
)

@InternalFFmpegKmpApi
public actual fun nativePlayerStatistics(): NativePlayerStatistics {
    JavaCppBridgeLoader.load()
    val statistics = ffplaykmp_statistics()
    return try {
        bridge.ffplaykmp_statistics_init(statistics)
        bridge.ffplaykmp_get_statistics(statistics)
        NativePlayerStatistics(
            codecOpens = statistics.codec_opens(),
            videoPackets = statistics.video_packets(),
            downloads = statistics.downloads(),
            codecCloses = statistics.codec_closes(),
            inputOpens = statistics.input_opens(),
            inputProbes = statistics.input_probes(),
        )
    } finally {
        statistics.close()
    }
}

@InternalFFmpegKmpApi
public actual fun nativeDecoderBudget(): NativeDecoderBudget {
    JavaCppBridgeLoader.load()
    return NativeDecoderBudget(
        hardwareDecoders = bridge.ffplaykmp_decoder_budget_get(),
        inUse = bridge.ffplaykmp_decoder_budget_in_use(),
    )
}

@InternalFFmpegKmpApi
public actual fun setNativeDecoderBudget(hardwareDecoders: Int) {
    require(hardwareDecoders >= 0) { "The hardware decoder budget must not be negative: $hardwareDecoders" }
    JavaCppBridgeLoader.load()
    bridge.ffplaykmp_decoder_budget_set(hardwareDecoders)
}

/** `ffplaykmp_hardware_decoder_available` for [codec]; null where the platform does not say. */
internal fun javaCppHardwareDecoderAvailable(codec: String): Boolean? {
    JavaCppBridgeLoader.load()
    return when (bridge.ffplaykmp_hardware_decoder_available(codec)) {
        1 -> true
        0 -> false
        else -> null
    }
}
