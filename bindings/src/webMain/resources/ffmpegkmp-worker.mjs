// SPDX-License-Identifier: Apache-2.0
// Kept separate from Kotlin/Wasm because Kotlin/Wasm cannot consume cinterop klibs.

let modulePromise;
let activeContext = 0;
let activeId = null;
let nativeRuntimeUnwound = false;
const nativeDiagnostics = [];
let playerHandle = 0;
let playerStateCallback = 0;
let playerFrameCallback = 0;
let playerPollTimer = 0;
let playerDecoderPreference = 0;
let playerDecoderConfigCallback = 0;
let playerPacketCallback = 0;
let webCodecsDecoder = null;
let webCodecsConfig = null;
let webCodecsActive = false;
let webCodecsPlaying = false;
let webCodecsPreviewing = false;
let webCodecsNeedsResumeSeek = false;
let webCodecsPumping = false;
let webCodecsEof = false;
let webCodecsClockOriginMs = 0;
let webCodecsPositionUs = 0;
let webCodecsQueueSerial = 0;
let webCodecsPresentationId = 1;
let webCodecsOutputFlags = 0;
const webCodecsPresentations = new Map();
let playerMessageTail = Promise.resolve();

// Audio of the prepared source, decoded here on demand and played by the page's AudioWorklet,
// which it reaches directly through `port`. Chunks carry the seek epoch they belong to.
const AUDIO_CHUNK_FRAMES = 2048;
const AUDIO_BUFFER_SECONDS = 0.25;
const audio = {
  handle: 0,
  port: null,
  pcm: 0,
  timer: 0,
  channels: 0,
  sampleRate: 0,
  epoch: 0,
  decoded: 0,
  played: 0,
  ended: false,
};

function audioFailure(message) {
  self.postMessage({ type: 'player-audio', event: 'failure', payload: message });
}

function closeAudio(module) {
  if (!audio.handle) return;
  clearInterval(audio.timer);
  module._ffmpegkmp_player_close(audio.handle);
  module._free(audio.pcm);
  audio.port.close();
  Object.assign(audio, { handle: 0, port: null, pcm: 0, timer: 0 });
}

/** Keeps about AUDIO_BUFFER_SECONDS decoded ahead of what the worklet has played. */
function pumpAudio(module) {
  if (!audio.handle || audio.ended) return;
  const target = audio.sampleRate * AUDIO_BUFFER_SECONDS;
  while (audio.decoded - audio.played < target) {
    const frames = module._ffmpegkmp_player_read(audio.handle, audio.pcm, AUDIO_CHUNK_FRAMES);
    if (frames < 0) {
      audioFailure(`Audio decoding failed (${frames})`);
      closeAudio(module);
      return;
    }
    if (frames === 0) {
      audio.ended = true;
      audio.port.postMessage({ type: 'end', epoch: audio.epoch });
      return;
    }
    // slice() copies out of the shared Wasm heap into a buffer that can be transferred.
    const samples = new Float32Array(module.HEAPU8.buffer, audio.pcm, frames * audio.channels).slice();
    audio.port.postMessage({ type: 'pcm', epoch: audio.epoch, samples }, [samples.buffer]);
    audio.decoded += frames;
  }
}

function openAudio(module, data) {
  closeAudio(module);
  const errorPointer = module._malloc(4);
  let handle;
  let error;
  try {
    handle = module._ffplaykmp_web_player_open_audio(
      playerHandle,
      data.sampleRate,
      data.channels,
      errorPointer,
    );
    error = new Int32Array(module.HEAPU8.buffer, errorPointer, 1)[0];
  } finally {
    module._free(errorPointer);
  }
  if (!handle) {
    data.port.close();
    audioFailure(`Could not open the source's audio (${error})`);
    return;
  }
  const text = pointer => (pointer ? decodeCString(module.HEAPU8, pointer) : null);
  const tracks = [];
  const count = module._ffmpegkmp_player_track_count(handle);
  for (let track = 0; track < count; track++) {
    tracks.push({
      codec: text(module._ffmpegkmp_player_track_codec(handle, track)) || '',
      language: text(module._ffmpegkmp_player_track_language(handle, track)),
      title: text(module._ffmpegkmp_player_track_title(handle, track)),
      channels: module._ffmpegkmp_player_track_channels(handle, track),
      sampleRate: module._ffmpegkmp_player_track_sample_rate(handle, track),
      isDefault: module._ffmpegkmp_player_track_is_default(handle, track) === 1,
      isDecodable: module._ffmpegkmp_player_track_is_decodable(handle, track) === 1,
      enabled: module._ffmpegkmp_player_track_enabled(handle, track) === 1,
    });
  }
  Object.assign(audio, {
    handle,
    port: data.port,
    pcm: module._malloc(AUDIO_CHUNK_FRAMES * data.channels * 4),
    channels: data.channels,
    sampleRate: data.sampleRate,
    epoch: 0,
    decoded: 0,
    played: 0,
    ended: false,
  });
  audio.port.onmessage = ({ data: report }) => {
    if (report.type === 'played' && report.epoch === audio.epoch) {
      audio.played = report.frames;
      pumpAudio(module);
    }
  };
  audio.timer = setInterval(() => pumpAudio(module), 20);
  self.postMessage({
    type: 'player-audio',
    event: 'opened',
    payload: JSON.stringify({
      durationUs: Number(module._ffmpegkmp_player_duration_us(handle)),
      tracks,
    }),
  });
  pumpAudio(module);
}

function handleAudioMessage(module, data) {
  if (data.type === 'player-audio-open') {
    openAudio(module, data);
    return;
  }
  if (!audio.handle) return;
  let result = 0;
  if (data.type === 'player-audio-seek') {
    result = module._ffmpegkmp_player_seek(audio.handle, BigInt(data.positionUs));
    Object.assign(audio, { epoch: data.epoch, decoded: 0, played: 0, ended: false });
    audio.port.postMessage({ type: 'flush', epoch: audio.epoch });
    pumpAudio(module);
  } else if (data.type === 'player-audio-track-enabled') {
    result = module._ffmpegkmp_player_set_track_enabled(audio.handle, data.track, data.enabled ? 1 : 0);
  } else if (data.type === 'player-audio-track-gain') {
    result = module._ffmpegkmp_player_set_track_gain(audio.handle, data.track, data.gain);
  } else if (data.type === 'player-audio-close') {
    closeAudio(module);
  }
  if (result < 0) audioFailure(`${data.type} failed (${result})`);
}

// TextDecoder rejects views backed by the FFmpeg pthread SharedArrayBuffer.
// Decode directly from that heap so event text crosses the worker boundary as a string.
function decodeUtf8(heap, start, length) {
  const end = start + length;
  let index = start;
  let text = '';
  while (index < end) {
    const first = heap[index++];
    if ((first & 0x80) === 0) {
      text += String.fromCharCode(first);
      continue;
    }
    if ((first & 0xe0) === 0xc0 && index < end) {
      text += String.fromCharCode(((first & 0x1f) << 6) | (heap[index++] & 0x3f));
      continue;
    }
    if ((first & 0xf0) === 0xe0 && index + 1 < end) {
      text += String.fromCharCode(
        ((first & 0x0f) << 12) | ((heap[index++] & 0x3f) << 6) | (heap[index++] & 0x3f),
      );
      continue;
    }
    if ((first & 0xf8) === 0xf0 && index + 2 < end) {
      let codePoint =
        ((first & 0x07) << 18) |
        ((heap[index++] & 0x3f) << 12) |
        ((heap[index++] & 0x3f) << 6) |
        (heap[index++] & 0x3f);
      codePoint -= 0x10000;
      text += String.fromCharCode(0xd800 | (codePoint >> 10), 0xdc00 | (codePoint & 0x3ff));
      continue;
    }
    text += '\ufffd';
  }
  return text;
}

function decodeCString(heap, start) {
  let length = 0;
  while (heap[start + length] !== 0) length++;
  return decodeUtf8(heap, start, length);
}

function webColorSpace(primaries, transfer, matrix) {
  const result = {};
  const primary = ({ 1: 'bt709', 5: 'bt470bg', 6: 'smpte170m', 9: 'bt2020' })[primaries];
  const transferName = ({
    1: 'bt709', 6: 'smpte170m', 13: 'iec61966-2-1', 16: 'pq', 18: 'hlg',
  })[transfer];
  const matrixName = ({
    0: 'rgb', 1: 'bt709', 5: 'bt470bg', 6: 'smpte170m', 9: 'bt2020-ncl',
  })[matrix];
  if (primary) result.primaries = primary;
  if (transferName) result.transfer = transferName;
  if (matrixName) result.matrix = matrixName;
  return Object.keys(result).length ? result : undefined;
}

