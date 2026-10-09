// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import java.nio.ByteBuffer

internal actual fun testPlaneMemory(bytes: ByteArray): Any = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip()
