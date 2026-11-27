package com.nuvio.app.bestcine

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ArmResolvedEntry(
    val imdbId: String,
    val season: Int? = null,
    val isMovie: Boolean = false,
)

/**
 * Traduz IDs de catálogos externos de anime (`kitsu:`, `mal:`, `anilist:`, `anidb:`, etc.)
 * para o ID IMDb (`tt...`) usando a API pública ARM (arm.haglund.dev/api/v2),
 * mantendo 100% de alinhamento com o BestCine TV sem exigir alterações no backend.
 */
object BestCineAnimeIdResolver {
    private val log = Logger.withTag("BestCineAnimeId")
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheMutex = Mutex()

    private const val SUCCESS_TTL_MS = 6L * 3600L * 1000L
    private const val FAILURE_TTL_MS = 10L * 60L * 1000L
    private const val MAX_CACHE_ENTRIES = 128

    private val prefixToArmSource = listOf(
        "kitsu:" to "kitsu",
        "mal:" to "myanimelist",
        "myanimelist:" to "myanimelist",
        "anilist:" to "anilist",
        "anidb:" to "anidb",
        "livechart:" to "livechart",
        "anisearch:" to "anisearch",
        "ann:" to "animenewsnetwork",
        "animenewsnetwork:" to "animenewsnetwork",
        "animecountdown:" to "animecountdown",
        "animeplanet:" to "anime-planet",
        "anime-planet:" to "anime-planet",
    )

    private data class CacheEntry(
        val resolved: ArmResolvedEntry?,
        val fetchedAtMs: Long,
    )

    private val cache = LinkedHashMap<String, CacheEntry>()

    fun isAnimeMetaId(id: String?): Boolean {
        val trimmed = id?.trim()?.lowercase().orEmpty()
        if (trimmed.isEmpty()) return false
        return prefixToArmSource.any { (prefix, _) -> trimmed.startsWith(prefix) }
    }

    internal fun parseSourceAndId(rawId: String): Pair<String, String>? {
        val trimmed = rawId.trim()
        val lower = trimmed.lowercase()
        val match = prefixToArmSource.firstOrNull { (prefix, _) -> lower.startsWith(prefix) } ?: return null
        val rest = trimmed.substring(match.first.length)
        val rawBase = rest.substringBefore(':').trim()
        if (rawBase.isEmpty()) return null
        val valid = if (match.second == "anime-planet") {
            rawBase.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        } else {
            rawBase.all(Char::isDigit)
        }
        if (!valid) return null
        return match.second to rawBase
    }

    internal fun parseArmPayload(payload: String): ArmResolvedEntry? {
        val element = runCatching { json.parseToJsonElement(payload) }.getOrNull() ?: return null
        val obj: JsonObject? = when (element) {
            is JsonObject -> element
            is JsonArray -> element.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
            else -> null
        }
        if (obj == null) return null
        val imdb = obj["imdb"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (!imdb.startsWith("tt") || imdb.length < 4) return null
        val tvdbSeason = obj["thetvdb-season"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        val tmdbSeason = obj["themoviedb-season"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        val season = when {
            tvdbSeason != null && tmdbSeason != null && tvdbSeason != tmdbSeason -> null
            tvdbSeason != null -> tvdbSeason
            tmdbSeason != null -> tmdbSeason
            else -> null
        }
        val isMovie = obj["media"]?.jsonPrimitive?.content.equals("MOVIE", ignoreCase = true)
        return ArmResolvedEntry(imdbId = imdb, season = season, isMovie = isMovie)
    }

    internal suspend fun resolveMetaTarget(metaId: String): ArmResolvedEntry? =
        resolveEntry(metaId)

    suspend fun resolveToImdbId(metaId: String): String? =
        resolveEntry(metaId)?.imdbId

    suspend fun resolveStreamVideoIdToImdb(
        videoId: String,
        type: String,
        season: Int? = null,
        episode: Int? = null,
    ): String? {
        if (!isAnimeMetaId(videoId)) return null
        val entry = resolveEntry(videoId) ?: return null
        val parts = videoId.trim().split(':')
        val isMovie = type.equals("movie", ignoreCase = true) && episode == null && parts.size <= 2
        if (isMovie) {
            return entry.imdbId
        }
        // Formatos comuns de anime:
        // - "kitsu:41370:5" (base:episodio) -> usa temporada do ARM (ou season explícito, ou 1)
        // - "kitsu:41370:1:5" (base:temporada:episodio) -> usa temporada e episodio explícitos
        val parsedSeason: Int?
        val parsedEpisode: Int?
        when {
            parts.size >= 4 -> {
                parsedSeason = parts[2].toIntOrNull()
                parsedEpisode = parts[3].toIntOrNull()
            }
            parts.size == 3 -> {
                parsedSeason = null
                parsedEpisode = parts[2].toIntOrNull()
            }
            else -> {
                parsedSeason = null
                parsedEpisode = null
            }
        }
        val finalEpisode = episode ?: parsedEpisode
        if (finalEpisode == null || finalEpisode <= 0) {
            return entry.imdbId
        }
        val finalSeason = (season ?: parsedSeason ?: entry.season ?: 1).coerceAtLeast(1)
        return "${entry.imdbId}:$finalSeason:$finalEpisode"
    }

    private suspend fun resolveEntry(rawId: String): ArmResolvedEntry? {
        val (source, idValue) = parseSourceAndId(rawId) ?: return null
        val cacheKey = "$source:$idValue"
        val now = bestCineCurrentTimeMillis()

        cacheMutex.withLock {
            cache[cacheKey]?.let { existing ->
                val ttl = if (existing.resolved != null) SUCCESS_TTL_MS else FAILURE_TTL_MS
                if (now - existing.fetchedAtMs < ttl) {
                    return existing.resolved
                }
            }
        }

        val url = "https://arm.haglund.dev/api/v2/ids?source=$source&id=$idValue"
        val resolved = runCatching {
            val payload = httpGetText(url)
            parseArmPayload(payload)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w(error) { "Falha ao traduzir ID de anime via ARM ($cacheKey)" }
        }.getOrNull()

        cacheMutex.withLock {
            if (cache.size >= MAX_CACHE_ENTRIES) {
                val oldestKey = cache.keys.firstOrNull()
                if (oldestKey != null) cache.remove(oldestKey)
            }
            cache[cacheKey] = CacheEntry(resolved = resolved, fetchedAtMs = bestCineCurrentTimeMillis())
        }
        return resolved
    }
}
