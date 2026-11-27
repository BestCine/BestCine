package com.nuvio.app.bestcine.catalog

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.mergeCatalogItems
import com.nuvio.app.features.home.HomeCatalogDefinition
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import com.nuvio.app.features.tmdb.normalizeTmdbLanguage
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Catálogos opcionais de serviços de streaming no Brasil via API oficial TMDB Discover
 * (`watch_region=BR`, `language=pt-BR`).
 *
 * - Desativado por padrão (zero custo de rede/CPU/memória quando desabilitado).
 * - Cache em memória por 6 horas para navegação instantânea na Tela Inicial e em "Ver todos".
 */
data class BestCineStreamingProvider(
    val id: String,
    val tmdbProviderId: Int,
    val displayName: String,
    val accentHex: Long,
    val monetizationTypes: String = "flatrate",
)

enum class BestCineMomentPeriod(
    val id: String,
    val label: String,
    val subtitle: String,
    val withGenres: String?,
    val minVoteAverage: String,
    val minVoteCount: Int,
    val recentOnly: Boolean,
) {
    MANHA(
        id = "manha",
        label = "Manhã Leve",
        subtitle = "Animações & Família",
        withGenres = "16|10751",
        minVoteAverage = "6.4",
        minVoteCount = 120,
        recentOnly = false,
    ),
    TARDE(
        id = "tarde",
        label = "Tarde Pipoca",
        subtitle = "Aventura & Ação",
        withGenres = "12|28",
        minVoteAverage = "6.2",
        minVoteCount = 150,
        recentOnly = false,
    ),
    NOITE(
        id = "noite",
        label = "Noite de Estreia",
        subtitle = "Lançamentos & Blockbusters",
        withGenres = null,
        minVoteAverage = "6.2",
        minVoteCount = 120,
        recentOnly = true,
    ),
    MADRUGADA(
        id = "madrugada",
        label = "Madrugada Adrenalina",
        subtitle = "Terror & Suspense",
        withGenres = "27|53",
        minVoteAverage = "6.0",
        minVoteCount = 120,
        recentOnly = false,
    ),
}

enum class BestCineSeasonalEvent(
    val id: String,
    val title: String,
    val subtitle: String,
    val withGenres: String?,
    val withKeywords: String?,
    val minVoteAverage: String,
    val minVoteCount: Int,
) {
    DIA_DAS_MAES(
        id = "dia_das_maes",
        title = "Especial Dia das Mães",
        subtitle = "Família & Drama",
        withGenres = "10751,18",
        withKeywords = null,
        minVoteAverage = "6.8",
        minVoteCount = 120,
    ),
    DIA_DOS_NAMORADOS(
        id = "dia_dos_namorados",
        title = "Especial Dia dos Namorados",
        subtitle = "Romance",
        withGenres = "10749",
        withKeywords = null,
        minVoteAverage = "6.6",
        minVoteCount = 150,
    ),
    DIA_DOS_PAIS(
        id = "dia_dos_pais",
        title = "Especial Dia dos Pais",
        subtitle = "Família & Aventura",
        withGenres = "10751|12",
        withKeywords = null,
        minVoteAverage = "6.8",
        minVoteCount = 150,
    ),
    DIA_DAS_CRIANCAS(
        id = "dia_das_criancas",
        title = "Especial Dia das Crianças",
        subtitle = "Animações & Família",
        withGenres = "16,10751",
        withKeywords = null,
        minVoteAverage = "6.8",
        minVoteCount = 150,
    ),
    HALLOWEEN(
        id = "halloween",
        title = "Especial Halloween",
        subtitle = "Terror & Mistério",
        withGenres = "27|9648",
        withKeywords = null,
        minVoteAverage = "6.2",
        minVoteCount = 150,
    ),
    NATAL(
        id = "natal",
        title = "Especial de Natal",
        subtitle = "Clássicos & Filmes Natalinos",
        withGenres = null,
        withKeywords = "207317",
        minVoteAverage = "5.8",
        minVoteCount = 80,
    ),
    FERIAS_ANO_NOVO(
        id = "ferias_ano_novo",
        title = "Férias & Ano Novo",
        subtitle = "Comédia & Aventura",
        withGenres = "35|12",
        withKeywords = null,
        minVoteAverage = "6.6",
        minVoteCount = 150,
    ),
}

data class BestCineRuntimeCatalog(
    val id: String,
    val displayName: String,
    val rowTitle: String,
    val type: String,
    val accentHex: Long,
)

