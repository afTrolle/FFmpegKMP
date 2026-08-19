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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray

class CommandRuntimeTest {
    @Test
    fun globallyQueuesDifferentClients() = runTest {
        val order = mutableListOf<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = FakeBridge { request, _ ->
            order += "start-${request.arguments.single()}"
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
        while (sessionOne.state.value != SessionState.RUNNING) delay(1)
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
        val input = TrackingRawSource("hello".encodeToByteArray())
        val output = TrackingRawSink()
        val bridge = FakeBridge { request, emit ->
            assertContentEquals("hello".encodeToByteArray(), request.inputs.single().bytes)
            emit(NativeExecutionEvent.Output(NativeExecutionEvent.Stream.STDOUT, "done"))
            NativeExecutionResult(0, mapOf("result.bin" to "world".encodeToByteArray()))
        }
        val client = CommandRuntimeClient(CommandKind.FFMPEG, bridge)
        val result = client.execute(
            listOf("-version"),
            CommandIo {
                input("input.bin", input.buffered())
                output("result.bin", output.buffered())
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
        val input = TrackingRawSource("queued".encodeToByteArray())
        val output = TrackingRawSink()
        val second = secondClient.enqueue(listOf("second"))
        val secondWithIo = secondClient.enqueue(
            listOf("third"),
            CommandIo {
                input("input.bin", input.buffered())
                output("output.bin", output.buffered())
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

        assertEquals(
            "Unknown option '-bad'.\nConversion failed.\n",
            result.errorOutput,
        )
        assertEquals(LogLevel.ERROR, result.logs.single().level)
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

private class TrackingRawSource(bytes: ByteArray) : RawSource {
    private val data = Buffer().apply { write(bytes) }
    var closeCount = 0

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = data.readAtMostTo(sink, byteCount)
    override fun close() { closeCount++ }
}

private class TrackingRawSink : RawSink {
    val data = Buffer()
    var closeCount = 0

    override fun write(source: Buffer, byteCount: Long) {
        data.write(source, byteCount)
    }

    override fun flush() = Unit
    override fun close() { closeCount++ }
}
