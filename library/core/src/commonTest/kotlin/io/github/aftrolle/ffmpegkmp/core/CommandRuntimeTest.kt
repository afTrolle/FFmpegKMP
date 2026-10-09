// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.core

import io.github.aftrolle.ffmpegkmp.bindings.NativeExecutionBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativeExecutionEvent
import io.github.aftrolle.ffmpegkmp.bindings.NativeExecutionRequest
import io.github.aftrolle.ffmpegkmp.bindings.NativeExecutionResult
import io.github.aftrolle.ffmpegkmp.bindings.NativeSinkResource
import io.github.aftrolle.ffmpegkmp.bindings.NativeSourceResource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer

class CommandRuntimeTest {
    @Test
    fun globallyQueuesDifferentClients() = runTest {
        val order = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = FakeBridge { request, _ ->
            order += "start-${request.arguments.single()}"
            firstStarted.complete(Unit)
            releaseFirst.await()
            order += "end-${request.arguments.single()}"
            NativeExecutionResult(0)
        }
        val second = FakeBridge { request, _ ->
            order += "start-${request.arguments.single()}"
            NativeExecutionResult(0)
        }
        val scheduler = singleLaneScheduler()
        val firstClient = CommandRuntimeClient(CommandKind.FFMPEG, first, scheduler = scheduler)
        val secondClient = CommandRuntimeClient(CommandKind.FFPROBE, second, scheduler = scheduler)

        val sessionOne = firstClient.enqueue(listOf("one"))
        val sessionTwo = secondClient.enqueue(listOf("two"))
        firstStarted.await()
        assertEquals(listOf("start-one"), order)

        releaseFirst.complete(Unit)
        sessionOne.await()
        sessionTwo.await()
        assertEquals(listOf("start-one", "end-one", "start-two"), order)

        firstClient.close()
        secondClient.close()
    }

    @Test
    fun transfersAndClosesIoExactlyOnce() = runTest {
        val input = TrackingSource("hello".encodeToByteArray())
        val output = TrackingSink()
        val bridge = FakeBridge { request, emit ->
            val mounted = request.mounts.single { it.path == "input.bin" }.resource as NativeSourceResource
            assertContentEquals("hello".encodeToByteArray(), mounted.source.buffer().readByteArray())
            emit(NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDOUT, "done"))
            val sink = (request.mounts.single { it.path == "result.bin" }.resource as NativeSinkResource).sink
            val payload = Buffer().also { it.write("world".encodeToByteArray()) }
            sink.write(payload, payload.size)
            NativeExecutionResult(0)
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge)
        val result = client.execute(
            listOf("-version"),
            CommandIo {
                input("input.bin", input)
                output("result.bin", output)
            },
        )

