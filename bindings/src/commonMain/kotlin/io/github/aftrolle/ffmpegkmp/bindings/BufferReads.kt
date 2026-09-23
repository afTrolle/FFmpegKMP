// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import okio.Buffer

/** Copies the requested byte count even when Okio serves it from multiple segments. */
internal fun Buffer.readExactly(destination: ByteArray, byteCount: Int) {
    require(byteCount in 0..destination.size) { "Invalid mounted-source byte count: $byteCount" }
    var copied = 0
    while (copied < byteCount) {
        val count = read(destination, copied, byteCount - copied)
        check(count > 0) { "Mounted source ended before its reported byte count" }
        copied += count
    }
}
