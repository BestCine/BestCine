package com.nuvio.app.bestcine

internal expect object BestCinePlatformStorage {
    fun loadSessionJson(): String?
    fun saveSessionJson(payload: String)
    fun clearSession()
    fun loadTvFavoriteChannelIds(): Set<String>
    fun saveTvFavoriteChannelIds(ids: Set<String>)
}

internal expect object BestCinePlatformAuth {
    suspend fun launchGoogleLogin(baseUrl: String): BestCineAuthResult
    fun cancelPendingLogin()
}

internal expect fun bestCineCurrentTimeMillis(): Long