function clearWebCodecsPresentations() {
  for (const { timer, frame } of webCodecsPresentations.values()) {
    clearTimeout(timer);
    frame.close();
  }
  webCodecsPresentations.clear();
}

function resetWebCodecs(module, closeDecoder = false) {
  webCodecsPlaying = false;
  webCodecsPreviewing = false;
  webCodecsNeedsResumeSeek = false;
  webCodecsPumping = false;
  webCodecsEof = false;
  clearWebCodecsPresentations();
  if (webCodecsDecoder) {
    try {
      if (closeDecoder) webCodecsDecoder.close();
      else webCodecsDecoder.reset();
    } catch (_) {
      // Decoder reclamation or an earlier error may already have closed it.
    }
  }
  if (closeDecoder) {
    webCodecsDecoder = null;
    webCodecsConfig = null;
    webCodecsActive = false;
    webCodecsOutputFlags = 0;
  }
  if (playerHandle) module._ffplaykmp_web_player_close_packets(playerHandle);
}

function finishWebCodecsIfDrained(module) {
  if (webCodecsEof && webCodecsPresentations.size === 0 && playerHandle) {
    module._ffplaykmp_web_player_webcodecs_end(playerHandle);
    module._ffplaykmp_web_player_poll(playerHandle);
  }
}

function scheduleWebCodecsFrame(module, frame) {
  if (!webCodecsActive || !playerHandle) {
    frame.close();
    return;
  }
  if (!webCodecsPlaying && !webCodecsPreviewing) {
    frame.close();
    return;
  }
  const presentationTimeUs = Number(frame.timestamp || 0);
  const delay = webCodecsPlaying
    ? Math.max(0, presentationTimeUs / 1000 - (performance.now() - webCodecsClockOriginMs))
    : 0;
  const id = webCodecsPresentationId++;
  const timer = setTimeout(() => {
    const pending = webCodecsPresentations.get(id);
    if (!pending) return;
    webCodecsPresentations.delete(id);
    webCodecsPositionUs = presentationTimeUs;
    if (webCodecsPreviewing) webCodecsNeedsResumeSeek = true;
    webCodecsPreviewing = false;
    module._ffplaykmp_web_player_webcodecs_presented(
      playerHandle,
      BigInt(presentationTimeUs),
      webCodecsQueueSerial,
      0,
    );
    self.postMessage({
      type: 'player-video-frame',
      frame,
      width: frame.displayWidth || frame.codedWidth,
      height: frame.displayHeight || frame.codedHeight,
      presentationTimeUs,
      queueSerial: webCodecsQueueSerial,
    }, [frame]);
    module._ffplaykmp_web_player_poll(playerHandle);
    finishWebCodecsIfDrained(module);
  }, Math.min(delay, 2147483647));
  webCodecsPresentations.set(id, { timer, frame });
}

function pumpWebCodecs(module, previewOnly = false) {
  if (!webCodecsActive || !webCodecsDecoder || webCodecsPumping || webCodecsEof) return;
  webCodecsPumping = true;
  try {
    let submitted = 0;
    while (webCodecsDecoder.decodeQueueSize < 8 && submitted < (previewOnly ? 4 : 16)) {
      const result = module._ffplaykmp_web_player_read_packet(
        playerHandle,
        playerPacketCallback,
        0,
      );
      if (result === 1) {
        webCodecsEof = true;
        webCodecsDecoder.flush().then(() => finishWebCodecsIfDrained(module)).catch(error => {
          fallbackFromWebCodecs(module, error);
        });
        break;
      }
      if (result < 0) {
        fallbackFromWebCodecs(module, new Error(`FFmpeg packet demux failed (${result})`));
        break;
      }
      submitted++;
    }
  } finally {
    webCodecsPumping = false;
  }
}

function fallbackFromWebCodecs(module, error) {
  if (!webCodecsActive) return;
  const message = `FFmpegKMP WebCodecs fallback: ${String(error?.stack || error)}`;
  console.warn(message);
  self.postMessage({ type: 'player-diagnostic', message });
  const wasPlaying = webCodecsPlaying;
  const fallbackOutputFlags = webCodecsOutputFlags;
  resetWebCodecs(module, true);
  const result = module._ffplaykmp_web_player_set_output(
    playerHandle,
    2 | (fallbackOutputFlags & 16),
  );
  if (result < 0) {
    self.postMessage({ type: 'player-failure', message: String(error?.stack || error) });
    return;
  }
  if (wasPlaying) module._ffplaykmp_player_play(playerHandle);
  module._ffplaykmp_web_player_poll(playerHandle);
}

async function configureWebCodecs(module, outputFlags) {
  if (typeof VideoDecoder !== 'function' || typeof EncodedVideoChunk !== 'function') {
    self.postMessage({
      type: 'player-diagnostic',
      message: 'FFmpegKMP WebCodecs unavailable: VideoDecoder or EncodedVideoChunk is missing',
    });
    return false;
  }
  let config = null;
  const result = module._ffplaykmp_web_player_open_packets(
    playerHandle,
    playerDecoderConfigCallback,
    0,
  );
  if (result < 0) {
    self.postMessage({
      type: 'player-diagnostic',
      message: `FFmpegKMP WebCodecs packet setup failed (${result})`,
    });
    return false;
  }
  config = webCodecsConfig;
  if (!config) {
    module._ffplaykmp_web_player_close_packets(playerHandle);
    self.postMessage({
      type: 'player-diagnostic',
      message: 'FFmpegKMP WebCodecs packet setup returned no decoder configuration',
    });
    return false;
  }
  let support;
  try {
    support = await VideoDecoder.isConfigSupported(config);
    if (!support.supported && config.hardwareAcceleration === 'prefer-hardware') {
      // `prefer-hardware` is a strict preference on some implementations (notably
      // headless or virtualized browsers). WebCodecs itself is still the preferred
      // decoded-frame path, so retry without requiring an available hardware backend.
      const portableConfig = { ...config, hardwareAcceleration: 'no-preference' };
      support = await VideoDecoder.isConfigSupported(portableConfig);
    }
  } catch (error) {
    module._ffplaykmp_web_player_close_packets(playerHandle);
    self.postMessage({
      type: 'player-diagnostic',
      message: `FFmpegKMP WebCodecs configuration probe failed: ${String(error?.stack || error)}`,
    });
    return false;
  }
  if (!support.supported) {
    module._ffplaykmp_web_player_close_packets(playerHandle);
    self.postMessage({
      type: 'player-diagnostic',
      message: `FFmpegKMP WebCodecs rejected decoder configuration ${JSON.stringify(config)}`,
    });
    return false;
  }
  webCodecsConfig = support.config;
  webCodecsOutputFlags = outputFlags;
  webCodecsDecoder = new VideoDecoder({
    output: frame => scheduleWebCodecsFrame(module, frame),
    error: error => fallbackFromWebCodecs(module, error),
  });
  webCodecsDecoder.ondequeue = () => {
    if (webCodecsPlaying) pumpWebCodecs(module);
  };
  webCodecsDecoder.configure(webCodecsConfig);
  webCodecsActive = true;
  webCodecsPositionUs = 0;
  webCodecsQueueSerial = 0;
  webCodecsNeedsResumeSeek = false;
  const outputResult = module._ffplaykmp_web_player_set_webcodecs_output(
    playerHandle,
    outputFlags,
  );
  if (outputResult < 0) {
    resetWebCodecs(module, true);
    self.postMessage({
      type: 'player-diagnostic',
      message: `FFmpegKMP WebCodecs output negotiation failed (${outputResult})`,
    });
    return false;
  }
  module._ffplaykmp_web_player_poll(playerHandle);
  webCodecsPreviewing = true;
  pumpWebCodecs(module, true);
  return true;
}

