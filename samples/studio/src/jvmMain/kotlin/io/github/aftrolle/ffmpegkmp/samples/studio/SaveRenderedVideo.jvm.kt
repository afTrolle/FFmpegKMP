// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.write

internal actual suspend fun saveRenderedVideo(bytes: ByteArray, fileName: String): Boolean {
    val destination = FileKit.openFileSaver(
        suggestedName = fileName.substringBeforeLast('.'),
        defaultExtension = "mp4",
        allowedExtensions = setOf("mp4"),
    ) ?: return false
    destination.write(bytes)
    return true
}

