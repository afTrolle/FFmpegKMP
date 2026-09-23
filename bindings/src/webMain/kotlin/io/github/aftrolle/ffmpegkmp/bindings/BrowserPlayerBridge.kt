// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.Buffer

internal interface BrowserPlayerWorker : AutoCloseable {
    fun prepare(source: NativePlayerSource, mountBytes: Array<ByteArray>)
    fun setOutput(flags: Int)
    fun clearOutput()
    fun play()
    fun pause()
    fun seek(positionUs: Long)
    fun stop()
    fun cancel()
}

internal interface BrowserPlayerWorkerListener {
    fun onSnapshot(snapshotJson: String)
    fun onFrame(
        bytes: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        presentationTimeUs: Long,
        queueSerial: UInt,
    )
    fun onPlatformFrame(
        frameId: Int,
        width: Int,
        height: Int,
        presentationTimeUs: Long,
        queueSerial: UInt,
    ): Boolean
    fun onFailure(message: String)
}

internal expect fun startBrowserPlayerWorker(
    decoderPreference: Int,
    listener: BrowserPlayerWorkerListener,
): BrowserPlayerWorker

internal fun createBrowserPlayerBridge(
    configuration: NativePlayerConfiguration,
    update: (NativePlayerSnapshot) -> Unit,
    frame: (NativeVideoFrame) -> Unit,
    platformFrame: (NativePlatformVideoFrame) -> Boolean,
    workerFactory: (Int, BrowserPlayerWorkerListener) -> BrowserPlayerWorker =
        ::startBrowserPlayerWorker,
): NativePlayerBridge = BrowserNativePlayerBridge(
    configuration,
    update,
    frame,
    platformFrame,
    workerFactory,
)

