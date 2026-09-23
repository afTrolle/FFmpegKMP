// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.long

/**
 * The prepared source's audio in the browser. The player worker decodes it with the same
 * ffmpegkmp_player engine as other platforms and streams PCM straight to an AudioWorklet over a
 * MessageChannel; this page side owns the AudioContext, the master gain and the reported clock.
 *
 * Positions are counted per seek epoch: the worklet reports frames played since the last seek,
 * and reports from an older epoch are ignored.
 */
internal class BrowserPlayerAudio private constructor(
    private val worker: BrowserPlayerWorker,
    private val graph: JsAny,
    private val sampleRate: Int,
    info: String,
    /** Where video is now, to rejoin it once autoplay is allowed. */
    private val videoPositionMicros: () -> Long,
) : NativePlayerAudio {
    private val parsed = Json.parseToJsonElement(info).jsonObject
    override val durationMicros: Long = parsed.getValue("durationUs").jsonPrimitive.long
    override val tracks: List<NativeAudioTrackInfo>
    private val enabled: BooleanArray

    init {
        val entries = parsed.getValue("tracks").jsonArray.map { it.jsonObject }
        tracks = entries.mapIndexed { index, track -> track.toTrackInfo(index) }
        enabled = BooleanArray(entries.size) { entries[it].getValue("enabled").jsonPrimitive.boolean }
    }

    private var listener: (NativeAudioProgress) -> Unit = {}
    private var epoch = 0
    private var epochStartMicros = 0L
    private var playing = false
    private var blocked = false
    private var closed = false
    private var progress = NativeAudioProgress(positionMicros = 0)

    override fun isTrackEnabled(track: Int): Boolean = enabled.getOrElse(track) { false }

    override fun setTrackEnabled(track: Int, enabled: Boolean) {
        require(track in tracks.indices) { "No audio track $track" }
        if (closed) return
        this.enabled[track] = enabled
        worker.post(audioTrackEnabledMessage(track, enabled), emptyTransfers())
    }

    override fun setTrackGain(track: Int, gain: Float) {
        require(track in tracks.indices) { "No audio track $track" }
        if (!closed) worker.post(audioTrackGainMessage(track, gain.toDouble()), emptyTransfers())
    }

    // On the page, so volume and mute are heard at once rather than after the decoded-ahead audio.
    override fun setMasterGain(gain: Float) {
        if (!closed) setAudioGraphGain(graph, gain.toDouble())
    }

    override fun play() {
        if (closed || playing) return
        playing = true
        playAudioGraph(graph)
        if (!audioGraphMayStart(graph)) {
            blocked = true
            publish(progress.copy(blockedByAutoplay = true))
        }
    }

    override fun pause() {
        if (closed) return
        playing = false
        pauseAudioGraph(graph)
    }

    override fun seek(positionMicros: Long) {
        require(positionMicros >= 0) { "Seek position must not be negative" }
        if (closed) return
        epoch++
        epochStartMicros = positionMicros
        worker.post(audioSeekMessage(positionMicros.toDouble(), epoch), emptyTransfers())
        publish(NativeAudioProgress(positionMicros, blockedByAutoplay = blocked))
    }

    override fun setProgressListener(listener: (NativeAudioProgress) -> Unit) {
        this.listener = listener
    }

    override fun close() {
        if (closed) return
        closed = true
        worker.post(audioCloseMessage(), emptyTransfers())
        closeAudioGraph(graph)
    }

    /** The worker can no longer serve this audio; only the page side is left to release. */
    fun fail(message: String) {
        if (closed) return
        publish(progress.copy(failure = message))
        closed = true
        closeAudioGraph(graph)
    }

    private fun onPlayed(reportEpoch: Int, frames: Double, drained: Boolean) {
        if (closed || reportEpoch != epoch) return
        val played = epochStartMicros + (frames * 1_000_000.0 / sampleRate).toLong()
        val audible = played - (audioGraphLatencySeconds(graph) * 1_000_000.0).toLong()
        publish(NativeAudioProgress(maxOf(epochStartMicros, audible), ended = drained))
    }

    private fun onRunning(running: Boolean) {
        if (closed || !running || !blocked) return
        blocked = false
        // Video kept going while the browser held audio back: pick up where it is now.
        seek(videoPositionMicros())
    }

    private fun publish(next: NativeAudioProgress) {
        progress = next
        listener(next)
    }

    companion object {
        /** Builds the page's audio graph, then has the worker open the source's audio into it. */
        suspend fun open(
            worker: BrowserPlayerWorker,
            workerOpened: CompletableDeferred<String>,
            videoPositionMicros: () -> Long,
        ): BrowserPlayerAudio {
            var audio: BrowserPlayerAudio? = null
            val graph = createAudioGraph(
                channels = CHANNELS,
                onPlayed = { epoch, frames, drained -> audio?.onPlayed(epoch, frames, drained) },
                onRunning = { running -> audio?.onRunning(running) },
            )
            try {
                awaitAudioGraph(graph)
                val sampleRate = audioGraphSampleRate(graph)
                worker.post(audioOpenMessage(graph, sampleRate, CHANNELS), audioOpenTransfers(graph))
                val info = workerOpened.await()
                return BrowserPlayerAudio(worker, graph, sampleRate, info, videoPositionMicros)
                    .also { audio = it }
            } catch (failure: Throwable) {
                closeAudioGraph(graph)
                throw failure
            }
        }

        private const val CHANNELS = 2
    }
}

