// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.filters

import kotlin.test.Test
import kotlin.test.assertContains

class ToneMapTest {
    @Test
    fun hdr10MappingsPreserveLuminanceAndProduceP010() {
        assertContains(ToneMap.ToHdr10Bt2020, "out_transfer=smpte2084")
        assertContains(ToneMap.ToHdr10Bt2020, "intent=absolute_colorimetric")
        assertContains(ToneMap.SdrBt709ToHdr10, "in_transfer=bt709")
        assertContains(ToneMap.SrgbToHdr10, "in_transfer=iec61966-2-1")
        assertContains(ToneMap.Hdr10P010Output, "format=p010le")
        assertContains(ToneMap.Hdr10P010Output, "color_primaries=bt2020")
    }
}
