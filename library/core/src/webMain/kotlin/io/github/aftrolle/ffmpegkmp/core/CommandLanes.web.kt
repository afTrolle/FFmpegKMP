// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

// Every command runs in a Worker of its own with its own Wasm instance, so commands don't share
// fftools' globals and can overlap. Each instance still costs a 64 MB heap that grows with the
// media and a pool of 32 pthread workers, so allow half the reported cores, at least 2 (a probe
// need not wait behind an encode) and at most 4.
internal actual val platformCommandLanes: Int = (hardwareConcurrency() / 2).coerceIn(2, 4)

private fun hardwareConcurrency(): Int =
    js("(globalThis.navigator && globalThis.navigator.hardwareConcurrency) || 0")
