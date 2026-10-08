// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

// fftools runs inside this process and keeps its state in globals: one command at a time.
internal actual val platformCommandLanes: Int = 1
