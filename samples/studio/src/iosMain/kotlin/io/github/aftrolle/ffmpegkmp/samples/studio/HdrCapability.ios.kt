// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

// hevc_videotoolbox already supports P010 natively, but FFmpegKMP has no Apple-side overlay
// for HDR static-info (mastering display / CLL) propagation yet, so the sample keeps the
// HDR10 export path Android-only for now.
internal actual fun isHdrHardwareEncodeSupported(): Boolean = false
