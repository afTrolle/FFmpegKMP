// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import android.os.PowerManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * 1, 2 and 4 software decoders of a 4K HEVC PQ clip at once, at `Auto`, `Fixed(1)`, `Fixed(2)` and
 * `Fixed(4)`, in full size and at 960×540: frames a second, peak threads and peak resident memory,
 * logged under [TAG]. It measures rather than checks, and takes about ten minutes, so it runs only
 * when asked, after `scripts/generate-budget-clip.sh` has made its clip:
 *
 * ```
 * ./gradlew :library:codec:connectedAndroidDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.aftrolle.ffmpegkmp.codec.ParallelDecoderBudgetDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.parallelBudget=true
 * ```
 *
 * `counts` (such as `4`) and `rounds` (2 by default) narrow or repeat it.
 */
class ParallelDecoderBudgetDeviceTest {
    @Test
    fun measure() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Runs only with the parallelBudget=true instrumentation argument", arguments.getString("parallelBudget") == "true")
        // JavaCPP's default limit, twice the heap, stops two 4K decoders; the measurement lifts it.
        System.setProperty("org.bytedeco.javacpp.maxPhysicalBytes", "0")
        System.setProperty("org.bytedeco.javacpp.maxBytes", "0")
        val resource = javaClass.getResourceAsStream("/budget/hevc-2160p-pq-4s.mp4")
        assumeTrue("Generate the clip first with scripts/generate-budget-clip.sh", resource != null)
        val clip = File.createTempFile("budget", ".mp4")
        checkNotNull(resource).use { input -> clip.outputStream().use { input.copyTo(it) } }
        Log.i(TAG, "cores ${Runtime.getRuntime().availableProcessors()}, idle threads ${threads()}")
        val counts = (arguments.getString("counts") ?: "1,2,4").split(',').map { it.toInt() }
        val rounds = arguments.getString("rounds")?.toInt() ?: 2
        val settings = listOf(DecoderThreads.Auto, DecoderThreads.Fixed(1), DecoderThreads.Fixed(2), DecoderThreads.Fixed(4))
        for (round in 1..rounds) {
            for (size in listOf(null, FrameSize(960, 540))) {
                for (count in counts) {
                    // Each round starts at another setting, so a phone warming up costs each of them alike.
                    for (index in settings.indices) {
                        coolDown()
                        measure(clip, size, count, settings[(index + round - 1) % settings.size], round)
                    }
                }
            }
        }
        clip.delete()
    }

    private suspend fun measure(clip: File, size: FrameSize?, count: Int, decoderThreads: DecoderThreads, round: Int) {
        val peak = AtomicInteger(threads())
        val peakRss = AtomicInteger(rssMegabytes())
        val sampling = AtomicBoolean(true)
        val sampler = thread {
            while (sampling.get()) {
                peak.accumulateAndGet(threads(), ::maxOf)
                peakRss.accumulateAndGet(rssMegabytes(), ::maxOf)
                Thread.sleep(5)
            }
        }
        val start = System.nanoTime()
        val decoded = coroutineScope {
            (1..count).map {
                async(Dispatchers.Default) {
                    VideoDecoder.open(
                        MediaSource(clip.path),
                        VideoOutput.Memory(FrameFormat.Rgba8, size),
                        DecoderPreference.SOFTWARE,
                        60.seconds,
                        decoderThreads,
                    ).use { decoder ->
                        for (index in 0 until FRAMES) decoder.frameAt(index.seconds / 24).use { }
                    }
                    FRAMES
                }
            }.awaitAll().sum()
        }
        val took = (System.nanoTime() - start) / 1e9
        sampling.set(false)
        sampler.join()
        Log.i(
            TAG,
            "round=$round size=${size ?: "full"} decoders=$count threads=$decoderThreads | " +
                "%.1f frames/s (%.1f each) | peak ${peak.get()} threads, ${peakRss.get()} MB".format(decoded / took, decoded / took / count),
        )
    }

    /** Waits, up to two minutes, until the phone reports no thermal throttling, then 30 s more, so each run starts alike. */
    private fun coolDown() {
        val power = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(PowerManager::class.java)
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
        const val FRAMES = 96
    }
}