data class BestCineStreamingCatalogConfig(
    val releasesCatalogEnabled: Boolean = true,
    val momentCatalogEnabled: Boolean = true,
    val seasonalCatalogEnabled: Boolean = true,
    val runtimeCatalogsEnabled: Boolean = false,
    val selectedRuntimeIds: List<String> = emptyList(),
    val enabled: Boolean = false,
    val selectedProviderIds: List<String> = emptyList(),
    val includeMovies: Boolean = true,
    val includeSeries: Boolean = true,
) {
    val signature: String
        get() = buildString {
            append(releasesCatalogEnabled)
            append('|')
            append(momentCatalogEnabled)
            append('|')
            append(seasonalCatalogEnabled)
            append('|')
            append(runtimeCatalogsEnabled)
            append('|')
            append(selectedRuntimeIds.joinToString(","))
            append('|')
            append(enabled)
            append('|')
            append(includeMovies)
            append('|')
            append(includeSeries)
            append('|')
            append(selectedProviderIds.joinToString(","))
        }
}

data class BestCineDiscoverCatalogSpec(
    val id: String,
    val type: String,
    val title: String,
    val sourceLabel: String,
    val genreOptions: List<String>,
)

object BestCineStreamingCatalogs {
    const val MANIFEST_URL = "bestcine://streaming-catalogs-br"
    const val DISCOVER_MANIFEST_URL = "bestcine://discover-br"
    const val RELEASES_MOVIES_CATALOG_ID = "lancamentos_filmes"
    const val RELEASES_SERIES_CATALOG_ID = "lancamentos_series"
    const val MOMENT_CATALOG_ID = "sessao_momento"
    const val SEASONAL_CATALOG_ID = "especial_sazonal"
    const val RUNTIME_QUICK_MOVIES_ID = "tempo_rapidos"
    const val RUNTIME_SHORT_SERIES_ID = "tempo_miniseries"
    const val RUNTIME_EPIC_MOVIES_ID = "tempo_epicos"
    private const val DISCOVER_POPULAR_ID = "discover_popular"
    private const val DISCOVER_TOP_RATED_ID = "discover_top_rated"
    private const val DISCOVER_RECENT_ID = "discover_recent"
    private const val DISCOVER_BRAZIL_ID = "discover_brazil"
    private const val DISCOVER_ANIMES_ID = "discover_animes"
    private const val DISCOVER_DORAMAS_ID = "discover_doramas"
    private const val DISCOVER_PROVIDER_PREFIX = "discover_prov_"
    private const val KEY_PREFIX = "bestcine_streaming_br:"
    private const val WATCH_REGION_BR = "BR"
    private const val TMDB_PAGE_SIZE = 20
    private val CACHE_TTL = 6.hours
    private val MOMENT_CACHE_TTL = 2.hours

    private val log = Logger.withTag("BestCineStreaming")
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheMutex = Mutex()
    private val pageCache = linkedMapOf<CacheKey, CachedPage>()

    val allRuntimeCatalogs: List<BestCineRuntimeCatalog> = listOf(
        BestCineRuntimeCatalog(
            id = RUNTIME_QUICK_MOVIES_ID,
            displayName = "Filmes Rápidos (até 90 min)",
            rowTitle = "Filmes Rápidos • Até 90 min",
            type = "movie",
            accentHex = 0xFF10B981L,
        ),
        BestCineRuntimeCatalog(
            id = RUNTIME_SHORT_SERIES_ID,
            displayName = "Séries Curtas & Minisséries",
            rowTitle = "Séries Curtas & Minisséries",
            type = "series",
            accentHex = 0xFF8B5CF6L,
        ),
        BestCineRuntimeCatalog(
            id = RUNTIME_EPIC_MOVIES_ID,
            displayName = "Épicos & Maratonas (+2h30)",
            rowTitle = "Épicos & Maratonas • +2h30",
            type = "movie",
            accentHex = 0xFFF59E0BL,
        ),
    )

    val defaultRuntimeIds: List<String> = allRuntimeCatalogs.map { it.id }

    private val runtimeById: Map<String, BestCineRuntimeCatalog> =
        allRuntimeCatalogs.associateBy { it.id }

    val allProviders: List<BestCineStreamingProvider> = listOf(
        BestCineStreamingProvider(
            id = "netflix",
            tmdbProviderId = 8,
            displayName = "Netflix",
            accentHex = 0xFFE50914L,
            monetizationTypes = "flatrate",
        ),
        BestCineStreamingProvider(
            id = "prime",
            tmdbProviderId = 119,
            displayName = "Prime Video",
            accentHex = 0xFF00A8E1L,
            monetizationTypes = "flatrate",
        ),
        BestCineStreamingProvider(
            id = "disney",
            tmdbProviderId = 337,
            displayName = "Disney+",
            accentHex = 0xFF1A56DBL,
            monetizationTypes = "flatrate",
        ),
        BestCineStreamingProvider(
            id = "max",
            tmdbProviderId = 1899,
            displayName = "Max",
            accentHex = 0xFF2563EBL,
            monetizationTypes = "flatrate",
        ),
        BestCineStreamingProvider(
            id = "globoplay",
            tmdbProviderId = 307,
            displayName = "Globoplay",
            accentHex = 0xFFFB0234L,
            monetizationTypes = "flatrate|free|ads",
        ),
        BestCineStreamingProvider(
            id = "appletv",
            tmdbProviderId = 350,
            displayName = "Apple TV+",
            accentHex = 0xFFA3A3A3L,
            monetizationTypes = "flatrate",
        ),
        BestCineStreamingProvider(
            id = "paramount",
            tmdbProviderId = 531,
            displayName = "Paramount+",
            accentHex = 0xFF0064FFL,
            monetizationTypes = "flatrate",
        ),
        BestCineStreamingProvider(
            id = "crunchyroll",
            tmdbProviderId = 283,
            displayName = "Crunchyroll",
            accentHex = 0xFFF47521L,
            monetizationTypes = "flatrate|free|ads",
        ),
    )

