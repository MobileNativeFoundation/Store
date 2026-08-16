package org.mobilenativefoundation.store6.devtoolsdemo

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { DemoApp() }