private fun JsonObject.toTrackInfo(index: Int) = NativeAudioTrackInfo(
    index = index,
    codec = getValue("codec").jsonPrimitive.content,
    language = get("language")?.jsonPrimitive?.contentOrNull,
    title = get("title")?.jsonPrimitive?.contentOrNull,
    channels = getValue("channels").jsonPrimitive.int,
    sampleRate = getValue("sampleRate").jsonPrimitive.int,
    isDefault = getValue("isDefault").jsonPrimitive.boolean,
    isDecodable = getValue("isDecodable").jsonPrimitive.boolean,
)

private suspend fun awaitAudioGraph(graph: JsAny) = suspendCancellableCoroutine { continuation ->
    onAudioGraphReady(graph) { error ->
        if (error.isEmpty()) continuation.resume(Unit)
        else continuation.resumeWithException(IllegalStateException("Browser audio is unavailable: $error"))
    }
}

/**
 * Plays `pcm` chunks from the worker port, dropping those from an older seek epoch, and reports
 * frames played in the current epoch to both the worker (flow control) and the page (the clock).
 */
private const val WORKLET_SOURCE = """
class FFmpegKmpAudioProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.channels = options.processorOptions.channels;
    this.queue = [];
    this.offset = 0;
    this.epoch = 0;
    this.played = 0;
    this.ended = false;
    this.playing = false;
    this.closed = false;
    this.source = null;
    this.reportedAt = 0;
    this.port.onmessage = ({ data }) => {
      if (data.type === 'source') {
        this.source = data.port;
        this.source.onmessage = event => this.receive(event.data);
      } else if (data.type === 'play') {
        this.playing = true;
      } else if (data.type === 'pause') {
        this.playing = false;
        this.report();
      } else if (data.type === 'close') {
        this.closed = true;
        if (this.source) this.source.close();
      }
    };
  }

  receive(message) {
    if (message.type === 'flush') {
      this.epoch = message.epoch;
      this.queue = [];
      this.offset = 0;
      this.played = 0;
      this.ended = false;
      this.report();
    } else if (message.epoch !== this.epoch) {
      return;
    } else if (message.type === 'pcm') {
      this.queue.push(message.samples);
    } else if (message.type === 'end') {
      this.ended = true;
    }
  }

  process(inputs, outputs) {
    if (this.closed) return false;
    const output = outputs[0];
    const frames = output[0].length;
    let written = 0;
    if (this.playing) {
      while (written < frames && this.queue.length > 0) {
        const chunk = this.queue[0];
        const count = Math.min(chunk.length / this.channels - this.offset, frames - written);
        for (let channel = 0; channel < output.length; channel++) {
          const target = output[channel];
          const from = Math.min(channel, this.channels - 1);
          for (let frame = 0; frame < count; frame++) {
            target[written + frame] = chunk[(this.offset + frame) * this.channels + from];
          }
        }
        written += count;
        this.offset += count;
        if (this.offset * this.channels >= chunk.length) {
          this.queue.shift();
          this.offset = 0;
        }
      }
      this.played += written;
    }
    for (const target of output) target.fill(0, written);
    if (currentTime - this.reportedAt >= 0.02) this.report();
    return true;
  }

  report() {
    this.reportedAt = currentTime;
    const drained = this.ended && this.queue.length === 0;
    if (this.source) this.source.postMessage({ type: 'played', epoch: this.epoch, frames: this.played });
    this.port.postMessage({ epoch: this.epoch, frames: this.played, drained });
  }
}
registerProcessor('ffmpegkmp-audio', FFmpegKmpAudioProcessor);
"""

