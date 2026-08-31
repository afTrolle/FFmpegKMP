// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

/**
 * Whether this device/build can hardware-encode HEVC Main10 HDR10 (P010 input, the HDR10
 * encoder profile, mastering-display/CLL propagation). Backed by FFmpegKMP's Android
 * MediaCodec P010/HDR10 overlay, so only Android reports true; other platforms have no
 * equivalent overlay yet and always report unsupported.
 */
internal expect fun isHdrHardwareEncodeSupported(): Boolean
