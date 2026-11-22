package com.nuvio.app.bestcine

import co.touchlab.kermit.Logger
import com.nuvio.app.core.poster.CustomPosterScreen
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.withCustomPosterUrls
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import io.ktor.http.encodeURLQueryComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ImdbSuggestionResponseDto(
    val d: List<ImdbSuggestionItemDto> = emptyList(),
)

@Serializable
private data class ImdbSuggestionItemDto(
    val id: String = "",
    val l: String = "",
    val qid: String? = null,
    val q: String? = null,
    val y: Int? = null,
    val s: String? = null,
    val i: ImdbSuggestionImageDto? = null,
)

@Serializable
private data class ImdbSuggestionImageDto(
    val imageUrl: String? = null,
)

@Serializable
private data class BestCineMetaLookupResponseDto(
    val meta: BestCineMetaLookupDto? = null,
)

@Serializable
private data class BestCineMetaLookupDto(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
)

/**
 * Motor de Busca em Português (PT-BR) integrado para o BestCine.
 *
 * Combina:
 * 1. Busca instantânea em memória nos catálogos PT-BR já carregados do BestCine (0ms);
 * 2. Busca global PT-BR via IMDb Suggestion (`Accept-Language: pt-BR`) + Cinemeta;
 * 3. Tradução automática de título, capa e sinopse para PT-BR via `/meta/{type}/{id}.json`
 *    com cache em memória RAM para respostas instantâneas.
 */
object BestCineSearchEngine {
    private val log = Logger.withTag("BestCineSearch")
    private val json = Json { ignoreUnknownKeys = true }
    private const val CINEMETA_MANIFEST_URL = "https://v3-cinemeta.strem.io/manifest.json"
    private const val MAX_PTBR_ENRICH_PER_TYPE = 10

    private val ptBrMetaCache = mutableMapOf<String, MetaPreview>()
    private val cacheMutex = Mutex()
    private val enrichSemaphore = Semaphore(6)

    suspend fun searchSections(
        query: String,
        addons: List<ManagedAddon>,
        forceRefresh: Boolean = false,
    ): List<HomeCatalogSection> = withContext(Dispatchers.Default) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val bestCineTransportUrl = resolveBestCineTransportUrl(addons)
        val localLoadedItems = collectLoadedBestCineItems()
        val localById = localLoadedItems.associateBy { "${it.type}:${it.id}" }

        // 1. Resultados diretos dos catálogos do BestCine já em memória (100% PT-BR)
        val localMatches = localLoadedItems.filter { item ->
            matchesQueryPtBr(item, cleanQuery)
        }

        // 2. Busca global em paralelo: IMDb Suggestion (entende títulos em PT-BR!) + Cinemeta
        val (imdbItems, cinemetaMovies, cinemetaSeries) = coroutineScope {
            val imdbDeferred = async { fetchImdbSuggestionsPtBr(cleanQuery) }
            val movieDeferred = async {
                runCatching {
                    fetchCatalogPage(
                        manifestUrl = CINEMETA_MANIFEST_URL,
                        type = "movie",
                        catalogId = "top",
                        search = cleanQuery,
                        forceRefresh = forceRefresh,
                    ).items
                }.getOrDefault(emptyList())
            }
            val seriesDeferred = async {
                runCatching {
                    fetchCatalogPage(
                        manifestUrl = CINEMETA_MANIFEST_URL,
                        type = "series",
                        catalogId = "top",
                        search = cleanQuery,
                        forceRefresh = forceRefresh,
                    ).items
                }.getOrDefault(emptyList())
            }
            Triple(imdbDeferred.await(), movieDeferred.await(), seriesDeferred.await())
        }

        // 3. Mescla sem duplicatas (priorizando matches locais PT-BR -> IMDb PT-BR -> Cinemeta)
        val mergedMovies = mergeUniqueById(
            localMatches.filter { it.type == "movie" },
            imdbItems.filter { it.type == "movie" },
            cinemetaMovies,
        )
        val mergedSeries = mergeUniqueById(
            localMatches.filter { it.type == "series" },
            imdbItems.filter { it.type == "series" },
            cinemetaSeries,
        )

