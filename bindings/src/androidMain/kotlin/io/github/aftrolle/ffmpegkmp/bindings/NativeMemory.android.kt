// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

// Android's Java heap is a small slice of the device's memory, so the process limit JavaCPP derives from it stops a few 4K
// decoders, whose memory a garbage collection cannot free; the platform's low-memory killer bounds the process instead.
internal val defaultProcessMemoryBytes: Long? = 0L
