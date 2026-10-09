// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class,
    io.github.aftrolle.ffmpegkmp.core.InternalFFmpegKmpApi::class,
)

package io.github.aftrolle.ffmpegkmp.codec

import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderKind
import io.github.aftrolle.ffmpegkmp.bindings.NativePlayerDecoderPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecoderModelsTest {
    @Test
    fun aFixedCountMustBePositive() {
        assertFailsWith<IllegalArgumentException> { DecoderThreads.Fixed(0) }
        assertFailsWith<IllegalArgumentException> { DecoderThreads.Fixed(-2) }
        assertEquals(1, DecoderThreads.Fixed(1).count)
    }

    @Test
    fun aSizeIsPositiveNeedsAFormatAndIsEvenFor420() {
        assertFailsWith<IllegalArgumentException> { FrameSize(0, 540) }
        assertFailsWith<IllegalArgumentException> { FrameSize(960, -1) }
        assertFailsWith<IllegalArgumentException> { VideoOutput.Memory(size = FrameSize(960, 540)) }
        assertFailsWith<IllegalArgumentException> { VideoOutput.Memory(FrameFormat.Nv12, FrameSize(961, 540)) }
        assertFailsWith<IllegalArgumentException> { VideoOutput.Memory(FrameFormat.P010Hdr10, FrameSize(960, 541)) }
        // RGB has no chroma to subsample.
        assertEquals(FrameSize(961, 541), VideoOutput.Memory(FrameFormat.Rgba8, FrameSize(961, 541)).size)
        assertEquals(FrameSize(960, 540), VideoOutput.Memory(FrameFormat.Nv12, FrameSize(960, 540)).size)
    }

    @Test
    fun threadsReachTheBridgeAsACountWithZeroForAuto() {
        assertEquals(0, DecoderThreads.Auto.toNative())
        assertEquals(6, DecoderThreads.Fixed(6).toNative())
    }

    @Test
    fun preferencesAndKindsMapOneToOne() {
        for (preference in DecoderPreference.entries) {
            assertEquals(preference.name, preference.toNative().name)
        }
        for (kind in NativePlayerDecoderKind.entries) {
            assertEquals(kind.name, kind.toPublic().name)
        }
        assertEquals(NativePlayerDecoderPreference.entries.size, DecoderPreference.entries.size)
    }

    @Test
    fun aSourceNeedsAnInput() {
        assertFailsWith<IllegalArgumentException> { MediaSource(" ") }
        assertFailsWith<IllegalArgumentException> { MediaSource("a\u0000b") }
    }
}