function installPthreadFailureForwarding(module) {
  for (const worker of module.PThread?.unusedWorkers ?? []) {
    worker.onerror = event => {
      const location = event.filename
        ? ` (${event.filename}:${event.lineno || 0}:${event.colno || 0})`
        : '';
      const fallback = event.error?.stack || event.message || 'An FFmpeg pthread crashed';
      event.preventDefault?.();
      // Messages written by the pthread immediately before the ErrorEvent are
      // queued separately. Give their proxied printErr calls one event-loop
      // turn to arrive so the caller receives the native stack, not a wrapper.
      setTimeout(() => {
        const diagnostics = nativeDiagnostics.slice(-20);
        if (!diagnostics.some(line => line.includes(fallback))) diagnostics.push(fallback);
        const detail = diagnostics.join('\n');
        if (activeId !== null) {
          self.postMessage({
            type: 'failure',
            id: activeId,
            message: `${detail}${location}`,
          });
          activeId = null;
        } else if (playerHandle) {
          self.postMessage({
            type: 'player-failure',
            message: `${detail}${location}`,
          });
        }
      }, 0);
    };
  }
}

function loadModule(moduleUrl) {
  if (!modulePromise) {
    modulePromise = import(moduleUrl).then(async ({ default: createModule }) => {
      const module = await createModule({
        printErr: line => {
          nativeDiagnostics.push(String(line));
          if (nativeDiagnostics.length > 100) nativeDiagnostics.shift();
          console.error(line);
        },
      });
      installPthreadFailureForwarding(module);
      return module;
    });
  }
  return modulePromise;
}

function allocateArguments(module, arguments_) {
  const strings = arguments_.map(value => module.stringToNewUTF8(value));
  const argv = module._malloc(strings.length * 4);
  strings.forEach((pointer, index) => module.setValue(argv + index * 4, pointer, '*'));
  return { argv, strings };
}

// Player commands run one at a time, in arrival order: each awaits the module and native calls.
async function handlePlayerMessage(data) {
  let module;
  try {
    module = await loadModule(data.moduleUrl || './ffmpegkmp.mjs');
    if (data.type === 'player-init') {
      if (playerHandle) throw new Error('The browser player is already initialized');
      playerDecoderPreference = data.decoderPreference;
      playerStateCallback = module.addFunction((opaque, json, size) => {
        self.postMessage({
          type: 'player-snapshot',
          snapshot: decodeUtf8(module.HEAPU8, json, Number(size)),
        });
      }, 'viii');
      playerFrameCallback = module.addFunction(
        (opaque, rgba, size, width, height, stride, presentationTimeUs, queueSerial) => {
          const copied = module.HEAPU8.slice(rgba, rgba + Number(size));
          self.postMessage({
            type: 'player-frame',
            bytes: copied,
            width,
            height,
            stride,
            presentationTimeUs: Number(presentationTimeUs),
            queueSerial,
          }, [copied.buffer]);
        },
        'viiiiiiji',
      );
      playerDecoderConfigCallback = module.addFunction(
        (opaque, codec, description, descriptionSize, width, height, primaries, transfer, matrix) => {
          const config = {
            codec: decodeCString(module.HEAPU8, codec),
            codedWidth: width,
            codedHeight: height,
            hardwareAcceleration: 'prefer-hardware',
            optimizeForLatency: true,
          };
          if (descriptionSize > 0) {
            config.description = module.HEAPU8.slice(
              description,
              description + Number(descriptionSize),
            );
          }
          const colorSpace = webColorSpace(primaries, transfer, matrix);
          if (colorSpace) config.colorSpace = colorSpace;
          webCodecsConfig = config;
        },
        'viiiiiiiii',
      );
      playerPacketCallback = module.addFunction(
        (opaque, bytes, size, timestampUs, durationUs, keyFrame, queueSerial, pts, duration) => {
          webCodecsQueueSerial = queueSerial;
          const copied = module.HEAPU8.slice(bytes, bytes + Number(size));
          const init = {
            type: keyFrame ? 'key' : 'delta',
            timestamp: Number(timestampUs),
            data: copied,
          };
          if (Number(durationUs) > 0) init.duration = Number(durationUs);
          webCodecsDecoder.decode(new EncodedVideoChunk(init));
        },
        'viiijjiijj',
      );
      playerHandle = module._ffplaykmp_web_player_create(
        data.decoderPreference,
        // The software decoder's threads; 0, FFmpeg's automatic count, when a caller sends none.
        data.decoderThreads ?? 0,
        playerStateCallback,
        playerFrameCallback,
        0,
      );
      if (!playerHandle) throw new Error('FFmpegKMP browser player allocation failed');
      playerPollTimer = setInterval(() => {
        if (playerHandle) module._ffplaykmp_web_player_poll(playerHandle);
      }, 8);
      self.postMessage({ type: 'player-ready' });
      return;
    }
    if (!playerHandle) throw new Error('The browser player is not initialized');
    if (data.type.startsWith('player-audio-')) {
      handleAudioMessage(module, data);
      return;
    }
    if (data.type === 'player-master-clock') {
      // Keep both video paths on the audible position; each extrapolates between reports.
      if (webCodecsActive) {
        if (webCodecsPlaying) webCodecsClockOriginMs = performance.now() - data.positionUs / 1000;
      } else {
        module._ffplaykmp_player_set_master_clock(playerHandle, BigInt(data.positionUs));
      }
      return;
    }
    let result = 0;
    if (data.type === 'player-prepare') {
      // The audio reads the input this replaces.
      closeAudio(module);
      resetWebCodecs(module, true);
      const mount = (data.mounts || []).find(candidate => candidate.path === data.input);
      if (!mount?.bytes?.length) {
        throw new Error('Browser playback requires a non-empty mounted input');
      }
      const extension = mount.path.match(/\.([A-Za-z0-9]+)$/)?.[1] || '';
      const extensionPointer = module.stringToNewUTF8(extension);
      const inputPointer = module._malloc(mount.bytes.length);
      try {
        module.HEAPU8.set(mount.bytes, inputPointer);
        result = module._ffplaykmp_web_player_prepare_bytes(
          playerHandle,
          inputPointer,
          mount.bytes.length,
          extensionPointer,
          data.requireSecurePath ? 1 : 0,
        );
      } finally {
        module._free(inputPointer);
        module._free(extensionPointer);
      }
    } else if (data.type === 'player-set-output') {
      const canTryWebCodecs = playerDecoderPreference !== 2 && (data.flags & 1) !== 0;
      if (canTryWebCodecs && await configureWebCodecs(module, data.flags)) {
        result = 0;
      } else {
        result = module._ffplaykmp_web_player_set_output(playerHandle, data.flags & ~1);
      }
    } else if (data.type === 'player-clear-output') {
      resetWebCodecs(module, true);
      module._ffplaykmp_player_clear_output(playerHandle);
    } else if (data.type === 'player-play') {
      if (webCodecsActive) {
        if (webCodecsNeedsResumeSeek) {
          clearWebCodecsPresentations();
          webCodecsDecoder.reset();
          webCodecsDecoder.configure(webCodecsConfig);
          webCodecsEof = false;
          result = module._ffplaykmp_web_player_webcodecs_seek(
            playerHandle,
            BigInt(webCodecsPositionUs),
          );
          webCodecsNeedsResumeSeek = false;
        }
        webCodecsPlaying = true;
        webCodecsPreviewing = false;
        webCodecsEof = false;
        webCodecsClockOriginMs = performance.now() - webCodecsPositionUs / 1000;
        if (result >= 0) result = module._ffplaykmp_web_player_webcodecs_play(playerHandle);
        pumpWebCodecs(module);
      } else {
        result = module._ffplaykmp_player_play(playerHandle);
      }
    } else if (data.type === 'player-pause') {
      if (webCodecsActive) {
        webCodecsPlaying = false;
        webCodecsPreviewing = false;
        clearWebCodecsPresentations();
        webCodecsDecoder.reset();
        webCodecsDecoder.configure(webCodecsConfig);
        webCodecsEof = false;
        result = module._ffplaykmp_web_player_webcodecs_pause(playerHandle);
        if (result >= 0) {
          result = module._ffplaykmp_web_player_webcodecs_seek(
            playerHandle,
            BigInt(webCodecsPositionUs),
          );
        }
        webCodecsNeedsResumeSeek = false;
      } else {
        result = module._ffplaykmp_player_pause(playerHandle);
      }
    } else if (data.type === 'player-seek') {
      if (webCodecsActive) {
        clearWebCodecsPresentations();
        webCodecsDecoder.reset();
        webCodecsDecoder.configure(webCodecsConfig);
        webCodecsEof = false;
        webCodecsPositionUs = Number(data.positionUs);
        webCodecsNeedsResumeSeek = false;
        result = module._ffplaykmp_web_player_webcodecs_seek(
          playerHandle,
          BigInt(data.positionUs),
        );
        webCodecsClockOriginMs = performance.now() - webCodecsPositionUs / 1000;
        webCodecsPreviewing = !webCodecsPlaying;
        pumpWebCodecs(module, !webCodecsPlaying);
      } else {
        result = module._ffplaykmp_player_seek(playerHandle, BigInt(data.positionUs));
      }
    } else if (data.type === 'player-stop') {
      closeAudio(module);
      resetWebCodecs(module, true);
      result = module._ffplaykmp_player_stop(playerHandle);
    }
  } catch (error) {
    // Emscripten throws this sentinel on the owning worker while transferring
    // control to a pthread. The pthread callback or its forwarded ErrorEvent
    // supplies the actual completion/failure signal.
    if (error !== 'unwind') {
      self.postMessage({
        type: 'player-failure',
        message: String(error?.stack ?? error),
      });
    }
  }
}

