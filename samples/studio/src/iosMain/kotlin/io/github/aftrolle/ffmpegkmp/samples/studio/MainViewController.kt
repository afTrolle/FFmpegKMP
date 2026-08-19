// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

public fun MainViewController(): UIViewController = ComposeUIViewController { StudioApp() }
