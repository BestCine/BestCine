package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerEvent
import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

internal actual val isIos: Boolean = true
internal actual val isDesktop: Boolean = false
internal actual val isWindows: Boolean = false

internal actual val supportsPosterNavigationMotion: Boolean = false

@Composable
internal actual fun rememberIsAndroidTv(): Boolean = false

internal actual fun PointerEvent.isMouseBackButtonPress(): Boolean = false
internal actual fun PointerEvent.isMouseForwardButtonPress(): Boolean = false