// The video stream of an input, demuxed by the player's packet reader: prepared without an output,
// so the player never decodes or plays. `onPacket` receives each packet read.
function openPacketReader(module, bytes, extension, onPacket) {
  const reader = { handle: 0, snapshot: null, config: null, callbacks: [] };
  reader.callbacks = [
    module.addFunction((opaque, json, size) => {
      reader.snapshot = decodeUtf8(module.HEAPU8, json, Number(size));
    }, 'viii'),
    module.addFunction(
      (opaque, codec, description, descriptionSize, width, height, primaries, transfer, matrix) => {
        const config = { codec: decodeCString(module.HEAPU8, codec), codedWidth: width, codedHeight: height };
        if (descriptionSize > 0) {
          config.description = module.HEAPU8.slice(description, description + Number(descriptionSize));
        }
        const colorSpace = webColorSpace(primaries, transfer, matrix);
        if (colorSpace) config.colorSpace = colorSpace;
        reader.config = config;
      },
      'viiiiiiiii',
    ),
    module.addFunction((opaque, bytes, size, timestampUs, durationUs, keyFrame, queueSerial, pts, duration) => {
      onPacket({
        key: keyFrame !== 0,
        timestampUs: Number(timestampUs),
        durationUs: Number(durationUs),
        pts,
        duration,
        data: module.HEAPU8.slice(bytes, bytes + Number(size)),
      });
    }, 'viiijjiijj'),
  ];
  reader.handle = module._ffplaykmp_web_player_create(0, 0, reader.callbacks[0], 0, 0);
  if (!reader.handle) throw new Error('FFmpegKMP demuxer allocation failed');
  const extensionPointer = module.stringToNewUTF8(extension || '');
  const inputPointer = module._malloc(bytes.length);
  let result;
  try {
    module.HEAPU8.set(bytes, inputPointer);
    result = module._ffplaykmp_web_player_prepare_bytes(reader.handle, inputPointer, bytes.length, extensionPointer, 0);
  } finally {
    module._free(inputPointer);
    module._free(extensionPointer);
  }
  if (result < 0) throw bridgeFailure(result, `FFmpeg could not open the input (${result})`);
  // Delivers the inspected stream's snapshot: size, rotation, aspect ratio and duration.
  module._ffplaykmp_web_player_poll(reader.handle);
  result = module._ffplaykmp_web_player_open_packets(reader.handle, reader.callbacks[1], 0);
  if (result < 0 || !reader.config) {
    throw bridgeFailure(result < 0 ? result : ERROR_UNSUPPORTED, `FFmpeg found no video stream WebCodecs can take (${result})`);
  }
  return reader;
}

// Reads the next packet into `onPacket`: false at the end of the stream.
function readPacket(module, reader) {
  const result = module._ffplaykmp_web_player_read_packet(reader.handle, reader.callbacks[2], 0);
  if (result === 1) return false;
  if (result < 0) throw bridgeFailure(result, `FFmpeg packet demux failed (${result})`);
  return true;
}

// A failure the page receives with its FFmpeg or bridge error code.
function bridgeFailure(code, message) {
  const error = new Error(message);
  error.code = code;
  return error;
}

function postFailure(type, id, error) {
  self.postMessage({
    type,
    id,
    code: typeof error?.code === 'number' ? error.code : ERROR_IO,
    message: String(error?.stack ?? error),
  });
}

const ERROR_INVALID_ARGUMENT = -1001;
const ERROR_INVALID_STATE = -1002;
const ERROR_UNSUPPORTED = -1004;
const ERROR_IO = -1005;
// AVERROR_EXIT and AVERROR_INVALIDDATA.
const ERROR_EXIT = -0x54495845;
const ERROR_INVALID_DATA = -0x41444e49;

// A pull demuxer, for a caller that decodes on its own schedule. One per worker.
const demux = { reader: null, packets: null };
let demuxMessageTail = Promise.resolve();

function openDemux(module, data) {
  if (demux.reader) throw new Error('The demuxer is already open');
  demux.reader = openPacketReader(module, data.bytes, data.extension, packet => {
    demux.packets.push({
      type: packet.key ? 'key' : 'delta',
      timestamp: packet.timestampUs,
      duration: packet.durationUs,
      data: packet.data,
    });
  });
  const { config, snapshot } = demux.reader;
  const transfers = config.description ? [config.description.buffer] : [];
  self.postMessage({ type: 'demux-opened', id: data.id, config, snapshot }, transfers);
}

function readDemux(module, data) {
  demux.packets = [];
  let end = false;
  while (demux.packets.length < data.count) {
    if (!readPacket(module, demux.reader)) {
      end = true;
      break;
    }
  }
  const packets = demux.packets;
  demux.packets = null;
  self.postMessage({ type: 'demux-packets', id: data.id, packets, end }, packets.map(packet => packet.data.buffer));
}

async function handleDemuxMessage(data) {
  try {
    const module = await loadModule(data.moduleUrl || './ffmpegkmp.mjs');
    if (data.type === 'demux-open') {
      openDemux(module, data);
      return;
    }
    if (!demux.reader) throw new Error('The demuxer is not open');
    if (data.type === 'demux-read') {
      readDemux(module, data);
    } else if (data.type === 'demux-seek') {
      // AVSEEK_FLAG_BACKWARD: the next packet is the keyframe at or before the position.
      const result = module._ffplaykmp_web_player_webcodecs_seek(demux.reader.handle, BigInt(data.positionUs));
      if (result < 0) throw new Error(`FFmpeg could not seek to ${data.positionUs} us (${result})`);
      self.postMessage({ type: 'demux-seeked', id: data.id });
    }
  } catch (error) {
    if (error !== 'unwind') {
      self.postMessage({ type: 'demux-failure', id: data.id, message: String(error?.stack ?? error) });
    }
  }
}

// A frame-accurate decoder for VideoDecoder: FFmpeg reads the packets and WebCodecs decodes them.
// Frames are counted as ffmpegkmp_decoder.c counts them, in the stream's time base: the frame
// shown at a position is the decoded one with the largest pts at or before it, held until the
// next one's. One per worker.
const NO_PTS = -(2n ** 63n);
const NANOSECONDS = [1n, 1000000000n];
const MICROSECONDS = [1n, 1000000n];
const SEEK_BACKOFF_NS = 1000000000n;
const SEEK_ATTEMPTS = 8;
// Packets in the decoder without a frame out yet; past this one more goes in only once it is quiet.
const VIDEO_AHEAD = 8;
// The formats WebCodecs names that PixelLayout has, by its ordinals.
const WEB_LAYOUTS = { RGBA: 0, RGBX: 0, BGRA: 1, BGRX: 1, NV12: 4, I420: 6, I420P10: 7 };
const video = {
  reader: null,
  decoder: null,
  config: null,
  // What the page asked for: a WebCodecs format and canvas colour space, or null for frames as decoded,
  // and the size to scale to, or null for the frame's own.
  format: null,
  colorSpace: null,
  width: null,
  height: null,
  timeBase: [1n, 1n],
  origin: 0n,
  defaultDuration: 1n,
  streamEnd: NO_PTS,
  packet: null,
  outputs: [],
  // Each chunk in the decoder's own pts and duration, by the timestamp it was given.
  times: new Map(),
  synthetic: -(2 ** 52),
  needsKey: true,
  flushing: false,
  demuxEnded: false,
  drained: false,
  current: null,
  next: null,
  firstPts: null,
  lastPts: null,
  seekFloor: null,
  awaitingSeekPacket: false,
  serial: 0,
  presentedSerial: -1,
  resync: false,
  interrupted: false,
  failure: null,
  wake: null,
};
let videoMessageTail = Promise.resolve();

