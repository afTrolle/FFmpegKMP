// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.core

import io.github.aftrolle.ffmpegkmp.bindings.NativeCommandKind
import io.github.aftrolle.ffmpegkmp.bindings.NativeExecutionBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativeExecutionEvent
import io.github.aftrolle.ffmpegkmp.bindings.NativeExecutionRequest
import io.github.aftrolle.ffmpegkmp.bindings.NativeFileResource
import io.github.aftrolle.ffmpegkmp.bindings.NativeIoAccess
import io.github.aftrolle.ffmpegkmp.bindings.NativeMountedIo
import io.github.aftrolle.ffmpegkmp.bindings.NativeSinkResource
import io.github.aftrolle.ffmpegkmp.bindings.NativeSourceResource
import io.github.aftrolle.ffmpegkmp.bindings.createPlatformExecutionBridge
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@InternalFFmpegKmpApi
public enum class CommandKind { FFMPEG, FFPROBE }

@OptIn(ExperimentalAtomicApi::class)
@InternalFFmpegKmpApi
public class CommandRuntimeClient private constructor(
    private val kind: CommandKind,
    private val bridge: NativeExecutionBridge,
    private val limits: CommandRuntimeLimits,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) : AutoCloseable {
    public constructor(
        kind: CommandKind,
        limits: CommandRuntimeLimits = CommandRuntimeLimits.Default,
    ) : this(kind, createPlatformExecutionBridge(), limits, Unit)

    internal constructor(
        kind: CommandKind,
        bridge: NativeExecutionBridge,
        limits: CommandRuntimeLimits = CommandRuntimeLimits.Default,
    ) : this(kind, bridge, limits, Unit)

    private val clientState = AtomicReference(ClientState())
    private val bridgeClosed = AtomicBoolean(false)

    public fun enqueue(
        arguments: List<String>,
        io: CommandIo = CommandIo.Empty,
    ): ExecutionSession<ExecutionResult> {
        require(arguments.none { '\u0000' in it }) { "Arguments must not contain NUL" }

        val session = CommandExecutionSession(
            id = nextExecutionId(),
            arguments = arguments.toList(),
            io = io,
            kind = kind,
            bridge = bridge,
            limits = limits,
            onTerminal = ::removeSession,
        )
        check(addSession(session)) { "The command client is closed" }
        GlobalExecutionScheduler.submit(session)
        return session
    }

    /**
     * Runs the command and awaits its result. Cancelling the calling coroutine (including via
     * `withTimeout`) also cancels the native run — otherwise an abandoned ffmpeg/ffprobe would
     * keep the single-run bridge busy and block every later command in the process.
     */
    public suspend fun execute(
        arguments: List<String>,
        io: CommandIo = CommandIo.Empty,
    ): ExecutionResult {
        val session = enqueue(arguments, io)
        return try {
            session.await()
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { session.cancelAndJoin() }
            throw cancellation
        }
    }

    override fun close() {
        val sessions = closeClient() ?: return
        sessions.forEach(ExecutionSession<*>::close)
        if (sessions.isEmpty()) closeBridgeOnce()
    }

    private fun addSession(session: CommandExecutionSession): Boolean {
        while (true) {
            val current = clientState.load()
            if (current.closed) return false
            val updated = current.copy(sessions = current.sessions + session)
            if (clientState.compareAndSet(current, updated)) return true
        }
    }

    private fun removeSession(session: CommandExecutionSession) {
        while (true) {
            val current = clientState.load()
            if (session !in current.sessions) return
            val updated = current.copy(sessions = current.sessions - session)
            if (clientState.compareAndSet(current, updated)) {
                if (updated.closed && updated.sessions.isEmpty()) closeBridgeOnce()
                return
            }
        }
    }

    private fun closeClient(): Set<CommandExecutionSession>? {
        while (true) {
            val current = clientState.load()
            if (current.closed) return null
            if (clientState.compareAndSet(current, current.copy(closed = true))) return current.sessions
        }
    }

    private fun closeBridgeOnce() {
        if (bridgeClosed.compareAndSet(expectedValue = false, newValue = true)) bridge.close()
    }
}

private data class ClientState(
    val closed: Boolean = false,
    val sessions: Set<CommandExecutionSession> = emptySet(),
)

private object GlobalExecutionScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<CommandExecutionSession>(GLOBAL_EXECUTION_QUEUE_CAPACITY)

    init {
        scope.launch {
            for (session in queue) session.run()
        }
    }

    fun submit(session: CommandExecutionSession) {
        if (!queue.trySend(session).isSuccess) {
            session.fail(
                NativeExecutionException(
                    "The global FFmpeg execution queue is full " +
                        "($GLOBAL_EXECUTION_QUEUE_CAPACITY waiting commands)",
                ),
            )
        }
    }
}

internal const val GLOBAL_EXECUTION_QUEUE_CAPACITY: Int = 64

