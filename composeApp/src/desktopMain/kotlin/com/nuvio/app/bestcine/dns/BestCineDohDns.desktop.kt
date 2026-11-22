package com.nuvio.app.bestcine.dns

import co.touchlab.kermit.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
private data class DohJsonAnswer(
    val type: Int = 0,
    @SerialName("TTL") val ttl: Long = 300L,
    val data: String = "",
)

@Serializable
private data class DohJsonResponse(
    @SerialName("Status") val status: Int = 0,
    @SerialName("Answer") val answer: List<DohJsonAnswer> = emptyList(),
)

/**
 * Resolvedor DNS-over-HTTPS (DoH) próprio do BestCine com suporte completo a IPv4 (A) e IPv6 (AAAA).
 * Conecta diretamente por IP literal (1.1.1.1 / 1.0.0.1 / [2606:4700:4700::1111] / 8.8.8.8),
 * ignorando 100% o DNS bloqueado do sistema operacional — inclusive no bootstrap!
 */
internal object BestCineDohDns : Dns {
    private val log = Logger.withTag("BestCineDoH")
    private val json = Json { ignoreUnknownKeys = true }

    private const val MIN_TTL_MS = 60_000L
    private const val MAX_TTL_MS = 600_000L

    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // Cliente dedicado que só fala com IPs literais (1.1.1.1, 1.0.0.1, [2606:4700:...], 8.8.8.8)
    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val cloudflareEndpoints = listOf(
        "https://1.1.1.1/dns-query",
        "https://1.0.0.1/dns-query",
        "https://[2606:4700:4700::1111]/dns-query",
        "https://[2606:4700:4700::1001]/dns-query",
    )

    private val googleEndpoints = listOf(
        "https://8.8.8.8/resolve",
        "https://8.8.4.4/resolve",
        "https://[2001:4860:4860::8888]/resolve",
    )

    fun clearCache() {
        cache.clear()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim().lowercase()
        if (cleanHost.isEmpty()) throw UnknownHostException("Empty hostname")

        // Hosts locais ou IPs literais vão direto sem DoH
        if (shouldBypassDoh(cleanHost)) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val mode = BestCineDnsSettingsRepository.currentMode()
        if (mode == BestCineDnsMode.SYSTEM) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val now = System.currentTimeMillis()
        cache[cleanHost]?.takeIf { it.expiresAtMs > now && it.addresses.isNotEmpty() }?.let {
            return it.addresses
        }

        val endpoints = when (mode) {
            BestCineDnsMode.CLOUDFLARE_DOH -> cloudflareEndpoints + googleEndpoints
            BestCineDnsMode.GOOGLE_DOH -> googleEndpoints + cloudflareEndpoints
            BestCineDnsMode.SYSTEM -> return Dns.SYSTEM.lookup(hostname)
        }

        val resolved = resolveViaDohEndpoints(cleanHost, endpoints)
        if (resolved != null && resolved.addresses.isNotEmpty()) {
            cache[cleanHost] = resolved
            return resolved.addresses
        }

        // Fallback final: DNS do sistema operacional
        log.w { "DoH falhou para $cleanHost, usando fallback DNS do sistema" }
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun resolveViaDohEndpoints(
        hostname: String,
        endpoints: List<String>,
    ): CacheEntry? {
        val encodedName = URLEncoder.encode(hostname, "UTF-8")
        for (endpoint in endpoints) {
            val ipv4 = queryRecord(endpoint, encodedName, "A")
            val ipv6 = queryRecord(endpoint, encodedName, "AAAA")

            val combined = mutableListOf<InetAddress>()
            var minTtlSeconds = Long.MAX_VALUE

            ipv4?.let { (addrs, ttl) ->
                combined += addrs.filterIsInstance<Inet4Address>()
                if (ttl > 0) minTtlSeconds = minOf(minTtlSeconds, ttl)
            }
            ipv6?.let { (addrs, ttl) ->
                combined += addrs.filterIsInstance<Inet6Address>()
                if (ttl > 0) minTtlSeconds = minOf(minTtlSeconds, ttl)
            }

            if (combined.isNotEmpty()) {
                val ttlMs = if (minTtlSeconds == Long.MAX_VALUE) {
                    180_000L
                } else {
                    (minTtlSeconds * 1000L).coerceIn(MIN_TTL_MS, MAX_TTL_MS)
                }
                return CacheEntry(
                    addresses = combined.distinct(),
                    expiresAtMs = System.currentTimeMillis() + ttlMs,
                )
            }
        }
        return null
    }

    private fun queryRecord(
        endpoint: String,
        encodedName: String,
        recordType: String,
    ): Pair<List<InetAddress>, Long>? {
        val url = "$endpoint?name=$encodedName&type=$recordType"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .get()
            .build()

        return runCatching {
            bootstrapClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                val parsed = json.decodeFromString<DohJsonResponse>(body)
                if (parsed.status != 0) return@use null

                val expectedType = if (recordType == "A") 1 else 28
                val answers = parsed.answer.filter { it.type == expectedType && it.data.isNotBlank() }
                if (answers.isEmpty()) return@use null

                val addresses = answers.mapNotNull { ans ->
                    runCatching { InetAddress.getByName(ans.data.trim()) }.getOrNull()
                }
                val minTtl = answers.minOfOrNull { it.ttl } ?: 180L
                if (addresses.isEmpty()) null else (addresses to minTtl)
            }
        }.getOrNull()
    }

    private fun shouldBypassDoh(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan")) return true
        if (host == "127.0.0.1" || host == "::1" || host == "[::1]") return true
        // IPv4 literal
        if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) return true
        // IPv6 literal
        if (host.contains(':')) return true
        return false
    }
}
