// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import android.content.Intent
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.view.Choreographer
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject

/*
 * What PlaybackTimingDeviceTest measures with, kept apart from its scenarios. Every time is
 * System.nanoTime(), CLOCK_MONOTONIC, the clock the native player schedules on
 * (av_gettime_relative), MediaCodec stamps rendered buffers with and Choreographer reports
 * vsyncs in, so times from all of them compare directly.
 */

/**
 * One frame as an output saw it: its media time [ptsUs], when the player released it to the
 * output ([releasedNs]), and the display frame it was first shown in ([shownNs]; null when it was
 * never shown). What "released" and "shown" are depends on the output; see PlaybackTimingDeviceTest.
 */
internal class TimedFrame(val ptsUs: Long, val releasedNs: Long, var shownNs: Long? = null, var readyNs: Long? = null)

/** The median, 95th percentile and maximum of [values], rounded to 0.01; empty for no values. */
internal fun distribution(values: List<Double>): JSONObject {
    val json = JSONObject()
    if (values.isEmpty()) return json
    val sorted = values.sorted()
    json.put("med", sorted[sorted.size / 2].round())
    json.put("p95", sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)].round())
    json.put("max", sorted.last().round())
    return json
}

internal fun Double.round(): Double = (this * 100).roundToLong() / 100.0

private fun Long.ms(): Double = this / 1e6

/**
 * The timing figures of the frames of one or more players ([players], each in release order: pts
 * order), for streams of frames [frameDurationUs] long on a display refreshing every [vsyncNs]. Each
 * player's values are taken against its own frames, then pooled:
 *
 * - `due`: frame i is due at the release of the player's first frame plus (pts i - pts 0), which is
 *   the native player's own schedule (with no audio it follows the wall clock from the first frame
 *   of a run). For Media3 it is the same line drawn from its first frame, an approximation of its clock.
 * - `releaseLate`: released - due, how late the player let each frame go; `convert`, where the
 *   output says when a frame was ready to draw, how long after its release.
 * - `late`: shown - due, over the frames shown.
 * - `jitter`: |(shown - pts) - median(shown - pts)|, how far each frame landed from an even line
 *   through the shown frames, whatever anchors it: the figure to compare between players.
 * - `gapErr`: |gap - frame duration| between consecutive shown frames, and `gapVsyncs`, those gaps
 *   counted in display refreshes (at 30 fps on 120 Hz, 4 is on time); `repeats` counts gaps longer than
 *   a frame by half a refresh or more (the previous frame stayed up longer) and `short` those shorter.
 * - `skipped`: frames released but never shown; `fps`: frames shown a second, per player when several.
 */
internal fun timing(players: List<List<TimedFrame>>, frameDurationUs: Long, vsyncNs: Long): JSONObject {
    val releaseLate = ArrayList<Double>()
    val convert = ArrayList<Double>()
    val late = ArrayList<Double>()
    val jitter = ArrayList<Double>()
    val gaps = ArrayList<Long>()
    val fps = ArrayList<Double>()
    var released = 0
    var shownCount = 0
    for (frames in players) {
        released += frames.size
        val first = frames.firstOrNull() ?: continue
        fun due(frame: TimedFrame): Long = first.releasedNs + (frame.ptsUs - first.ptsUs) * 1000
        val shown = frames.filter { it.shownNs != null }
        shownCount += shown.size
        frames.mapTo(releaseLate) { (it.releasedNs - due(it)).ms() }
        frames.mapNotNullTo(convert) { frame -> frame.readyNs?.let { (it - frame.releasedNs).ms() } }
        shown.mapTo(late) { (it.shownNs!! - due(it)).ms() }
        val offsets = shown.map { it.shownNs!! - it.ptsUs * 1000 }
        val median = offsets.sorted().getOrNull(offsets.size / 2) ?: 0
        offsets.mapTo(jitter) { abs(it - median).ms() }
        gaps += shown.zipWithNext { a, b -> b.shownNs!! - a.shownNs!! }
        if (shown.size > 1) fps += ((shown.size - 1) * 1e9 / (shown.last().shownNs!! - shown.first().shownNs!!)).round()
    }
    val frameNs = frameDurationUs * 1000
    val json = JSONObject()
        .put("released", released)
        .put("shown", shownCount)
        .put("skipped", released - shownCount)
        .put("releaseLate", distribution(releaseLate))
        .put("late", distribution(late))
        .put("jitter", distribution(jitter))
        .put("gapErr", distribution(gaps.map { abs(it - frameNs).ms() }))
        .put("repeats", gaps.count { it - frameNs >= vsyncNs / 2 })
        .put("short", gaps.count { frameNs - it >= vsyncNs / 2 })
    if (convert.isNotEmpty()) json.put("convert", distribution(convert))
    if (vsyncNs > 0) {
        val counts = sortedMapOf<Long, Int>()
        gaps.forEach { gap -> counts.merge((gap.toDouble() / vsyncNs).roundToLong(), 1, Int::plus) }
        json.put("gapVsyncs", JSONObject(counts.mapKeys { it.key.toString() }))
    }
    if (players.size == 1) fps.singleOrNull()?.let { json.put("fps", it) } else json.put("fps", JSONArray(fps))
    return json
}

