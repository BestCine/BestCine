package com.nuvio.app

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.platform.LocalContext

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = false
internal actual val isWindows: Boolean = false

internal actual val supportsPosterNavigationMotion: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
internal actual fun rememberIsAndroidTv(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTvMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val hasLeanback = context.packageManager?.hasSystemFeature(PackageManager.FEATURE_LEANBACK) == true
        isTvMode || hasLeanback
    }
}

internal actual fun PointerEvent.isMouseBackButtonPress(): Boolean = false
internal actual fun PointerEvent.isMouseForwardButtonPress(): Boolean = false
