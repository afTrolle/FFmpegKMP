// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFPLAYKMP_PLATFORM_FRAME_CV_PIXEL_BUFFER
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffmpegkmp_frame_ref
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_configuration
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_configuration_default
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_input_hint
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_input_hint_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_get_input_hint
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_pixel_format_name
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_output_capabilities
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_output_capabilities_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_cancel
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_clear_output
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_create
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_destroy
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_get_snapshot
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_pause
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_player_set_master_clock
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
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_statistics
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_statistics_init
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_get_statistics
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_decoder_budget_get
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_decoder_budget_in_use
import io.github.aftrolle.ffmpegkmp.bindings.cinterop.ffplaykmp_decoder_budget_set
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
import kotlinx.cinterop.staticCFunction

@InternalFFmpegKmpApi
public actual fun createPlatformPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: (NativeVideoFrame) -> Unit,
    platformFrame: (NativePlatformVideoFrame) -> Boolean,
): NativePlayerBridge {
    val callbackState = NativePlayerCallbackState(update, frame, platformFrame)
    return GuardedPlayerBridge(callbackState.guard, NativeCInteropPlayerEngine(configuration, callbackState))
}

private class NativeCInteropPlayerEngine(
    configuration: NativePlayerConfiguration,
    private val callbackState: NativePlayerCallbackState,
) : PlayerEngineCalls {
    private val callbackReference = StableRef.create(callbackState)
    private val player = memScoped {
        val nativeConfiguration = alloc<ffplaykmp_configuration>()
        ffplaykmp_configuration_default(nativeConfiguration.ptr)
        nativeConfiguration.decoder_preference = configuration.decoderPreference.ordinal.toUInt()
        nativeConfiguration.decoder_threads = configuration.decoderThreads
        nativeConfiguration.codec_release_delay_us = configuration.codecReleaseDelayUs
        ffplaykmp_player_create(
            nativeConfiguration.ptr,
            staticCFunction(::receiveNativePlayerState),
            callbackReference.asCPointer(),
        )
    } ?: run {
        callbackReference.dispose()
        throw NativeBridgeUnavailableException("FFmpegKMP native player allocation failed")
    }

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

    override fun mount(mounts: List<NativeMountedIo>) {
        callbackState.mounts = MountTable(mounts, replayableSources = true)
    }

    override fun prepare(input: String, requireSecurePath: Boolean): Int = ffplaykmp_player_prepare(
        player,
        input,
        if (requireSecurePath) FFPLAYKMP_SOURCE_REQUIRE_SECURE_PATH else 0u,
    )

    override fun resetCancel() = ffplaykmp_player_reset_cancel(player)

    override fun setOutput(flags: Int): Int = memScoped {
        val nativeCapabilities = alloc<ffplaykmp_output_capabilities>()
        ffplaykmp_output_capabilities_init(nativeCapabilities.ptr)
        nativeCapabilities.flags = flags.toUInt()
        ffplaykmp_player_set_output(player, nativeCapabilities.ptr)
    }

    override fun setPlatformOutputTarget(target: Any?, secure: Boolean): Int =
        if (target == null) 0 else NativePlayerError.UNSUPPORTED

    override fun clearOutput() = ffplaykmp_player_clear_output(player)
    override fun play(): Int = ffplaykmp_player_play(player)
    override fun pause(): Int = ffplaykmp_player_pause(player)
    override fun seek(positionUs: Long): Int = ffplaykmp_player_seek(player, positionUs)
    override fun stop(): Int = ffplaykmp_player_stop(player)
    override fun cancel() = ffplaykmp_player_cancel(player)
    override fun setMasterClock(mediaTimeUs: Long) = ffplaykmp_player_set_master_clock(player, mediaTimeUs)

    override fun snapshot(): NativePlayerSnapshot = memScoped {
        val nativeSnapshot = alloc<ffplaykmp_snapshot>()
        ffplaykmp_snapshot_init(nativeSnapshot.ptr)
        val result = ffplaykmp_player_get_snapshot(player, nativeSnapshot.ptr)
        check(result == 0) { "Native player snapshot failed with error $result" }
        nativeSnapshot.toNativeSnapshot()
    }

    override fun inputHint(): NativeInputHint? = memScoped {
        val hint = alloc<ffplaykmp_input_hint>()
        ffplaykmp_input_hint_init(hint.ptr)
        if (ffplaykmp_player_get_input_hint(player, hint.ptr) != 0) return@memScoped null
        NativeInputHint(
            formatName = hint.format_name.toKString(),
            startUs = hint.start_us,
            durationUs = hint.duration_us,
            audioStreams = hint.audio_streams,
        )
    }

    override fun release() {
        ffplaykmp_player_destroy(player)
        callbackReference.dispose()
    }
}

private class NativePlayerCallbackState(
    val update: (NativePlayerSnapshot) -> Unit,
    val frame: (NativeVideoFrame) -> Unit,
    val platformFrame: (NativePlatformVideoFrame) -> Boolean,
) {
    val guard = NativeHandleGuard()

    @kotlin.concurrent.Volatile
    var mounts = MountTable.Empty
}

