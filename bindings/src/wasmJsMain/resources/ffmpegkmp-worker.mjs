// SPDX-License-Identifier: Apache-2.0
// Kept separate from Kotlin/Wasm because Kotlin/Wasm cannot consume cinterop klibs.

let modulePromise;
let activeContext = 0;
let activeId = null;
let nativeRuntimeUnwound = false;
const nativeDiagnostics = [];

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

function ensureParentDirectories(FS, path) {
  const parts = path.split('/').filter(Boolean);
  let current = '';
  for (const part of parts.slice(0, -1)) {
    current += `/${part}`;
    try { FS.mkdir(current); } catch (error) {
      if (!String(error).includes('File exists')) throw error;
    }
  }
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
  const mountedPaths = [];
  let callback = 0;
  let allocated;
  nativeRuntimeUnwound = false;
  nativeDiagnostics.length = 0;
  try {
    module = await loadModule(data.moduleUrl);
    for (const input of data.inputs ?? []) {
      ensureParentDirectories(module.FS, input.path);
      module.FS.writeFile(input.path, new Uint8Array(input.bytes));
      mountedPaths.push(input.path);
    }

    callback = module.addFunction((opaque, kind, level, bytes, size) => {
      const byteCount = Number(size);
      const value = module.HEAPU8.slice(bytes, bytes + byteCount);
      self.postMessage({ type: 'event', id: data.id, kind, level, bytes: value }, [value.buffer]);
    }, 'viiiij');
    activeContext = module._ffmpegkmp_context_create(callback, 0);
    activeId = data.id;
    const executable = data.kind === 'ffprobe' ? 'ffprobe' : 'ffmpeg';
    allocated = allocateArguments(module, [executable, ...data.arguments]);
    const returnCode = module._ffmpegkmp_execute(
      activeContext,
      data.kind === 'ffprobe' ? 1 : 0,
      data.arguments.length + 1,
      allocated.argv,
    );
    const outputs = (data.outputPaths ?? []).map(path => {
      const bytes = module.FS.readFile(path);
      return { path, bytes };
    });
    self.postMessage({ type: 'complete', id: data.id, returnCode, outputs });
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
    if (module && callback) module.removeFunction(callback);
    if (module) {
      for (const path of [...mountedPaths, ...(data.outputPaths ?? [])]) {
        try { module.FS.unlink(path); } catch (_) {}
      }
    }
    activeContext = 0;
    activeId = null;
  }
};
