// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.filters

import kotlin.test.Test
import kotlin.test.assertEquals

class FilterGraphTest {
    @Test
    fun compilesTransformOverlayAndAlphaMask() {
        val graph = FilterGraph {
            val base = scale(input(0), 1920, 1080, "base")
            val logo = scale(input(1), 320, -1, "logo")
            val composed = overlay(base, logo, x = "W-w-24", y = "24", opacity = 0.75, label = "composed")
            alphaMask(composed, input(2), "masked")
        }

        assertEquals(
            "[0:v:0]scale=1920:1080[base];" +
                "[1:v:0]scale=320:-1[logo];" +
                "[logo]format=rgba,colorchannelmixer=aa=0.75[ffk0];" +
                "[base][ffk0]overlay=x=W-w-24:y=24[composed];" +
                "[composed][2:v:0]alphamerge[masked]",
            graph.compile(),
        )
    }

    @Test
    fun compilesLumaMaskAndAudioOperations() {
        val graph = FilterGraph {
            lumaMask(input(0), input(1), input(2), "masked")
            val trimmed = audioTrim(input(0, "a:0"), startSeconds = 1.5, endSeconds = 4.0, label = "trimmed")
            tempo(volume(trimmed, 0.5, "quiet"), 1.25, "sped")
        }

        assertEquals(
            "[0:v:0][1:v:0][2:v:0]maskedmerge[masked];" +
                "[0:a:0]atrim=start=1.5:end=4[trimmed];" +
                "[trimmed]volume=0.5[quiet];" +
                "[quiet]atempo=1.25[sped]",
            graph.compile(),
        )
    }
}