        assertEquals("done", result.output)
        assertContentEquals("world".encodeToByteArray(), output.data.readByteArray())
        assertEquals(1, input.closeCount)
        assertEquals(1, output.closeCount)
        client.close()
    }

    @Test
    fun cancellationBeforeExecutionSkipsBridge() = runTest {
        val blocker = CompletableDeferred<Unit>()
        val firstBridge = FakeBridge { _, _ -> blocker.await(); NativeExecutionResult(0) }
        var secondExecuted = false
        val secondBridge = FakeBridge { _, _ -> secondExecuted = true; NativeExecutionResult(0) }
        val scheduler = singleLaneScheduler()
        val firstClient = CommandRuntimeClient(CommandKind.FFMPEG, firstBridge, scheduler = scheduler)
        val secondClient = CommandRuntimeClient(CommandKind.FFMPEG, secondBridge, scheduler = scheduler)

        val first = firstClient.enqueue(listOf("first"))
        first.state.first { it == SessionState.RUNNING }
        val input = TrackingSource("queued".encodeToByteArray())
        val output = TrackingSink()
        val second = secondClient.enqueue(listOf("second"))
        val secondWithIo = secondClient.enqueue(
            listOf("third"),
            CommandIo {
                input("input.bin", input)
                output("output.bin", output)
            },
        )
        second.cancel()
        secondWithIo.cancel()
        blocker.complete(Unit)

        first.await()
        val cancelled = second.await()
        val cancelledWithIo = secondWithIo.await()
        assertTrue(cancelled.cancelled)
        assertTrue(cancelledWithIo.cancelled)
        assertEquals(SessionState.CANCELLED, second.state.value)
        assertEquals(false, secondExecuted)
        assertEquals(1, input.closeCount)
        assertEquals(1, output.closeCount)
        firstClient.close()
        secondClient.close()
        assertEquals(1, secondBridge.closeCount)
    }

    @Test
    fun serializesEventsEmittedFromConcurrentCallbacks() = runTest {
        val bridge = FakeBridge { _, emit ->
            coroutineScope {
                repeat(100) {
                    launch(Dispatchers.Default) {
                        emit(NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDOUT, "x"))
                        emit(NativeExecutionEvent.Log(32, "log"))
                    }
                }
            }
            NativeExecutionResult(0)
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge)

        val result = client.execute(listOf("concurrent-events"))

        assertEquals(100, result.output.length)
        assertEquals(100, result.logs.size)
        client.close()
    }

    @Test
    fun routesNativeLogsAndStderrToErrorOutput() = runTest {
        val bridge = FakeBridge { _, emit ->
            emit(NativeExecutionEvent.Log(16, "Unknown option '-bad'.\n"))
            emit(NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDERR, "Conversion failed.\n"))
            NativeExecutionResult(1)
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge)

        val result = client.execute(listOf("-bad"))

        assertContains(result.errorOutput, "Unknown option '-bad'.")
        assertContains(result.errorOutput, "Conversion failed.")
        assertEquals(LogLevel.ERROR, result.logs.single().level)
        client.close()
    }

    @Test
    fun boundsRetainedResultDataAndReportsTruncation() = runTest {
        val bridge = FakeBridge { _, emit ->
            emit(NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDOUT, "output"))
            emit(NativeExecutionEvent.Log(32, "first-log"))
            emit(NativeExecutionEvent.Log(32, "second-log"))
            emit(NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDERR, "stderr"))
            NativeExecutionResult(0)
        }
        val limits = CommandRuntimeLimits(
            maxCapturedOutputCharacters = 4,
            maxCapturedErrorOutputCharacters = 5,
            maxRetainedLogEvents = 1,
            maxRetainedLogCharacters = 3,
        )
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge, limits)

        val result = client.execute(listOf("bounded-capture"))

        assertEquals("outp", result.output)
        assertEquals("first", result.errorOutput)
        assertEquals("fir", result.logs.single().message)
        assertTrue(result.captureStatus.outputTruncated)
        assertTrue(result.captureStatus.errorOutputTruncated)
        assertTrue(result.captureStatus.logsTruncated)
        assertEquals(1, result.captureStatus.omittedLogEvents)
        assertEquals(16, result.captureStatus.omittedLogCharacters)
        client.close()
    }

    @Test
    fun cancellingARunningSessionCancelsTheBridgeAndReportsOnlyAfterItUnwound() = runTest {
        val started = CompletableDeferred<Unit>()
        val unwound = CompletableDeferred<Unit>()
        val bridge = FakeBridge { request, _ ->
            if (request.arguments.single() != "long") return@FakeBridge NativeExecutionResult(0)
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                // FFmpeg writing its trailer after the transcode loop broke.
                withContext(NonCancellable) { delay(20) }
                unwound.complete(Unit)
            }
        }
        val input = TrackingSource("running".encodeToByteArray())
        val output = TrackingSink()
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge)
        val session = client.enqueue(
            listOf("long"),
            CommandIo {
                input("input.bin", input)
                output("output.bin", output)
            },
        )
        started.await()

        session.cancelAndJoin()
        val result = session.await()

        assertTrue(unwound.isCompleted, "The session completed before the bridge had unwound")
        assertTrue(result.cancelled)
        assertEquals(255, result.returnCode)
        assertEquals(SessionState.CANCELLED, session.state.value)
        assertEquals(1, input.closeCount)
        assertEquals(1, output.closeCount)

        val next = client.execute(listOf("next"))
        assertTrue(next.isSuccess)
        client.close()
    }

    @Test
    fun cancellingTheCallerOfExecuteCancelsTheBridgeAndFreesTheRuntime() = runTest {
        val started = CompletableDeferred<Unit>()
        var sawCancellation = false
        val bridge = FakeBridge { request, _ ->
            if (request.arguments.single() == "long") {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } catch (cancellation: CancellationException) {
                    sawCancellation = true
                    throw cancellation
                }
            }
            NativeExecutionResult(0)
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge)
        var rethrown = false
        val caller = launch {
            try {
                client.execute(listOf("long"))
            } catch (cancellation: CancellationException) {
                rethrown = true
                throw cancellation
            }
        }
        started.await()

        caller.cancelAndJoin()

        assertTrue(sawCancellation)
        assertTrue(rethrown, "execute() must rethrow its caller's cancellation")
        val next = client.execute(listOf("next"))
        assertTrue(next.isSuccess)
        client.close()
        assertEquals(1, bridge.closeCount)
    }

    @Test
    fun executeFromAnAlreadyCancelledCallerThrowsAndLeavesTheQueueUsable() = runTest {
        var executed = 0
        val bridge = FakeBridge { _, _ -> executed++; NativeExecutionResult(0) }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge)

        launch {
            cancel()
            assertFailsWith<CancellationException> { client.execute(listOf("too-late")) }
        }.join()

        // The refused session must not hold a turn or stay registered with the client.
        assertTrue(client.execute(listOf("next")).isSuccess)
        assertEquals(1, executed)
        client.close()
        assertEquals(1, bridge.closeCount)
    }

    @Test
    fun aCancelledResultKeepsTheEventsFFmpegEmittedWhileUnwinding() = runTest {
        val started = CompletableDeferred<Unit>()
        val bridge = FakeBridge { _, emit ->
            try {
                emit(NativeExecutionEvent.Log(32, "frame=1\n"))
                started.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(10)
                    emit(NativeExecutionEvent.Log(32, "Exiting normally, received signal 15.\n"))
                }
            }
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge)
        val session = client.enqueue(listOf("long"))
        started.await()

        session.cancelAndJoin()
        val result = session.await()

        assertTrue(result.cancelled)
        assertEquals(listOf("frame=1\n", "Exiting normally, received signal 15.\n"), result.logs.map { it.message })
        assertContains(result.errorOutput, "received signal 15")
        client.close()
    }

    @Test
    fun eventsCompleteWhenTheSessionEnds() = runTest {
        val subscribed = CompletableDeferred<Unit>()
        val bridge = FakeBridge { _, emit ->
            emitUntil(subscribed, emit)
            emit(NativeExecutionEvent.Log(32, "last\n"))
            NativeExecutionResult(0)
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge)
        val session = client.enqueue(listOf("events"))

        val events = async(Dispatchers.Default) {
            session.events.onEach { subscribed.complete(Unit) }.toList()
        }

        session.await()
        assertEquals("last\n", assertIs<ExecutionEvent.Log>(events.await().last()).message)
        client.close()
    }

    @Test
    fun aCollectorThatSubscribesAfterTheSessionEndedCompletes() = runTest {
        val bridge = FakeBridge { _, emit ->
            emit(NativeExecutionEvent.Log(16, "Unknown input format: 'lavfi'\n"))
            NativeExecutionResult(-22)
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge)
        val session = client.enqueue(listOf("fails-at-once"))

        assertEquals(-22, session.await().returnCode)
        assertEquals(emptyList(), session.events.toList())
        client.close()
    }

    @Test
    fun aSlowCollectorLosesItsOldestEventsInsteadOfFailingTheRun() = runTest {
        val subscribed = CompletableDeferred<Unit>()
        val bridge = FakeBridge { _, emit ->
            emitUntil(subscribed, emit)
            repeat(200) {
                emit(NativeExecutionEvent.Log(32, "line $it\n"))
                delay(1)
            }
            NativeExecutionResult(0)
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge, CommandRuntimeLimits(maxPendingNativeEvents = 16))
        val session = client.enqueue(listOf("slow-collector"))

        val events = async(Dispatchers.Default) {
            session.events.onEach {
                subscribed.complete(Unit)
                delay(20)
            }.toList()
        }

        val result = session.await()
        val lines = events.await().map { assertIs<ExecutionEvent.Log>(it).message }.filter { it.startsWith("line") }
        assertTrue(result.isSuccess)
        assertEquals(200, result.logs.count { it.message.startsWith("line") })
        assertTrue(lines.size < 200, "a collector this slow cannot have kept every line")
        assertEquals("line 199\n", lines.last())
        client.close()
    }

    @Test
    fun closingTheClientCancelsTheRunningSessionDropsTheQueuedOneAndThenClosesTheBridge() = runTest {
        val started = CompletableDeferred<Unit>()
        var queuedExecuted = false
        val bridge = FakeBridge { request, _ ->
            when (request.arguments.single()) {
                "running" -> {
                    started.complete(Unit)
                    awaitCancellation()
                }
                else -> {
                    queuedExecuted = true
                    NativeExecutionResult(0)
                }
            }
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge, scheduler = singleLaneScheduler())
        val running = client.enqueue(listOf("running"))
        started.await()
        val queued = client.enqueue(listOf("queued"))

        client.close()

        assertTrue(running.await().cancelled)
        assertTrue(queued.await().cancelled)
        assertFalse(queuedExecuted)
        running.state.first { it == SessionState.CLOSED }
        queued.state.first { it == SessionState.CLOSED }
        assertEquals(1, bridge.closeCount)
    }

    @Test
    fun aSessionCancelledWhileWaitingKeepsTheQueueOrderForThoseBehindIt() = runTest {
        val order = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val bridge = FakeBridge { request, _ ->
            order += "start-${request.arguments.single()}"
            if (request.arguments.single() == "first") {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            order += "end-${request.arguments.single()}"
            NativeExecutionResult(0)
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge, scheduler = singleLaneScheduler())
        val first = client.enqueue(listOf("first"))
        firstStarted.await()
        val second = client.enqueue(listOf("second"))
        val third = client.enqueue(listOf("third"))

        second.cancelAndJoin()
        assertTrue(second.await().cancelled)
        // The cancelled session must not have handed its turn on while the first still runs.
        assertEquals(listOf("start-first"), order)

        releaseFirst.complete(Unit)
        first.await()
        third.await()
        assertEquals(listOf("start-first", "end-first", "start-third", "end-third"), order)
        client.close()
    }

    @Test
    fun closingTheClientCancelsItsRunningSessionAndThenClosesTheBridge() = runTest {
        val started = CompletableDeferred<Unit>()
        val bridge = FakeBridge { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge)
        val session = client.enqueue(listOf("long"))
        started.await()

        client.close()
        val result = session.await()

        assertTrue(result.cancelled)
        assertEquals(SessionState.CLOSED, session.state.first { it == SessionState.CLOSED })
        assertEquals(1, bridge.closeCount)
    }

    @Test
    fun rejectsCommandsBeyondTheGlobalQueueLimit() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val bridge = FakeBridge { request, _ ->
            if (request.arguments.single() == "blocker") {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            NativeExecutionResult(0)
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge, scheduler = singleLaneScheduler())
        val blocker = client.enqueue(listOf("blocker"))
        firstStarted.await()
        val accepted = List(GLOBAL_EXECUTION_QUEUE_CAPACITY) { index ->
            client.enqueue(listOf("queued-$index"))
        }
        val rejected = client.enqueue(listOf("overflow"))

        val failure = runCatching { rejected.await() }.exceptionOrNull()
        assertIs<NativeExecutionException>(failure)
        assertContains(failure.message.orEmpty(), "queue is full")

        releaseFirst.complete(Unit)
        blocker.await()
        accepted.forEach { it.await() }
        client.close()
    }

    @Test
    fun executeWaitsForRoomInAFullQueue() = runTest {
        val started = mutableListOf<String>()
        val blockerStarted = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val bridge = FakeBridge { request, _ ->
            started += request.arguments.single()
            if (request.arguments.single() == "blocker") {
                blockerStarted.complete(Unit)
                releaseBlocker.await()
            }
            NativeExecutionResult(0)
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge, scheduler = singleLaneScheduler(capacity = 2))
        val blocker = client.enqueue(listOf("blocker"))
        blockerStarted.await()
        val queued = List(2) { index -> client.enqueue(listOf("queued-$index")) }

        val waiting = async(start = CoroutineStart.UNDISPATCHED) { client.execute(listOf("waiting")) }
        // The queue is still full, so enqueue fails fast while execute keeps waiting.
        val rejected = runCatching { client.enqueue(listOf("overflow")).await() }.exceptionOrNull()
        assertIs<NativeExecutionException>(rejected)
        assertFalse(waiting.isCompleted)

        releaseBlocker.complete(Unit)
        blocker.await()
        queued.forEach { it.await() }
        assertTrue(waiting.await().isSuccess)
        assertEquals(listOf("blocker", "queued-0", "queued-1", "waiting"), started)
        client.close()
    }

    @Test
    fun cancellingAnExecuteThatWaitsForRoomQueuesNothing() = runTest {
        val started = mutableListOf<String>()
        val blockerStarted = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val bridge = FakeBridge { request, _ ->
            started += request.arguments.single()
            if (request.arguments.single() == "blocker") {
                blockerStarted.complete(Unit)
                releaseBlocker.await()
            }
            NativeExecutionResult(0)
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge, scheduler = singleLaneScheduler(capacity = 1))
        val blocker = client.enqueue(listOf("blocker"))
        blockerStarted.await()
        val queued = client.enqueue(listOf("queued"))
        val input = TrackingSource("never read".encodeToByteArray())

        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            client.execute(listOf("withdrawn"), CommandIo { input("input.bin", input) })
        }
        waiting.cancelAndJoin()
        assertEquals(1, input.closeCount)

        releaseBlocker.complete(Unit)
        blocker.await()
        queued.await()
        assertEquals(listOf("blocker", "queued"), started)
        // The withdrawn command left no session behind, so closing closes the bridge at once.
        client.close()
        assertEquals(1, bridge.closeCount)
    }

    @Test
    fun lanesRunCommandsAtOnceAndStartThemInOrder() = runTest {
        val names = listOf("one", "two", "three", "four")
        val startedCommands = names.associateWith { CompletableDeferred<Unit>() }
        val releases = names.associateWith { CompletableDeferred<Unit>() }
        val bridge = FakeBridge { request, _ ->
            val name = request.arguments.single()
            startedCommands.getValue(name).complete(Unit)
            releases.getValue(name).await()
            NativeExecutionResult(0)
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge, scheduler = CommandScheduler(lanes = 2))

        val sessions = names.associateWith { client.enqueue(listOf(it)) }
        startedCommands.getValue("one").await()
        startedCommands.getValue("two").await()
        assertFalse(startedCommands.getValue("three").isCompleted)

        // The second command finishes first and frees its lane for the third, not the fourth.
        releases.getValue("two").complete(Unit)
        sessions.getValue("two").await()
        startedCommands.getValue("three").await()
        assertEquals(SessionState.RUNNING, sessions.getValue("one").state.value)
        assertFalse(startedCommands.getValue("four").isCompleted)

        // A session that leaves the line without a turn holds no lane.
        sessions.getValue("four").cancelAndJoin()
        releases.getValue("one").complete(Unit)
        sessions.getValue("one").await()
        assertFalse(startedCommands.getValue("four").isCompleted)

        releases.getValue("three").complete(Unit)
        sessions.getValue("three").await()
        assertTrue(sessions.getValue("four").await().cancelled)
        client.close()
    }
}

