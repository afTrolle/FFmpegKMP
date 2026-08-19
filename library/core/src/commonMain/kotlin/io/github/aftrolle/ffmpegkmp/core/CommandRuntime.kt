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
import io.github.aftrolle.ffmpegkmp.bindings.NativeMountedInput
import io.github.aftrolle.ffmpegkmp.bindings.NativeMountedOutput
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
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) : AutoCloseable {
    public constructor(kind: CommandKind) : this(kind, createPlatformExecutionBridge(), Unit)

    internal constructor(kind: CommandKind, bridge: NativeExecutionBridge) : this(kind, bridge, Unit)

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
    private val queue = Channel<CommandExecutionSession>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (session in queue) session.run()
        }
    }

    fun submit(session: CommandExecutionSession) {
        if (!queue.trySend(session).isSuccess) {
            session.fail(NativeExecutionException("The global FFmpeg execution queue is unavailable"))
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class CommandExecutionSession(
    override val id: Long,
    override val arguments: List<String>,
    private val io: CommandIo,
    private val kind: CommandKind,
    private val bridge: NativeExecutionBridge,
    private val onTerminal: (CommandExecutionSession) -> Unit,
) : ExecutionSession<ExecutionResult> {
    private val mutableState = MutableStateFlow(SessionState.QUEUED)
    private val mutableEvents = MutableSharedFlow<ExecutionEvent>(extraBufferCapacity = 64)
    private val completion = CompletableDeferred<ExecutionResult>()
    private val retainedLogs = mutableListOf<ExecutionEvent.Log>()
    private val capturedOutput = StringBuilder()
    private val capturedErrorOutput = StringBuilder()
    private val progressParser = ProgressParser { progress ->
        latestProgress = progress
        mutableEvents.tryEmit(progress)
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
            io.outputs.forEach { output -> output.sink.flush() }

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
        )

    private suspend fun executeAndCaptureEvents(): io.github.aftrolle.ffmpegkmp.bindings.NativeExecutionResult = coroutineScope {
        val nativeEvents = Channel<NativeExecutionEvent>(Channel.UNLIMITED)
        val collector = launch {
            for (event in nativeEvents) acceptNativeEvent(event)
        }
        try {
            bridge.execute(
                NativeExecutionRequest(
                    id = id,
                    kind = if (kind == CommandKind.FFMPEG) NativeCommandKind.FFMPEG else NativeCommandKind.FFPROBE,
                    arguments = arguments,
                    inputs = io.inputs.map { NativeMountedInput(it.path, it.source) },
                    outputs = io.outputs.map { NativeMountedOutput(it.path, it.sink) },
                ),
            ) { event -> nativeEvents.trySend(event) }
        } finally {
            nativeEvents.close()
            collector.join()
        }
    }

    private fun acceptNativeEvent(event: NativeExecutionEvent) {
        val publicEvent = when (event) {
            is NativeExecutionEvent.Log -> ExecutionEvent.Log(event.level.toLogLevel(), event.message)
            is NativeExecutionEvent.Output -> ExecutionEvent.Output(
                if (event.stream == NativeExecutionEvent.Stream.STDOUT) OutputStream.STDOUT else OutputStream.STDERR,
                event.text,
            )
        }
        when (publicEvent) {
            is ExecutionEvent.Log -> {
                retainedLogs += publicEvent
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
        mutableEvents.tryEmit(publicEvent)
    }

    private fun closeIoOnce() {
        if (!ioClosed.compareAndSet(expectedValue = false, newValue = true)) return
        io.inputs.forEach { runCatching { it.source.close() } }
        io.outputs.forEach { runCatching { it.sink.close() } }
    }

    private fun notifyTerminalOnce() {
        if (terminalNotified.compareAndSet(expectedValue = false, newValue = true)) onTerminal(this)
    }
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
