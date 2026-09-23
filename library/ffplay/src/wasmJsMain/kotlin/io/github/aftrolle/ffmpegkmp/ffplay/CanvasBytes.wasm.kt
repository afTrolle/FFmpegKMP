// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import org.khronos.webgl.toInt8Array

// One bulk copy out of Wasm memory, instead of an interop call per byte.
internal actual fun ByteArray.toCanvasBytes(): JsAny = toInt8Array()
