// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.aftrolle.ffmpegkmp.samples.studio.StudioApp

@OptIn(ExperimentalComposeUiApi::class)
public fun main() {
    ComposeViewport { StudioApp() }
}
