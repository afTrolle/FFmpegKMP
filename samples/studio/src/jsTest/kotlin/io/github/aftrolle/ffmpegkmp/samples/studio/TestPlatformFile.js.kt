// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import io.github.vinceglb.filekit.BrowserFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile

internal actual fun testPlatformFile(name: String): PlatformFile =
    PlatformFile(WebFile.FileWrapper(newBrowserFile(name)))

private fun newBrowserFile(name: String): BrowserFile = js("new File([], name)")