@OptIn(ExperimentalAtomicApi::class)
private class CommandExecutionSession(
    override val id: Long,
    override val arguments: List<String>,
    private val io: CommandIo,
    private val kind: CommandKind,
    private val bridge: NativeExecutionBridge,
    private val limits: CommandRuntimeLimits,
    private val onTerminal: (CommandExecutionSession) -> Unit,
) : ExecutionSession<ExecutionResult> {
    private val mutableState = MutableStateFlow(SessionState.QUEUED)
    private val mutableEvents = MutableSharedFlow<ExecutionEvent>()
    private val completion = CompletableDeferred<ExecutionResult>()
    private val retainedLogs = BoundedLogCapture(
        maxEvents = limits.maxRetainedLogEvents,
        maxCharacters = limits.maxRetainedLogCharacters,
    )
    private val capturedOutput = BoundedTextCapture(limits.maxCapturedOutputCharacters)
    private val capturedErrorOutput = BoundedTextCapture(limits.maxCapturedErrorOutputCharacters)
    private var pendingProgress: ExecutionEvent.Progress? = null
    private val progressParser = ProgressParser { progress ->
        latestProgress = progress
        // FFmpeg may combine several status lines in one native callback. Retain and publish the
        // most recent report from that callback instead of growing another intermediate queue.
        pendingProgress = progress
    }

    @kotlin.concurrent.Volatile
    private var latestProgress: ExecutionEvent.Progress? = null
    private val cancelled = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val ioClosed = AtomicBoolean(false)
    private val terminalNotified = AtomicBoolean(false)

    override val state = mutableState.asStateFlow()
    override val events: Flow<ExecutionEvent> = mutableEvents.asSharedFlow()

    override suspend fun await(): ExecutionResult = completion.await()

    override fun cancel() {
        if (completion.isCompleted) return
        cancelled.store(true)
        if (mutableState.value == SessionState.RUNNING) bridge.cancel(id)
    }

    override suspend fun cancelAndJoin() {
        cancel()
        runCatching { completion.await() }
    }

    override fun close() {
        if (!closeRequested.compareAndSet(expectedValue = false, newValue = true)) return
        if (!completion.isCompleted) cancel() else mutableState.value = SessionState.CLOSED
    }

    suspend fun run() {
        val started = TimeSource.Monotonic.markNow()
        try {
            if (cancelled.load()) {
                completeCancelled(kotlin.time.Duration.ZERO)
                return
            }

            mutableState.value = SessionState.RUNNING
            val nativeResult = executeAndCaptureEvents()
            io.mounts.forEach { mount ->
                when (val resource = mount.resource) {
                    is NativeFileResource -> if (resource.access != NativeIoAccess.READ) {
                        resource.fileHandle.flush()
                    }
                    is NativeSinkResource -> resource.sink.flush()
                    is NativeSourceResource -> Unit
                }
            }

            if (cancelled.load()) {
                completeCancelled(started.elapsedNow(), nativeResult.returnCode)
            } else {
                val result = result(nativeResult.returnCode, started.elapsedNow(), false)
                mutableState.value = if (nativeResult.returnCode == 0) SessionState.SUCCEEDED else SessionState.FAILED
                completion.complete(result)
            }
        } catch (cancellation: CancellationException) {
            cancelled.store(true)
            completeCancelled(started.elapsedNow())
        } catch (failure: Throwable) {
            mutableState.value = SessionState.FAILED
            completion.completeExceptionally(NativeExecutionException("Native FFmpeg execution failed", failure))
        } finally {
            closeIoOnce()
            notifyTerminalOnce()
            if (closeRequested.load()) mutableState.value = SessionState.CLOSED
        }
    }

    fun fail(failure: Throwable) {
        mutableState.value = SessionState.FAILED
        closeIoOnce()
        completion.completeExceptionally(failure)
        notifyTerminalOnce()
    }

    private fun completeCancelled(duration: kotlin.time.Duration, returnCode: Int = 255) {
        mutableState.value = SessionState.CANCELLED
        completion.complete(result(returnCode, duration, true))
    }

    private fun result(returnCode: Int, duration: kotlin.time.Duration, cancelled: Boolean) =
        ExecutionResult(
            returnCode,
            capturedOutput.toString(),
            capturedErrorOutput.toString(),
            retainedLogs.toList(),
            duration,
            cancelled,
            latestProgress,
            ExecutionCaptureStatus(
                outputTruncated = capturedOutput.truncated,
                errorOutputTruncated = capturedErrorOutput.truncated,
                logsTruncated = retainedLogs.truncated,
                omittedLogEvents = retainedLogs.omittedEvents,
                omittedLogCharacters = retainedLogs.omittedCharacters,
            ),
        )

    private suspend fun executeAndCaptureEvents(): io.github.aftrolle.ffmpegkmp.bindings.NativeExecutionResult = coroutineScope {
        val nativeEvents = Channel<NativeExecutionEvent>(limits.maxPendingNativeEvents)
        val acceptingEvents = AtomicBoolean(true)
        val overflow = AtomicReference<NativeExecutionException?>(null)
        val collector = launch {
            for (event in nativeEvents) acceptNativeEvent(event)
        }
        val nativeResult = try {
            bridge.execute(
                NativeExecutionRequest(
                    id = id,
                    kind = if (kind == CommandKind.FFMPEG) NativeCommandKind.FFMPEG else NativeCommandKind.FFPROBE,
                    arguments = arguments,
                    mounts = io.mounts.map { NativeMountedIo(it.path, it.resource) },
                ),
            ) { event ->
                if (acceptingEvents.load() && !nativeEvents.trySend(event).isSuccess) {
                    overflow.compareAndSet(
                        expectedValue = null,
                        newValue = NativeExecutionException(
                            "Native event buffer exceeded ${limits.maxPendingNativeEvents} events; " +
                                "increase CommandRuntimeLimits.maxPendingNativeEvents or consume " +
                                "ExecutionSession.events faster",
                        ),
                    )
                }
            }
        } finally {
            acceptingEvents.store(false)
            nativeEvents.close()
            collector.join()
        }
        overflow.load()?.let { throw it }
        nativeResult
    }

    private suspend fun acceptNativeEvent(event: NativeExecutionEvent) {
        val publicEvent = when (event) {
            is NativeExecutionEvent.Log -> ExecutionEvent.Log(event.level.toLogLevel(), event.message)
            is NativeExecutionEvent.Output -> ExecutionEvent.Output(
                if (event.stream == NativeExecutionEvent.Stream.STDOUT) OutputStream.STDOUT else OutputStream.STDERR,
                event.text,
            )
        }
        when (publicEvent) {
            is ExecutionEvent.Log -> {
                retainedLogs.add(publicEvent)
                // FFmpeg's default av_log callback writes diagnostics to stderr.
                // Preserve that CLI behavior while also retaining structured logs.
                capturedErrorOutput.append(publicEvent.message)
                if (kind == CommandKind.FFMPEG) progressParser.accept(publicEvent.message)
            }
            is ExecutionEvent.Output -> when (publicEvent.stream) {
                OutputStream.STDOUT -> capturedOutput.append(publicEvent.text)
                OutputStream.STDERR -> {
                    capturedErrorOutput.append(publicEvent.text)
                    if (kind == CommandKind.FFMPEG) progressParser.accept(publicEvent.text)
                }
            }
            is ExecutionEvent.Progress -> Unit
        }
        mutableEvents.emit(publicEvent)
        pendingProgress?.let { progress ->
            pendingProgress = null
            mutableEvents.emit(progress)
        }
    }

    private fun closeIoOnce() {
        if (!ioClosed.compareAndSet(expectedValue = false, newValue = true)) return
        io.mounts.forEach { mount ->
            runCatching {
                when (val resource = mount.resource) {
                    is NativeFileResource -> resource.fileHandle.close()
                    is NativeSourceResource -> resource.source.close()
                    is NativeSinkResource -> resource.sink.close()
                }
            }
        }
    }

    private fun notifyTerminalOnce() {
        if (terminalNotified.compareAndSet(expectedValue = false, newValue = true)) onTerminal(this)
    }
}