/**
 * The display's refreshes, as Choreographer reports them to a frame callback that re-posts itself
 * on the main thread while it runs: the grid a Surface's buffers are latched on. It wakes the main
 * thread every refresh but draws nothing. Its callback runs in each UI frame before that frame's
 * drawing (animation callbacks precede traversals), so [latest], read while drawing on the main
 * thread, is the frame time (vsync) of the frame being drawn.
 */
internal class VsyncGrid {
    private val times = ArrayList<Long>()
    private val running = AtomicBoolean(false)
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            synchronized(times) { times += frameTimeNanos }
            latest = frameTimeNanos
            if (running.get()) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** The frame time of the last frame the callback ran in; 0 before the first. */
    @Volatile
    var latest: Long = 0
        private set

    fun start() {
        running.set(true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
        // The first refresh, so that a drawing from here on has its frame time.
        var waited = 0
        while (latest == 0L && waited++ < 100) Thread.sleep(5)
    }

    fun stop() {
        running.set(false)
    }

    /** The median time between refreshes; 0 before two. */
    val periodNs: Long
        get() = synchronized(times) { times.zipWithNext { a, b -> b - a }.sorted().let { it.getOrNull(it.size / 2) ?: 0 } }

    /**
     * The first refresh at or after [time]: where an idealised compositor that latches a buffer at the first
     * refresh after its timestamp shows it. Past the last refresh seen, the grid is extended by [periodNs].
     */
    fun at(time: Long): Long? = synchronized(times) {
        if (times.isEmpty()) return null
        val index = times.binarySearch(time).let { if (it >= 0) it else -it - 1 }
        if (index < times.size) return times[index]
        val period = periodNs.takeIf { it > 0 } ?: return null
        val last = times.last()
        last + (time - last + period - 1) / period * period
    }
}

/**
 * The process's memory, garbage collection and CPU over a run: native heap, resident memory, Java
 * heap and threads sampled every 50 ms, PSS (`Debug.MemoryInfo`, which reads smaps) every second, and
 * the ART collector's and CPU time's change between [start] and [stop]. Decoders in the media
 * server (MediaCodec) are another process and not in these figures.
 */
internal class ResourceSampler {
    private val running = AtomicBoolean(false)
    private var sampler: Thread? = null
    private var idle = JSONObject()
    private var peakNative = 0L
    private var peakRss = 0L
    private var peakJava = 0L
    private var peakPss = 0L
    private var peakThreads = 0
    private var gcCount = 0L
    private var gcTimeMs = 0L
    private var allocated = 0L
    private var blockingGc = 0L
    private var cpuMs = 0L
    private var wallNs = 0L

    fun start() {
        System.gc()
        Thread.sleep(100)
        idle = JSONObject()
            .put("nativeMB", Debug.getNativeHeapAllocatedSize().mb())
            .put("rssMB", rssBytes().mb())
            .put("pssMB", pssBytes().mb())
            .put("javaMB", javaBytes().mb())
            .put("threads", threads())
        peakNative = 0
        peakRss = 0
        peakJava = 0
        peakPss = 0
        peakThreads = 0
        gcCount = stat("art.gc.gc-count")
        gcTimeMs = stat("art.gc.gc-time")
        allocated = stat("art.gc.bytes-allocated")
        blockingGc = stat("art.gc.blocking-gc-count")
        cpuMs = Process.getElapsedCpuTime()
        wallNs = System.nanoTime()
        running.set(true)
        sampler = thread(name = "PlaybackTiming sampler") {
            var tick = 0
            while (running.get()) {
                synchronized(this) {
                    peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
                    peakRss = maxOf(peakRss, rssBytes())
                    peakJava = maxOf(peakJava, javaBytes())
                    peakThreads = maxOf(peakThreads, threads())
                    if (tick % 20 == 0) peakPss = maxOf(peakPss, pssBytes())
                }
                tick++
                Thread.sleep(50)
            }
        }
    }

    fun stop(): JSONObject {
        val wall = System.nanoTime() - wallNs
        val cpu = Process.getElapsedCpuTime() - cpuMs
        running.set(false)
        sampler?.join()
        val gc = JSONObject()
            .put("count", stat("art.gc.gc-count") - gcCount)
            .put("blocking", stat("art.gc.blocking-gc-count") - blockingGc)
            .put("timeMs", stat("art.gc.gc-time") - gcTimeMs)
            .put("allocMB", (stat("art.gc.bytes-allocated") - allocated).mb())
        return synchronized(this) {
            JSONObject()
                .put("idle", idle)
                .put("peak", JSONObject()
                    .put("nativeMB", peakNative.mb())
                    .put("rssMB", peakRss.mb())
                    .put("pssMB", peakPss.mb())
                    .put("javaMB", peakJava.mb())
                    .put("threads", peakThreads))
                .put("gc", gc)
                .put("cpuCores", (cpu * 1e6 / wall).round())
        }
    }

    private fun Long.mb(): Double = (this / 1048576.0).round()

