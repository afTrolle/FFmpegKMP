// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import io.github.vinceglb.filekit.PlatformFile

internal actual fun testPlatformFile(name: String): PlatformFile = PlatformFile("/tmp/$name")
