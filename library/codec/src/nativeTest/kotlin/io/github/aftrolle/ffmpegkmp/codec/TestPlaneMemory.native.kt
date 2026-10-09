// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set

// Kept for the test process: a plane's memory outlives the views of it.
internal actual fun testPlaneMemory(bytes: ByteArray): Any =
    nativeHeap.allocArray<ByteVar>(bytes.size).also { memory -> bytes.forEachIndexed { index, byte -> memory[index] = byte } }
