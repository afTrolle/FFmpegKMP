// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.ffplay

import android.graphics.SurfaceTexture
import android.media.MediaCodecList
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.compose.ui.layout.ContentScale
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.DecoderKind
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoInfo
import io.github.aftrolle.ffmpegkmp.core.CommandIo
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.floor
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer

/**
 * The real player on a device: [FFplayPlayer]'s production engine, through the JavaCPP bridge to the native player,
 * presenting onto Surfaces wired as `PlatformFFplaySurface` wires them. Under [CodecPreference.AUTO] MediaCodec renders
 * straight to the Surface; under [CodecPreference.SOFTWARE] the frames are drawn onto it by [AndroidSurfaceOutput].
 *
 * The Surfaces are SurfaceTextures, read back through GL for the 12-bit frame number cfr-30-h264-128.mp4 draws in its
 * top rows, so each check sees which frame reached the Surface. Every wait and every blocking call has a timeout, so a
 * hang fails the test, naming where the call was stuck.
 */
class FFplayNativePlayerDeviceTest {
    private val clip: ByteArray by lazy {
        checkNotNull(javaClass.getResourceAsStream("/video-decoder/$CLIP")) { "Missing fixture $CLIP" }.use { it.readBytes() }
    }

    private companion object {
        /** 128x128 H.264 at 30 fps, five seconds: the frame code over cfr-30.mp4, large enough for hardware decoders. */
        const val CLIP = "cfr-30-h264-128.mp4"
        const val SIZE = 128
        const val FPS = 30
        const val TAG = "FFplayNativePlayerTest"
    }

    @Test
    fun autoPlaysTheClipThroughMediaCodecOntoTheSurface() = runBlocking {
        playThroughTheLifecycle(CodecPreference.AUTO, attachFirst = false)
    }

    /**
     * The composable's order under AUTO: the Surface is attached before the stream is known, so the preview is drawn
     * through [AndroidSurfaceOutput]'s canvas, and MediaCodec is configured on the same Surface once it is known.
     *
     * Fails today: `lockCanvas` connects the Surface as a CPU producer and nothing disconnects it, so MediaCodec's
     * connect fails (`BufferQueueProducer: connect: already connected (cur=2 req=3)`, configure returns -22) and the
     * player falls back to software decoding for as long as that Surface lives.
     */
    @Ignore("A Surface drawn through a canvas cannot be connected to MediaCodec afterwards")
    @Test
    fun autoDecodesInHardwareOnASurfaceThatShowedACanvasPreview() = runBlocking {
        playThroughTheLifecycle(CodecPreference.AUTO, attachFirst = true)
    }

    @Test
    fun softwarePlaysTheClipThroughTheSurfaceCanvas() = runBlocking {
        playThroughTheLifecycle(CodecPreference.SOFTWARE, attachFirst = true)
    }

    @Test
    fun autoMovesPlaybackToANewSurfaceWhilePlayingAndWhilePaused() = runBlocking {
        swapSurfaces(CodecPreference.AUTO)
    }

    @Test
    fun softwareMovesPlaybackToANewSurfaceWhilePlayingAndWhilePaused() = runBlocking {
        swapSurfaces(CodecPreference.SOFTWARE)
    }

    @Test
    fun threePlayersPlayAtOnceEachAtItsOwnPosition() = runBlocking {
        val preferences = listOf(CodecPreference.AUTO, CodecPreference.AUTO, CodecPreference.SOFTWARE)
        val starts = listOf(0.seconds, 2.seconds, 1.seconds)
        val players = preferences.map { Playing(it) }
        try {
            for ((playing, start) in players.zip(starts)) {
                playing.prepareAndShow()
                within("seek to $start") { playing.player.seekTo(start) }
                playing.player.await("the frame at $start") { it.state == FFplayState.PAUSED || it.state == FFplayState.READY }
                playing.surface.await("frame ${frameAt(start)}") { it.number == frameAt(start) }
            }
            for (playing in players) within("play") { playing.player.play() }
            for ((playing, start) in players.zip(starts)) {
                playing.player.await("playback past ${start + 1.seconds}") { it.position >= start + 1.seconds }
            }
            for (playing in players) within("pause") { playing.player.pause() }
            val positions = players.map { it.player.snapshot.value.position }
            for ((index, playing) in players.withIndex()) {
                val position = positions[index]
                val start = starts[index]
                // Each moved on from its own start, about as far as the others, while they all played.
                assertTrue(position in start + 1.seconds..start + 2.5.seconds, "player $index at $position from $start")
                val shown = playing.surface.settled()
                assertTrue(shown.number in frameAt(start) + FPS..frameAt(position), "player $index shows ${shown.number} at $position")
                log("player $index (${preferences[index]}): ${playing.player.snapshot.value.output?.decoder} at $position")
            }
            val spread = positions.zip(starts) { position, start -> position - start }
            assertTrue(spread.max() - spread.min() < 1.seconds, "the players advanced by $spread")
        } finally {
            players.forEach(Playing::close)
        }
    }

