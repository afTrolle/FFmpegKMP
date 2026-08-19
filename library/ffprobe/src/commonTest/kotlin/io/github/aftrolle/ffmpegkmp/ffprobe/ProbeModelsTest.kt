// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProbeModelsTest {
    @Test
    fun parsesTypedValuesAndRetainsUnknownFields() {
        val media = FFprobeClient().use { client ->
            client.parse(
                """
                {
                  "format": {"filename":"movie.mp4","duration":"12.500","size":"9007199254740991","mystery":42},
                  "streams": [{
                    "index":0,"codec_name":"h264","codec_type":"video","width":1920,"height":1080,
                    "nb_frames":"N/A","disposition":{"default":1},
                    "side_data_list":[{"side_data_type":"Display Matrix","rotation":-90,"future":"kept"}]
                  }],
                  "chapters":[{"id":1,"start":"0","end":"1000","tags":{"title":"Intro"}}],
                  "packets":[{"stream_index":0,"pts":"9223372036854775806","size":"2048"}],
                  "frames":[{"media_type":"video","stream_index":0,"key_frame":1,"pkt_size":"1024"}],
                  "future_section":{"enabled":true}
                }
                """.trimIndent(),
            )
        }

        assertEquals(12.5, media.format?.durationSeconds)
        assertEquals(9_007_199_254_740_991L, media.format?.sizeBytes)
        assertEquals(42, media.format?.raw?.get("mystery")?.toString()?.toInt())
        assertEquals("h264", media.streams.single().codecName)
        assertNull(media.streams.single().frameCount)
        assertEquals(true, media.streams.single().disposition.default)
        assertEquals(-90.0, assertIs<ProbeSideData.DisplayMatrix>(media.streams.single().sideData.single()).rotationDegrees)
        assertEquals(Long.MAX_VALUE - 1, media.packets.single().pts)
        assertEquals(true, media.frames.single().keyFrame)
        assertTrue("future_section" in media.raw)
    }

    @Test
    fun queryKeepsExpensiveSectionsOptIn() {
        val defaults = ProbeQuery.Default.arguments("input.mp4")
        val full = ProbeQuery.Full.arguments("input.mp4")

        assertTrue("-show_streams" in defaults)
        assertTrue("-show_packets" !in defaults)
        assertTrue("-show_packets" in full)
        assertTrue("-show_frames" in full)
    }
}
