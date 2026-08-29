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

self.onmessage = async ({ data }) => {
  if (data.type.startsWith('player-') && !data.__ffplaySerialized) {
    playerMessageTail = playerMessageTail.then(() => self.onmessage({
      data: { ...data, __ffplaySerialized: true },
    }));
    return;
  }
  if (data.type.startsWith('player-')) {
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
          (opaque, bytes, size, timestampUs, durationUs, keyFrame, queueSerial) => {
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
          'viiijjii',
        );
        playerHandle = module._ffplaykmp_web_player_create(
          data.decoderPreference,
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
      let result = 0;
      if (data.type === 'player-prepare') {
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
        resetWebCodecs(module, true);
        result = module._ffplaykmp_player_stop(playerHandle);
      } else if (data.type === 'player-cancel') {
        resetWebCodecs(module, true);
        module._ffplaykmp_player_cancel(playerHandle);
      } else if (data.type === 'player-close') {
        if (playerPollTimer) clearInterval(playerPollTimer);
        playerPollTimer = 0;
        resetWebCodecs(module, true);
        module._ffplaykmp_web_player_poll(playerHandle);
        module._ffplaykmp_web_player_destroy(playerHandle);
        playerHandle = 0;
        if (playerFrameCallback) module.removeFunction(playerFrameCallback);
        if (playerStateCallback) module.removeFunction(playerStateCallback);
        if (playerDecoderConfigCallback) module.removeFunction(playerDecoderConfigCallback);
        if (playerPacketCallback) module.removeFunction(playerPacketCallback);
        playerFrameCallback = 0;
        playerStateCallback = 0;
        playerDecoderConfigCallback = 0;
        playerPacketCallback = 0;
        self.postMessage({ type: 'player-closed' });
        self.close();
        return;
      }
      self.postMessage({ type: 'player-result', operation: data.type, result });
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
    ioCallback = module.addFunction((opaque, resourceId, operation, offset, bytes, size) => {
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
    }, 'jijijij');
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
