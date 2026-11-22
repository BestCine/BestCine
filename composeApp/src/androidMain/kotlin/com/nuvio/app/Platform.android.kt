package com.nuvio.app

import android.os.Build
import androidx.compose.ui.input.pointer.PointerEvent

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = false
internal actual val isWindows: Boolean = false

internal actual val supportsPosterNavigationMotion: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

internal actual fun PointerEvent.isMouseBackButtonPress(): Boolean = false
internal actual fun PointerEvent.isMouseForwardButtonPress(): Boolean = false
