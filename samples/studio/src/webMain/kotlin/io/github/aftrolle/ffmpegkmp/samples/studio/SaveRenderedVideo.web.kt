// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.download

internal actual suspend fun saveRenderedVideo(bytes: ByteArray, fileName: String): Boolean {
    FileKit.download(bytes, fileName)
    return true
}
