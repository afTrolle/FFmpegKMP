// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

/** Browser bindings call resources from one thread: the worker reads mounts into memory up front. */
internal actual class IoLock actual constructor() {
    actual fun lock() = Unit
    actual fun unlock() = Unit
}
