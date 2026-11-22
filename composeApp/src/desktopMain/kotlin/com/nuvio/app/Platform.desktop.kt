package com.nuvio.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent

class DesktopPlatform : Platform {
    override val name: String = "Desktop ${System.getProperty("os.name").orEmpty()}".trim()
}

actual fun getPlatform(): Platform = DesktopPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = true
internal actual val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")
internal actual val supportsPosterNavigationMotion: Boolean = false

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun PointerEvent.isMouseBackButtonPress(): Boolean = button == PointerButton.Back

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun PointerEvent.isMouseForwardButtonPress(): Boolean = button == PointerButton.Forward
