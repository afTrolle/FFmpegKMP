// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

/**
 * Sets the native runtime's memory limits, in bytes: [processBytes] on the process's physical memory, [trackedBytes] on
 * the native memory the runtime tracks. Null keeps the platform's default and 0 lifts the limit. Only JavaCPP, on JVM
 * and Android, has them; elsewhere this does nothing. Throws [IllegalStateException] when the runtime has already
 * loaded with other limits.
 */
@InternalFFmpegKmpApi
public expect fun configureNativeMemory(processBytes: Long?, trackedBytes: Long?)
