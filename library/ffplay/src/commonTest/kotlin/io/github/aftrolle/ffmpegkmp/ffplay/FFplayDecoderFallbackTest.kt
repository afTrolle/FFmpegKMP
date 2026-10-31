// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerBridge
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderFallback
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerError
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerException
import io.github.aftrolle.ffmpegkmp.bindings.createInMemoryPlayerBridge
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.DecoderFallbackReason
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** The engine's reporting of what the native player says, over a bridge that says what each test needs. */
class FFplayDecoderFallbackTest {
    @Test
    fun aFallbackReasonIsStateAndOneEventPerSource() {
        val events = mutableListOf<FFplayEvent>()
        var snapshot = FFplaySnapshot()
        val engine = engine(fallback = NativePlayerDecoderFallback.UNSUPPORTED, update = { snapshot = it }, emit = events::add)
        engine.prepare(MediaSource("movie.mp4"), FFplayContentProtection.CLEAR_OR_AUTO_DETECT)
        // No output yet: nothing decodes, so nothing fell back.
        assertEquals(emptyList(), events)

        // A Canvas output, which on the desktop wants hardware frames as well.
        engine.attachOutput(CanvasOutput())
        engine.seekTo(1.seconds)
        engine.play()

        assertEquals(DecoderFallbackReason.UNSUPPORTED, snapshot.output?.decoderFallback)
        assertEquals(
            listOf(DecoderFallbackReason.UNSUPPORTED),
            events.filterIsInstance<FFplayEvent.DecoderFallback>().map { it.reason },
        )
        engine.close()
    }

    @Test
    fun noReasonMeansNoFallback() {
        val events = mutableListOf<FFplayEvent>()
        var snapshot = FFplaySnapshot()
        val engine = engine(fallback = NativePlayerDecoderFallback.NONE, update = { snapshot = it }, emit = events::add)
        engine.prepare(MediaSource("movie.mp4"), FFplayContentProtection.CLEAR_OR_AUTO_DETECT)
        engine.attachOutput(CanvasOutput())

        assertNull(snapshot.output?.decoderFallback)
        assertEquals(emptyList(), events.filterIsInstance<FFplayEvent.DecoderFallback>())
        engine.close()
    }

    @Test
    fun aFailedNativeCallCarriesItsErrorCode() {
        val engine = engine(fallback = NativePlayerDecoderFallback.NONE, seekResult = NativePlayerError.UNSUPPORTED)
        engine.prepare(MediaSource("movie.mp4"), FFplayContentProtection.CLEAR_OR_AUTO_DETECT)

        val failure = assertFailsWith<NativePlayerException> { engine.seekTo(1.seconds) }
        assertEquals(NativePlayerError.UNSUPPORTED, failure.errorCode)
        engine.close()
    }

    /** The in-memory bridge, its snapshots carrying [fallback] once an output is attached, and its seeks returning [seekResult]. */
    private fun engine(
        fallback: NativePlayerDecoderFallback,
        seekResult: Int = 0,
        update: (FFplaySnapshot) -> Unit = {},
        emit: (FFplayEvent) -> Unit = {},
    ): FFplayEngine = createFFplayEngineWithBridge(
        FFplayConfiguration(decoderPreference = CodecPreference.AUTO, audio = false),
        update,
        emit,
    ) { configuration, nativeUpdate, _, _ ->
        val delegate = createInMemoryPlayerBridge(configuration) { native ->
            nativeUpdate(if (native.outputCapabilities != null) native.copy(decoderFallback = fallback) else native)
        }
        object : NativePlayerBridge by delegate {
            override fun seek(positionUs: Long): Int = if (seekResult < 0) seekResult else delegate.seek(positionUs)
        }
    }

    private class CanvasOutput : FFplayVideoOutput {
        override val kind = FFplayRendererKind.COMPOSE_CANVAS
        override val capabilities = FFplayOutputCapabilities()

        override fun submitNative(frame: VideoFrame, video: VideoInfo?): Boolean {
            frame.close()
            return true
        }

        override fun discard() = Unit
    }
}
