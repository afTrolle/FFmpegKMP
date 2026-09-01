// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

// The Android MediaCodec P010/HDR10 overlay has no Wasm/browser counterpart.
internal actual fun isHdrHardwareEncodeSupported(): Boolean = false
