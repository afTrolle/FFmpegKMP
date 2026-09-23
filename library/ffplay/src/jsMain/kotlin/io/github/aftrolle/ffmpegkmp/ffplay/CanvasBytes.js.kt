// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

// A Kotlin/JS ByteArray is already an Int8Array.
internal actual fun ByteArray.toCanvasBytes(): JsAny = unsafeCast<JsAny>()
