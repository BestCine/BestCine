package com.nuvio.app.bestcine

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual object BestCinePlatformStorage {
    private var memorySession: String? = null
    actual fun loadSessionJson(): String? = memorySession
    actual fun saveSessionJson(payload: String) { memorySession = payload }
    actual fun clearSession() { memorySession = null }
}

internal actual object BestCinePlatformAuth {
    actual suspend fun launchGoogleLogin(baseUrl: String): BestCineAuthResult =
        BestCineAuthResult.Error("Login embutido ainda não configurado nesta plataforma.")
    actual fun cancelPendingLogin() = Unit
}

internal actual fun bestCineCurrentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