private fun singleLaneScheduler(capacity: Int = GLOBAL_EXECUTION_QUEUE_CAPACITY) =
    CommandScheduler(lanes = 1, capacity = capacity)

// A collector launched beside its session may subscribe after the first events went out, so the
// bridge keeps emitting until the collector has seen one.
private suspend fun emitUntil(subscribed: CompletableDeferred<Unit>, emit: (NativeExecutionEvent) -> Unit) {
    while (!subscribed.isCompleted) {
        emit(NativeExecutionEvent.Log(32, "waiting\n"))
        delay(1)
    }
}

private class FakeBridge(
    private val block: suspend (NativeExecutionRequest, (NativeExecutionEvent) -> Unit) -> NativeExecutionResult,
) : NativeExecutionBridge {
    var closeCount = 0

    override suspend fun execute(
        request: NativeExecutionRequest,
        emit: (NativeExecutionEvent) -> Unit,
    ): NativeExecutionResult = block(request, emit)

    override fun close() { closeCount++ }
}

private class TrackingSource(bytes: ByteArray) : Source {
    private val data = Buffer().apply { write(bytes) }
    var closeCount = 0

    override fun read(sink: Buffer, byteCount: Long): Long = data.read(sink, byteCount)
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() { closeCount++ }
}

private class TrackingSink : Sink {
    val data = Buffer()
    var closeCount = 0

    override fun write(source: Buffer, byteCount: Long) {
        data.write(source, byteCount)
    }

    override fun flush() = Unit
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() { closeCount++ }
}
