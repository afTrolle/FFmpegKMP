// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.aftrolle.ffmpegkmp.samples.studio.StudioApp
import io.github.vinceglb.filekit.FileKit

public fun main() {
    System.setProperty("apple.awt.application.appearance", "system")
    FileKit.init("io.github.aftrolle.ffmpegkmp.studio")
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "FFmpegKMP Studio",
        ) {
            StudioApp()
        }
    }
}
