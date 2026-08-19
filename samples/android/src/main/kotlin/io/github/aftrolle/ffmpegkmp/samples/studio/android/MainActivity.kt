// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import io.github.aftrolle.ffmpegkmp.samples.studio.StudioApp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileKit.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent { StudioApp() }
    }
}
