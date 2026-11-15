// SPDX-License-Identifier: Apache-2.0
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.core

import io.github.aftrolle.ffmpegkmp.bindings.configureNativeMemory

/** Sets up FFmpegKMP's native runtime, before its first use. */
public object FFmpegKmp {
    /**
     * Sets up the native runtime with [config]. Call it once, before FFmpegKMP's first use: the runtime reads it as it
     * loads. Throws [IllegalStateException] when the runtime has already loaded with other limits.
     */
    public fun configure(config: FFmpegKmpConfig) {
        configureNativeMemory(config.processMemoryLimit.bytes(), config.nativeMemoryLimit.bytes())
    }
}

/**
 * How FFmpegKMP's native runtime is set up. Its memory limits apply on JVM and Android, where the runtime, JavaCPP,
 * collects garbage and retries an allocation that finds memory past a limit, up to 10 times 100 ms apart, then fails it
 * with an `OutOfMemoryError`; iOS and the browser have none.
 *
 * @property processMemoryLimit the process's physical memory, which counts decoders', GPU buffers' and bitmaps' memory
 *   that a garbage collection cannot free. [MemoryLimit.Default] is none on Android and four times the Java heap's
 *   limit on JVM, or the `org.bytedeco.javacpp.maxPhysicalBytes` system property where it is set.
 * @property nativeMemoryLimit the native memory FFmpegKMP holds through the runtime, freed when its owners are closed or
 *   collected. [MemoryLimit.Default] is the Java heap's limit, or the `org.bytedeco.javacpp.maxBytes` system property
 *   where it is set.
 */
public class FFmpegKmpConfig(
    public val processMemoryLimit: MemoryLimit = MemoryLimit.Default,
    public val nativeMemoryLimit: MemoryLimit = MemoryLimit.Default,
)

/** A limit on memory for [FFmpegKmpConfig]. */
public sealed interface MemoryLimit {
    /** The platform's default, which [FFmpegKmpConfig] describes for each limit. */
    public data object Default : MemoryLimit

    public data object None : MemoryLimit

    public data class Bytes(val bytes: Long) : MemoryLimit {
        init {
            require(bytes > 0) { "A memory limit must be positive; use MemoryLimit.None for none" }
        }
    }
}

private fun MemoryLimit.bytes(): Long? = when (this) {
    MemoryLimit.Default -> null
    MemoryLimit.None -> 0L
    is MemoryLimit.Bytes -> bytes
}