// av_rescale_q with AV_ROUND_NEAR_INF: `value` from time base `from` into `to`, halfway cases away from zero.
function rescale(value, [fromNum, fromDen], [toNum, toDen]) {
  const numerator = value * fromNum * toDen;
  const denominator = fromDen * toNum;
  const quotient = numerator / denominator;
  const remainder = numerator % denominator;
  const magnitude = remainder < 0n ? -remainder : remainder;
  if (2n * magnitude >= denominator) return quotient + (numerator < 0n ? -1n : 1n);
  return quotient;
}

function wakeVideo() {
  const wake = video.wake;
  video.wake = null;
  wake?.(true);
}

// Resolves true at the decoder's next output, dequeue or error, and false if it stays quiet a moment.
function videoChange() {
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      video.wake = null;
      resolve(false);
    }, 20);
    video.wake = changed => {
      clearTimeout(timer);
      resolve(changed);
    };
  });
}

function createVideoDecoder() {
  video.decoder = new VideoDecoder({
    output: frame => {
      video.outputs.push(frame);
      wakeVideo();
    },
    error: error => {
      video.failure = bridgeFailure(ERROR_INVALID_DATA, `WebCodecs could not decode: ${error?.message ?? error}`);
      wakeVideo();
    },
  });
  video.decoder.addEventListener('dequeue', wakeVideo);
  video.decoder.configure(video.config);
  video.needsKey = true;
}

function clearVideoFrames() {
  for (const output of video.outputs) output.close();
  video.outputs = [];
  video.current?.frame.close();
  video.next?.frame.close();
  video.current = null;
  video.next = null;
  video.times.clear();
  video.drained = false;
  video.demuxEnded = false;
  video.lastPts = null;
}

// Drops what the decoder holds, as avcodec_flush_buffers does; one that failed is made anew.
function resetVideoDecoder() {
  video.failure = null;
  if (video.decoder.state === 'closed') {
    createVideoDecoder();
    return;
  }
  video.decoder.reset();
  video.decoder.configure(video.config);
  video.needsKey = true;
}

// The next frame in presentation order with its pts in stream time, or null once the stream is drained.
async function decodeNextFrame(module) {
  for (;;) {
    if (video.interrupted) throw bridgeFailure(ERROR_EXIT, 'The call was interrupted');
    if (video.failure) throw video.failure;
    const output = video.outputs.shift();
    if (output) {
      const time = video.times.get(output.timestamp);
      // Frames come out in presentation order: a chunk shown earlier gives none now.
      for (const timestamp of video.times.keys()) {
        if (timestamp <= output.timestamp) video.times.delete(timestamp);
      }
      let pts = time ? time.pts : NO_PTS;
      if (pts === NO_PTS) pts = video.lastPts === null ? video.origin : video.lastPts + video.defaultDuration;
      if ((video.lastPts !== null && pts <= video.lastPts) || (video.seekFloor !== null && pts < video.seekFloor)) {
        output.close();
        continue;
      }
      video.lastPts = pts;
      if (video.firstPts === null) video.firstPts = pts;
      return { frame: output, pts, duration: time ? time.duration : 0n };
    }
    if (video.drained) return null;
    if (video.flushing || video.decoder.decodeQueueSize > 0) {
      await videoChange();
      continue;
    }
    // A decoder that holds its frames until it has more input gets more once it has gone quiet.
    if (video.times.size >= VIDEO_AHEAD && await videoChange()) continue;
    if (video.demuxEnded) {
      video.flushing = true;
      try {
        // Resolves once every frame the decoder still holds is out.
        await video.decoder.flush();
        video.drained = true;
        video.needsKey = true;
      } catch (error) {
        video.failure ??= bridgeFailure(ERROR_INVALID_DATA, `WebCodecs could not finish decoding: ${error?.message ?? error}`);
      } finally {
        video.flushing = false;
      }
      continue;
    }
    video.packet = null;
    if (!readPacket(module, video.reader)) {
      video.demuxEnded = true;
      continue;
    }
    const packet = video.packet;
    if (video.awaitingSeekPacket) {
      video.awaitingSeekPacket = false;
      video.seekFloor = packet.pts === NO_PTS ? null : packet.pts;
    }
    // WebCodecs starts at a keyframe after each configure, reset and flush.
    if (video.needsKey && !packet.key) continue;
    video.needsKey = false;
    const timestamp = packet.pts === NO_PTS
      ? video.synthetic--
      : Number(rescale(packet.pts - video.origin, video.timeBase, MICROSECONDS));
    video.times.set(timestamp, { pts: packet.pts, duration: packet.duration });
    video.decoder.decode(new EncodedVideoChunk({ type: packet.key ? 'key' : 'delta', timestamp, data: packet.data }));
  }
}

async function primeVideo(module) {
  const current = await decodeNextFrame(module);
  if (!current) throw bridgeFailure(ERROR_INVALID_DATA, 'The video has no frame to decode from there');
  video.current = current;
  video.serial++;
  video.next = await decodeNextFrame(module);
}

async function shiftVideo(module) {
  video.current.frame.close();
  video.current = video.next;
  video.next = null;
  video.serial++;
  video.next = await decodeNextFrame(module);
}

function currentEnd() {
  const { current } = video;
  if (video.next) return video.next.pts;
  // Containers store durations in decode order; the stream end is exact for the last frame.
  if (video.streamEnd !== NO_PTS && video.streamEnd > current.pts) return video.streamEnd;
  if (current.duration > 0n) return current.pts + current.duration;
  return current.pts + video.defaultDuration;
}

function coversVideo(target) {
  const { pts } = video.current;
  if (target < pts) return pts === video.firstPts;
  return !video.next || target < currentEnd();
}

async function advanceVideo(module, target) {
  while (video.next && video.next.pts <= target) await shiftVideo(module);
}

async function seekVideo(module, target) {
  const backoff = rescale(SEEK_BACKOFF_NS, NANOSECONDS, video.timeBase);
  let seekTarget = target;
  for (let attempt = 0; attempt < SEEK_ATTEMPTS; attempt++) {
    const result = module._ffplaykmp_web_player_seek_packets(video.reader.handle, seekTarget);
    if (result < 0) throw bridgeFailure(result, `FFmpeg could not seek (${result})`);
    resetVideoDecoder();
    clearVideoFrames();
    video.seekFloor = null;
    video.awaitingSeekPacket = true;
    await primeVideo(module);
    // Some demuxers index keyframes by decode time and land after the target's GOP.
    if (video.current.pts <= target || video.current.pts <= video.firstPts || seekTarget <= video.origin) break;
    seekTarget -= backoff << BigInt(attempt);
    if (seekTarget < video.origin) seekTarget = video.origin;
  }
  await advanceVideo(module, target);
}

// Whether the index has a keyframe after the lookahead and at or before the target.
function seekIsShorter(module, target) {
  if (!video.next) return false;
  const keyframe = module._ffplaykmp_web_player_keyframe_before(video.reader.handle, target);
  return keyframe !== NO_PTS && keyframe > video.next.pts;
}

async function positionVideo(module, positionNs, seek) {
  const target = video.origin + rescale(positionNs, NANOSECONDS, video.timeBase);
  if (video.resync) {
    await seekVideo(module, target);
    video.resync = false;
    return;
  }
  if (!video.current) throw bridgeFailure(ERROR_INVALID_STATE, 'The decoder has no current frame');
  if (coversVideo(target)) return;
  if (target < video.current.pts || seek || seekIsShorter(module, target)) return seekVideo(module, target);
  return advanceVideo(module, target);
}

function describeVideo() {
  const { current } = video;
  const rect = current.frame.visibleRect;
  return {
    serial: video.serial,
    ptsNs: Number(rescale(current.pts - video.origin, video.timeBase, NANOSECONDS)),
    durationNs: Number(rescale(currentEnd() - current.pts, video.timeBase, NANOSECONDS)),
    width: video.width ?? (rect ? rect.width : current.frame.codedWidth),
    height: video.height ?? (rect ? rect.height : current.frame.codedHeight),
  };
}

function planeRows(format, plane, height) {
  const chroma = (format.startsWith('I420') || format.startsWith('NV12')) && plane > 0 && plane < 3;
  return chroma ? Math.ceil(height / 2) : height;
}