/**
 * Resolves a callback's state, or null once the player is closing. A Kotlin exception must never
 * cross back into C (it would terminate the process), so [block] failures are swallowed.
 */
private inline fun <T> COpaquePointer?.withCallbackState(fallback: T, block: (NativePlayerCallbackState) -> T): T {
    val state = this?.asStableRef<NativePlayerCallbackState>()?.get() ?: return fallback
    if (!state.guard.isOpen) return fallback
    return runCatching { block(state) }.getOrDefault(fallback)
}

private fun receiveNativePlatformVideoFrame(
    opaque: COpaquePointer?,
    nativeFrame: CPointer<ffplaykmp_platform_video_frame>?,
): Int {
    if (nativeFrame == null) return 0
    val value = nativeFrame.pointed
    val handle = value.handle ?: return 0
    val kind = when (value.kind) {
        FFPLAYKMP_PLATFORM_FRAME_CV_PIXEL_BUFFER -> NativePlatformVideoFrameKind.CV_PIXEL_BUFFER
        else -> return 0
    }
    return opaque.withCallbackState(0) { state ->
        if (
        state.platformFrame(
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
}

private fun receiveNativeVideoFrame(
    opaque: COpaquePointer?,
    nativeFrame: CPointer<ffplaykmp_video_frame>?,
) {
    if (nativeFrame == null) return
    val value = nativeFrame.pointed
    val borrowed = value.frame ?: return
    opaque.withCallbackState(Unit) { state ->
        // The frame is borrowed for the callback; the receiver gets a reference of its own.
        val retained = ffmpegkmp_frame_ref(borrowed).toNativeFrame() ?: return@withCallbackState
        state.frame(
            NativeVideoFrame(
                frame = retained,
                width = value.width,
                height = value.height,
                presentationTimeUs = value.presentation_time_us,
                queueSerial = value.queue_serial,
            ),
        )
    }
}

private fun receiveNativePlayerState(
    opaque: COpaquePointer?,
    snapshot: CPointer<ffplaykmp_snapshot>?,
) {
    if (snapshot == null) return
    opaque.withCallbackState(Unit) { state -> state.update(snapshot.pointed.toNativeSnapshot()) }
}

private fun receiveNativePlayerIo(
    opaque: COpaquePointer?,
    resourceId: Long,
    operation: UInt,
    offset: Long,
    data: CPointer<UByteVar>?,
    size: ULong,
): Long = opaque.withCallbackState(-1L) { state ->
    state.mounts.dispatch(resourceId, operation.toInt(), offset, data, size)
}

internal fun ffplaykmp_snapshot.toNativeSnapshot(): NativePlayerSnapshot = nativePlayerSnapshot(
    state = state.toInt(),
    positionUs = position_us,
    durationUs = duration_us,
    queueSerial = queue_serial,
    outputFlags = output_flags.toInt(),
    errorCode = last_error,
    videoWidth = video_width,
    videoHeight = video_height,
    activeDecoder = active_decoder.toInt(),
    droppedFrames = dropped_frames.toLong(),
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
    hdrFlags = hdr_flags,
    masteringHasPrimaries = mastering_has_primaries != 0,
    masteringHasLuminance = mastering_has_luminance != 0,
    mastering = {
        doubleArrayOf(
            mastering_red_x, mastering_red_y, mastering_green_x, mastering_green_y,
            mastering_blue_x, mastering_blue_y, mastering_white_x, mastering_white_y,
            mastering_min_luminance, mastering_max_luminance,
        )
    },
    contentLightPresent = content_light_present != 0,
    maxContentLightLevel = max_content_light_level.toInt(),
    maxFrameAverageLightLevel = max_frame_average_light_level.toInt(),
    decoderFallback = decoder_fallback.toInt(),
)

@InternalFFmpegKmpApi
public actual fun nativePlayerStatistics(): NativePlayerStatistics = memScoped {
    val statistics = alloc<ffplaykmp_statistics>()
    ffplaykmp_statistics_init(statistics.ptr)
    ffplaykmp_get_statistics(statistics.ptr)
    NativePlayerStatistics(
        codecOpens = statistics.codec_opens.toLong(),
        videoPackets = statistics.video_packets.toLong(),
        downloads = statistics.downloads.toLong(),
        codecCloses = statistics.codec_closes.toLong(),
        inputOpens = statistics.input_opens.toLong(),
        inputProbes = statistics.input_probes.toLong(),
    )
}

@InternalFFmpegKmpApi
public actual fun nativeDecoderBudget(): NativeDecoderBudget =
    NativeDecoderBudget(hardwareDecoders = ffplaykmp_decoder_budget_get(), inUse = ffplaykmp_decoder_budget_in_use())

@InternalFFmpegKmpApi
public actual fun setNativeDecoderBudget(hardwareDecoders: Int) {
    require(hardwareDecoders >= 0) { "The hardware decoder budget must not be negative: $hardwareDecoders" }
    ffplaykmp_decoder_budget_set(hardwareDecoders)
}