    val defaultStarterProviderIds: List<String> = listOf("netflix", "prime", "disney")

    private val providersById: Map<String, BestCineStreamingProvider> =
        allProviders.associateBy { it.id }

    fun isStreamingManifestUrl(manifestUrl: String): Boolean =
        manifestUrl == MANIFEST_URL

    fun isDiscoverManifestUrl(manifestUrl: String): Boolean =
        manifestUrl == DISCOVER_MANIFEST_URL

    private val movieDiscoverGenreIds: Map<String, String> = linkedMapOf(
        "Ação" to "28",
        "Aventura" to "12",
        "Animação" to "16",
        "Comédia" to "35",
        "Crime" to "80",
        "Documentário" to "99",
        "Drama" to "18",
        "Família" to "10751",
        "Fantasia" to "14",
        "Ficção Científica" to "878",
        "Guerra" to "10752",
        "Mistério" to "9648",
        "Romance" to "10749",
        "Suspense" to "53",
        "Terror" to "27",
    )

    private val seriesDiscoverGenreIds: Map<String, String> = linkedMapOf(
        "Ação & Aventura" to "10759",
        "Animação" to "16",
        "Comédia" to "35",
        "Crime" to "80",
        "Documentário" to "99",
        "Drama" to "18",
        "Família" to "10751",
        "Ficção & Fantasia" to "10765",
        "Mistério" to "9648",
        "Reality" to "10764",
    )

    val discoverCatalogs: List<BestCineDiscoverCatalogSpec> by lazy {
        val movieGenres = movieDiscoverGenreIds.keys.toList()
        val seriesGenres = seriesDiscoverGenreIds.keys.toList()
        buildList {
            // Filmes
            add(BestCineDiscoverCatalogSpec(DISCOVER_POPULAR_ID, "movie", "Populares", "BestCine", movieGenres))
            add(BestCineDiscoverCatalogSpec(DISCOVER_RECENT_ID, "movie", "Lançamentos Recentes", "BestCine", movieGenres))
            add(BestCineDiscoverCatalogSpec(DISCOVER_TOP_RATED_ID, "movie", "Mais Bem Avaliados", "BestCine", movieGenres))
            add(BestCineDiscoverCatalogSpec(DISCOVER_BRAZIL_ID, "movie", "Cinema Brasileiro", "BestCine", movieGenres))
            allProviders.forEach { prov ->
                add(
                    BestCineDiscoverCatalogSpec(
                        id = "$DISCOVER_PROVIDER_PREFIX${prov.id}",
                        type = "movie",
                        title = prov.displayName,
                        sourceLabel = "BestCine Streaming",
                        genreOptions = movieGenres,
                    ),
                )
            }
            // Séries
            add(BestCineDiscoverCatalogSpec(DISCOVER_POPULAR_ID, "series", "Populares", "BestCine", seriesGenres))
            add(BestCineDiscoverCatalogSpec(DISCOVER_TOP_RATED_ID, "series", "Mais Bem Avaliadas", "BestCine", seriesGenres))
            add(BestCineDiscoverCatalogSpec(DISCOVER_ANIMES_ID, "series", "Animes", "BestCine", emptyList()))
            add(BestCineDiscoverCatalogSpec(DISCOVER_DORAMAS_ID, "series", "Doramas & K-Dramas", "BestCine", emptyList()))
            allProviders.forEach { prov ->
                add(
                    BestCineDiscoverCatalogSpec(
                        id = "$DISCOVER_PROVIDER_PREFIX${prov.id}",
                        type = "series",
                        title = prov.displayName,
                        sourceLabel = "BestCine Streaming",
                        genreOptions = seriesGenres,
                    ),
                )
            }
        }
    }

    fun isStreamingKey(key: String): Boolean =
        key.startsWith(KEY_PREFIX)

    fun providerById(id: String): BestCineStreamingProvider? =
        providersById[id.trim().lowercase()]

