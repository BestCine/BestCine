package com.nuvio.app.bestcine.dns

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BestCineDnsMode(
    val key: String,
    val label: String,
    val description: String,
) {
    CLOUDFLARE_DOH(
        key = "cloudflare_doh",
        label = "Cloudflare DoH (IPv4 + IPv6)",
        description = "1.1.1.1 / 1.0.0.1 e 2606:4700:4700::1111 (recomendado contra bloqueios de operadora)",
    ),
    GOOGLE_DOH(
        key = "google_doh",
        label = "Google DoH (IPv4 + IPv6)",
        description = "8.8.8.8 / 8.8.4.4 e 2001:4860:4860::8888",
    ),
    SYSTEM(
        key = "system",
        label = "DNS do Sistema (PC)",
        description = "Usa o DNS padrão configurado no Windows/rede",
    ),
    ;

    companion object {
        fun fromKey(key: String?): BestCineDnsMode =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: CLOUDFLARE_DOH
    }
}

data class BestCineDnsUiState(
    val mode: BestCineDnsMode = BestCineDnsMode.CLOUDFLARE_DOH,
    val proxyVideoViaDoh: Boolean = true,
    val isTesting: Boolean = false,
    val lastTestResult: String? = null,
)

internal expect object BestCineDnsPlatform {
    fun loadModeKey(): String?
    fun saveModeKey(key: String)
    fun loadProxyVideoEnabled(): Boolean?
    fun saveProxyVideoEnabled(enabled: Boolean)
    fun clearDnsCache()
    suspend fun testDnsResolution(hostname: String): String
}

object BestCineDnsSettingsRepository {
    private val _state = MutableStateFlow(BestCineDnsUiState())
    val state: StateFlow<BestCineDnsUiState> = _state.asStateFlow()

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val mode = BestCineDnsMode.fromKey(BestCineDnsPlatform.loadModeKey())
        val proxyVideo = BestCineDnsPlatform.loadProxyVideoEnabled() ?: true
        _state.value = BestCineDnsUiState(
            mode = mode,
            proxyVideoViaDoh = proxyVideo,
        )
    }

    fun currentMode(): BestCineDnsMode {
        ensureLoaded()
        return _state.value.mode
    }

    fun shouldProxyVideoViaDoh(): Boolean {
        ensureLoaded()
        return _state.value.mode != BestCineDnsMode.SYSTEM && _state.value.proxyVideoViaDoh
    }

    fun setMode(mode: BestCineDnsMode) {
        ensureLoaded()
        if (_state.value.mode == mode) return
        BestCineDnsPlatform.saveModeKey(mode.key)
        BestCineDnsPlatform.clearDnsCache()
        _state.update { it.copy(mode = mode, lastTestResult = null) }
    }

    fun setProxyVideoViaDoh(enabled: Boolean) {
        ensureLoaded()
        if (_state.value.proxyVideoViaDoh == enabled) return
        BestCineDnsPlatform.saveProxyVideoEnabled(enabled)
        _state.update { it.copy(proxyVideoViaDoh = enabled) }
    }

    suspend fun runDiagnosticTest(hostname: String = "example.invalid") {
        ensureLoaded()
        _state.update { it.copy(isTesting = true, lastTestResult = "Testando resolução DNS...") }
        val result = BestCineDnsPlatform.testDnsResolution(hostname)
        _state.update { it.copy(isTesting = false, lastTestResult = result) }
    }
}
