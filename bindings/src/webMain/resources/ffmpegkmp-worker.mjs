// SPDX-License-Identifier: Apache-2.0
// Kept separate from Kotlin/Wasm because Kotlin/Wasm cannot consume cinterop klibs.

let modulePromise;
let activeContext = 0;
let activeId = null;
let nativeRuntimeUnwound = false;
const nativeDiagnostics = [];

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