    fun normalizeProviderIds(ids: List<String>): List<String> {
        val valid = ids.map { it.trim().lowercase() }.filter { it in providersById }.toSet()
        return allProviders.map { it.id }.filter { it in valid }
    }

    fun normalizeRuntimeIds(ids: List<String>): List<String> {
        val valid = ids.map { it.trim().lowercase() }.filter { it in runtimeById }.toSet()
        return allRuntimeCatalogs.map { it.id }.filter { it in valid }
    }

    fun runtimeCatalogById(id: String): BestCineRuntimeCatalog? =
        runtimeById[id.trim().lowercase()]

    fun currentSeasonalEvent(isoDate: String = CurrentDateProvider.todayIsoDate()): BestCineSeasonalEvent? {
        val parts = isoDate.split('-')
        val month = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val day = parts.getOrNull(2)?.toIntOrNull() ?: return null
        val md = month * 100 + day
        return when {
            md in 501..515 -> BestCineSeasonalEvent.DIA_DAS_MAES
            md in 605..614 -> BestCineSeasonalEvent.DIA_DOS_NAMORADOS
            md in 801..815 -> BestCineSeasonalEvent.DIA_DOS_PAIS
            md in 1005..1014 -> BestCineSeasonalEvent.DIA_DAS_CRIANCAS
            md in 1015..1102 -> BestCineSeasonalEvent.HALLOWEEN
            md in 1201..1227 -> BestCineSeasonalEvent.NATAL
            md >= 1228 || md <= 110 -> BestCineSeasonalEvent.FERIAS_ANO_NOVO
            else -> null
        }
    }

    fun currentLocalHour(): Int {
        val nowMs = WatchProgressClock.nowEpochMs()
        val todayIso = CurrentDateProvider.todayIsoDate()
        val startOfDayMs = CurrentDateProvider.localStartOfDayEpochMs(todayIso)
        return if (startOfDayMs != null) {
            ((nowMs - startOfDayMs) / 3_600_000L).toInt().coerceIn(0, 23)
        } else {
            ((((nowMs - 3L * 3_600_000L) / 3_600_000L) % 24L + 24L) % 24L).toInt()
        }
    }

    fun currentMomentPeriod(): BestCineMomentPeriod = when (currentLocalHour()) {
        in 6..11 -> BestCineMomentPeriod.MANHA
        in 12..17 -> BestCineMomentPeriod.TARDE
        in 18..23 -> BestCineMomentPeriod.NOITE
        else -> BestCineMomentPeriod.MADRUGADA
    }

