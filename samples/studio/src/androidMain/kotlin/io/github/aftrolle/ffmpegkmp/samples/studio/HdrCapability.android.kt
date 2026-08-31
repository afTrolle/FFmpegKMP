// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Queries the device's actual codec list rather than gating on an SDK version: what matters
 * is whether some installed HEVC encoder advertises both P010 byte-buffer input and the
 * HDR10 encoder profile, which is exactly what FFmpegKMP's build-time overlay adds support
 * for on the FFmpeg side (see MediaCodecP010Overlay.kt).
 */
internal actual fun isHdrHardwareEncodeSupported(): Boolean = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        info.isEncoder &&
            info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) } &&
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).let { capabilities ->
                capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010) &&
                    capabilities.profileLevels.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                    }
            }
    }
}.getOrDefault(false)
