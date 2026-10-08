// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SwscaleSliceOverlayTest {
    @Test
    fun alignsUnscaledSlicesToThePinnedGraphSource() {
        val sourceFile = listOf(File("ffmpeg"), File("../ffmpeg"))
            .map { it.resolve("libswscale/graph.c") }
            .first(File::isFile)
        val patched = alignUnscaledSlicesToSourceChroma(sourceFile.readText())
        assertContains(patched, "align = FFMAX(align, 1 << src_desc->log2_chroma_h);")
        assertContains(patched, "Modified by FFmpegKMP contributors in 2026")
        assertEquals(patched, alignUnscaledSlicesToSourceChroma(patched))
    }
}
