// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.aftrolle.ffmpegkmp.codec

import kotlin.concurrent.atomics.AtomicBoolean
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.Source
import okio.Timeout
import platform.posix.usleep

internal fun readVideoDecoderFixture(name: String): ByteArray =
    FileSystem.SYSTEM.read(VIDEO_DECODER_FIXTURES.toPath() / name) { readByteArray() }

/** Okio has no Pipe off the JVM: this Source serves [prefix], then blocks until released. */
internal fun stalledSource(prefix: ByteArray): StalledSource {
    val remaining = Buffer().write(prefix)
    val released = AtomicBoolean(false)
    val source = object : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (!remaining.exhausted()) return remaining.read(sink, byteCount)
            while (!released.load()) pauseBriefly()
            return -1L
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }
    return StalledSource(source, release = { released.store(true) })
}

internal fun pauseBriefly() {
    usleep(10_000u)
}
