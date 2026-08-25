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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
        val firstClient = CommandRuntimeClient(CommandKind.FFMPEG, first)
        val secondClient = CommandRuntimeClient(CommandKind.FFPROBE, second)

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
        val firstClient = CommandRuntimeClient(CommandKind.FFMPEG, firstBridge)
        val secondClient = CommandRuntimeClient(CommandKind.FFMPEG, secondBridge)

        val first = firstClient.enqueue(listOf("first"))
        while (first.state.value != SessionState.RUNNING) delay(1)
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
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge)
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
}

private class FakeBridge(
    private val block: suspend (NativeExecutionRequest, (NativeExecutionEvent) -> Unit) -> NativeExecutionResult,
) : NativeExecutionBridge {
    var closeCount = 0

    override suspend fun execute(
        request: NativeExecutionRequest,
        emit: (NativeExecutionEvent) -> Unit,
    ): NativeExecutionResult = block(request, emit)

    override fun cancel(executionId: Long) = Unit
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
