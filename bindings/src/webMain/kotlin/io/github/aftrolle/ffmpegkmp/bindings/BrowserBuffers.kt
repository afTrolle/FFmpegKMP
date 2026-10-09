// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/** A copy of [bytes] in a `Uint8Array` that owns its whole buffer, which a worker can take over. */
internal expect fun copyToJsUint8Array(bytes: ByteArray): JsAny

/** A copy of [size] bytes of [bytes] from [offset], in a `Uint8Array` that owns its whole buffer. */
internal expect fun copyToJsUint8Array(bytes: ByteArray, offset: Int, size: Int): JsAny

/** A copy of [size] samples of [samples] from [offset], in a `Float32Array` that owns its whole buffer. */
internal expect fun copyToJsFloat32Array(samples: FloatArray, offset: Int, size: Int): JsAny

/** The bytes of an `ArrayBuffer` a worker transferred: a view of it with Kotlin/JS, a copy with Kotlin/Wasm. */
internal expect fun jsBufferToByteArray(buffer: JsAny): ByteArray
