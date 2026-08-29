// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * State-driven owner for one FFplay engine instance.
 *
 * The native engine is intentionally accessed through [FFplayEngine]. This keeps SDL and native
 * frame handles out of the public API and lets a Compose surface attach after media preparation.
 */
@OptIn(ExperimentalAtomicApi::class)
public class FFplayPlayer internal constructor(
    public val configuration: FFplayConfiguration = FFplayConfiguration(),
    engineFactory: FFplayEngineFactory,
) : AutoCloseable {
    public constructor(
        configuration: FFplayConfiguration = FFplayConfiguration(),
    ) : this(configuration, ::createPlatformFFplayEngine)

    internal constructor(
        configuration: FFplayConfiguration,
        useInMemoryEngine: Boolean,
    ) : this(
        configuration,
        if (useInMemoryEngine) ::createInMemoryFFplayEngine else ::createPlatformFFplayEngine,
    )

    private val closed = AtomicBoolean(false)
    private val closeCompleted = AtomicBoolean(false)
    private val prepareMutex = Mutex()
    private val operationLock = FFplayOperationLock()
    private val mutableSnapshot = MutableStateFlow(FFplaySnapshot())
    private val mutableSecureOutputRequired = MutableStateFlow(false)
    private val mutableEvents = MutableSharedFlow<FFplayEvent>(extraBufferCapacity = 16)
    private val engine: FFplayEngine = engineFactory(
        configuration,
        ::acceptEngineUpdate,
        ::emit,
    )

    public val snapshot = mutableSnapshot.asStateFlow()
    public val events: Flow<FFplayEvent> = mutableEvents.asSharedFlow()
    internal val secureOutputRequired = mutableSecureOutputRequired.asStateFlow()

    public suspend fun prepare(source: FFplaySource) {
        prepareMutex.withLock {
            operationLock.withLock {
                checkOpen()
                mutableSecureOutputRequired.value =
                    source.protection == FFplayContentProtection.REQUIRE_SECURE_PATH
                mutableSnapshot.value = FFplaySnapshot(state = FFplayState.PREPARING)
            }
            try {
                prepareOnWorker(source)
            } catch (cancellation: CancellationException) {
                operationLock.withLock {
                    if (!closed.load()) {
                        mutableSecureOutputRequired.value = false
                        mutableSnapshot.value = FFplaySnapshot(state = FFplayState.IDLE)
                    }
                }
                throw cancellation
            } catch (failure: Throwable) {
                operationLock.withLock {
                    if (!closed.load()) {
                        mutableSecureOutputRequired.value = false
                        fail(
                            "Unable to prepare ${source.input}: " +
                                (failure.message ?: failure::class.simpleName),
                            failure,
                        )
                    }
                }
                throw failure
            }
        }
    }

    public fun play(): Unit = operationLock.withLock {
        checkOpen()
        engine.play()
    }

    public fun pause(): Unit = operationLock.withLock {
        checkOpen()
        engine.pause()
    }

    public fun seekTo(position: Duration): Unit = operationLock.withLock {
        checkOpen()
        require(!position.isNegative()) { "Seek position must not be negative" }
        engine.seekTo(position)
    }

    public fun stop(): Unit = operationLock.withLock {
        checkOpen()
        engine.stop()
        mutableSecureOutputRequired.value = false
    }

    internal fun attachOutput(output: FFplayVideoOutput): Unit = operationLock.withLock {
        checkOpen()
        engine.attachOutput(output)
    }

    internal fun detachOutput(output: FFplayVideoOutput): Unit = operationLock.withLock {
        if (!closed.load()) engine.detachOutput(output)
    }

    internal fun requestClose() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        // Cancellation is the one operation intentionally allowed to race an active native call.
        engine.cancel()
    }

    override fun close() {
        requestClose()
        // The operation lock prevents destruction until an active native call has observed
        // cancellation. Multiple callers may wait here, but only one destroys the engine.
        operationLock.withLock {
            if (!closeCompleted.compareAndSet(expectedValue = false, newValue = true)) {
                return@withLock
            }
            engine.close()
            mutableSecureOutputRequired.value = false
            mutableSnapshot.value = mutableSnapshot.value.copy(state = FFplayState.CLOSED)
        }
    }

    private suspend fun prepareOnWorker(source: FFplaySource): Unit = coroutineScope {
        val preparation = async(Dispatchers.Default) {
            operationLock.withLock {
                checkOpen()
                engine.resetCancellation()
                // close() may have raced the reset. Checking again guarantees its cancellation
                // cannot be cleared immediately before entering a blocking native prepare.
                checkOpen()
                engine.prepare(source)
                checkOpen()
            }
            engine.awaitPreparation()
            operationLock.withLock { checkOpen() }
        }
        try {
            preparation.await()
        } catch (cancellation: CancellationException) {
            engine.cancel()
            withContext(NonCancellable) { preparation.join() }
            throw cancellation
        }
    }

    private fun acceptEngineUpdate(snapshot: FFplaySnapshot) {
        if (!closed.load()) mutableSnapshot.value = snapshot
    }

    private fun emit(event: FFplayEvent) {
        if (!closed.load()) mutableEvents.tryEmit(event)
    }

    private fun fail(message: String, cause: Throwable) {
        val failure = FFplayFailure(message, cause)
        mutableSnapshot.value = mutableSnapshot.value.copy(state = FFplayState.FAILED, failure = failure)
        emit(FFplayEvent.Fatal(message, cause))
    }

    private fun checkOpen() {
        check(!closed.load()) { "FFplayPlayer is closed" }
    }
}
