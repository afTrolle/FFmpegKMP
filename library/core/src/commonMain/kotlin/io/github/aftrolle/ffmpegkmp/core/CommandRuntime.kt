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
import io.github.aftrolle.ffmpegkmp.bindings.NativeExecutionResult
import io.github.aftrolle.ffmpegkmp.bindings.NativeFileResource
import io.github.aftrolle.ffmpegkmp.bindings.NativeIoAccess
import io.github.aftrolle.ffmpegkmp.bindings.NativeMountedIo
import io.github.aftrolle.ffmpegkmp.bindings.NativeSinkResource
import io.github.aftrolle.ffmpegkmp.bindings.NativeSourceResource
import io.github.aftrolle.ffmpegkmp.bindings.createPlatformExecutionBridge
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Sink
import okio.buffer

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

    /** Owns the sessions started with [enqueue]; [execute] runs its session in the caller's scope. */
    private val enqueuedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Starts the command in the background and returns a handle to it. The handle's `cancel`
     * stops the native run; the session reports itself cancelled once the run has unwound.
     */
    public fun enqueue(
        arguments: List<String>,
        io: CommandIo = CommandIo.Empty,
    ): ExecutionSession<ExecutionResult> = start(enqueuedScope, arguments, io)

    /**
     * Runs the command and awaits its result. The session is a child of the calling coroutine, so
     * cancelling the caller (including via `withTimeout`) cancels the native run, and this
     * returns only after that run has unwound — otherwise an abandoned ffmpeg/ffprobe would keep
     * the single-run bridge busy and block every later command in the process.
     */
    public suspend fun execute(
        arguments: List<String>,
        io: CommandIo = CommandIo.Empty,
    ): ExecutionResult {
        currentCoroutineContext().ensureActive()
        return coroutineScope {
            start(this, arguments, io).await()
        }
    }

    private fun start(scope: CoroutineScope, arguments: List<String>, io: CommandIo): CommandExecutionSession {
        require(arguments.none { '\u0000' in it }) { "Arguments must not contain NUL" }

        val session = CommandExecutionSession(
            scope = scope,
            id = nextExecutionId(),
            arguments = arguments.toList(),
            io = io,
            kind = kind,
            bridge = bridge,
            limits = limits,
            onTerminal = ::removeSession,
        )
        if (!addSession(session)) {
            session.abandon()
            throw IllegalStateException("The command client is closed")
        }
        session.start()
        return session
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

/**
 * Hands the process-wide, single-run native runtime to sessions in the order they were started.
 *
 * A session takes a ticket when it starts and waits for the ticket before it on its own
 * coroutine, so leaving the line is just cancelling that coroutine. A ticket is released when its
 * session ends, whether or not the session ever ran, and only passes the turn on once the ticket
 * before it has finished — a session that left the line must not let the one behind it run
 * beside the one still in front.
 */
@OptIn(ExperimentalAtomicApi::class)
private object GlobalExecutionQueue {
    private val tail = AtomicReference<Job>(Job().apply { complete() })
    private val waiting = AtomicInt(0)

    /** Null when [GLOBAL_EXECUTION_QUEUE_CAPACITY] sessions are already waiting for their turn. */
    fun take(): Ticket? {
        if (waiting.incrementAndFetch() > GLOBAL_EXECUTION_QUEUE_CAPACITY) {
            waiting.decrementAndFetch()
            return null
        }
        val turn = Job()
        return Ticket(previous = tail.exchange(turn), turn = turn)
    }

    class Ticket(private val previous: Job, private val turn: CompletableJob) {
        private val waitingInLine = AtomicBoolean(true)
        private val released = AtomicBoolean(false)

        suspend fun awaitTurn() {
            previous.join()
            leaveLine()
        }

        fun release() {
            if (!released.compareAndSet(expectedValue = false, newValue = true)) return
            leaveLine()
            previous.invokeOnCompletion { turn.complete() }
        }

        private fun leaveLine() {
            if (waitingInLine.compareAndSet(expectedValue = true, newValue = false)) waiting.decrementAndFetch()
        }
    }
}

internal const val GLOBAL_EXECUTION_QUEUE_CAPACITY: Int = 64

private sealed interface Outcome {
    class Finished(val returnCode: Int) : Outcome
    object Cancelled : Outcome
    class Failed(val failure: Throwable) : Outcome
}

/**
 * One command from start to result. The session is a coroutine [Job]: cancelling it is the only
 * cancellation path, and it reaches the native run through the bridge's own suspension, so the
 * job completes only after FFmpeg has unwound and every mounted resource has been released.
 */
@OptIn(ExperimentalAtomicApi::class)
private class CommandExecutionSession(
    scope: CoroutineScope,
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
    private val closeRequested = AtomicBoolean(false)
    /** Set by whichever of [finish] and [fail] runs first; the session settles exactly once. */
    private val settled = AtomicBoolean(false)

    @kotlin.concurrent.Volatile
    private var ticket: GlobalExecutionQueue.Ticket? = null

    // Native code and file copies run off the caller's dispatcher even when the caller is on Main.
    private val job: Job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) { run() }

    override val state = mutableState.asStateFlow()
    override val events: Flow<ExecutionEvent> = mutableEvents.asSharedFlow()

    override suspend fun await(): ExecutionResult = completion.await()

    override fun cancel() {
        job.cancel()
    }

    override suspend fun cancelAndJoin() {
        job.cancelAndJoin()
    }

    override fun close() {
        if (!closeRequested.compareAndSet(expectedValue = false, newValue = true)) return
        if (job.isCompleted) mutableState.value = SessionState.CLOSED else job.cancel()
    }

    /**
     * Takes a place in the global line and starts the job; the session fails if the line is full.
     * Called once the client has registered the session, so everything the completion handler
     * releases (the ticket, the I/O, the registration) exists by the time it can run — including
     * when the job was already cancelled with its scope and the handler runs right here.
     */
    fun start() {
        val taken = GlobalExecutionQueue.take()
        if (taken == null) {
            fail(
                NativeExecutionException(
                    "The global FFmpeg execution queue is full " +
                        "($GLOBAL_EXECUTION_QUEUE_CAPACITY waiting commands)",
                ),
            )
            return
        }
        ticket = taken
        job.invokeOnCompletion { cause ->
            // A session cancelled before it ran, or one whose run ended outside [run], still owes
            // its caller a result and its resources a release.
            finish(if (cause == null || cause is CancellationException) Outcome.Cancelled else Outcome.Failed(cause), Duration.ZERO)
            if (closeRequested.load()) mutableState.value = SessionState.CLOSED
        }
        job.start()
    }

    /** For a session its client refused: nothing was handed to it yet, so the job just goes away. */
    fun abandon() {
        job.cancel()
    }

    private fun fail(failure: Throwable) {
        if (!settled.compareAndSet(expectedValue = false, newValue = true)) return
        mutableState.value = SessionState.FAILED
        closeIo()
        onTerminal(this)
        completion.completeExceptionally(failure)
        job.cancel()
    }

    private suspend fun run() {
        val started = TimeSource.Monotonic.markNow()
        val outcome = try {
            checkNotNull(ticket).awaitTurn()
            mutableState.value = SessionState.RUNNING
            prepareStaging().use { staged ->
                val nativeResult = executeAndCaptureEvents(staged.mounts)
                if (nativeResult.returnCode == 0) {
                    // Verify every staged mount before copying any of them: a command with two
                    // staged outputs where only one was actually written must not leave the other
                    // sink populated while the overall result reports failure.
                    staged.contexts.forEach(::verifyStagedOutputWasWritten)
                    staged.contexts.forEach(::copyStagedOutput)
                }
                flushMounts()
                Outcome.Finished(nativeResult.returnCode)
            }
        } catch (cancellation: CancellationException) {
            Outcome.Cancelled
        } catch (failure: Throwable) {
            // A failure while a cancelled run unwinds (or an event overflow after the collector
            // stopped) must not turn the cancellation the caller asked for into a failure.
            if (currentCoroutineContext().isActive) Outcome.Failed(failure) else Outcome.Cancelled
        }
        finish(outcome, started.elapsedNow())
    }

    /**
     * Completing tells callers the session is over, so it happens only after the turn, the
     * caller's I/O and the client registration have been released.
     */
    private fun finish(outcome: Outcome, duration: Duration) {
        if (!settled.compareAndSet(expectedValue = false, newValue = true)) return
        ticket?.release()
        closeIo()
        onTerminal(this)
        when (outcome) {
            is Outcome.Finished -> {
                mutableState.value =
                    if (outcome.returnCode == 0) SessionState.SUCCEEDED else SessionState.FAILED
                completion.complete(result(outcome.returnCode, duration, cancelled = false))
            }
            Outcome.Cancelled -> {
                mutableState.value = SessionState.CANCELLED
                completion.complete(result(CANCELLED_RETURN_CODE, duration, cancelled = true))
            }
            is Outcome.Failed -> {
                mutableState.value = SessionState.FAILED
                completion.completeExceptionally(
                    NativeExecutionException("Native FFmpeg execution failed", outcome.failure),
                )
            }
        }
    }

    /** Substitutes a real temporary [NativeFileResource] for each mount that requested [Staging]. */
    private fun prepareStaging(): StagedMounts {
        val contexts = mutableListOf<StagingContext>()
        try {
            val mounts = io.mounts.mapIndexed { index, mount ->
                val resource = mount.resource
                if (mount.staging && resource is NativeSinkResource) {
                    val temporaryFile = TemporaryFile("ffmpegkmp-$id-$index")
                    contexts += StagingContext(mount.path, resource.sink, temporaryFile)
                    NativeMountedIo(
                        mount.path,
                        NativeFileResource(temporaryFile.fileHandle, NativeIoAccess.WRITE, truncate = false),
                    )
                } else {
                    NativeMountedIo(mount.path, resource)
                }
            }
            return StagedMounts(mounts, contexts)
        } catch (failure: Throwable) {
            // A later mount's TemporaryFile failed to open; close what earlier mounts already
            // opened instead of leaking their file handles and backing files.
            contexts.forEach { context -> runCatching { context.temporaryFile.close() } }
            throw failure
        }
    }

    /** A command that reports success must not silently hand the caller an empty sink. */
    private fun verifyStagedOutputWasWritten(context: StagingContext) {
        if (context.temporaryFile.fileHandle.size() <= 0L) {
            throw StagedOutputEmptyException(
                "FFmpeg reported success but wrote no output for staged mount '${context.path}'",
            )
        }
    }

    private fun copyStagedOutput(context: StagingContext) {
        val source = context.temporaryFile.fileHandle.source(0L).buffer()
        try {
            source.readAll(context.sink)
        } finally {
            source.close()
        }
    }

    private fun flushMounts() {
        io.mounts.forEach { mount ->
            when (val resource = mount.resource) {
                is NativeFileResource -> if (resource.access != NativeIoAccess.READ) {
                    resource.fileHandle.flush()
                }
                is NativeSinkResource -> resource.sink.flush()
                is NativeSourceResource -> Unit
            }
        }
    }

    private fun result(returnCode: Int, duration: Duration, cancelled: Boolean) =
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

    private suspend fun executeAndCaptureEvents(mounts: List<NativeMountedIo>): NativeExecutionResult = coroutineScope {
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
                    mounts = mounts,
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
            // A cancelled session's collector stops at its next suspension, but FFmpeg keeps
            // reporting while it unwinds (final stats, the exit message). Those events are part
            // of the cancelled result, so drain what the collector left behind.
            withContext(NonCancellable) {
                collector.join()
                for (event in nativeEvents) acceptNativeEvent(event)
            }
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

    private fun closeIo() {
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

}

/** FFmpeg's own exit status after a signal, reported for a session cancelled before it finished. */
private const val CANCELLED_RETURN_CODE = 255

/** The mounts handed to the bridge plus the temporary files behind the staged ones; closing deletes them. */
private class StagedMounts(val mounts: List<NativeMountedIo>, val contexts: List<StagingContext>) : AutoCloseable {
    override fun close() {
        contexts.forEach { context -> runCatching { context.temporaryFile.close() } }
    }
}

private class StagingContext(val path: String, val sink: Sink, val temporaryFile: TemporaryFile)

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