private class BrowserNativePlayerBridge(
    private val configuration: NativePlayerConfiguration,
    private val update: (NativePlayerSnapshot) -> Unit,
    private val frame: (NativeVideoFrame) -> Unit,
    private val platformFrame: (NativePlatformVideoFrame) -> Boolean,
    private val workerFactory: (Int, BrowserPlayerWorkerListener) -> BrowserPlayerWorker,
) : NativePlayerBridge {
    private var current = NativePlayerSnapshot()
    private var source: NativePlayerSource? = null
    private var output: NativePlayerOutputCapabilities? = null
    private var closed = false
    private var workerGeneration = 0
    private var worker: BrowserPlayerWorker? = startWorker()
    private var pendingPreparation: PendingBrowserPreparation? = null

    override fun resetCancellation() {
        checkOpen()
        if (worker == null) worker = startWorker()
    }

    override fun prepare(source: NativePlayerSource): Int {
        checkOpen()
        if (source.requireSecurePath) return fail(-95)
        val activeWorker = worker ?: startWorker().also { worker = it }
        check(pendingPreparation?.completion?.isActive != true) {
            "A browser player preparation is already active"
        }
        val pending = PendingBrowserPreparation(workerGeneration, CompletableDeferred())
        pendingPreparation = pending
        this.source = source
        current = current.copy(
            state = NativePlayerState.PREPARING,
            queueSerial = current.queueSerial + 1u,
            errorCode = 0,
        )
        update(current)
        activeWorker.prepare(source, source.mounts.readBytesForBrowserPlayer())
        output?.let { activeWorker.setOutput(it.toBrowserFlags()) }
        return 0
    }

    override suspend fun awaitPreparation(): Int {
        val pending = checkNotNull(pendingPreparation) { "No browser player preparation is active" }
        return try {
            pending.completion.await()
        } finally {
            if (pendingPreparation === pending) pendingPreparation = null
        }
    }

    override fun setOutput(capabilities: NativePlayerOutputCapabilities): Int {
        checkOpen()
        val validation = when {
            capabilities.protectedContent -> -95
            configuration.decoderPreference == NativePlayerDecoderPreference.REQUIRE_HARDWARE -> -95
            !capabilities.softwareFrameUpload && !capabilities.hardwareFrameImport -> -95
            else -> 0
        }
        if (validation < 0) return fail(validation)
        output = capabilities
        if (source != null) worker?.setOutput(capabilities.toBrowserFlags())
        return 0
    }

    override fun clearOutput() {
        checkOpen()
        output = null
        worker?.clearOutput()
    }

    override fun play(): Int = preparedCall { it.play() }
    override fun pause(): Int = preparedCall { it.pause() }

    override fun seek(positionUs: Long): Int {
        if (positionUs < 0) return -22
        return preparedCall { it.seek(positionUs) }
    }

    override fun stop(): Int {
        checkOpen()
        worker?.stop()
        source = null
        return 0
    }

    override fun cancel() {
        if (closed) return
        workerGeneration++
        worker?.cancel()
        worker = null
        source = null
        pendingPreparation?.completion?.cancel(
            CancellationException("Browser player preparation was cancelled"),
        )
    }

    override fun snapshot(): NativePlayerSnapshot = current

    override fun close() {
        if (closed) return
        closed = true
        workerGeneration++
        source = null
        output = null
        worker?.cancel()
        worker = null
        pendingPreparation?.completion?.cancel(
            CancellationException("The browser player bridge was closed"),
        )
    }

    private fun preparedCall(action: (BrowserPlayerWorker) -> Unit): Int {
        checkOpen()
        check(source != null) { "Prepare a source before controlling playback" }
        action(checkNotNull(worker) { "The browser player worker is not active" })
        return 0
    }

    private fun startWorker(): BrowserPlayerWorker {
        val generation = ++workerGeneration
        return workerFactory(
            configuration.decoderPreference.ordinal,
            BrowserPlayerListener(generation),
        )
    }

    private inner class BrowserPlayerListener(
        private val generation: Int,
    ) : BrowserPlayerWorkerListener {
        override fun onSnapshot(snapshotJson: String) {
            if (closed || generation != workerGeneration) return
            current = snapshotJson.toBrowserNativePlayerSnapshot()
            if (current.state == NativePlayerState.FAILED) source = null
            update(current)
            when (current.state) {
                NativePlayerState.WAITING_FOR_OUTPUT,
                NativePlayerState.READY,
                -> pendingPreparationForGeneration()?.completion?.complete(0)
                NativePlayerState.FAILED -> pendingPreparationForGeneration()
                    ?.completion
                    ?.complete(current.errorCode.takeIf { it < 0 } ?: -5)
                else -> Unit
            }
        }

        override fun onFrame(
            bytes: ByteArray,
            width: Int,
            height: Int,
            stride: Int,
            presentationTimeUs: Long,
            queueSerial: UInt,
        ) {
            if (closed || generation != workerGeneration || queueSerial != current.queueSerial) return
            frame(
                NativeVideoFrame(
                    rgba = bytes,
                    width = width,
                    height = height,
                    stride = stride,
                    presentationTimeUs = presentationTimeUs,
                    queueSerial = queueSerial,
                ),
            )
        }

        override fun onPlatformFrame(
            frameId: Int,
            width: Int,
            height: Int,
            presentationTimeUs: Long,
            queueSerial: UInt,
        ): Boolean {
            if (closed || generation != workerGeneration || queueSerial != current.queueSerial) return false
            return platformFrame(
                NativePlatformVideoFrame(
                    kind = NativePlatformVideoFrameKind.WEB_VIDEO_FRAME,
                    handle = frameId,
                    width = width,
                    height = height,
                    presentationTimeUs = presentationTimeUs,
                    queueSerial = queueSerial,
                ),
            )
        }

        override fun onFailure(message: String) {
            if (closed || generation != workerGeneration) return
            source = null
            current = current.copy(state = NativePlayerState.FAILED, errorCode = -5)
            update(current)
            pendingPreparationForGeneration()?.completion?.complete(-5)
        }

        private fun pendingPreparationForGeneration(): PendingBrowserPreparation? =
            pendingPreparation?.takeIf { it.generation == generation }
    }

    private fun fail(code: Int): Int {
        current = current.copy(state = NativePlayerState.FAILED, errorCode = code)
        update(current)
        return code
    }

    private fun checkOpen() = check(!closed) { "The browser player bridge is closed" }
}

private class PendingBrowserPreparation(
    val generation: Int,
    val completion: CompletableDeferred<Int>,
)