// PixelLayout's colour codes for a frame's VideoColorSpace, with the defaults FFmpeg assumes.
function frameColor(colorSpace, rgb) {
  const primaries = { bt709: 0, bt2020: 1, smpte432: 2 }[colorSpace?.primaries] ?? 0;
  const transfer = { 'iec61966-2-1': 0, bt709: 1, smpte170m: 1, linear: 2, pq: 3, hlg: 4 }[colorSpace?.transfer] ?? (rgb ? 0 : 1);
  const matrix = rgb ? 0 : ({ bt709: 1, 'bt2020-ncl': 2, bt470bg: 3, smpte170m: 3 }[colorSpace?.matrix] ?? 1);
  const fullRange = colorSpace?.fullRange ?? rgb;
  return { primaries, transfer, matrix, range: fullRange ? 1 : 0 };
}

// Browsers whose copyTo cannot convert, and sizes copyTo cannot scale to, draw the frame into a
// canvas of the colour space and size instead.
function drawVideoFrame(frame, format, colorSpace, width = frame.visibleRect.width, height = frame.visibleRect.height) {
  const context = new OffscreenCanvas(width, height).getContext('2d', { colorSpace });
  // Smoothing that widens its filter to downscale, as swscale's bilinear does.
  context.imageSmoothingQuality = 'high';
  context.drawImage(frame, 0, 0, width, height);
  const pixels = context.getImageData(0, 0, width, height, { colorSpace }).data;
  if (format === 'BGRA') {
    for (let index = 0; index < pixels.length; index += 4) {
      const red = pixels[index];
      pixels[index] = pixels[index + 2];
      pixels[index + 2] = red;
    }
  }
  return { buffer: pixels.buffer, layout: [{ offset: 0, stride: width * 4 }] };
}

// Copies the current frame once into a buffer the page takes over, converting it if asked.
async function presentVideo() {
  const { frame } = video.current;
  const format = video.format ?? frame.format ?? 'RGBA';
  const options = video.format || !frame.format ? { format, colorSpace: video.colorSpace ?? 'srgb' } : {};
  let buffer;
  let layout;
  if (video.width) {
    // A size comes with an RGB format, which the page has checked.
    ({ buffer, layout } = drawVideoFrame(frame, format, options.colorSpace, video.width, video.height));
  } else {
    try {
      buffer = new ArrayBuffer(frame.allocationSize(options));
      layout = await frame.copyTo(buffer, options);
    } catch (error) {
      if (!options.format) throw bridgeFailure(ERROR_UNSUPPORTED, `WebCodecs could not copy the ${format} frame: ${error?.message ?? error}`);
      ({ buffer, layout } = drawVideoFrame(frame, format, options.colorSpace));
    }
  }
  const rgb = WEB_LAYOUTS[format] !== undefined && WEB_LAYOUTS[format] <= 1;
  const color = options.format
    ? { primaries: options.colorSpace === 'display-p3' ? 2 : 0, transfer: 0, matrix: 0, range: 1 }
    : frameColor(frame.colorSpace, rgb);
  const height = video.height ?? (frame.visibleRect ? frame.visibleRect.height : frame.codedHeight);
  return {
    buffer,
    layout: WEB_LAYOUTS[format] ?? -1,
    ...color,
    planes: layout.map((plane, index) => ({ offset: plane.offset, rowBytes: plane.stride, rows: planeRows(format, index, height) })),
  };
}

async function openVideo(module, data) {
  if (video.reader) throw bridgeFailure(ERROR_INVALID_STATE, 'The decoder is already open');
  if (typeof VideoDecoder !== 'function') throw bridgeFailure(ERROR_UNSUPPORTED, 'This browser has no WebCodecs VideoDecoder');
  video.reader = openPacketReader(module, data.bytes, data.extension, packet => {
    video.packet = packet;
  });
  const timing = module._malloc(40);
  try {
    const result = module._ffplaykmp_web_player_packet_timing(video.reader.handle, timing);
    if (result < 0) throw bridgeFailure(result, `FFmpeg could not time the video stream (${result})`);
    const values = new BigInt64Array(module.HEAPU8.buffer, timing, 5).slice();
    video.timeBase = [values[0], values[1]];
    [, , video.origin, video.defaultDuration, video.streamEnd] = values;
  } finally {
    module._free(timing);
  }
  video.config = { ...video.reader.config, hardwareAcceleration: data.hardwareAcceleration, optimizeForLatency: true };
  const support = await VideoDecoder.isConfigSupported(video.config);
  if (!support.supported) throw bridgeFailure(ERROR_UNSUPPORTED, `This browser's WebCodecs cannot decode ${video.config.codec}`);
  video.format = data.format ?? null;
  video.colorSpace = data.colorSpace ?? null;
  video.width = data.width || null;
  video.height = data.height || null;
  createVideoDecoder();
  await primeVideo(module);
  self.postMessage({ type: 'video-opened', id: data.id, snapshot: video.reader.snapshot, codec: video.config.codec });
}

async function videoFrameAt(module, data) {
  await positionVideo(module, BigInt(data.positionNs), false);
  const reply = { type: 'video-frame', id: data.id, ...describeVideo() };
  // A frame the page already has goes without its pixels.
  if (video.presentedSerial === video.serial) {
    self.postMessage(reply);
    return;
  }
  const pixels = await presentVideo();
  video.presentedSerial = video.serial;
  self.postMessage({ ...reply, ...pixels }, [pixels.buffer]);
}

async function handleVideoMessage(data) {
  try {
    const module = await loadModule(data.moduleUrl || './ffmpegkmp.mjs');
    video.interrupted = false;
    if (data.type === 'video-open') {
      await openVideo(module, data);
      return;
    }
    if (!video.decoder) throw bridgeFailure(ERROR_INVALID_STATE, 'The decoder is not open');
    if (data.type === 'video-frame') {
      await videoFrameAt(module, data);
    } else if (data.type === 'video-seek') {
      await positionVideo(module, BigInt(data.positionNs), true);
      self.postMessage({ type: 'video-seeked', id: data.id });
    }
  } catch (error) {
    // The next call seeks to its own position first, whatever this one left behind.
    if (video.decoder) video.resync = true;
    if (error !== 'unwind') postFailure('video-failure', data.id, error);
  }
}

// Encoders and a muxer for one output, for MediaWriter: WebCodecs encodes the video tracks and
// FFmpeg's AAC encoder the audio ones, and FFmpeg's muxer writes them into memory, which finish
// hands to the page. One per worker.
// sizeof the ffmpegkmp_writer.h structs in wasm32, which their init functions store first.
const VIDEO_CONFIG_SIZE = 168;
const AUDIO_CONFIG_SIZE = 24;
const WRITER_RESULT_SIZE = 24;
// Frames a video encoder may hold before a write waits for it.
const ENCODER_QUEUE = 2;
const writer = { handle: 0, resources: null, ioCallback: 0, tracks: [], aborted: false };
let writerMessageTail = Promise.resolve();

function heapView(module) {
  return new DataView(module.HEAPU8.buffer);
}

function checkedStruct(module, init, size) {
  const pointer = module._malloc(size);
  init(pointer);
  if (heapView(module).getUint32(pointer, true) !== size) {
    module._free(pointer);
    throw new Error('ffmpegkmp_writer.h changed a struct the worker fills');
  }
  return pointer;
}

function failTrack(track, failure) {
  track.failure ??= failure;
  wakeTrack(track);
}

function wakeTrack(track) {
  const wake = track.wake;
  track.wake = null;
  wake?.();
}

function trackChange(track) {
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      track.wake = null;
      resolve();
    }, 20);
    track.wake = () => {
      clearTimeout(timer);
      resolve();
    };
  });
}

function writerTrack(index, kind) {
  const track = writer.tracks[index];
  if (!track || track.kind !== kind) throw bridgeFailure(ERROR_INVALID_ARGUMENT, `Track ${index} is not a ${kind} track`);
  if (track.failure) throw track.failure;
  return track;
}

function bytesOf(source) {
  return source instanceof ArrayBuffer
    ? new Uint8Array(source)
    : new Uint8Array(source.buffer, source.byteOffset, source.byteLength);
}

