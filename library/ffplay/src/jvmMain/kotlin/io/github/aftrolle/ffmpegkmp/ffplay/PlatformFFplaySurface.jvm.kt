// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize

@Composable
internal actual fun PlatformFFplaySurface(
    player: FFplayPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    backgroundColor: Color,
    resolution: IntSize?,
) = ComposeCanvasFFplaySurface(player, modifier, contentScale, backgroundColor, resolution)
