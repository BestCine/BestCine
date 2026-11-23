package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerEvent

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

internal expect val isIos: Boolean
internal expect val isDesktop: Boolean
internal expect val isWindows: Boolean

internal expect val supportsPosterNavigationMotion: Boolean

@Composable
internal expect fun rememberIsAndroidTv(): Boolean

internal expect fun PointerEvent.isMouseBackButtonPress(): Boolean
internal expect fun PointerEvent.isMouseForwardButtonPress(): Boolean
