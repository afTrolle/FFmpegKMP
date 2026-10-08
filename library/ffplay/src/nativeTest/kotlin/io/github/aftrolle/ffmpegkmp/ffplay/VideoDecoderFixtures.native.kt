// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

/** A decoder fixture from codec's commonTest/resources/video-decoder. */
internal fun readVideoDecoderFixture(name: String): ByteArray = bundledTestResource("video-decoder/$name")