function openWriter(module, data) {
  if (writer.handle) throw bridgeFailure(ERROR_INVALID_STATE, 'The writer is already open');
  writer.resources = new Map([[1, {
    access: 'readwrite',
    bytes: new Uint8Array(0),
    size: 0,
    truncate: true,
    truncated: false,
    dirty: false,
  }]]);
  writer.ioCallback = module.addFunction(
    (opaque, resourceId, operation, offset, bytes, size) =>
      serveResource(module, writer.resources, resourceId, operation, offset, bytes, size),
    'jijijij',
  );
  const url = module.stringToNewUTF8(data.url);
  const error = module._malloc(4);
  try {
    writer.handle = module._ffmpegkmp_writer_create(
      url, data.container, data.fastStart ? 1 : 0, BigInt(data.timeoutUs), writer.ioCallback, 0, error);
    if (!writer.handle) {
      const code = heapView(module).getInt32(error, true);
      throw bridgeFailure(code, `FFmpeg could not open the output (${code})`);
    }
  } finally {
    module._free(url);
    module._free(error);
  }
  self.postMessage({ type: 'writer-opened', id: data.id });
}

// Hands an encoded chunk to the muxer, with the parameter sets the first one comes with.
function muxChunk(module, track, chunk, metadata) {
  if (track.failure || writer.aborted) return;
  const ptsNs = track.times.get(chunk.timestamp) ?? chunk.timestamp * 1000;
  track.times.delete(chunk.timestamp);
  const description = track.described ? null : metadata?.decoderConfig?.description;
  const bytes = new Uint8Array(chunk.byteLength);
  chunk.copyTo(bytes);
  const extradata = description ? bytesOf(description) : null;
  const data = module._malloc(bytes.length);
  const extradataPointer = extradata ? module._malloc(extradata.length) : 0;
  try {
    module.HEAPU8.set(bytes, data);
    if (extradata) module.HEAPU8.set(extradata, extradataPointer);
    const durationNs = chunk.duration ? chunk.duration * 1000 : track.frameDurationNs;
    const result = module._ffmpegkmp_writer_write_packet(
      writer.handle, track.index, data, bytes.length, BigInt(Math.round(ptsNs)), BigInt(Math.round(durationNs)),
      chunk.type === 'key' ? 1 : 0, extradataPointer, extradata ? extradata.length : 0);
    if (result < 0) failTrack(track, bridgeFailure(result, `FFmpeg could not mux the packet at ${ptsNs} ns (${result})`));
    track.described = true;
  } finally {
    module._free(data);
    if (extradataPointer) module._free(extradataPointer);
  }
}

async function addWriterVideoTrack(module, data) {
  if (typeof VideoEncoder !== 'function') throw bridgeFailure(ERROR_UNSUPPORTED, 'This browser has no WebCodecs VideoEncoder');
  const support = await VideoEncoder.isConfigSupported(data.encoderConfig);
  if (!support.supported) {
    throw bridgeFailure(ERROR_UNSUPPORTED, `This browser's WebCodecs cannot encode ${data.encoderConfig.codec} at ${data.width}x${data.height}`);
  }
  const config = checkedStruct(module, pointer => module._ffmpegkmp_video_encoder_config_init(pointer), VIDEO_CONFIG_SIZE);
  let index;
  try {
    const view = heapView(module);
    [data.width, data.height, data.frameRateNum, data.frameRateDen, data.codec, data.dynamicRange, data.preference]
      .forEach((value, field) => view.setInt32(config + 4 + field * 4, value, true));
    view.setBigInt64(config + 32, BigInt(data.bitRate), true);
    view.setBigInt64(config + 40, BigInt(data.keyframeIntervalUs), true);
    // bit_depth, after the HDR metadata.
    view.setInt32(config + 160, data.bitDepth, true);
    index = module._ffmpegkmp_writer_add_packet_track(writer.handle, config);
  } finally {
    module._free(config);
  }
  if (index < 0) throw bridgeFailure(index, `FFmpeg could not add the video track (${index})`);
  const track = {
    index,
    kind: 'video',
    encoder: null,
    failure: null,
    wake: null,
    times: new Map(),
    described: false,
    nextKeyNs: -Infinity,
    keyIntervalNs: data.keyframeIntervalUs * 1000,
    frameDurationNs: data.frameRateNum > 0 ? 1e9 * data.frameRateDen / data.frameRateNum : 0,
  };
  track.encoder = new VideoEncoder({
    output: (chunk, metadata) => {
      muxChunk(module, track, chunk, metadata);
      wakeTrack(track);
    },
    error: error => failTrack(track, bridgeFailure(ERROR_IO, `WebCodecs could not encode: ${error?.message ?? error}`)),
  });
  track.encoder.addEventListener('dequeue', () => wakeTrack(track));
  track.encoder.configure(data.encoderConfig);
  writer.tracks[index] = track;
  self.postMessage({ type: 'writer-track-added', id: data.id, index });
}

function addWriterAudioTrack(module, data) {
  const config = checkedStruct(module, pointer => module._ffmpegkmp_audio_encoder_config_init(pointer), AUDIO_CONFIG_SIZE);
  let index;
  try {
    const view = heapView(module);
    view.setInt32(config + 4, data.sampleRate, true);
    view.setInt32(config + 8, data.channels, true);
    view.setBigInt64(config + 16, BigInt(data.bitRate), true);
    index = module._ffmpegkmp_writer_add_audio_track(writer.handle, config);
  } finally {
    module._free(config);
  }
  if (index < 0) throw bridgeFailure(index, `FFmpeg could not add the audio track (${index})`);
  writer.tracks[index] = { index, kind: 'audio', failure: null };
  self.postMessage({ type: 'writer-track-added', id: data.id, index });
}

async function writeWriterVideo(module, data) {
  const track = writerTrack(data.track, 'video');
  // WebCodecs counts microseconds; the muxer gets each frame's own nanoseconds back.
  const timestamp = Math.round(data.ptsNs / 1000);
  track.times.set(timestamp, data.ptsNs);
  const init = {
    format: data.format,
    codedWidth: data.width,
    codedHeight: data.height,
    timestamp,
    layout: data.planes,
    colorSpace: data.colorSpace,
  };
  if (track.frameDurationNs > 0) init.duration = Math.round(track.frameDurationNs / 1000);
  const keyFrame = data.ptsNs >= track.nextKeyNs;
  if (keyFrame) track.nextKeyNs = data.ptsNs + track.keyIntervalNs;
  const frame = new VideoFrame(data.buffer, init);
  try {
    track.encoder.encode(frame, { keyFrame });
  } catch (error) {
    failTrack(track, bridgeFailure(ERROR_IO, `WebCodecs could not encode: ${error?.message ?? error}`));
  } finally {
    frame.close();
  }
  // Each write waits while the encoder holds more than a few frames, as a native encoder's does.
  while (!track.failure && !writer.aborted && track.encoder.encodeQueueSize > ENCODER_QUEUE) await trackChange(track);
  if (writer.aborted) throw bridgeFailure(ERROR_EXIT, 'The writer was aborted');
  if (track.failure) throw track.failure;
  self.postMessage({ type: 'writer-written', id: data.id });
}

function writeWriterAudio(module, data) {
  writerTrack(data.track, 'audio');
  const pointer = module._malloc(data.samples.byteLength);
  try {
    new Float32Array(module.HEAPU8.buffer, pointer, data.samples.length).set(data.samples);
    const result = module._ffmpegkmp_writer_write_audio(writer.handle, data.track, pointer, data.frames);
    if (result < 0) throw bridgeFailure(result, `FFmpeg could not encode audio (${result})`);
  } finally {
    module._free(pointer);
  }
  self.postMessage({ type: 'writer-written', id: data.id });
}

async function endWriterTrack(module, data) {
  const track = writer.tracks[data.track];
  if (track?.kind === 'video') {
    try {
      if (!track.failure) await track.encoder.flush();
    } catch (error) {
      failTrack(track, bridgeFailure(ERROR_IO, `WebCodecs could not finish encoding: ${error?.message ?? error}`));
    }
    if (track.encoder.state !== 'closed') track.encoder.close();
    if (track.failure) throw track.failure;
  }
  const result = module._ffmpegkmp_writer_end_track(writer.handle, data.track);
  if (result < 0) throw bridgeFailure(result, `FFmpeg could not finish track ${data.track} (${result})`);
  self.postMessage({ type: 'writer-track-ended', id: data.id });
}

function releaseWriterTrack(module, data) {
  const track = writer.tracks[data.track];
  if (track?.encoder && track.encoder.state !== 'closed') track.encoder.close();
  module._ffmpegkmp_writer_release_track(writer.handle, data.track);
}

