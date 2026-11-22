package com.nuvio.app.bestcine.dns

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address

private const val DNS_STORE_NAME = "bestcine_dns"
private const val KEY_DNS_MODE = "dns_mode"
private const val KEY_PROXY_VIDEO = "proxy_video_doh"

internal actual object BestCineDnsPlatform {
    private val store by lazy { DesktopStorage.store(DNS_STORE_NAME) }

    actual fun loadModeKey(): String? = store.getString(KEY_DNS_MODE)

    actual fun saveModeKey(key: String) {
        store.putString(KEY_DNS_MODE, key)
    }

    actual fun loadProxyVideoEnabled(): Boolean? = store.getBoolean(KEY_PROXY_VIDEO)

    actual fun saveProxyVideoEnabled(enabled: Boolean) {
        store.putBoolean(KEY_PROXY_VIDEO, enabled)
    }

    actual fun clearDnsCache() {
        BestCineDohDns.clearCache()
    }

    actual suspend fun testDnsResolution(hostname: String): String = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        BestCineDohDns.clearCache()
        runCatching {
            val addresses = BestCineDohDns.lookup(hostname)
            val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val v4 = addresses.filterIsInstance<Inet4Address>().map { it.hostAddress }
            val v6 = addresses.filterIsInstance<Inet6Address>().map { it.hostAddress }
            val modeLabel = BestCineDnsSettingsRepository.currentMode().label
            buildString {
                append("OK ($modeLabel em ${elapsedMs}ms): ")
                if (v4.isNotEmpty()) append("IPv4=${v4.take(2).joinToString(",")}")
                if (v6.isNotEmpty()) {
                    if (v4.isNotEmpty()) append(" | ")
                    append("IPv6=${v6.take(1).joinToString(",")}")
                }
            }
        }.getOrElse { error ->
            "Falha ao resolver $hostname: ${error.message ?: "erro desconhecido"}"
        }
    }
}