    fun buildHomeDefinitions(config: BestCineStreamingCatalogConfig): List<HomeCatalogDefinition> {
        return buildList {
            if (config.releasesCatalogEnabled) {
                add(
                    HomeCatalogDefinition(
                        key = "${KEY_PREFIX}movie:$RELEASES_MOVIES_CATALOG_ID",
                        defaultTitle = "Filmes • Lançamentos",
                        catalogName = "Filmes • Lançamentos",
                        addonName = "BestCine Lançamentos",
                        manifestUrl = MANIFEST_URL,
                        type = "movie",
                        catalogId = RELEASES_MOVIES_CATALOG_ID,
                        supportsPagination = true,
                        descriptorSignature = "bestcine_lancamentos_movie_v1",
                    ),
                )
                add(
                    HomeCatalogDefinition(
                        key = "${KEY_PREFIX}series:$RELEASES_SERIES_CATALOG_ID",
                        defaultTitle = "Séries • Lançamentos",
                        catalogName = "Séries • Lançamentos",
                        addonName = "BestCine Lançamentos",
                        manifestUrl = MANIFEST_URL,
                        type = "series",
                        catalogId = RELEASES_SERIES_CATALOG_ID,
                        supportsPagination = true,
                        descriptorSignature = "bestcine_lancamentos_series_v1",
                    ),
                )
            }
            if (config.momentCatalogEnabled) {
                val period = currentMomentPeriod()
                add(
                    HomeCatalogDefinition(
                        key = "${KEY_PREFIX}movie:$MOMENT_CATALOG_ID",
                        defaultTitle = "Sessão do Momento • ${period.label}",
                        catalogName = "Sessão do Momento • ${period.label}",
                        addonName = "BestCine Dinâmico",
                        manifestUrl = MANIFEST_URL,
                        type = "movie",
                        catalogId = MOMENT_CATALOG_ID,
                        supportsPagination = true,
                        descriptorSignature = "bestcine_momento_${period.id}_v1",
                    ),
                )
            }
            if (config.seasonalCatalogEnabled) {
                currentSeasonalEvent()?.let { event ->
                    add(
                        HomeCatalogDefinition(
                            key = "${KEY_PREFIX}movie:$SEASONAL_CATALOG_ID",
                            defaultTitle = "${event.title} • ${event.subtitle}",
                            catalogName = event.title,
                            addonName = "BestCine Sazonal",
                            manifestUrl = MANIFEST_URL,
                            type = "movie",
                            catalogId = SEASONAL_CATALOG_ID,
                            supportsPagination = true,
                            descriptorSignature = "bestcine_sazonal_${event.id}_v1",
                        ),
                    )
                }
            }
            if (config.runtimeCatalogsEnabled) {
                normalizeRuntimeIds(config.selectedRuntimeIds)
                    .mapNotNull { runtimeById[it] }
                    .forEach { runtime ->
                        add(
                            HomeCatalogDefinition(
                                key = "${KEY_PREFIX}${runtime.type}:${runtime.id}",
                                defaultTitle = runtime.rowTitle,
                                catalogName = runtime.rowTitle,
                                addonName = "BestCine Duração",
                                manifestUrl = MANIFEST_URL,
                                type = runtime.type,
                                catalogId = runtime.id,
                                supportsPagination = true,
                                descriptorSignature = "bestcine_runtime_${runtime.id}_v1",
                            ),
                        )
                    }
            }
            if (!config.enabled) return@buildList
            val selected = normalizeProviderIds(config.selectedProviderIds)
                .mapNotNull { providersById[it] }
            if (selected.isEmpty()) return@buildList
            val includeMovies = config.includeMovies || (!config.includeMovies && !config.includeSeries)
            val includeSeries = config.includeSeries

            selected.forEach { provider ->
                if (includeMovies) {
                    add(
                        HomeCatalogDefinition(
                            key = "${KEY_PREFIX}movie:${provider.id}",
                            defaultTitle = "${provider.displayName} • Filmes",
                            catalogName = provider.displayName,
                            addonName = "Streaming Brasil (TMDB)",
                            manifestUrl = MANIFEST_URL,
                            type = "movie",
                            catalogId = provider.id,
                            supportsPagination = true,
                            descriptorSignature = "bestcine_br_${provider.id}_movie_v1",
                        ),
                    )
                }
                if (includeSeries) {
                    add(
                        HomeCatalogDefinition(
                            key = "${KEY_PREFIX}series:${provider.id}",
                            defaultTitle = "${provider.displayName} • Séries",
                            catalogName = provider.displayName,
                            addonName = "Streaming Brasil (TMDB)",
                            manifestUrl = MANIFEST_URL,
                            type = "series",
                            catalogId = provider.id,
                            supportsPagination = true,
                            descriptorSignature = "bestcine_br_${provider.id}_series_v1",
                        ),
                    )
                }
            }
        }
    }

    suspend fun fetchHomePreview(
        catalogId: String,
        type: String,
        isDesktop: Boolean,
        forceRefresh: Boolean = false,
    ): CatalogPage {
        val maxPages = if (isDesktop) 2 else 1
        var merged = emptyList<MetaPreview>()
        var lastNextSkip: Int? = null
        for (pageNum in 1..maxPages) {
            val page = fetchTmdbPage(
                providerId = catalogId,
                type = type,
                page = pageNum,
                forceRefresh = forceRefresh,
            )
            merged = mergeCatalogItems(merged, page.items)
            lastNextSkip = page.nextSkip
            if (page.nextSkip == null) break
        }
        return CatalogPage(
            items = merged,
            rawItemCount = merged.size,
            nextSkip = lastNextSkip,
        )
    }

    suspend fun fetchCatalogPage(
        catalogId: String,
        type: String,
        skip: Int?,
        forceRefresh: Boolean = false,
    ): CatalogPage {
        val pageNum = ((skip ?: 0) / TMDB_PAGE_SIZE) + 1
        return fetchTmdbPage(
            providerId = catalogId,
            type = type,
            page = pageNum.coerceAtLeast(1),
            forceRefresh = forceRefresh,
        )
    }

