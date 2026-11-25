package com.nuvio.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.nuvio.app.bestcine.BestCinePlatformAuth
import com.nuvio.app.features.settings.AppIconPlatform

class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            intent?.dataString?.let { link ->
                val isBestCineAuthCallback = link.startsWith("bestcine://auth/bestcine", ignoreCase = true)
                if (isBestCineAuthCallback) {
                    BestCinePlatformAuth.deliverDeepLinkCallback(link)
                }
                val launchIntent = runCatching {
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = AppIconPlatform.currentLauncherComponent(this@LinkActivity)
                        if (!isBestCineAuthCallback) {
                            putExtra(EXTRA_LINK, link)
                        }
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                }.getOrNull() ?: packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    if (!isBestCineAuthCallback) {
                        putExtra(EXTRA_LINK, link)
                    }
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                }
                if (launchIntent != null) {
                    startActivity(launchIntent)
                }
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_LINK = "link"
    }
}
