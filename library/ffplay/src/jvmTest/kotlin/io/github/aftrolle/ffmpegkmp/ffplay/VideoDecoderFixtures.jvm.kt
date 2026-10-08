// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

/** A decoder fixture from codec's commonTest/resources/video-decoder. */
internal fun readVideoDecoderFixture(name: String): ByteArray =
    checkNotNull(VideoFrameImageTest::class.java.getResourceAsStream("/video-decoder/$name")) {
        "Missing fixture $name"
    }.use { it.readBytes() }
