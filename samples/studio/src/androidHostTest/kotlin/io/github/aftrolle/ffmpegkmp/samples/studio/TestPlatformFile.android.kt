// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import java.io.File

internal actual fun testPlatformFile(name: String): PlatformFile =
    PlatformFile(AndroidFile.FileWrapper(File("/tmp/$name")))
