// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlinx.coroutines.CancellationException
import kotlin.js.JsAny
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.Buffer

/** A player running in its own Web Worker; [cancel] terminates the worker. */
internal interface BrowserPlayerWorker {
    fun prepare(source: NativePlayerSource, mountBytes: Array<ByteArray>)
    fun setOutput(flags: Int)
    fun clearOutput()
    fun play()
    fun pause()
    fun seek(positionUs: Long)
    fun stop()
    fun cancel()

    /** Posts a player message built in JavaScript, handing over the JS array [transfers]. */
    fun post(message: JsAny, transfers: JsAny)
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

    /** `opened` with the audio's tracks as JSON, or `failure` with a message. */
    fun onAudioEvent(event: String, payload: String)
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
    private var audio: BrowserPlayerAudio? = null
    private var audioOpening: CompletableDeferred<String>? = null

    override fun resetCancellation() {
        checkOpen()
        if (worker == null) worker = startWorker()
    }

    override fun prepare(source: NativePlayerSource): Int {
        checkOpen()
        if (source.requireSecurePath) return fail(NativePlayerError.UNSUPPORTED)
        val activeWorker = worker ?: startWorker().also { worker = it }
        check(pendingPreparation?.completion?.isActive != true) {
            "A browser player preparation is already active"
        }
        val pending = PendingBrowserPreparation(workerGeneration, CompletableDeferred())
        pendingPreparation = pending
        releaseAudio()
        this.source = source
        current = current.copy(
            state = NativePlayerState.PREPARING,
            queueSerial = current.queueSerial + 1u,
            errorCode = 0,
        )
        update(current)
        activeWorker.prepare(source, source.mounts.readBytesForBrowserPlayer())
        output?.let { activeWorker.setOutput(it.toNativeFlags()) }
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
            capabilities.protectedContent -> NativePlayerError.UNSUPPORTED
            configuration.decoderPreference == NativePlayerDecoderPreference.REQUIRE_HARDWARE -> NativePlayerError.UNSUPPORTED
            !capabilities.softwareFrameUpload && !capabilities.hardwareFrameImport -> NativePlayerError.UNSUPPORTED
            else -> 0
        }
        if (validation < 0) return fail(validation)
        output = capabilities
        if (source != null) worker?.setOutput(capabilities.toNativeFlags())
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
        if (positionUs < 0) return NativePlayerError.INVALID_ARGUMENT
        return preparedCall { it.seek(positionUs) }
    }

    override fun setMasterClock(mediaTimeUs: Long) {
        if (!closed && source != null) worker?.post(masterClockMessage(mediaTimeUs.toDouble()), emptyTransfers())
    }

    override suspend fun openAudio(): NativePlayerAudio {
        checkOpen()
        val activeWorker = checkNotNull(worker) { "The browser player worker is not active" }
        check(source != null) { "Prepare a source before opening its audio" }
        releaseAudio()
        val opened = CompletableDeferred<String>().also { audioOpening = it }
        return try {
            BrowserPlayerAudio.open(activeWorker, opened) { current.positionUs }.also { audio = it }
        } finally {
            if (audioOpening === opened) audioOpening = null
        }
    }

    override fun stop(): Int {
        checkOpen()
        releaseAudio()
        worker?.stop()
        source = null
        return 0
    }

    override fun cancel() {
        if (closed) return
        workerGeneration++
        releaseAudio()
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
        releaseAudio()
        source = null
        output = null
        worker?.cancel()
        worker = null
        pendingPreparation?.completion?.cancel(
            CancellationException("The browser player bridge was closed"),
        )
    }

    /** Its caller normally closes the audio first; this covers a worker that is going away. */
    private fun releaseAudio() {
        audio?.close()
        audio = null
        audioOpening?.cancel(CancellationException("The browser player source changed"))
        audioOpening = null
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
                    ?.complete(current.errorCode.takeIf { it < 0 } ?: NativePlayerError.IO)
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
            current = current.copy(state = NativePlayerState.FAILED, errorCode = NativePlayerError.IO)
            update(current)
            pendingPreparationForGeneration()?.completion?.complete(NativePlayerError.IO)
        }

        override fun onAudioEvent(event: String, payload: String) {
            if (closed || generation != workerGeneration) return
            when (event) {
                "opened" -> audioOpening?.complete(payload)
                "failure" -> audioOpening?.completeExceptionally(IllegalStateException(payload))
                    ?: audio?.fail(payload)
            }
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
        // Through the replay cache, so preparing the same source again still has its bytes.
        is NativeSourceResource -> {
            resource.replay.readAll()
        }
        is NativeSinkResource -> ByteArray(0)
    }
}.toTypedArray()

internal fun String.toBrowserNativePlayerSnapshot(): NativePlayerSnapshot {
    val value = Json.parseToJsonElement(this) as JsonObject
    return nativePlayerSnapshot(
        state = value.int("state"),
        positionUs = value.long("positionUs"),
        durationUs = value.long("durationUs"),
        queueSerial = value.long("queueSerial").toUInt(),
        outputFlags = value.int("outputFlags"),
        errorCode = value.int("errorCode"),
        videoWidth = value.int("videoWidth"),
        videoHeight = value.int("videoHeight"),
        activeDecoder = value.int("activeDecoder"),
        droppedFrames = value.long("droppedFrames"),
        pixelFormat = value.int("pixelFormat"),
        pixelFormatName = value.getValue("pixelFormatName").jsonPrimitive.content,
        bitDepth = value.int("bitDepth"),
        sampleAspectRatioNumerator = value.int("sarNum"),
        sampleAspectRatioDenominator = value.int("sarDen"),
        rotationDegrees = value.double("rotation"),
        colorPrimaries = value.int("colorPrimaries"),
        colorTransfer = value.int("colorTransfer"),
        colorSpace = value.int("colorSpace"),
        colorRange = value.int("colorRange"),
        chromaLocation = value.int("chromaLocation"),
        hdrType = value.int("hdrType"),
        masteringHasPrimaries = value.int("masteringHasPrimaries") != 0,
        masteringHasLuminance = value.int("masteringHasLuminance") != 0,
        mastering = {
            listOf(
                "RedX", "RedY", "GreenX", "GreenY", "BlueX", "BlueY", "WhiteX", "WhiteY",
                "MinLuminance", "MaxLuminance",
            ).map { value.double("mastering$it") }.toDoubleArray()
        },
        contentLightPresent = value.int("contentLightPresent") != 0,
        maxContentLightLevel = value.int("maxContentLightLevel"),
        maxFrameAverageLightLevel = value.int("maxFrameAverageLightLevel"),
    )
}

private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long
private fun JsonObject.double(name: String): Double = getValue(name).jsonPrimitive.double