    suspend fun fetchDiscoverPage(
        catalogId: String,
        type: String,
        genre: String?,
        skip: Int?,
        forceRefresh: Boolean = false,
    ): CatalogPage = withContext(Dispatchers.Default) {
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        if (apiKey.isBlank()) {
            log.w { "TMDB API key indisponível para Descobrir ($catalogId)" }
            return@withContext CatalogPage(items = emptyList(), rawItemCount = 0, nextSkip = null)
        }

        val isSeries = type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
        val mediaKey = if (isSeries) "tv" else "movie"
        val page = (((skip ?: 0) / TMDB_PAGE_SIZE) + 1).coerceAtLeast(1)
        val language = normalizeTmdbLanguage(TmdbSettingsRepository.snapshot().language).ifBlank { "pt-BR" }
        val normalizedGenre = genre?.trim()?.takeIf { it.isNotEmpty() }
        val genreId = normalizedGenre?.let {
            if (isSeries) seriesDiscoverGenreIds[it] else movieDiscoverGenreIds[it]
        }
        val cacheId = "disc_${catalogId}_${genreId ?: "all"}"
        val cacheKey = CacheKey(providerId = cacheId, mediaType = mediaKey, page = page, language = language)

        if (!forceRefresh) {
            cacheMutex.withLock {
                val cached = pageCache[cacheKey]
                if (cached != null && cached.createdAt.elapsedNow() < CACHE_TTL) {
                    return@withContext cached.page
                }
            }
        }

        val endpoint = if (isSeries) "discover/tv" else "discover/movie"
        val query = linkedMapOf(
            "language" to language,
            "sort_by" to "popularity.desc",
            "include_adult" to "false",
            "page" to page.toString(),
        ).apply {
            when {
                catalogId == DISCOVER_TOP_RATED_ID -> {
                    put("sort_by", "vote_average.desc")
                    put("vote_count.gte", if (isSeries) "200" else "400")
                    put("region", WATCH_REGION_BR)
                }
                catalogId == DISCOVER_RECENT_ID && !isSeries -> {
                    val todayIso = CurrentDateProvider.todayIsoDate()
                    val currentYear = todayIso.take(4).toIntOrNull() ?: 2025
                    put("region", WATCH_REGION_BR)
                    put("primary_release_date.gte", "${currentYear - 1}-01-01")
                    put("primary_release_date.lte", todayIso)
                    put("vote_count.gte", "40")
                }
                catalogId == DISCOVER_BRAZIL_ID -> {
                    put("with_original_language", "pt")
                    put("with_origin_country", WATCH_REGION_BR)
                    put("vote_count.gte", "20")
                }
                catalogId == DISCOVER_ANIMES_ID -> {
                    put("with_genres", "16")
                    put("with_original_language", "ja")
                    put("vote_count.gte", "40")
                }
                catalogId == DISCOVER_DORAMAS_ID -> {
                    put("with_origin_country", "KR")
                    put("with_original_language", "ko")
                    put("vote_count.gte", "30")
                }
                catalogId.startsWith(DISCOVER_PROVIDER_PREFIX) -> {
                    val provId = catalogId.removePrefix(DISCOVER_PROVIDER_PREFIX)
                    val prov = providerById(provId)
                    if (prov != null) {
                        put("watch_region", WATCH_REGION_BR)
                        put("with_watch_providers", prov.tmdbProviderId.toString())
                        put("with_watch_monetization_types", prov.monetizationTypes)
                    }
                }
                else -> {
                    put("region", WATCH_REGION_BR)
                    put("vote_count.gte", if (isSeries) "50" else "80")
                }
            }
            if (genreId != null) {
                val existingGenres = get("with_genres")
                put("with_genres", if (existingGenres.isNullOrBlank()) genreId else "$existingGenres,$genreId")
            }
        }

        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)
        val response = runCatching {
            json.decodeFromString<TmdbStreamingDiscoverResponse>(httpGetText(url))
        }.onFailure { error ->
            log.w(error) { "Falha ao carregar Descobrir nativo $cacheId ($mediaKey, página $page)" }
        }.getOrNull() ?: return@withContext CatalogPage(items = emptyList(), rawItemCount = 0, nextSkip = null)

        val items = response.results
            .mapNotNull { it.toMetaPreview(isSeries = isSeries) }
            .distinctBy { it.id }

        val currentPage = response.page ?: page
        val totalPages = (response.totalPages ?: currentPage).coerceAtMost(25)
        val nextSkip = if (currentPage < totalPages && items.isNotEmpty()) {
            currentPage * TMDB_PAGE_SIZE
        } else {
            null
        }

        val catalogPage = CatalogPage(
            items = items,
            rawItemCount = items.size,
            nextSkip = nextSkip,
        )

        if (items.isNotEmpty()) {
            cacheMutex.withLock {
                pageCache[cacheKey] = CachedPage(
                    page = catalogPage,
                    createdAt = TimeSource.Monotonic.markNow(),
                )
            }
        }

