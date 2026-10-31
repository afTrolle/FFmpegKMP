// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.unsafeCast
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.khronos.webgl.toFloat32Array
import org.khronos.webgl.toInt8Array

// Wasm arrays live in Wasm memory, which JavaScript cannot see: every crossing copies.

internal actual fun transferToJsUint8Array(bytes: ByteArray): JsAny = unsignedView(bytes.toInt8Array())

internal actual fun copyToJsUint8Array(bytes: ByteArray, offset: Int, size: Int): JsAny =
    unsignedView(bytes.copyOfRange(offset, offset + size).toInt8Array())

internal actual fun copyToJsFloat32Array(samples: FloatArray, offset: Int, size: Int): JsAny =
    samples.copyOfRange(offset, offset + size).toFloat32Array()

internal actual fun int8ArrayToByteArray(bytes: JsAny): ByteArray = bytes.unsafeCast<Int8Array>().toByteArray()

// toInt8Array's array normally has a buffer of its own, which a worker can take over without another copy.
private fun unsignedView(bytes: Int8Array): JsAny = js(
    "bytes.byteOffset === 0 && bytes.buffer.byteLength === bytes.length " +
        "? new Uint8Array(bytes.buffer) : new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.length).slice()",
)
