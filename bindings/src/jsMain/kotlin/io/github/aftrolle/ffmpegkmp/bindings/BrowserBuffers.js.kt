// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

// A Kotlin/JS ByteArray is an Int8Array, and a FloatArray a Float32Array.

internal actual fun transferToJsUint8Array(bytes: ByteArray): JsAny = unsignedView(bytes)

internal actual fun copyToJsUint8Array(bytes: ByteArray, offset: Int, size: Int): JsAny = uint8RangeCopy(bytes, offset, size)

internal actual fun copyToJsFloat32Array(samples: FloatArray, offset: Int, size: Int): JsAny = float32RangeCopy(samples, offset, size)

internal actual fun int8ArrayToByteArray(bytes: JsAny): ByteArray = bytes.unsafeCast<ByteArray>()

private fun unsignedView(bytes: dynamic): JsAny = js(
    "bytes.byteOffset === 0 && bytes.buffer.byteLength === bytes.length " +
        "? new Uint8Array(bytes.buffer) : new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.length).slice()",
)

private fun uint8RangeCopy(bytes: dynamic, offset: Int, size: Int): JsAny =
    js("new Uint8Array(bytes.buffer, bytes.byteOffset + offset, size).slice()")

private fun float32RangeCopy(samples: dynamic, offset: Int, size: Int): JsAny =
    js("new Float32Array(samples.buffer, samples.byteOffset + offset * 4, size).slice()")
