package com.nuvio.app.bestcine.dns

internal actual object BestCineDnsPlatform {
    private var modeKey: String? = null
    private var proxyVideo: Boolean? = true
    actual fun loadModeKey(): String? = modeKey
    actual fun saveModeKey(key: String) { modeKey = key }
    actual fun loadProxyVideoEnabled(): Boolean? = proxyVideo
    actual fun saveProxyVideoEnabled(enabled: Boolean) { proxyVideo = enabled }
    actual fun clearDnsCache() = Unit
    actual suspend fun testDnsResolution(hostname: String): String = "Diagnóstico DoH disponível no Desktop."
}