    @Test
    fun autoCloseWhilePlayingReturns() = runBlocking {
        closeWhilePlaying(CodecPreference.AUTO)
    }

    @Test
    fun softwareCloseWhilePlayingReturns() = runBlocking {
        closeWhilePlaying(CodecPreference.SOFTWARE)
    }

    @Test
    fun closeDuringASeekReturns() = runBlocking {
        for (preference in listOf(CodecPreference.AUTO, CodecPreference.SOFTWARE)) {
            for ((attempt, head) in listOf(0.milliseconds, 0.milliseconds, 2.milliseconds, 5.milliseconds, 20.milliseconds).withIndex()) {
                Playing(preference).use { playing ->
                    playing.prepareAndShow()
                    if (attempt % 2 == 1) {
                        within("play") { playing.player.play() }
                        playing.player.await("playback") { it.position >= 0.3.seconds }
                    }
                    // The last is past the end, so its seek drains the decoder for the last frame.
                    val target = (1 + attempt).seconds + 0.5.seconds
                    // The seek blocks while its frame is decoded; close interrupts it from another thread.
                    val seek = FutureTask { runCatching { playing.player.seekTo(target) } }
                    Thread(seek, "FFplay test seek").apply { isDaemon = true }.start()
                    Thread.sleep(head.inWholeMilliseconds)
                    within("close during a seek to $target ($preference, attempt $attempt)") { playing.player.close() }
                    val result = try {
                        seek.get(10, TimeUnit.SECONDS)
                    } catch (_: TimeoutException) {
                        fail("The seek to $target did not return after close ($preference, attempt $attempt)")
                    }
                    assertEquals(FFplayState.CLOSED, playing.player.snapshot.value.state)
                    log("close during a seek ($preference, attempt $attempt): the seek ${result.exceptionOrNull() ?: "returned"}")
                }
            }
        }
    }

    @Test
    fun aCanvasOutputReceivesTheFrameOnScreenAtEachTarget() = runBlocking {
        val output = CountingOutput()
        val player = FFplayPlayer(FFplayConfiguration(decoderPreference = CodecPreference.AUTO, audio = false))
        try {
            within("attach") { player.attachOutput(output) }
            withTimeout(15.seconds) { player.prepare(source()) }
            assertEquals(FFplayState.READY, player.snapshot.value.state)
            assertEquals(listOf(0), output.frames.value.map(::frameAt), "the preview")
            assertEquals(DecoderKind.SOFTWARE, player.snapshot.value.output?.decoder)
            assertEquals(FFplayRendererKind.COMPOSE_CANVAS, player.snapshot.value.output?.renderer)
            for (target in listOf(1.55.seconds, 0.4.seconds, 4.9.seconds, 2.seconds)) {
                within("seek to $target") { player.seekTo(target) }
                assertEquals(frameAt(target), frameAt(output.frames.value.last()), "the frame shown at $target")
                assertEquals(target, player.snapshot.value.position)
            }
            within("play") { player.play() }
            player.await("playback past 3 s") { it.position >= 3.seconds }
            within("pause") { player.pause() }
            val played = output.frames.value.dropWhile { frameAt(it) != frameAt(2.seconds) }.drop(1).map(::frameAt)
            // Every frame from the one after the target, in order, unless the clock dropped one.
            assertTrue(played.size >= FPS - 3, "played $played")
            assertEquals(played.sorted().distinct(), played, "in order")
            val count = output.frames.value.size
            delay(300)
            assertEquals(count, output.frames.value.size, "no frame while paused")
            within("seek while paused") { player.seekTo(1.seconds) }
            assertEquals(frameAt(1.seconds), frameAt(output.frames.value.last()))
            within("stop") { player.stop() }
            assertEquals(FFplayState.STOPPED, player.snapshot.value.state)
        } finally {
            within("close") { player.close() }
        }
        assertEquals(FFplayState.CLOSED, player.snapshot.value.state)
    }