private class BoundedTextCapture(private val maxCharacters: Int) {
    private val value = StringBuilder(minOf(maxCharacters, 8_192))
    var truncated: Boolean = false
        private set

    fun append(text: String) {
        val remaining = maxCharacters - value.length
        if (remaining > 0) value.append(text.take(remaining))
        if (text.length > remaining.coerceAtLeast(0)) truncated = true
    }

    override fun toString(): String = value.toString()
}

private class BoundedLogCapture(
    private val maxEvents: Int,
    private val maxCharacters: Int,
) {
    private val values = mutableListOf<ExecutionEvent.Log>()
    private var retainedCharacters = 0
    var truncated: Boolean = false
        private set
    var omittedEvents: Long = 0
        private set
    var omittedCharacters: Long = 0
        private set

    fun add(log: ExecutionEvent.Log) {
        val remainingCharacters = maxCharacters - retainedCharacters
        if (values.size >= maxEvents || remainingCharacters <= 0) {
            truncated = true
            omittedEvents++
            omittedCharacters += log.message.length.toLong()
            return
        }

        val retainedMessage = log.message.take(remainingCharacters)
        values += if (retainedMessage.length == log.message.length) log else log.copy(message = retainedMessage)
        retainedCharacters += retainedMessage.length
        if (retainedMessage.length != log.message.length) {
            truncated = true
            omittedCharacters += (log.message.length - retainedMessage.length).toLong()
        }
    }

    fun toList(): List<ExecutionEvent.Log> = values.toList()
}

private fun Int.toLogLevel(): LogLevel = when {
    this <= -8 -> LogLevel.QUIET
    this <= 0 -> LogLevel.PANIC
    this <= 8 -> LogLevel.FATAL
    this <= 16 -> LogLevel.ERROR
    this <= 24 -> LogLevel.WARNING
    this <= 32 -> LogLevel.INFO
    this <= 40 -> LogLevel.VERBOSE
    this <= 48 -> LogLevel.DEBUG
    this <= 56 -> LogLevel.TRACE
    else -> LogLevel.UNKNOWN
}

private fun nextExecutionId(): Long = Random.nextLong(1, Long.MAX_VALUE)
