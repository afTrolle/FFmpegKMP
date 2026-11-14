// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aftrolle.ffmpegkmp.codec.CodecPreference
import io.github.aftrolle.ffmpegkmp.codec.DecoderThreads
import io.github.aftrolle.ffmpegkmp.core.MediaSource
import java.io.File
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue

/**
 * Measures how the player paces video on the phone, against Media3, and what playing costs: no
 * check, only one line per scenario, logged under [TAG] as JSON. scripts/measure-playback.sh
 * generates and pushes the clips (scripts/generate-playback-clips.sh), runs this class and tables
 * the lines; later changes to the player rerun it and compare. It runs only when asked and only
 * once the clips are on the phone in [CLIPS]:
 *
 * ```
 * ./gradlew :library:ffplay:connectedAndroidDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.aftrolle.ffmpegkmp.ffplay.PlaybackTimingDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.playbackTiming=true \
 *   -Pandroid.testInstrumentationRunnerArguments.scenario=canvas-sw-1080p+surface-hw
 * ```
 *
 * `scenario` keeps the scenarios whose names start with one of its '+'-separated parts (all by
 * default); `rounds` (1 by default) runs the chosen ones that many times over, round after round.
 * Each scenario plays its clip from the start to its end (10 s) in a full-screen activity held at
 * the display's highest refresh rate, after the phone reports no thermal throttling.
 *
 * Per output, "released" and "shown" are:
 * - `canvas-*`: [FFplaySurface] on the Compose Canvas. Released when the player hands the frame to
 *   the output (the engine's refresh presents the frame once it is due, then the output converts
 *   it into a bitmap); shown at the Choreographer frame time (the vsync) of the first UI frame whose
 *   drawing of the surface showed it, seen through the test-only [canvasFrameProbe]. That frame
 *   reaches the glass one or two refreshes later, through the render thread and the compositor.
 * - `surface-hw`: the player's native Surface output ([AndroidSurfaceOutput], as `FFplaySurface`
 *   wires it) on a [FrameCodeReader] instead of a SurfaceView, so each buffer can be identified.
 *   Released at the buffer's timestamp, which the native player's refresh sets to the moment it
 *   renders it (`av_mediacodec_render_buffer_at_time(now)`); shown at the first display refresh at or after
 *   it, where a compositor latching on vsync would show it.
 * - `media3-reader`: Media3's ExoPlayer on the same reader, measured the same way. Media3 releases
 *   each buffer ahead, stamped with the vsync it is meant for less 80% of a refresh.
 * - `media3-surfaceview`, `media3-compose`: ExoPlayer on a SurfaceView, and on a Compose
 *   [AndroidExternalSurface], from its [VideoFrameMetadataListener]: released at the release time it
 *   gives a frame, shown at the first refresh at or after it. The frame shown is ExoPlayer's word for it.
 *
 * See [timing] for the figures. Memory, collections and CPU are the process's ([ResourceSampler]):
 * MediaCodec decodes in the media server, so a hardware decoder's memory and CPU are not in them.
 */
class PlaybackTimingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    private enum class Clip(val key: String, val file: String, val width: Int, val height: Int) {
        P1080("1080p", "h264-1080p30.mp4", 1920, 1080),
        MP13("13mp", "hevc-13mp30.mp4", 3240, 4050),
        K8("8k", "hevc-8k30.mp4", 7680, 4320),
        HLG("hlg", "hevc-2160p30-hlg.mp4", 3840, 2160),
    }

    private class Scenario(val name: String, val run: suspend (TimingHost) -> JSONObject)

    /** Each clip on each output of the player, software and AUTO on the Canvas, and through Media3. */
    @Test
    fun timing() = measure(
        Clip.entries.flatMap { clip ->
            listOf(
                Scenario("canvas-sw-${clip.key}") { canvas(it, listOf(clip), CodecPreference.SOFTWARE, DecoderThreads.Auto) },
                Scenario("canvas-auto-${clip.key}") { canvas(it, listOf(clip), CodecPreference.AUTO, DecoderThreads.Auto) },
                Scenario("surface-hw-${clip.key}") { surface(it, clip) },
                Scenario("media3-reader-${clip.key}") { media3Reader(it, clip) },
                Scenario("media3-surfaceview-${clip.key}") { media3Surface(it, clip, compose = false) },
                Scenario("media3-compose-${clip.key}") { media3Surface(it, clip, compose = true) },
            )
        },
    )

    /** Software decoding on the Canvas at 1, 2, 3, 4 and 8 decoder threads, for the two large clips. */
    @Test
    fun threadSweep() = measure(
        listOf(Clip.MP13, Clip.K8).flatMap { clip ->
            listOf(1, 2, 3, 4, 8).map { threads ->
                Scenario("threads-${clip.key}-$threads") {
                    canvas(it, listOf(clip), CodecPreference.SOFTWARE, DecoderThreads.Fixed(threads))
                }
            }
        },
    )

    /**
     * Four players at once on one screen, each a quarter of it, on the Canvas. On Android a Canvas output
     * never gets a hardware decoder (MediaCodec needs a Surface), so AUTO decodes in software too.
     */
    @Test
    fun fourPlayers() = measure(
        listOf(Clip.P1080, Clip.MP13).map { clip ->
            Scenario("four-canvas-sw-${clip.key}") { canvas(it, List(4) { clip }, CodecPreference.SOFTWARE, DecoderThreads.Auto) }
        },
    )

    private fun measure(scenarios: List<Scenario>) = runBlocking {
        assumeTrue("Runs only with the playbackTiming=true instrumentation argument", arguments.getString("playbackTiming") == "true")
        // AGP splits an instrumentation argument on commas before it reaches the runner, so names are joined with '+'.
        val filters = arguments.getString("scenario")?.split('+')?.filter(String::isNotBlank)
        val chosen = scenarios.filter { scenario -> filters == null || filters.any { scenario.name.startsWith(it) } }
        assumeTrue("No scenario of this test matches ${arguments.getString("scenario")}", chosen.isNotEmpty())
        assumeTrue("Push the clips with scripts/measure-playback.sh prepare", Clip.entries.all { remote(it) != null })
        // JavaCPP's default limit, twice the Java heap, is far below what 8K software decoding holds.
        System.setProperty("org.bytedeco.javacpp.maxPhysicalBytes", "0")
        System.setProperty("org.bytedeco.javacpp.maxBytes", "0")
        val rounds = arguments.getString("rounds")?.toInt() ?: 1
        TimingHost().use { host ->
            for (round in 1..rounds) {
                for (scenario in chosen) {
                    coolDown()
                    val line = try {
                        scenario.run(host)
                    } catch (failure: Throwable) {
                        Log.w(TAG, "${scenario.name} failed", failure)
                        JSONObject().put("error", "${failure::class.simpleName}: ${failure.message}")
                    } finally {
                        canvasFrameProbe = null
                        host.show {}
                    }
                    val json = JSONObject().put("scenario", scenario.name).put("round", round)
                    line.keys().forEach { json.put(it, line.get(it)) }
                    Log.i(TAG, json.toString())
                }
            }
        }
    }

    /**
     * [clips] on the Canvas through [FFplaySurface], one player each, in a grid of equal tiles. With one clip the
     * figures are its player's; with several, the timing distributions pool every player's frames and `fps`
     * lists each player's.
     */
    private suspend fun canvas(host: TimingHost, clips: List<Clip>, decoder: CodecPreference, threads: DecoderThreads): JSONObject {
        val players = clips.map {
            FFplayPlayer(
                FFplayConfiguration(
                    decoder = decoder,
                    outputPreference = FFplayOutputPreference.COMPOSE_CANVAS,
                    audio = false,
                    threads = threads,
                ),
            )
        }
        val probe = CanvasRecorder()
        canvasFrameProbe = probe
        try {
            players.zip(clips).forEach { (player, clip) ->
                withTimeout(30.seconds) { player.prepare(MediaSource(local(clip).path)) }
            }
            host.show {
                if (players.size == 1) {
                    FFplaySurface(players.single(), Modifier.fillMaxSize())
                } else {
                    val columns = 2
                    Column(Modifier.fillMaxSize()) {
                        players.chunked(columns).forEach { row ->
                            Row(Modifier.weight(1f)) {
                                row.forEach { player -> FFplaySurface(player, Modifier.weight(1f).fillMaxSize()) }
                            }
                        }
                    }
                }
            }
            players.forEach { player -> player.awaitState("ready", FFplayState.READY) }
            // The previews drawn, and the layout settled.
            delay(1000)
            val vsync = VsyncGrid()
            val sampler = ResourceSampler()
            val thermalBefore = thermal()
            vsync.start()
            sampler.start()
            probe.vsync = vsync
            probe.arm()
            players.forEach(FFplayPlayer::play)
            players.forEach { player -> player.awaitState("the end", FFplayState.ENDED, 15.seconds) }
            // The last frame's drawing.
            delay(200)
            probe.disarm()
            val resources = sampler.stop()
            vsync.stop()
            val period = vsync.periodNs
            return timing(players.map(probe::frames), FRAME_US, period)
                .put("output", "canvas")
                .put("player", "ffplay")
                .put("players", players.size)
                .put("clip", clips.first().key)
                .put("decoder", JSONArray(players.map { it.snapshot.value.output?.decoder?.name }))
                .put("threads", (threads as? DecoderThreads.Fixed)?.count?.toString() ?: "auto")
                .put("dropped", players.sumOf { it.snapshot.value.droppedFrames })
                .put("unmatchedDraws", probe.unclocked)
                .put("resources", resources)
                .put("display", host.display().put("vsyncMs", (period / 1e6).round()))
                .put("thermal", JSONObject().put("before", thermalBefore).put("after", thermal()))
        } finally {
            canvasFrameProbe = null
            host.show {}
            withContext(Dispatchers.IO) { players.forEach(FFplayPlayer::close) }
        }
    }

    /** The native player's Surface output on a [FrameCodeReader], hardware decoding where the phone has a decoder. */
    private suspend fun surface(host: TimingHost, clip: Clip): JSONObject {
        val reader = FrameCodeReader()
        val player = FFplayPlayer(FFplayConfiguration(decoder = CodecPreference.AUTO, audio = false))
        val output = AndroidSurfaceOutput(ContentScale.Fit, 0)
        try {
            withTimeout(30.seconds) { player.prepare(MediaSource(local(clip).path)) }
            withContext(Dispatchers.IO) {
                output.updateVideoInfo(player.snapshot.value.video)
                output.attachSurface(reader.surface, clip.width, clip.height)
                player.attachOutput(output)
            }
            player.awaitState("ready", FFplayState.READY)
            delay(1000)
            return playOnReader(reader, host, play = { player.play() }, ended = { player.awaitState("the end", FFplayState.ENDED, 15.seconds) })
                .put("output", "surface")
                .put("player", "ffplay")
                .put("clip", clip.key)
                .put("decoder", player.snapshot.value.output?.decoder?.name)
                .put("dropped", player.snapshot.value.droppedFrames)
        } finally {
            withContext(Dispatchers.IO) {
                player.detachOutput(output)
                output.detachSurface()
                player.close()
            }
            reader.close()
        }
    }

    /** ExoPlayer on a [FrameCodeReader], measured as [surface] measures the native player. */
    private suspend fun media3Reader(host: TimingHost, clip: Clip): JSONObject {
        val reader = FrameCodeReader()
        val file = local(clip)
        val exo = host.onMain { exoPlayer(file).apply { setVideoSurface(reader.surface) } }
        try {
            exo.awaitReady(host)
            delay(1000)
            return playOnReader(reader, host, play = { host.onMain { exo.play() } }, ended = { exo.awaitEnded(host) })
                .put("output", "surface")
                .put("player", "media3")
                .put("clip", clip.key)
                .put("decoder", host.onMain { exo.videoDecoderName() })
                .put("dropped", host.onMain { exo.videoDecoderCounters?.droppedBufferCount ?: -1 })
        } finally {
            host.onMain { exo.release() }
            reader.close()
        }
    }

    /** Plays through [play] until [ended] returns, with the resources and the display sampled, and times what [reader] latched. */
    private suspend fun playOnReader(reader: FrameCodeReader, host: TimingHost, play: suspend () -> Unit, ended: suspend () -> Unit): JSONObject {
        val vsync = VsyncGrid()
        val sampler = ResourceSampler()
        val thermalBefore = thermal()
        reader.take()
        vsync.start()
        sampler.start()
        play()
        ended()
        delay(200)
        val resources = sampler.stop()
        vsync.stop()
        reader.failure?.let { throw AssertionError("reading the Surface", it) }
        val latched = reader.take()
        val frames = latched.map { TimedFrame(ptsUs = it.number * 1_000_000L / 30, releasedNs = it.timestampNs, shownNs = vsync.at(it.timestampNs)) }
        val period = vsync.periodNs
        // Frames the reader never saw between the first and the last it did: dropped by the player, or replaced before it latched them.
        val numbers = latched.map { it.number }.toSet()
        val missing = if (latched.isEmpty()) 0 else (latched.first().number..latched.last().number).count { it !in numbers }
        return timing(listOf(frames), FRAME_US, period)
            .put("notLatched", missing)
            .put("outOfOrder", latched.zipWithNext().count { (a, b) -> b.number <= a.number })
            .put("resources", resources)
            .put("display", host.display().put("vsyncMs", (period / 1e6).round()))
            .put("thermal", JSONObject().put("before", thermalBefore).put("after", thermal()))
    }

    /** ExoPlayer on a SurfaceView, or on a Compose [AndroidExternalSurface], timed by its own frame metadata. */
    private suspend fun media3Surface(host: TimingHost, clip: Clip, compose: Boolean): JSONObject {
        val released = ArrayList<Pair<Long, Long>>()
        val listener = VideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
            synchronized(released) { released += presentationTimeUs to releaseTimeNs }
        }
        val file = local(clip)
        val exo = host.onMain { exoPlayer(file).apply { setVideoFrameMetadataListener(listener) } }
        try {
            if (compose) {
                host.show {
                    AndroidExternalSurface(Modifier.fillMaxSize()) {
                        onSurface { surface, _, _ ->
                            exo.setVideoSurface(surface)
                            surface.onDestroyed { exo.clearVideoSurface(this) }
                        }
                    }
                }
            } else {
                val view = host.onMain { SurfaceView(host.activity) }
                host.showView(view)
                host.onMain { exo.setVideoSurfaceView(view) }
            }
            exo.awaitReady(host)
            delay(1000)
            val vsync = VsyncGrid()
            val sampler = ResourceSampler()
            val thermalBefore = thermal()
            synchronized(released) { released.clear() }
            vsync.start()
            sampler.start()
            host.onMain { exo.play() }
            exo.awaitEnded(host)
            delay(200)
            val resources = sampler.stop()
            vsync.stop()
            val period = vsync.periodNs
            val frames = synchronized(released) { released.toList() }
                .map { (pts, release) -> TimedFrame(ptsUs = pts, releasedNs = release, shownNs = vsync.at(release)) }
            return timing(listOf(frames), FRAME_US, period)
                .put("output", if (compose) "compose-surface" else "surfaceview")
                .put("player", "media3")
                .put("clip", clip.key)
                .put("decoder", host.onMain { exo.videoDecoderName() })
                .put("dropped", host.onMain { exo.videoDecoderCounters?.droppedBufferCount ?: -1 })
                .put("resources", resources)
                .put("display", host.display().put("vsyncMs", (period / 1e6).round()))
                .put("thermal", JSONObject().put("before", thermalBefore).put("after", thermal()))
        } finally {
            host.onMain {
                exo.clearVideoFrameMetadataListener(listener)
                exo.release()
            }
            host.show {}
        }
    }

    /** An ExoPlayer for [file] on the main looper (call it there), prepared and paused at the start. */
    private fun exoPlayer(file: File): ExoPlayer = ExoPlayer.Builder(context).build().apply {
        val player = this
        addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                synchronized(decoderNames) { decoderNames[player] = decoderName }
            }
        })
        setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        volume = 0f
        playWhenReady = false
        prepare()
    }

    private val decoderNames = IdentityHashMap<ExoPlayer, String>()

    /** The video decoder ExoPlayer initialised, as its analytics report it; null before one is. */
    private fun ExoPlayer.videoDecoderName(): String? = synchronized(decoderNames) { decoderNames[this] }

    private suspend fun ExoPlayer.awaitReady(host: TimingHost) = awaitPlaybackState(host, "ready", Player.STATE_READY)

    private suspend fun ExoPlayer.awaitEnded(host: TimingHost) = awaitPlaybackState(host, "the end", Player.STATE_ENDED, 15.seconds)

    private suspend fun ExoPlayer.awaitPlaybackState(host: TimingHost, what: String, state: Int, timeout: Duration = 30.seconds) {
        withTimeoutOrNull(timeout) {
            while (true) {
                val (now, error) = host.onMain { playbackState to playerError }
                if (error != null) throw AssertionError("Media3 failed waiting for $what", error)
                if (now == state) break
                delay(20)
            }
        } ?: throw AssertionError("Media3 did not reach $what within $timeout")
    }

    private suspend fun FFplayPlayer.awaitState(what: String, state: FFplayState, timeout: Duration = 30.seconds) {
        withTimeoutOrNull(timeout) {
            snapshot.first { snapshot ->
                if (snapshot.state == FFplayState.FAILED) throw AssertionError("The player failed waiting for $what: ${snapshot.failure}")
                snapshot.state == state
            }
        } ?: throw AssertionError("Timed out after $timeout waiting for $what; the player is at ${snapshot.value}")
    }

    /**
     * Each Canvas player's frames as its output received them and the display frame its surface first
     * drew each in, between [arm] and [disarm]. The output reports the [FrameImage.Shown] each frame
     * produced, and each drawing the one it drew; that pairs a drawing with its frame.
     */
    private class CanvasRecorder : CanvasFrameProbe {
        private class Record {
            val frames = ArrayList<TimedFrame>()
            val byShown = IdentityHashMap<FrameImage.Shown, TimedFrame>()
            var pending: TimedFrame? = null

            /** What the surface drew last before [arm]: playback may start by handing the output that frame again. */
            var before: FrameImage.Shown? = null
        }

        private val records = IdentityHashMap<FFplayPlayer, Record>()

        @Volatile
        private var armed = false

        /** The refreshes of the run, whose latest is the frame a drawing belongs to; set before [arm]. */
        @Volatile
        var vsync: VsyncGrid? = null

        /** First drawings of a frame whose display frame could not be read. */
        var unclocked = 0
            private set

        fun arm() {
            synchronized(this) { records.values.forEach { it.frames.clear(); it.byShown.clear(); it.pending = null } }
            armed = true
        }

        fun disarm() {
            armed = false
        }

        fun frames(player: FFplayPlayer): List<TimedFrame> = synchronized(this) { records[player]?.frames?.toList().orEmpty() }

        override fun submitting(player: FFplayPlayer, pts: Duration) {
            if (!armed) return
            val now = System.nanoTime()
            synchronized(this) { records.getOrPut(player, ::Record).pending = TimedFrame(pts.inWholeMicroseconds, now) }
        }

        override fun submitted(player: FFplayPlayer, pts: Duration, shown: FrameImage.Shown?) {
            if (!armed) return
            val now = System.nanoTime()
            synchronized(this) {
                val record = records[player] ?: return
                val frame = record.pending?.takeIf { it.ptsUs == pts.inWholeMicroseconds } ?: return
                record.pending = null
                frame.readyNs = now
                // The preview on screen handed over again: drawn before the run, it is not part of it.
                if (shown != null && shown === record.before) return
                record.frames += frame
                if (shown != null) record.byShown[shown] = frame
            }
        }

        override fun drawn(player: FFplayPlayer, shown: FrameImage.Shown?) {
            if (!armed) {
                synchronized(this) { records.getOrPut(player, ::Record).before = shown }
                return
            }
            if (shown == null) return
            val frameTime = vsync?.latest?.takeIf { it > 0 }
            synchronized(this) {
                val frame = records[player]?.byShown?.get(shown) ?: return
                if (frame.shownNs != null) return
                if (frameTime == null) unclocked++ else frame.shownNs = frameTime
            }
        }
    }

    /** Where the clip lies on the phone, pushed by scripts/measure-playback.sh; null when it is not there. */
    private fun remote(clip: Clip): String? {
        val path = "$CLIPS/${clip.file}"
        val size = shell("stat -c %s $path").trim().toLongOrNull() ?: return null
        return path.takeIf { size > 0 }
    }

    /**
     * The clip copied into the app's cache, where the player and Media3 can open it (an app cannot read
     * /data/local/tmp itself); copied by the shell once per install.
     */
    private fun local(clip: Clip): File {
        val file = File(context.cacheDir, "playback/${clip.file}")
        val size = shell("stat -c %s $CLIPS/${clip.file}").trim().toLongOrNull()
        if (file.length() != size) {
            file.parentFile?.mkdirs()
            val input = instrumentation.uiAutomation.executeShellCommand("cat $CLIPS/${clip.file}")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(input).use { stream ->
                file.outputStream().use { stream.copyTo(it, 1 shl 20) }
            }
        }
        return file
    }

    private fun shell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
    }

    /** Waits until the phone reports no thermal throttling (up to two minutes), then lets it settle 3 s. */
    private suspend fun coolDown() {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        var waited = 0
        while (power.currentThermalStatus != android.os.PowerManager.THERMAL_STATUS_NONE && waited < 120) {
            delay(1000)
            waited++
        }
        if (waited > 0) Log.i(TAG, "cooled for $waited s, thermal status ${power.currentThermalStatus}")
        System.gc()
        delay(3000)
    }

    private companion object {
        const val TAG = "PLAYBACK_TIMING"
        const val CLIPS = "/data/local/tmp/ffmpegkmp-playback"

        /** Every clip is 30 fps. */
        const val FRAME_US = 33_333L
    }
}
