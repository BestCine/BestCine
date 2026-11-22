package com.nuvio.app.bestcine.tv

import com.nuvio.app.bestcine.bestCineCurrentTimeMillis
import com.nuvio.app.features.streams.StreamItem

/**
 * Proteção preventiva no cliente para zapping de TV ao vivo.
 * Espelha e respeita com folga as travas do servidor BestCine:
 * - Servidor Nível 1: > 25 canais distintos / 60s (pausa 2-5 min)
 * - Servidor Nível 2: >= 45 canais distintos / 60s (auto-ban Free / pausa 10 min VIP)
 * Aqui limitamos em 18 canais distintos / 60s + intervalo mínimo de 800ms entre trocas
 * e mantemos cache em RAM de 45s por canal para evitar chamadas redundantes.
 */
internal object BestCineTvZappingGuard {
    private const val WINDOW_MS = 60_000L
    private const val MAX_DISTINCT_CHANNELS_PER_MINUTE = 18
    private const val MIN_SWITCH_INTERVAL_MS = 800L
    private const val STREAM_CACHE_TTL_MS = 45_000L

    private data class CachedChannelStreams(
        val streams: List<StreamItem>,
        val fetchedAtMs: Long,
    )

    private val distinctChannelTimestamps = mutableMapOf<String, Long>()
    private val streamCache = mutableMapOf<String, CachedChannelStreams>()
    private var lastNetworkRequestAtMs: Long = 0L
    private var lastRequestedChannelId: String? = null

    sealed interface CheckOutcome {
        data object Allowed : CheckOutcome
        data class Blocked(val retryAfterSeconds: Int, val message: String) : CheckOutcome
    }

    fun getCachedStreams(channelId: String): List<StreamItem>? {
        val now = bestCineCurrentTimeMillis()
        val entry = streamCache[channelId] ?: return null
        if (now - entry.fetchedAtMs > STREAM_CACHE_TTL_MS) {
            streamCache.remove(channelId)
            return null
        }
        return entry.streams
    }

    fun cacheStreams(channelId: String, streams: List<StreamItem>) {
        if (streams.isEmpty()) return
        val now = bestCineCurrentTimeMillis()
        streamCache[channelId] = CachedChannelStreams(streams = streams, fetchedAtMs = now)
        if (streamCache.size > 40) {
            val expiredKeys = streamCache.entries
                .filter { (_, v) -> now - v.fetchedAtMs > STREAM_CACHE_TTL_MS }
                .map { it.key }
            expiredKeys.forEach(streamCache::remove)
        }
    }

    fun checkAndRecordNetworkFetch(channelId: String): CheckOutcome {
        val now = bestCineCurrentTimeMillis()

        // Limpa entradas fora da janela deslizante de 60s
        val expired = distinctChannelTimestamps.entries
            .filter { (_, ts) -> now - ts > WINDOW_MS }
            .map { it.key }
        expired.forEach(distinctChannelTimestamps::remove)

        // 1. Debounce rápido entre trocas consecutivas de canais diferentes
        if (lastRequestedChannelId != null &&
            lastRequestedChannelId != channelId &&
            (now - lastNetworkRequestAtMs) < MIN_SWITCH_INTERVAL_MS
        ) {
            return CheckOutcome.Blocked(
                retryAfterSeconds = 1,
                message = "Aguarde um instante antes de trocar de canal novamente.",
            )
        }

        // 2. Limite preventivo de canais distintos por minuto (18 vs 25 do servidor)
        val alreadyInWindow = distinctChannelTimestamps.containsKey(channelId)
        if (!alreadyInWindow && distinctChannelTimestamps.size >= MAX_DISTINCT_CHANNELS_PER_MINUTE) {
            val oldestTs = distinctChannelTimestamps.values.minOrNull() ?: now
            val waitSeconds = (((oldestTs + WINDOW_MS) - now + 999L) / 1000L).toInt().coerceAtLeast(1)
            return CheckOutcome.Blocked(
                retryAfterSeconds = waitSeconds,
                message = "Proteção anti-zapping ativa: você abriu muitos canais em 1 minuto. Aguarde ${waitSeconds}s para evitar bloqueio no servidor.",
            )
        }

        distinctChannelTimestamps[channelId] = now
        lastNetworkRequestAtMs = now
        lastRequestedChannelId = channelId
        return CheckOutcome.Allowed
    }

    fun clearCache() {
        streamCache.clear()
    }
}
