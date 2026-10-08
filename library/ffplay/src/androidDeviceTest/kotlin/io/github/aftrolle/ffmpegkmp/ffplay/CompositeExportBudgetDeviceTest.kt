// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aftrolle.ffmpegkmp.codec.DecoderPreference
import io.github.aftrolle.ffmpegkmp.codec.DynamicRange
import io.github.aftrolle.ffmpegkmp.codec.FrameRate
import io.github.aftrolle.ffmpegkmp.codec.MediaOutput
import io.github.aftrolle.ffmpegkmp.codec.MediaSource
import io.github.aftrolle.ffmpegkmp.codec.MediaWriter
import io.github.aftrolle.ffmpegkmp.codec.VideoCodec
import io.github.aftrolle.ffmpegkmp.codec.VideoDecoder
import io.github.aftrolle.ffmpegkmp.codec.VideoEncoderConfig
import io.github.aftrolle.ffmpegkmp.codec.VideoFrame
import io.github.aftrolle.ffmpegkmp.codec.VideoOutput
import io.github.aftrolle.ffmpegkmp.codec.VideoTrack
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Four 4K sources drawn as 2×2 tiles into a 4K HEVC export, through `frames()`, [FrameImage],
 * [ComposeFrameRenderer] and [MediaWriter], once for each [Case]: frames a second, peak resident
 * memory and threads, native heap once warm and at the end, and each frame's `update`, GPU `draw`,
 * `copy` out of the `HardwareBuffer` and `write`, with the copy's share of the frame's time, logged
 * under [TAG]. It measures rather than checks, so it runs only when asked, after
 * `scripts/generate-budget-clip.sh` has made its clips:
 *
 * ```
 * ./gradlew :library:ffplay:connectedAndroidDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.aftrolle.ffmpegkmp.ffplay.CompositeExportBudgetDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.compositeBudget=true
 * ```
 *
 * `clip` picks the sources: `pq` (the default), 10-bit HEVC PQ, or `h264`, 8-bit H.264, which
 * `GpuBuffers` keeps on the GPU, as it does no deeper source yet. The export is HDR10 for the PQ
 * clip where the phone encodes it and SDR otherwise; `range` (`SDR` or `HDR10`) picks one.
 * `cases` (such as `GpuBuffers`) narrows the cases by name, and `rounds` (1 by default) repeats
 * them.
 */
class CompositeExportBudgetDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** One way of decoding the sources. */
    private class Case(
        val name: String,
        val decoder: DecoderPreference = DecoderPreference.SOFTWARE,
        val output: (VideoTrack) -> VideoOutput,
    )

    private val cases = listOf(
        Case("Memory(canvasFormat)") { track -> VideoOutput.Memory(track.canvasFormat) },
        Case("Memory()") { VideoOutput.Memory() },
        // MediaCodec's frames, drawn from their HardwareBuffers.
        Case("GpuBuffers", DecoderPreference.AUTO) { VideoOutput.GpuBuffers },
    )

    /** Each frame's phases, in milliseconds. */
    private class Phases {
        val times = linkedMapOf<String, MutableList<Double>>()

        inline fun <T> time(phase: String, block: () -> T): T {
            val started = TimeSource.Monotonic.markNow()
            return block().also { times.getOrPut(phase) { mutableListOf() } += started.elapsedNow().inWholeMicroseconds / 1000.0 }
        }

        override fun toString(): String = times.entries.joinToString(", ") { (phase, values) ->
            val sorted = values.sorted()
            "$phase %.1f ms (p95 %.1f)".format(values.average(), sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)])
        }
    }

    @Test
    fun measure() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Runs only with the compositeBudget=true instrumentation argument", arguments.getString("compositeBudget") == "true")
        // JavaCPP's default limit, twice the heap, stops four 4K decoders; the measurement lifts it.
        System.setProperty("org.bytedeco.javacpp.maxPhysicalBytes", "0")
        System.setProperty("org.bytedeco.javacpp.maxBytes", "0")
        val pq = (arguments.getString("clip") ?: "pq") == "pq"
        val resource = javaClass.getResourceAsStream(if (pq) "/budget/hevc-2160p-pq-4s.mp4" else "/budget/h264-2160p-4s.mp4")
        assumeTrue("Generate the clip first with scripts/generate-budget-clip.sh", resource != null)
        val clip = File.createTempFile("budget", ".mp4")
        checkNotNull(resource).use { input -> clip.outputStream().use { input.copyTo(it) } }
        val hdr = VideoEncoderConfig(WIDTH, HEIGHT, FRAME_RATE, VideoCodec.HEVC, DynamicRange.HDR10)
        val range = arguments.getString("range")?.let(DynamicRange::valueOf)
            ?: if (pq && MediaWriter.canEncode(hdr)) DynamicRange.HDR10 else DynamicRange.SDR
        Log.i(TAG, "cores ${Runtime.getRuntime().availableProcessors()}, idle threads ${threads()}, ${if (pq) "PQ" else "H.264"} clip, export $range")
        val rounds = arguments.getString("rounds")?.toInt() ?: 1
        val names = arguments.getString("cases")?.split(',')
        for (round in 1..rounds) {
            for (case in cases.filter { names == null || it.name in names }) {
                coolDown()
                export(clip, hdr.copy(dynamicRange = range), case, round)
            }
        }
        clip.delete()
    }

    private suspend fun export(clip: File, config: VideoEncoderConfig, case: Case, round: Int) {
        val output = File.createTempFile("composite", ".mp4")
        val peakThreads = AtomicInteger(threads())
        val peakRss = AtomicInteger(rssMegabytes())
        val sampling = AtomicBoolean(true)
        val sampler = thread {
            while (sampling.get()) {
                peakThreads.accumulateAndGet(threads(), ::maxOf)
                peakRss.accumulateAndGet(rssMegabytes(), ::maxOf)
                Thread.sleep(5)
            }
        }
        val phases = Phases()
        var warmNative = 0L
        var warmRss = 0
        var frames = 0
        val started = TimeSource.Monotonic.markNow()
        MediaWriter.open(MediaOutput.File(output.path), timeout = 60.seconds).use { writer ->
            val track = writer.addVideoTrack(config)
            val decoders = (0 until SOURCES).map {
                VideoDecoder.open(MediaSource(clip.path), case.output(track), case.decoder, 60.seconds)
            }
            Log.i(TAG, "${case.name}: ${decoders.map { it.decoderKind }}")
            val images = decoders.map { FrameImage() }
            try {
                ComposeFrameRenderer<List<FrameImage>>(context, track) { tiles ->
                    Canvas(Modifier.fillMaxSize()) {
                        val tile = Size(size.width / 2, size.height / 2)
                        tiles.forEachIndexed { index, image ->
                            drawFrameImage(image, Offset(tile.width * (index % 2), tile.height * (index / 2)), tile)
                        }
                    }
                }.use { renderer ->
                    coroutineScope {
                        // Each channel is the one frame ahead that frames() decodes by default: it decodes the
                        // next frame while this loop works on the current one.
                        val sources: List<ReceiveChannel<VideoFrame>> =
                            decoders.map { it.frames(prefetch = 0).buffer(Channel.RENDEZVOUS).produceIn(this) }
                        while (true) {
                            val received = sources.map { it.receiveCatching() }
                            received.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
                            val tick = received.map { it.getOrNull() }
                            if (tick.any { it == null }) {
                                tick.forEach { it?.close() }
                                break
                            }
                            val pts = checkNotNull(tick.first()).pts
                            phases.time("update") { tick.forEachIndexed { index, frame -> checkNotNull(frame).use(images[index]::update) } }
                            val drawn = phases.time("draw") { renderer.draw(pts, images) }
                            val rendered = phases.time("copy") { renderer.copy(pts, drawn) }
                            phases.time("write") { track.write(rendered) }
                            frames++
                            if (frames == WARM_FRAMES) {
                                warmNative = Debug.getNativeHeapAllocatedSize()
                                warmRss = rssMegabytes()
                            }
                        }
                        sources.forEach { it.cancel() }
                    }
                }
            } finally {
                images.forEach(FrameImage::close)
                decoders.forEach(VideoDecoder::close)
            }
            writer.finish()
        }
        val took = started.elapsedNow().inWholeMilliseconds / 1000.0
        sampling.set(false)
        sampler.join()
        // The encoder half of change set 12 is worth building if the copy is at least 30% of a frame's time.
        val copyShare = checkNotNull(phases.times["copy"]).average() / (took * 1000 / frames)
        Log.i(
            TAG,
            "round=$round ${case.name} ${config.dynamicRange} | %.1f frames/s over $frames | peak ${peakThreads.get()} threads, ${peakRss.get()} MB | ".format(frames / took) +
                "after $WARM_FRAMES frames $warmRss MB resident, native heap ${warmNative / MB} MB; at the end ${rssMegabytes()} MB, " +
                "${Debug.getNativeHeapAllocatedSize() / MB} MB | $phases | copy %.0f%% of a frame".format(copyShare * 100),
        )
        output.delete()
    }

    /** Waits, up to two minutes, until the phone reports no thermal throttling, then 30 s more, so each run starts alike. */
    private fun coolDown() {
        val power = context.getSystemService(PowerManager::class.java)
        var waited = 0
        while (power.currentThermalStatus != PowerManager.THERMAL_STATUS_NONE && waited < 120) {
            Thread.sleep(1000)
            waited++
        }
        Thread.sleep(30_000)
        if (waited > 0) Log.i(TAG, "cooled for $waited s, thermal status ${power.currentThermalStatus}")
    }

    private fun threads(): Int = File("/proc/self/task").list()?.size ?: 0

    private fun rssMegabytes(): Int =
        File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toInt() / 1024

    private companion object {
        const val TAG = "FFmpegKmpBudget"
        const val SOURCES = 4
        const val WIDTH = 3840
        const val HEIGHT = 2160
        const val WARM_FRAMES = 24
        const val MB = 1024 * 1024
        val FRAME_RATE = FrameRate(24)
    }
}
