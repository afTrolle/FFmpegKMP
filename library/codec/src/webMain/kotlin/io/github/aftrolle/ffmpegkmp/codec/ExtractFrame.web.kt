// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

// The browser's decoder neither resizes an open decoder nor crops and turns frames yet.
internal actual val frameExtractionSupported: Boolean = false
