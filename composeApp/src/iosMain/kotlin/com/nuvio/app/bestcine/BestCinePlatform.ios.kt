package com.nuvio.app.bestcine

import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

private const val IOS_KEY_SESSION_JSON = "bestcine_account_session_json"
private const val IOS_KEY_TV_FAVORITES = "bestcine_tv_favorite_channel_ids"

internal actual object BestCinePlatformStorage {
    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    actual fun loadSessionJson(): String? = defaults.stringForKey(IOS_KEY_SESSION_JSON)

    actual fun saveSessionJson(payload: String) {
        defaults.setObject(payload, forKey = IOS_KEY_SESSION_JSON)
    }

    actual fun clearSession() {
        defaults.removeObjectForKey(IOS_KEY_SESSION_JSON)
    }

    actual fun loadTvFavoriteChannelIds(): Set<String> {
        val raw = defaults.stringForKey(IOS_KEY_TV_FAVORITES).orEmpty()
        return raw.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .toSet()
    }

    actual fun saveTvFavoriteChannelIds(ids: Set<String>) {
        val cleaned = ids.map(String::trim).filter { it.isNotEmpty() }.toSet()
        defaults.setObject(cleaned.joinToString("\n"), forKey = IOS_KEY_TV_FAVORITES)
    }
}

internal actual object BestCinePlatformAuth {
    actual suspend fun launchGoogleLogin(baseUrl: String): BestCineAuthResult =
        BestCineAuthResult.Error("Login embutido ainda não configurado nesta plataforma.")
    actual fun cancelPendingLogin() = Unit
}

internal actual fun bestCineCurrentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