    private fun stat(name: String): Long = Debug.getRuntimeStat(name)?.toLongOrNull() ?: -1

    private fun javaBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun pssBytes(): Long = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss * 1024L

    private fun threads(): Int = File("/proc/self/task").list()?.size ?: 0

    private fun rssBytes(): Long =
        File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong() * 1024
}

/** The phone's thermal state now: status (0 is none) and the headroom forecast 10 s ahead (1.0 is throttling). */
internal fun thermal(): JSONObject {
    val power = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(PowerManager::class.java)
    return JSONObject().put("status", power.currentThermalStatus).put("headroom", power.getThermalHeadroom(10).toDouble().round())
}

/**
 * A full-screen activity of the test APK's own, kept on and at the display's highest refresh rate at its
 * current resolution (the S25 adapts its rate; this asks for 120 Hz throughout), showing Compose
 * content or a View.
 */
internal class TimingHost : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val activity: ComponentActivity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as ComponentActivity
    private var content: @Composable () -> Unit by mutableStateOf({})

    /** Whether [showView] replaced the Compose content; touched on the main thread. */
    private var viewShown = false

    init {
        onMain {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val display = checkNotNull(activity.display)
            val current = display.mode
            val fastest = display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxBy { it.refreshRate }
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = fastest.modeId }
            composeContent()
        }
    }

    /** Shows [composable] full screen on black. */
    fun show(composable: @Composable () -> Unit) = onMain {
        content = composable
        if (viewShown) composeContent()
        viewShown = false
    }

    /** Shows [view] full screen in place of Compose content. */
    fun showView(view: View) = onMain {
        activity.setContentView(view)
        viewShown = true
    }

    /** The display's mode and refresh rate now. */
    fun display(): JSONObject = onMain {
        val display = checkNotNull(activity.display)
        JSONObject()
            .put("modeId", display.mode.modeId)
            .put("hz", display.refreshRate.toDouble().round())
            .put("size", "${display.mode.physicalWidth}x${display.mode.physicalHeight}")
    }

    private fun composeContent() {
        activity.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
        }
    }

    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    override fun close() {
        onMain { activity.finish() }
    }
}

/**
 * A Surface standing in for a display: a SurfaceTexture, which takes MediaCodec's buffers as a
 * SurfaceView does, latched on its own GL thread as each buffer arrives. For each it keeps the
 * buffer's timestamp (MediaCodec stamps a buffer with the time it was released for, so for the native
 * player the moment it rendered it, and for Media3 the vsync-aligned time it scheduled) and the frame
 * number the clip draws in its top strip (see scripts/generate-playback-clips.sh), read back from a
 * small pbuffer. A buffer replaced before the GL thread latched it is not seen, as a compositor would
 * not show it either.
 */
internal class FrameCodeReader : AutoCloseable {
    /** A buffer the reader latched: the [number] drawn in it and its [timestampNs]. */
    class Latched(val number: Int, val timestampNs: Long)

    private val thread = HandlerThread("PlaybackTiming Surface").apply { start() }
    private val handler = Handler(thread.looper)
    private val latched = ArrayList<Latched>()

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
    private val pixels = ByteBuffer.allocateDirect(WIDTH * STRIP * 4).order(ByteOrder.nativeOrder())
    private val quad = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .put(QUAD).apply { position(0) }

    val surface: Surface = onGlThread {
        setUpGl()
        Surface(texture)
    }

    /** The buffers latched so far, and forgets them. */
    fun take(): List<Latched> = synchronized(latched) { latched.toList().also { latched.clear() } }

    override fun close() {
        onGlThread {
            texture.release()
            GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
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
            display, config, intArrayOf(EGL14.EGL_WIDTH, WIDTH, EGL14.EGL_HEIGHT, HEIGHT, EGL14.EGL_NONE), 0,
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
            setDefaultBufferSize(WIDTH, HEIGHT)
            setOnFrameAvailableListener({ read() }, handler)
        }
    }

    /** Latches the next buffer, draws it into the pbuffer and reads the frame number back from the strip. */
    private fun read() {
        try {
            texture.updateTexImage()
            val timestamp = texture.timestamp
            texture.getTransformMatrix(matrix)
            GLES20.glViewport(0, 0, WIDTH, HEIGHT)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "textureMatrix"), 1, false, matrix, 0)
            val position = GLES20.glGetAttribLocation(program, "position")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            pixels.clear()
            // The strip is the picture's top twelfth: GL's rows run bottom up, so its last rows.
            GLES20.glReadPixels(0, HEIGHT - STRIP, WIDTH, STRIP, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val row = STRIP / 2
            val number = (0 until 16).sumOf { bit ->
                val x = bit * WIDTH / 16 + WIDTH / 32
                if ((pixels.get((row * WIDTH + x) * 4 + 1).toInt() and 0xff) > 128) 1 shl bit else 0
            }
            synchronized(latched) { latched += Latched(number, timestamp) }
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
        const val WIDTH = 512
        const val HEIGHT = 192

        /** The rows of the pbuffer the strip covers: a twelfth of its height. */
        const val STRIP = HEIGHT / 12
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
