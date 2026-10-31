// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * [bytes] in a `Uint8Array` that owns its whole buffer, which a worker can take over: with
 * Kotlin/JS a view of [bytes] itself where it owns its buffer, which transferring empties, so the
 * caller gives [bytes] up; with Kotlin/Wasm a copy out of Wasm memory.
 */
internal expect fun transferToJsUint8Array(bytes: ByteArray): JsAny

/** A copy of [size] bytes of [bytes] from [offset], in a `Uint8Array` that owns its whole buffer. */
internal expect fun copyToJsUint8Array(bytes: ByteArray, offset: Int, size: Int): JsAny

/** A copy of [size] samples of [samples] from [offset], in a `Float32Array` that owns its whole buffer. */
internal expect fun copyToJsFloat32Array(samples: FloatArray, offset: Int, size: Int): JsAny

/** The bytes of an `Int8Array`: the array itself with Kotlin/JS, whose `ByteArray` is one, a copy with Kotlin/Wasm. */
internal expect fun int8ArrayToByteArray(bytes: JsAny): ByteArray

/** The bytes of an `ArrayBuffer` a worker transferred: a view of it with Kotlin/JS, a copy with Kotlin/Wasm. */
internal fun jsBufferToByteArray(buffer: JsAny): ByteArray = int8ArrayToByteArray(int8View(buffer))

private fun int8View(buffer: JsAny): JsAny = js("new Int8Array(buffer)")