function finishWriter(module, data) {
  const info = checkedStruct(module, pointer => module._ffmpegkmp_writer_result_init(pointer), WRITER_RESULT_SIZE);
  try {
    const result = module._ffmpegkmp_writer_finish(writer.handle, info);
    if (result < 0) throw bridgeFailure(result, `FFmpeg could not finish the output (${result})`);
    const view = heapView(module);
    const output = writer.resources.get(1);
    const bytes = output.bytes.slice(0, output.size);
    self.postMessage({
      type: 'writer-finished',
      id: data.id,
      size: Number(view.getBigInt64(info + 8, true)),
      durationUs: Number(view.getBigInt64(info + 16, true)),
      output: bytes,
    }, [bytes.buffer]);
  } finally {
    module._free(info);
  }
}

async function handleWriterMessage(data) {
  try {
    const module = await loadModule(data.moduleUrl || './ffmpegkmp.mjs');
    if (data.type === 'writer-open') {
      openWriter(module, data);
      return;
    }
    if (!writer.handle) throw bridgeFailure(ERROR_INVALID_STATE, 'The writer is not open');
    if (writer.aborted) throw bridgeFailure(ERROR_EXIT, 'The writer was aborted');
    if (data.type === 'writer-add-video') await addWriterVideoTrack(module, data);
    else if (data.type === 'writer-add-audio') addWriterAudioTrack(module, data);
    else if (data.type === 'writer-video') await writeWriterVideo(module, data);
    else if (data.type === 'writer-audio') writeWriterAudio(module, data);
    else if (data.type === 'writer-end') await endWriterTrack(module, data);
    else if (data.type === 'writer-release') releaseWriterTrack(module, data);
    else if (data.type === 'writer-finish') finishWriter(module, data);
  } catch (error) {
    if (error !== 'unwind') postFailure('writer-failure', data.id, error);
  }
}

// Unblocks a write waiting on its encoder; the writer takes no more calls.
function abortWriter() {
  writer.aborted = true;
  for (const track of writer.tracks) {
    if (!track) continue;
    if (track.encoder && track.encoder.state !== 'closed') track.encoder.close();
    if (track.kind === 'video') wakeTrack(track);
  }
  modulePromise?.then(module => {
    if (writer.handle) module._ffmpegkmp_writer_abort(writer.handle);
  });
}

// ffplaykmp_io_callback over in-memory resources, by id: what a command or writer reads and writes.
function serveResource(module, resources, resourceId, operation, offset, bytes, size) {
  const resource = resources.get(Number(resourceId));
  if (!resource) return -1n;
  const position = Number(offset);
  const byteCount = Number(size);
  if (!Number.isSafeInteger(position) || position < 0 ||
      !Number.isSafeInteger(byteCount) || byteCount < 0) return -1n;

  if (operation === 0) {
    const flags = position;
    if (resource.truncate && (flags & 2) !== 0 && !resource.truncated) {
      resource.size = 0;
      resource.truncated = true;
      resource.dirty = true;
    }
    const read = resource.access !== 'write' ? 1 : 0;
    const write = resource.access !== 'read' ? 2 : 0;
    return BigInt(read | write | 4);
  }
  if (operation === 1) {
    if (resource.access === 'write' || position >= resource.size) return 0n;
    const count = Math.min(byteCount, resource.size - position);
    module.HEAPU8.set(resource.bytes.subarray(position, position + count), bytes);
    return BigInt(count);
  }
  if (operation === 2) {
    if (resource.access === 'read') return -1n;
    const required = position + byteCount;
    if (!Number.isSafeInteger(required)) return -1n;
    if (required > resource.bytes.length) {
      let capacity = Math.max(resource.bytes.length, 8192);
      while (capacity < required) capacity = Math.max(required, capacity * 2);
      const grown = new Uint8Array(capacity);
      grown.set(resource.bytes.subarray(0, resource.size));
      resource.bytes = grown;
    }
    resource.bytes.set(module.HEAPU8.subarray(bytes, bytes + byteCount), position);
    resource.size = Math.max(resource.size, required);
    resource.dirty = true;
    return BigInt(byteCount);
  }
  if (operation === 3) return BigInt(resource.size);
  if (operation === 4) return 0n;
  return -1n;
}

self.onmessage = async ({ data }) => {
  if (data.type.startsWith('player-')) {
    playerMessageTail = playerMessageTail.then(() => handlePlayerMessage(data));
    return;
  }
  if (data.type.startsWith('demux-')) {
    demuxMessageTail = demuxMessageTail.then(() => handleDemuxMessage(data));
    return;
  }
  // Interrupts and aborts reach a call that is still running, instead of queueing behind it.
  if (data.type === 'video-interrupt') {
    video.interrupted = true;
    wakeVideo();
    return;
  }
  if (data.type.startsWith('video-')) {
    videoMessageTail = videoMessageTail.then(() => handleVideoMessage(data));
    return;
  }
  if (data.type === 'writer-abort') {
    abortWriter();
    return;
  }
  if (data.type.startsWith('writer-')) {
    writerMessageTail = writerMessageTail.then(() => handleWriterMessage(data));
    return;
  }
  if (data.type === 'cancel') {
    if (activeId === data.id && activeContext) {
      const module = await modulePromise;
      module._ffmpegkmp_cancel(activeContext);
    }
    return;
  }
  if (data.type !== 'execute') return;

  let module;
  let callback = 0;
  let ioCallback = 0;
  let allocated;
  nativeRuntimeUnwound = false;
  nativeDiagnostics.length = 0;
  try {
    module = await loadModule(data.moduleUrl);
    const resources = new Map();
    const mountedPaths = new Map();
    (data.mounts ?? []).forEach((mount, index) => {
      const id = index + 1;
      const bytes = mount.bytes;
      resources.set(id, {
        access: mount.access,
        bytes,
        size: bytes.length,
        truncate: mount.truncate,
        truncated: false,
        dirty: false,
      });
      const extension = mount.path.match(/\.[^./\\]+$/)?.[0] ?? '';
      mountedPaths.set(mount.path, `ffmpegkmp:${id}${extension}`);
    });

    callback = module.addFunction((opaque, kind, level, bytes, size) => {
      const byteCount = Number(size);
      const text = decodeUtf8(module.HEAPU8, bytes, byteCount);
      self.postMessage({ type: 'event', id: data.id, kind, level, text });
    }, 'viiiij');
    ioCallback = module.addFunction(
      (opaque, resourceId, operation, offset, bytes, size) =>
        serveResource(module, resources, resourceId, operation, offset, bytes, size),
      'jijijij',
    );
    activeContext = module._ffmpegkmp_context_create(callback, 0);
    module._ffmpegkmp_context_set_io_callback(activeContext, ioCallback);
    activeId = data.id;
    const executable = data.kind === 'ffprobe' ? 'ffprobe' : 'ffmpeg';
    const arguments_ = data.arguments.map(argument => mountedPaths.get(argument) ?? argument);
    allocated = allocateArguments(module, [executable, ...arguments_]);
    const returnCode = module._ffmpegkmp_execute(
      activeContext,
      data.kind === 'ffprobe' ? 1 : 0,
      data.arguments.length + 1,
      allocated.argv,
    );
    const outputs = (data.mounts ?? []).flatMap((mount, index) => {
      if (mount.access === 'read') return [];
      const resource = resources.get(index + 1);
      if (!resource.dirty) return [];
      return [{ path: mount.path, bytes: resource.bytes, size: resource.size }];
    });
    self.postMessage(
      { type: 'complete', id: data.id, returnCode, outputs },
      outputs.map(output => output.bytes.buffer),
    );
  } catch (error) {
    if (error === 'unwind') {
      // Emscripten uses this sentinel when the main runtime notices a crashed
      // pthread. The pthread's error event contains the real failure. Do not
      // release native memory or callback-table entries while it is unwinding.
      nativeRuntimeUnwound = true;
    } else {
      self.postMessage({ type: 'failure', id: data.id, message: String(error?.stack ?? error) });
    }
  } finally {
    if (nativeRuntimeUnwound) return;
    if (module && allocated) {
      allocated.strings.forEach(pointer => module._free(pointer));
      module._free(allocated.argv);
    }
    if (module && activeContext) module._ffmpegkmp_context_destroy(activeContext);
    if (module && ioCallback) module.removeFunction(ioCallback);
    if (module && callback) module.removeFunction(callback);
    activeContext = 0;
    activeId = null;
  }
};
