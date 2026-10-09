// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.codec

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The pts of frame [index] of a [fps] clip as the decoder stamps it: `av_rescale_q` rounds the
 * nanosecond to the nearest, halves up, where `index.seconds / fps` truncates it.
 */
internal fun framePts(index: Int, fps: Int = 30): Duration = ((index * 1_000_000_000L + fps / 2) / fps).nanoseconds