    /**
     * Prepare and show the clip, then seek while paused, play, pause, seek while playing, stop and close. With
     * [attachFirst] the Surface is attached before prepare, while the stream is unknown, and again once it is known,
     * as the composable's `LaunchedEffect` does; otherwise only once the player is prepared.
     */
    private suspend fun playThroughTheLifecycle(preference: CodecPreference, attachFirst: Boolean) {
        val started = System.nanoTime()
        Playing(preference).use { playing ->
            val player = playing.player
            if (attachFirst) {
                // Before prepare the stream is unknown, so the output offers software frames only.
                playing.show()
                withTimeout(15.seconds) { player.prepare(source()) }
                assertEquals(FFplayState.READY, player.snapshot.value.state)
                assertEquals(SIZE, player.snapshot.value.video?.width)
                // Drawn through the output's canvas.
                playing.surface.await("the preview") { it.number == 0 }
                log("$preference preview on ${player.snapshot.value.output?.decoder}, ${playing.surface.frames.value}")
                val previews = playing.surface.frames.value.count
                // Now that it is known, MediaCodec may render straight to the Surface.
                playing.show()
                player.await("the output after renegotiation") { it.state == FFplayState.READY && it.output != null }
                playing.surface.await("the preview after renegotiation") { it.count > previews && it.number == 0 }
            } else {
                playing.prepareAndShow()
            }
            val decoder = player.snapshot.value.output?.decoder
            log("$preference prepared on $decoder; zero copy ${player.snapshot.value.output?.zeroCopy}")
            assertExpectedDecoder(preference, decoder)

            val target = 1.55.seconds
            within("seek while paused") { player.seekTo(target) }
            player.await("the seek") { it.state == FFplayState.PAUSED }
            assertEquals(target, player.snapshot.value.position)
            playing.surface.await("frame ${frameAt(target)}") { it.number == frameAt(target) }

            val beforePlay = playing.surface.frames.value.count
            within("play") { player.play() }
            player.await("playing") { it.state == FFplayState.PLAYING }
            player.await("playback past ${target + 1.seconds}") { it.position >= target + 1.seconds }
            playing.surface.await("frames past ${frameAt(target + 1.seconds)}") { it.number >= frameAt(target + 1.seconds) }
            assertTrue(playing.surface.frames.value.count - beforePlay >= FPS - 3, "frames shown in a second of playback")
            assertExpectedDecoder(preference, player.snapshot.value.output?.decoder)

            within("pause") { player.pause() }
            assertEquals(FFplayState.PAUSED, player.snapshot.value.state)
            val paused = player.snapshot.value.position
            val held = playing.surface.settled()
            assertTrue(held.number in frameAt(paused) - 1..frameAt(paused), "showing ${held.number} paused at $paused")
            delay(400)
            assertEquals(paused, player.snapshot.value.position, "the position holds while paused")
            assertEquals(held, playing.surface.frames.value, "no frame reaches the Surface while paused")

            within("play again") { player.play() }
            player.await("playback past $paused") { it.position > paused + 0.2.seconds }
            val back = 0.5.seconds
            within("seek while playing") { player.seekTo(back) }
            player.await("playing after the seek") { it.state == FFplayState.PLAYING }
            // The frames before the seek were past frame 75; these start over from the target.
            playing.surface.await("frames from ${frameAt(back)}") { it.number in frameAt(back) until frameAt(back) + FPS }
            player.await("playback past ${back + 0.5.seconds}") { it.position in back + 0.5.seconds..back + 2.seconds }
            playing.surface.await("frames past ${frameAt(back + 0.5.seconds)}") {
                it.number in frameAt(back + 0.5.seconds) until frameAt(back) + 2 * FPS
            }

            // Past the end, while paused: the seek is bounded to the duration and shows the last frame.
            within("pause before the end") { player.pause() }
            within("seek past the end") { player.seekTo(10.seconds) }
            player.await("the seek to the end") { it.state == FFplayState.PAUSED }
            assertEquals(player.snapshot.value.duration, player.snapshot.value.position)
            playing.surface.await("the last frame") { it.number == 5 * FPS - 1 }

            within("stop") { player.stop() }
            assertEquals(FFplayState.STOPPED, player.snapshot.value.state)
            val stopped = playing.surface.settled()
            delay(200)
            assertEquals(stopped, playing.surface.frames.value, "no frame after stop")
            playing.check()
        }
        log("$preference lifecycle took ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /** Destroys the Surface and attaches a new one, as `AndroidExternalSurface` does, while playing and while paused. */
    private suspend fun swapSurfaces(preference: CodecPreference) {
        Playing(preference).use { playing ->
            val player = playing.player
            playing.prepareAndShow()
            within("play") { player.play() }
            player.await("playback") { it.position >= 0.5.seconds }

            // While playing.
            val second = playing.replaceSurface()
            player.await("playing on the new Surface") { it.state == FFplayState.PLAYING }
            val resumed = player.snapshot.value.position
            second.await("frames on the new Surface") { it.count >= 10 && it.number > frameAt(resumed) }
            log("$preference after a swap while playing: ${player.snapshot.value.output?.decoder}")
            assertExpectedDecoder(preference, player.snapshot.value.output?.decoder)

            // While paused: the new Surface shows the paused frame.
            within("pause") { player.pause() }
            val paused = player.snapshot.value.position
            val held = second.settled()
            val third = playing.replaceSurface()
            player.await("ready on the third Surface") { it.state == FFplayState.READY || it.state == FFplayState.PAUSED }
            third.await("the paused frame") { it.count >= 1 }
            assertEquals(paused, player.snapshot.value.position)
            assertTrue(third.frames.value.number in held.number - 1..held.number + 1, "showing ${third.frames.value.number}, paused on ${held.number}")

            within("play on the third Surface") { player.play() }
            player.await("playback past ${paused + 1.seconds}") { it.position >= paused + 1.seconds }
            third.await("playback on the third Surface") { it.number >= frameAt(paused + 1.seconds) }
            assertExpectedDecoder(preference, player.snapshot.value.output?.decoder)
            playing.check()
        }
    }

    private suspend fun closeWhilePlaying(preference: CodecPreference) {
        for (played in listOf(0.milliseconds, 300.milliseconds, 1.5.seconds)) {
            val playing = Playing(preference)
            try {
                playing.prepareAndShow()
                within("play") { playing.player.play() }
                if (played.isPositive()) playing.player.await("playback past $played") { it.position >= played }
                val started = System.nanoTime()
                within("close while playing ($preference, after $played)") { playing.player.close() }
                log("$preference close while playing after $played took ${(System.nanoTime() - started) / 1_000_000} ms")
                assertEquals(FFplayState.CLOSED, playing.player.snapshot.value.state)
                val shown = playing.surface.settled()
                delay(200)
                assertEquals(shown, playing.surface.frames.value, "no frame after close")
            } finally {
                playing.close()
            }
        }
    }

    /** Under AUTO, MediaCodec decodes where the device has a hardware H.264 decoder for the clip. */
    private fun assertExpectedDecoder(preference: CodecPreference, decoder: DecoderKind?) {
        val expected = when {
            preference == CodecPreference.SOFTWARE -> DecoderKind.SOFTWARE
            hardwareAvcDecoder() -> DecoderKind.HARDWARE
            else -> return log("no hardware H.264 decoder takes $SIZE x $SIZE; decoded on $decoder")
        }
        assertEquals(expected, decoder, "the decoder under $preference")
    }

    private fun hardwareAvcDecoder(): Boolean = Build.VERSION.SDK_INT >= 29 &&
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.isHardwareAccelerated && "video/avc" in info.supportedTypes &&
                info.getCapabilitiesForType("video/avc").videoCapabilities?.isSizeSupported(SIZE, SIZE) == true
        }

    private fun source() = MediaSource(CLIP, CommandIo { input(CLIP, Buffer().write(clip)) })

    /** One player and the Surface it shows on, wired as `PlatformFFplaySurface` wires them. */
    private inner class Playing(val preference: CodecPreference) : AutoCloseable {
        val player = FFplayPlayer(FFplayConfiguration(decoderPreference = preference, audio = false))
        val output = AndroidSurfaceOutput(ContentScale.Fit, 0)
        var surface = SurfaceReader()
            private set
        private val retired = mutableListOf<SurfaceReader>()
        private val events = mutableListOf<FFplayEvent>()
        private val collecting: Job = CoroutineScope(Dispatchers.Default).launch {
            player.events.collect { event -> synchronized(events) { events += event } }
        }

        /** The composable's `onSurface`, and its `LaunchedEffect` once the stream is known. */
        fun show() = within("attach the output") {
            output.updateVideoInfo(player.snapshot.value.video)
            output.attachSurface(surface.surface, SIZE, SIZE)
            player.attachOutput(output)
        }

        suspend fun prepareAndShow() {
            withTimeout(15.seconds) { player.prepare(source()) }
            assertEquals(FFplayState.WAITING_FOR_OUTPUT, player.snapshot.value.state)
            show()
            player.await("ready") { it.state == FFplayState.READY }
            surface.await("the preview") { it.number == 0 }
            assertExpectedDecoder(preference, player.snapshot.value.output?.decoder)
        }

        /** The composable's `onDestroyed` for the current Surface, then `onSurface` with a new one. */
        fun replaceSurface(): SurfaceReader {
            val old = surface
            within("detach the output") {
                player.detachOutput(output)
                output.detachSurface(old.surface)
            }
            old.close()
            retired += old
            surface = SurfaceReader()
            show()
            return surface
        }

        /** Fails on a fatal event or a failure reading the Surfaces. */
        fun check() {
            val fatal = synchronized(events) { events.filterIsInstance<FFplayEvent.Fatal>() }
            assertEquals(emptyList(), fatal, "fatal events")
            (retired + surface).forEach { it.failure?.let { failure -> throw AssertionError("reading the Surface", failure) } }
        }

        override fun close() {
            within("close") { player.close() }
            runBlocking { collecting.cancelAndJoin() }
            surface.close()
            log("$preference events: ${synchronized(events) { events.toList() }}")
        }
    }

    /** What reached a Surface: how many frames, and the number drawn into the latest. */
    private data class Shown(val count: Int, val number: Int)

    /**
     * A Surface that keeps up with its producer, as a display does, reading each frame's number: a SurfaceTexture,
     * which takes MediaCodec's buffers and [AndroidSurfaceOutput]'s canvas alike, as a SurfaceView does, drawn on its
     * own GL thread into a pbuffer the size of the clip and read back.
     */
    private class SurfaceReader : AutoCloseable {
        private val thread = HandlerThread("FFplay test Surface").apply { start() }
        private val handler = Handler(thread.looper)
        private val shown = MutableStateFlow(Shown(0, -1))
        val frames: StateFlow<Shown> = shown

        @Volatile
        var failure: Throwable? = null
            private set

        // Touched only on the GL thread.
        private lateinit var display: EGLDisplay
        private lateinit var context: EGLContext
        private lateinit var pbuffer: EGLSurface
        private lateinit var texture: SurfaceTexture
        private var program = 0
        private val matrix = FloatArray(16)
        private val pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
        private val quad = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(QUAD).apply { position(0) }

        val surface: Surface = onGlThread {
            setUpGl()
            Surface(texture)
        }

        suspend fun await(what: String, timeout: Duration = 10.seconds, predicate: (Shown) -> Boolean): Shown {
            failure?.let { throw AssertionError("reading the Surface", it) }
            return withTimeoutOrNull(timeout) { shown.first(predicate) }
                ?: fail("Timed out after $timeout waiting for $what on the Surface; it has ${shown.value}, failure $failure")
        }

        /** What the Surface shows once the frames already on their way have arrived. */
        suspend fun settled(): Shown {
            delay(150)
            return shown.value
        }

        override fun close() {
            onGlThread {
                texture.release()
                GLES20.glDeleteProgram(program)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                // Not eglTerminate: the display is the process's, shared with the other readers.
                EGL14.eglReleaseThread()
            }
            surface.release()
            thread.quitSafely()
        }

        private fun <T> onGlThread(block: () -> T): T {
            val task = FutureTask(block)
            handler.post(task)
            return try {
                task.get(10, TimeUnit.SECONDS)
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            }
        }

        private fun setUpGl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
            val config = configs[0]
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            pbuffer = EGL14.eglCreatePbufferSurface(
                display, config, intArrayOf(EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE), 0,
            )
            check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent" }
            program = GLES20.glCreateProgram().also { program ->
                GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
                GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
                GLES20.glLinkProgram(program)
                val linked = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
                check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
            }
            val name = IntArray(1)
            GLES20.glGenTextures(1, name, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, name[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            texture = SurfaceTexture(name[0]).apply {
                setDefaultBufferSize(SIZE, SIZE)
                setOnFrameAvailableListener({ read() }, handler)
            }
        }

        /** Latches the next frame, draws it upright into the pbuffer and reads its number back. */
        private fun read() {
            try {
                texture.updateTexImage()
                texture.getTransformMatrix(matrix)
                GLES20.glViewport(0, 0, SIZE, SIZE)
                GLES20.glUseProgram(program)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "textureMatrix"), 1, false, matrix, 0)
                val position = GLES20.glGetAttribLocation(program, "position")
                GLES20.glEnableVertexAttribArray(position)
                GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                pixels.clear()
                GLES20.glReadPixels(0, 0, SIZE, SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                // GL's rows run bottom up.
                val number = (0 until 12).sumOf { bit ->
                    val x = (bit % 6) * 16 + 8
                    val y = SIZE - 1 - ((bit / 6) * 16 + 8)
                    if ((pixels.get((y * SIZE + x) * 4 + 1).toInt() and 0xff) > 128) 1 shl bit else 0
                }
                shown.update { Shown(it.count + 1, number) }
            } catch (failure: Throwable) {
                this.failure = failure
            }
        }

        private fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        }

        private companion object {
            /** A triangle strip over the viewport, in positions that double as texture coordinates. */
            val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            const val VERTEX_SHADER = """
                attribute vec2 position;
                uniform mat4 textureMatrix;
                varying vec2 coordinate;
                void main() {
                    gl_Position = vec4(position, 0.0, 1.0);
                    coordinate = (textureMatrix * vec4(position * 0.5 + 0.5, 0.0, 1.0)).xy;
                }
            """
            const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES frame;
                varying vec2 coordinate;
                void main() {
                    gl_FragColor = texture2D(frame, coordinate);
                }
            """
        }
    }

    /** A Compose Canvas style output: keeps the presentation time of each software frame it is given. */
    private class CountingOutput : FFplayVideoOutput {
        val frames = MutableStateFlow(emptyList<Duration>())
        override val kind = FFplayRendererKind.COMPOSE_CANVAS
        override val capabilities = FFplayOutputCapabilities(softwareFrameUpload = true)

        override fun submitNative(frame: VideoFrame, video: VideoInfo?): Boolean = frame.use {
            frames.update { frames -> frames + it.pts }
            true
        }

        override fun discard() = Unit
    }

    /** The frame on screen at [time]; a frame's own start time, in whole microseconds, may fall just short of it. */
    private fun frameAt(time: Duration): Int = floor(time.inWholeMicroseconds * FPS / 1e6 + 1e-3).toInt()

    private suspend fun FFplayPlayer.await(
        what: String,
        timeout: Duration = 10.seconds,
        predicate: (FFplaySnapshot) -> Boolean,
    ): FFplaySnapshot = withTimeoutOrNull(timeout) {
        snapshot.first { snapshot ->
            if (snapshot.state == FFplayState.FAILED) fail("The player failed waiting for $what: $snapshot")
            predicate(snapshot)
        }
    } ?: fail("Timed out after $timeout waiting for $what; the player is at ${snapshot.value}")

    /** Runs a blocking player call on its own thread, failing with where it is stuck if it does not return in time. */
    private fun <T> within(what: String, timeout: Duration = 10.seconds, block: () -> T): T {
        val task = FutureTask(block)
        val thread = Thread(task, "FFplay test: $what").apply { isDaemon = true }
        thread.start()
        return try {
            task.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            fail("$what did not return within $timeout; it is at\n${thread.stackTrace.joinToString("\n")}")
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }
}