        catalogPage
    }

    suspend fun clearCache() {
        cacheMutex.withLock {
            pageCache.clear()
        }
    }

    private suspend fun fetchTmdbPage(
        providerId: String,
        type: String,
        page: Int,
        forceRefresh: Boolean,
    ): CatalogPage = withContext(Dispatchers.Default) {
        val isReleasesMovies = providerId.equals(RELEASES_MOVIES_CATALOG_ID, ignoreCase = true)
        val isReleasesSeries = providerId.equals(RELEASES_SERIES_CATALOG_ID, ignoreCase = true)
        val isMomentCatalog = providerId.equals(MOMENT_CATALOG_ID, ignoreCase = true)
        val isSeasonalCatalog = providerId.equals(SEASONAL_CATALOG_ID, ignoreCase = true)
        val runtimeCatalog = if (!isReleasesMovies && !isReleasesSeries && !isMomentCatalog && !isSeasonalCatalog) {
            runtimeCatalogById(providerId)
        } else null
        val provider = if (!isReleasesMovies && !isReleasesSeries && !isMomentCatalog && !isSeasonalCatalog && runtimeCatalog == null) {
            providerById(providerId)
        } else null
        val seasonalEvent = if (isSeasonalCatalog) currentSeasonalEvent() else null
        if (!isReleasesMovies && !isReleasesSeries && !isMomentCatalog && seasonalEvent == null && runtimeCatalog == null && provider == null) {
            return@withContext CatalogPage(items = emptyList(), rawItemCount = 0, nextSkip = null)
        }

        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        if (apiKey.isBlank()) {
            log.w { "TMDB API key indisponível para catálogo $providerId" }
            return@withContext CatalogPage(items = emptyList(), rawItemCount = 0, nextSkip = null)
        }

        val isSeries = when {
            isReleasesSeries -> true
            isReleasesMovies || isMomentCatalog || isSeasonalCatalog -> false
            runtimeCatalog != null -> runtimeCatalog.type == "series"
            else -> type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
        }
        val mediaKey = if (isSeries) "tv" else "movie"
        val language = normalizeTmdbLanguage(TmdbSettingsRepository.snapshot().language).ifBlank { "pt-BR" }
        val momentPeriod = if (isMomentCatalog) currentMomentPeriod() else null
        val cacheId = when {
            isReleasesMovies -> RELEASES_MOVIES_CATALOG_ID
            isReleasesSeries -> RELEASES_SERIES_CATALOG_ID
            momentPeriod != null -> "${MOMENT_CATALOG_ID}_${momentPeriod.id}"
            seasonalEvent != null -> "${SEASONAL_CATALOG_ID}_${seasonalEvent.id}"
            runtimeCatalog != null -> runtimeCatalog.id
            else -> provider!!.id
        }
        val cacheTtl = if (isMomentCatalog) MOMENT_CACHE_TTL else CACHE_TTL
        val cacheKey = CacheKey(providerId = cacheId, mediaType = mediaKey, page = page, language = language)

        if (!forceRefresh) {
            cacheMutex.withLock {
                val cached = pageCache[cacheKey]
                if (cached != null && cached.createdAt.elapsedNow() < cacheTtl) {
                    return@withContext cached.page
                }
            }
        }

        val endpoint = if (isSeries) "discover/tv" else "discover/movie"
        val query = linkedMapOf(
            "language" to language,
            "sort_by" to "popularity.desc",
            "include_adult" to "false",
            "page" to page.toString(),
        ).apply {
            when {
                isReleasesMovies -> {
                    val todayIso = CurrentDateProvider.todayIsoDate()
                    put("region", WATCH_REGION_BR)
                    put("primary_release_date.gte", shiftIsoDateMonthsBack(todayIso, monthsBack = 4))
                    put("primary_release_date.lte", todayIso)
                    put("vote_count.gte", "25")
                }
                isReleasesSeries -> {
                    val todayIso = CurrentDateProvider.todayIsoDate()
                    put("first_air_date.gte", shiftIsoDateMonthsBack(todayIso, monthsBack = 6))
                    put("first_air_date.lte", todayIso)
                    put("vote_count.gte", "15")
                }
                momentPeriod != null -> {
                    put("region", WATCH_REGION_BR)
                    put("vote_average.gte", momentPeriod.minVoteAverage)
                    put("vote_count.gte", momentPeriod.minVoteCount.toString())
                    momentPeriod.withGenres?.let { put("with_genres", it) }
                    if (momentPeriod.recentOnly) {
                        val todayIso = CurrentDateProvider.todayIsoDate()
                        val currentYear = todayIso.take(4).toIntOrNull() ?: 2025
                        put("primary_release_date.gte", "${currentYear - 1}-01-01")
                        put("primary_release_date.lte", todayIso)
                    }
                }
                seasonalEvent != null -> {
                    put("region", WATCH_REGION_BR)
                    put("vote_average.gte", seasonalEvent.minVoteAverage)
                    put("vote_count.gte", seasonalEvent.minVoteCount.toString())
                    seasonalEvent.withGenres?.let { put("with_genres", it) }
                    seasonalEvent.withKeywords?.let { put("with_keywords", it) }
                }
                runtimeCatalog != null -> {
                    put("region", WATCH_REGION_BR)
                    when (runtimeCatalog.id) {
                        RUNTIME_QUICK_MOVIES_ID -> {
                            put("with_runtime.gte", "65")
                            put("with_runtime.lte", "90")
                            put("vote_average.gte", "6.3")
                            put("vote_count.gte", "150")
                        }
                        RUNTIME_SHORT_SERIES_ID -> {
                            put("with_type", "2")
                            put("vote_average.gte", "7.0")
                            put("vote_count.gte", "80")
                        }
                        RUNTIME_EPIC_MOVIES_ID -> {
                            put("with_runtime.gte", "150")
                            put("vote_average.gte", "6.8")
                            put("vote_count.gte", "250")
                        }
                    }
                }
                else -> {
                    put("watch_region", WATCH_REGION_BR)
                    put("with_watch_providers", provider!!.tmdbProviderId.toString())
                    put("with_watch_monetization_types", provider.monetizationTypes)
                }
            }
        }
        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)

        val response = runCatching {
            json.decodeFromString<TmdbStreamingDiscoverResponse>(httpGetText(url))
        }.onFailure { error ->
            log.w(error) { "Falha ao carregar catálogo $cacheId ($mediaKey, página $page)" }
        }.getOrNull() ?: return@withContext CatalogPage(items = emptyList(), rawItemCount = 0, nextSkip = null)

        val items = response.results
            .mapNotNull { it.toMetaPreview(isSeries = isSeries) }
            .distinctBy { it.id }

        val currentPage = response.page ?: page
        val totalPages = (response.totalPages ?: currentPage).coerceAtMost(25)
        val nextSkip = if (currentPage < totalPages && items.isNotEmpty()) {
            currentPage * TMDB_PAGE_SIZE
        } else {
            null
        }

        val catalogPage = CatalogPage(
            items = items,
            rawItemCount = items.size,
            nextSkip = nextSkip,
        )

        if (items.isNotEmpty()) {
            cacheMutex.withLock {
                pageCache[cacheKey] = CachedPage(
                    page = catalogPage,
                    createdAt = TimeSource.Monotonic.markNow(),
                )
            }
        }

        catalogPage
    }

    private fun TmdbStreamingDiscoverItem.toMetaPreview(isSeries: Boolean): MetaPreview? {
        val resolvedTitle = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null

        val posterUrl = tmdbImageUrl(posterPath, "w500") ?: tmdbImageUrl(backdropPath, "w780")
        if (posterUrl.isNullOrBlank()) return null

        val rawDate = if (isSeries) firstAirDate else releaseDate
        return MetaPreview(
            id = "tmdb:$id",
            type = if (isSeries) "series" else "movie",
            name = resolvedTitle,
            poster = posterUrl,
            banner = tmdbImageUrl(backdropPath, "w1280"),
            posterShape = PosterShape.Poster,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = rawDate?.take(4),
            rawReleaseDate = rawDate,
            popularity = popularity,
            voteCount = voteCount,
            imdbRating = voteAverage?.takeIf { it > 0.0 }?.let { ((it * 10).roundToInt() / 10.0).toString() },
            genres = genreIds.mapNotNull { TMDB_GENRE_NAMES_PT[it] },
        )
    }

    private val TMDB_GENRE_NAMES_PT = mapOf(
        28 to "Ação",
        12 to "Aventura",
        16 to "Animação",
        35 to "Comédia",
        80 to "Crime",
        99 to "Documentário",
        18 to "Drama",
        10751 to "Família",
        14 to "Fantasia",
        36 to "História",
        27 to "Terror",
        10402 to "Música",
        9648 to "Mistério",
        10749 to "Romance",
        878 to "Sci-Fi",
        10770 to "Filme TV",
        53 to "Suspense",
        10752 to "Guerra",
        37 to "Faroeste",
        10759 to "Ação",
        10762 to "Infantil",
        10763 to "Notícias",
        10764 to "Reality",
        10765 to "Sci-Fi",
        10766 to "Novela",
        10767 to "Talk Show",
        10768 to "Guerra",
    )

    private fun shiftIsoDateMonthsBack(todayIso: String, monthsBack: Int): String {
        val parts = todayIso.split("-")
        var year = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        var month = (parts.getOrNull(1)?.toIntOrNull() ?: 1) - monthsBack
        while (month <= 0) {
            month += 12
            year -= 1
        }
        val mm = month.toString().padStart(2, '0')
        return "$year-$mm-01"
    }

    private fun tmdbImageUrl(path: String?, size: String): String? {
        val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size/${clean.removePrefix("/")}"
    }

    private data class CacheKey(
        val providerId: String,
        val mediaType: String,
        val page: Int,
        val language: String,
    )

    private data class CachedPage(
        val page: CatalogPage,
        val createdAt: TimeMark,
    )
}

@Serializable
private data class TmdbStreamingDiscoverResponse(
    val page: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null,
    val results: List<TmdbStreamingDiscoverItem> = emptyList(),
)

@Serializable
private data class TmdbStreamingDiscoverItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val popularity: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
)
