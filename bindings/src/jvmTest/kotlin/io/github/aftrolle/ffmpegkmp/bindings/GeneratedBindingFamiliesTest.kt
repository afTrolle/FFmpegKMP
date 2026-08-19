// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.avcodec.global.avcodec
import io.github.aftrolle.ffmpegkmp.bindings.generated.avdevice.global.avdevice
import io.github.aftrolle.ffmpegkmp.bindings.generated.avfilter.global.avfilter
import io.github.aftrolle.ffmpegkmp.bindings.generated.avformat.global.avformat
import io.github.aftrolle.ffmpegkmp.bindings.generated.avutil.global.avutil
import io.github.aftrolle.ffmpegkmp.bindings.generated.swresample.global.swresample
import io.github.aftrolle.ffmpegkmp.bindings.generated.swscale.global.swscale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedBindingFamiliesTest {
    @Test
    fun everyLibraryLoadsAndReportsItsVersion() {
        assertTrue(avutil.avutil_version() > 0)
        assertTrue(swresample.swresample_version() > 0)
        assertTrue(swscale.swscale_version() > 0)
        assertTrue(avcodec.avcodec_version() > 0)
        assertTrue(avformat.avformat_version() > 0)
        assertTrue(avfilter.avfilter_version() > 0)
        assertTrue(avdevice.avdevice_version() > 0)
        assertEquals(1_000_000, avutil.AV_TIME_BASE)
        assertEquals(-22, avutil.ffmpegkmp_averror(22))
        assertEquals((61 shl 16) or (1 shl 8) or 101, avutil.ffmpegkmp_av_version_int(61, 1, 101))
    }
}
