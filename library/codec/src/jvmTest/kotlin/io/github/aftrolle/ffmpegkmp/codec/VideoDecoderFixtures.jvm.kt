// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import okio.Buffer
import okio.Pipe

internal fun readVideoDecoderFixture(name: String): ByteArray =
    checkNotNull(VideoDecoderSystemTest::class.java.getResourceAsStream("/video-decoder/$name")) {
        "Missing fixture $name"
    }.use { it.readBytes() }

/** A Pipe holding [prefix] whose writer never writes again. */
internal fun stalledSource(prefix: ByteArray): StalledSource {
    val pipe = Pipe(maxBufferSize = prefix.size.toLong().coerceAtLeast(1L))
    pipe.sink.write(Buffer().write(prefix), prefix.size.toLong())
    return StalledSource(pipe.source, release = pipe::cancel)
}

/** Interruptible, so the decoder's watchdog can end a stalled read. */
internal fun pauseBriefly() = Thread.sleep(10)