private fun createAudioGraph(
    channels: Int,
    onPlayed: (Int, Double, Boolean) -> Unit,
    onRunning: (Boolean) -> Unit,
): JsAny = createAudioGraphWith(WORKLET_SOURCE, channels, onPlayed, onRunning)

private fun createAudioGraphWith(
    source: String,
    channels: Int,
    onPlayed: (Int, Double, Boolean) -> Unit,
    onRunning: (Boolean) -> Unit,
): JsAny = js(
    """
    (() => {
      const context = new AudioContext({ latencyHint: 'playback' });
      const gain = context.createGain();
      gain.connect(context.destination);
      const graph = { context, gain, node: null, workerPort: null, closed: false, ready: null };
      context.onstatechange = () => onRunning(context.state === 'running');
      const url = URL.createObjectURL(new Blob([source], { type: 'text/javascript' }));
      graph.ready = context.audioWorklet.addModule(url).then(() => {
        URL.revokeObjectURL(url);
        if (graph.closed) throw new Error('closed');
        const node = new AudioWorkletNode(context, 'ffmpegkmp-audio', {
          numberOfInputs: 0,
          outputChannelCount: [channels],
          processorOptions: { channels },
        });
        node.connect(gain);
        const channel = new MessageChannel();
        node.port.postMessage({ type: 'source', port: channel.port2 }, [channel.port2]);
        node.port.onmessage = ({ data }) => onPlayed(data.epoch, data.frames, data.drained);
        graph.node = node;
        graph.workerPort = channel.port1;
      });
      return graph;
    })()
    """,
)

private fun onAudioGraphReady(graph: JsAny, done: (String) -> Unit): Unit =
    js("graph.ready.then(() => done(''), error => done(String(error && error.message || error)))")

private fun audioGraphSampleRate(graph: JsAny): Int = js("graph.context.sampleRate")

/** False when the context can only start after a user gesture on this page. */
private fun audioGraphMayStart(graph: JsAny): Boolean =
    js(
        "graph.context.state === 'running' || !!(globalThis.navigator && " +
            "globalThis.navigator.userActivation && globalThis.navigator.userActivation.hasBeenActive)",
    )

private fun audioGraphLatencySeconds(graph: JsAny): Double =
    js("(graph.context.outputLatency || 0) + (graph.context.baseLatency || 0)")

private fun setAudioGraphGain(graph: JsAny, gain: Double): Unit = js("graph.gain.gain.value = gain")

/** Browsers keep an AudioContext suspended until the page has had a user gesture. */
private fun playAudioGraph(graph: JsAny): Unit = js(
    """
    {
      graph.node.port.postMessage({ type: 'play' });
      if (graph.context.state !== 'running') {
        graph.context.resume().catch(() => {});
        const resume = () => { if (!graph.closed) graph.context.resume().catch(() => {}); };
        for (const type of ['pointerdown', 'keydown', 'touchend']) {
          if (globalThis.addEventListener) globalThis.addEventListener(type, resume, { once: true, capture: true });
        }
      }
    }
    """,
)

private fun pauseAudioGraph(graph: JsAny): Unit = js("graph.node.port.postMessage({ type: 'pause' })")

private fun closeAudioGraph(graph: JsAny): Unit = js(
    """
    {
      if (graph.closed) return;
      graph.closed = true;
      if (graph.node) {
        graph.node.port.postMessage({ type: 'close' });
        graph.node.disconnect();
      }
      if (graph.workerPort) graph.workerPort.close();
      graph.context.close().catch(() => {});
    }
    """,
)

private fun audioOpenMessage(graph: JsAny, sampleRate: Int, channels: Int): JsAny =
    js("({ type: 'player-audio-open', sampleRate, channels, port: graph.workerPort })")

private fun audioOpenTransfers(graph: JsAny): JsAny = js("[graph.workerPort]")

private fun audioSeekMessage(positionUs: Double, epoch: Int): JsAny =
    js("({ type: 'player-audio-seek', positionUs, epoch })")

private fun audioTrackEnabledMessage(track: Int, enabled: Boolean): JsAny =
    js("({ type: 'player-audio-track-enabled', track, enabled })")

private fun audioTrackGainMessage(track: Int, gain: Double): JsAny =
    js("({ type: 'player-audio-track-gain', track, gain })")

private fun audioCloseMessage(): JsAny = js("({ type: 'player-audio-close' })")

internal fun masterClockMessage(positionUs: Double): JsAny =
    js("({ type: 'player-master-clock', positionUs })")

internal fun emptyTransfers(): JsAny = js("[]")