private fun List<NativeMountedIo>.readBytesForBrowserPlayer(): Array<ByteArray> = map { mount ->
    when (val resource = mount.resource) {
        is NativeFileResource -> {
            val buffer = Buffer()
            resource.fileHandle.read(0L, buffer, resource.fileHandle.size())
            buffer.readByteArray()
        }
        is NativeSourceResource -> {
            val buffer = Buffer()
            while (resource.source.read(buffer, 8_192L) != -1L) {
                // Drain the mounted source before transferring its independently owned buffer.
            }
            buffer.readByteArray()
        }
        is NativeSinkResource -> ByteArray(0)
    }
}.toTypedArray()

private fun NativePlayerOutputCapabilities.toBrowserFlags(): Int =
    (if (hardwareFrameImport) 1 else 0) or
        (if (softwareFrameUpload) 2 else 0) or
        (if (zeroCopy) 4 else 0) or
        (if (protectedContent) 8 else 0) or
        (if (toneMapHdrToSdr) 16 else 0)

internal fun String.toBrowserNativePlayerSnapshot(): NativePlayerSnapshot {
    val value = Json.parseToJsonElement(this) as JsonObject
    val mastering = if (value.int("masteringHasPrimaries") != 0 ||
        value.int("masteringHasLuminance") != 0
    ) {
        NativePlayerMasteringDisplayMetadata(
            hasPrimaries = value.int("masteringHasPrimaries") != 0,
            hasLuminance = value.int("masteringHasLuminance") != 0,
            redX = value.double("masteringRedX"),
            redY = value.double("masteringRedY"),
            greenX = value.double("masteringGreenX"),
            greenY = value.double("masteringGreenY"),
            blueX = value.double("masteringBlueX"),
            blueY = value.double("masteringBlueY"),
            whiteX = value.double("masteringWhiteX"),
            whiteY = value.double("masteringWhiteY"),
            minLuminance = value.double("masteringMinLuminance"),
            maxLuminance = value.double("masteringMaxLuminance"),
        )
    } else {
        null
    }
    val width = value.int("videoWidth")
    val height = value.int("videoHeight")
    val outputFlags = value.int("outputFlags")
    val duration = value.long("durationUs")
    return NativePlayerSnapshot(
        state = NativePlayerState.entries.getOrElse(value.int("state")) { NativePlayerState.FAILED },
        positionUs = value.long("positionUs"),
        durationUs = duration.takeIf { it >= 0 },
        queueSerial = value.long("queueSerial").toUInt(),
        outputCapabilities = outputFlags.takeIf { it != 0 }?.let {
            NativePlayerOutputCapabilities(
                hardwareFrameImport = it and 1 != 0,
                softwareFrameUpload = it and 2 != 0,
                zeroCopy = it and 4 != 0,
                protectedContent = it and 8 != 0,
                toneMapHdrToSdr = it and 16 != 0,
            )
        },
        errorCode = value.int("errorCode"),
        videoWidth = width,
        videoHeight = height,
        activeDecoder = NativePlayerDecoderKind.entries.getOrElse(value.int("activeDecoder")) {
            NativePlayerDecoderKind.UNKNOWN
        },
        videoInfo = if (width > 0 && height > 0) {
            NativePlayerVideoInfo(
                width = width,
                height = height,
                pixelFormat = value.int("pixelFormat"),
                pixelFormatName = value.getValue("pixelFormatName").jsonPrimitive.content.ifEmpty { null },
                bitDepth = value.int("bitDepth"),
                sampleAspectRatioNumerator = value.int("sarNum"),
                sampleAspectRatioDenominator = value.int("sarDen"),
                rotationDegrees = value.double("rotation"),
                colorPrimaries = value.int("colorPrimaries"),
                colorTransfer = value.int("colorTransfer"),
                colorSpace = value.int("colorSpace"),
                colorRange = value.int("colorRange"),
                chromaLocation = value.int("chromaLocation"),
                hdrType = NativePlayerHdrType.entries.getOrElse(value.int("hdrType")) {
                    NativePlayerHdrType.UNKNOWN_HDR
                },
                masteringDisplay = mastering,
                maxContentLightLevel = value.int("maxContentLightLevel")
                    .takeIf { value.int("contentLightPresent") != 0 },
                maxFrameAverageLightLevel = value.int("maxFrameAverageLightLevel")
                    .takeIf { value.int("contentLightPresent") != 0 },
            )
        } else {
            null
        },
        droppedFrames = value.long("droppedFrames"),
    )
}

private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long
private fun JsonObject.double(name: String): Double = getValue(name).jsonPrimitive.double