        // 4. Traduz os primeiros resultados para Português (PT-BR) via /meta/ do BestCine (com cache em RAM)
        val (localizedMovies, localizedSeries) = coroutineScope {
            val moviesAsync = async {
                localizeItemsToPtBr(
                    items = mergedMovies,
                    type = "movie",
                    bestCineTransportUrl = bestCineTransportUrl,
                    localById = localById,
                )
            }
            val seriesAsync = async {
                localizeItemsToPtBr(
                    items = mergedSeries,
                    type = "series",
                    bestCineTransportUrl = bestCineTransportUrl,
                    localById = localById,
                )
            }
            moviesAsync.await() to seriesAsync.await()
        }

        val posterPattern = CustomPosterUrlRepository.let {
            it.ensureLoaded()
            it.patternForScreen(CustomPosterScreen.SEARCH)
        }

        buildList {
            if (localizedMovies.isNotEmpty()) {
                val finalMovies = localizedMovies.withCustomPosterUrls(posterPattern)
                add(
                    HomeCatalogSection(
                        key = "bestcine:search:movie:${cleanQuery.lowercase()}",
                        title = "Filmes",
                        subtitle = BestCineBranding.APP_NAME,
                        addonName = BestCineBranding.APP_NAME,
                        target = CatalogTarget.Addon(
                            manifestUrl = bestCineTransportUrl ?: CINEMETA_MANIFEST_URL,
                            contentType = "movie",
                            catalogId = "bestcine_search_movie",
                            supportsPagination = false,
                        ),
                        items = finalMovies,
                        availableItemCount = finalMovies.size,
                        hasMore = false,
                    ),
                )
            }
            if (localizedSeries.isNotEmpty()) {
                val finalSeries = localizedSeries.withCustomPosterUrls(posterPattern)
                add(
                    HomeCatalogSection(
                        key = "bestcine:search:series:${cleanQuery.lowercase()}",
                        title = "Séries",
                        subtitle = BestCineBranding.APP_NAME,
                        addonName = BestCineBranding.APP_NAME,
                        target = CatalogTarget.Addon(
                            manifestUrl = bestCineTransportUrl ?: CINEMETA_MANIFEST_URL,
                            contentType = "series",
                            catalogId = "bestcine_search_series",
                            supportsPagination = false,
                        ),
                        items = finalSeries,
                        availableItemCount = finalSeries.size,
                        hasMore = false,
                    ),
                )
            }
        }
    }

    private suspend fun fetchImdbSuggestionsPtBr(query: String): List<MetaPreview> {
        val encoded = query.trim().encodeURLQueryComponent()
        val url = "https://v3.sg.media-imdb.com/suggestion/x/$encoded.json"
        val raw = runCatching {
            httpGetTextWithHeaders(
                url = url,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8",
                ),
            )
        }.getOrNull() ?: return emptyList()

        val dto = runCatching {
            json.decodeFromString<ImdbSuggestionResponseDto>(raw)
        }.getOrNull() ?: return emptyList()

        return dto.d.mapNotNull { item ->
            val id = item.id.trim()
            if (!id.startsWith("tt", ignoreCase = true) || item.l.isBlank()) return@mapNotNull null
            val kind = (item.qid ?: item.q).orEmpty().lowercase()
            val type = when {
                kind.contains("series") || kind.contains("tvmini") || kind == "tv" -> "series"
                kind.contains("movie") || kind.contains("feature") || kind.contains("short") -> "movie"
                else -> return@mapNotNull null
            }
            MetaPreview(
                id = id,
                type = type,
                name = item.l.trim(),
                poster = item.i?.imageUrl?.takeIf { it.isNotBlank() },
                posterShape = PosterShape.Poster,
                banner = item.i?.imageUrl?.takeIf { it.isNotBlank() },
                logo = null,
                description = item.s?.takeIf { it.isNotBlank() },
                releaseInfo = item.y?.toString(),
                imdbRating = null,
                genres = emptyList(),
            )
        }
    }

    private suspend fun localizeItemsToPtBr(
        items: List<MetaPreview>,
        type: String,
        bestCineTransportUrl: String?,
        localById: Map<String, MetaPreview>,
    ): List<MetaPreview> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope emptyList()
        val baseUrl = bestCineTransportUrl?.removeSuffix("/manifest.json")?.trimEnd('/')

        items.mapIndexed { index, item ->
            async {
                val key = "$type:${item.id}"
                // 1. Se já estiver nos catálogos do BestCine em memória, usa direto (0ms, 100% PT-BR)
                localById[key]?.let { return@async it }
                // 2. Se já estiver no cache de tradução PT-BR em RAM, usa direto (0ms)
                cacheMutex.withLock { ptBrMetaCache[key] }?.let { return@async it }

                // 3. Traduz via /meta/ do BestCine para os primeiros resultados visíveis
                if (baseUrl == null || index >= MAX_PTBR_ENRICH_PER_TYPE || !item.id.startsWith("tt")) {
                    return@async item
                }

                enrichSemaphore.withPermit {
                    val metaUrl = "$baseUrl/meta/$type/${item.id}.json"
                    val payload = runCatching {
                        fetchAddonResponseText(metaUrl, forceRefresh = false)
                    }.getOrNull() ?: return@withPermit item

                    val parsed = runCatching {
                        json.decodeFromString<BestCineMetaLookupResponseDto>(payload).meta
                    }.getOrNull() ?: return@withPermit item

                    val ptName = parsed.name.trim().takeIf { it.isNotEmpty() && it != item.id }
                        ?: item.name
                    val localized = item.copy(
                        name = ptName,
                        poster = parsed.poster?.takeIf { it.isNotBlank() } ?: item.poster,
                        banner = parsed.background?.takeIf { it.isNotBlank() } ?: item.banner,
                        description = parsed.description?.takeIf { it.isNotBlank() } ?: item.description,
                        releaseInfo = parsed.releaseInfo?.takeIf { it.isNotBlank() } ?: item.releaseInfo,
                        imdbRating = parsed.imdbRating?.takeIf { it.isNotBlank() } ?: item.imdbRating,
                        genres = parsed.genres.ifEmpty { item.genres },
                    )
                    cacheMutex.withLock {
                        ptBrMetaCache[key] = localized
                    }
                    localized
                }
            }
        }.awaitAll()
    }

    private fun collectLoadedBestCineItems(): List<MetaPreview> {
        val seen = HashSet<String>()
        val result = ArrayList<MetaPreview>()
        HomeRepository.uiState.value.sections.forEach { section ->
            section.items.forEach { item ->
                if ((item.type == "movie" || item.type == "series") && seen.add("${item.type}:${item.id}")) {
                    result.add(item)
                }
            }
        }
        return result
    }

    private fun resolveBestCineTransportUrl(addons: List<ManagedAddon>): String? {
        BestCineAccountRepository.ensureLoaded()
        BestCineAccountRepository.state.value.session?.manifestUrl?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        return addons.firstOrNull { it.enabled && it.manifestUrl.isNotBlank() }?.manifestUrl
    }

    private fun mergeUniqueById(vararg lists: List<MetaPreview>): List<MetaPreview> {
        val seen = HashSet<String>()
        val out = ArrayList<MetaPreview>()
        lists.forEach { list ->
            list.forEach { item ->
                if (item.id.isNotBlank() && seen.add(item.id)) {
                    out.add(item)
                }
            }
        }
        return out
    }

    private fun matchesQueryPtBr(item: MetaPreview, query: String): Boolean {
        val normQuery = normalizeForSearch(query)
        if (normQuery.isEmpty()) return false
        val tokens = normQuery.split(' ').filter { it.isNotBlank() }
        val normTitle = normalizeForSearch(item.name)
        return tokens.all { token -> normTitle.contains(token) }
    }

    private fun normalizeForSearch(input: String): String {
        val lower = input.lowercase()
        val sb = StringBuilder(lower.length)
        for (ch in lower) {
            val mapped = when (ch) {
                'á', 'à', 'ã', 'â', 'ä' -> 'a'
                'é', 'è', 'ê', 'ë' -> 'e'
                'í', 'ì', 'î', 'ï' -> 'i'
                'ó', 'ò', 'õ', 'ô', 'ö' -> 'o'
                'ú', 'ù', 'û', 'ü' -> 'u'
                'ç' -> 'c'
                'ñ' -> 'n'
                '-', ':', '.', ',', '!', '?', '/', '\\' -> ' '
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
