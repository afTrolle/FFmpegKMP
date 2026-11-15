// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import kotlinx.coroutines.CoroutineDispatcher

/** Where a blocking native call runs: a native prepare or attach opens decoders and waits on the player's session thread. */
internal expect val blockingDispatcher: CoroutineDispatcher
