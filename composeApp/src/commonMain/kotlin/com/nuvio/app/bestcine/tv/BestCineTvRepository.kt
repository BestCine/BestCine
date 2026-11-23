package com.nuvio.app.bestcine.tv

import co.touchlab.kermit.Logger
import com.nuvio.app.bestcine.BestCineAccountRepository
import com.nuvio.app.bestcine.BestCineBranding
import com.nuvio.app.bestcine.BestCinePlatformStorage
import com.nuvio.app.bestcine.bestCineCurrentTimeMillis
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.player.sanitizePlaybackResponseHeaders
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

object BestCineTvRepository {
    private val log = Logger.withTag("BestCineTv")
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(BestCineTvUiState())
    val uiState: StateFlow<BestCineTvUiState> = _uiState.asStateFlow()

    private const val EPG_CACHE_TTL_MS = 5 * 60_000L
    private data class CachedEpg(val details: BestCineTvEpgDetails, val fetchedAtMs: Long)
    private val epgCache = mutableMapOf<String, CachedEpg>()
    private var favoritesLoaded = false

    private fun ensureFavoritesLoaded(): Set<String> {
        if (!favoritesLoaded) {
            favoritesLoaded = true
            val loaded = runCatching { BestCinePlatformStorage.loadTvFavoriteChannelIds() }.getOrDefault(emptySet())
            _uiState.update { state ->
                state.copy(
                    favoriteChannelIds = loaded,
                    categories = buildCategories(state.channels, loaded),
                )
            }
            return loaded
        }
        return _uiState.value.favoriteChannelIds
    }

    private fun buildCategories(
        channels: List<BestCineTvChannel>,
        favoriteIds: Set<String>,
    ): List<BestCineTvCategoryItem> {
        val countsByGroup = channels.groupingBy { it.group }.eachCount()
        val favCount = channels.count { it.id in favoriteIds }
        return buildList {
            add(BestCineTvCategoryItem(BESTCINE_TV_ALL_CATEGORY, channels.size))
            add(BestCineTvCategoryItem(BESTCINE_TV_FAVORITES_CATEGORY, favCount))
            addAll(
                countsByGroup.entries
                    .sortedBy { it.key.lowercase() }
                    .map { (groupName, count) -> BestCineTvCategoryItem(groupName, count) },
            )
        }
    }

    fun toggleFavoriteChannel(channelId: String) {
        val cleanId = channelId.trim()
        if (cleanId.isEmpty()) return
        val currentFavs = ensureFavoritesLoaded()
        val nextFavs = if (cleanId in currentFavs) currentFavs - cleanId else currentFavs + cleanId
        runCatching { BestCinePlatformStorage.saveTvFavoriteChannelIds(nextFavs) }
        _uiState.update { state ->
            state.copy(
                favoriteChannelIds = nextFavs,
                categories = buildCategories(state.channels, nextFavs),
            )
        }
    }

    fun setSelectedCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun setCategorySearchQuery(query: String) {
        _uiState.update { it.copy(categorySearchQuery = query) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectActiveStreamIndex(index: Int) {
        _uiState.update { state ->
            if (index !in state.activeStreams.indices) state
            else state.copy(activeStreamIndex = index)
        }
    }

    fun setFullscreen(enabled: Boolean) {
        _uiState.update { state ->
            val next = enabled && state.activeChannel != null
            if (state.isFullscreen == next) state else state.copy(isFullscreen = next)
        }
    }

    fun closeEmbeddedPlayer() {
        _uiState.update {
            it.copy(
                activeChannel = null,
                activeStreams = emptyList(),
                activeStreamIndex = 0,
                isFullscreen = false,
                activeEpgDetails = null,
                isLoadingEpg = false,
            )
        }
    }

    suspend fun loadChannelEpgDetails(channel: BestCineTvChannel) {
        val now = bestCineCurrentTimeMillis()
        epgCache[channel.id]?.takeIf { now - it.fetchedAtMs < EPG_CACHE_TTL_MS }?.let { cached ->
            if (_uiState.value.activeChannel?.id == channel.id) {
                _uiState.update { it.copy(activeEpgDetails = cached.details, isLoadingEpg = false) }
            }
            return
        }

        // Exibe imediatamente o resumo do catálogo enquanto busca o EPG completo em /meta/tv/
        val fallbackDetails = parseEpgDescription(channel.epgSummary)
        if (_uiState.value.activeChannel?.id == channel.id) {
            _uiState.update { it.copy(activeEpgDetails = fallbackDetails, isLoadingEpg = true) }
        }

        val addonBaseUrl = resolveBestCineAddonBaseUrl() ?: run {
            if (_uiState.value.activeChannel?.id == channel.id) {
                _uiState.update { it.copy(isLoadingEpg = false) }
            }
            return
        }

        val metaUrl = "$addonBaseUrl/meta/tv/${channel.id}.json"
        val fetched = runCatching {
            withContext(Dispatchers.Default) {
                val payload = fetchAddonResponseText(metaUrl, forceRefresh = false)
                json.decodeFromString<BestCineTvMetaResponseDto>(payload)
            }
        }.getOrNull()

        val rawDesc = fetched?.meta?.description?.trim()?.takeIf { it.isNotEmpty() } ?: channel.epgSummary
        val parsed = parseEpgDescription(rawDesc)
        epgCache[channel.id] = CachedEpg(details = parsed, fetchedAtMs = bestCineCurrentTimeMillis())

        if (_uiState.value.activeChannel?.id == channel.id) {
            _uiState.update { it.copy(activeEpgDetails = parsed, isLoadingEpg = false) }
        }
    }

    private fun parseEpgDescription(raw: String): BestCineTvEpgDetails {
        val lines = raw.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return BestCineTvEpgDetails(rawText = "")

        var nowLine: String? = null
        val synopsisLines = mutableListOf<String>()
        val upcomingLines = mutableListOf<String>()
        var footerLine: String? = null

        lines.forEach { line ->
            when {
                line.contains("AGORA", ignoreCase = true) && nowLine == null -> nowLine = line
                line.startsWith("⏭") || line.startsWith("📅") || line.startsWith("Próximo", ignoreCase = true) -> {
                    upcomingLines += line
                }
                line.startsWith("📺") -> footerLine = line
                nowLine != null && upcomingLines.isEmpty() -> synopsisLines += line
                else -> synopsisLines += line
            }
        }

        return BestCineTvEpgDetails(
            nowLine = nowLine,
            synopsis = synopsisLines.joinToString("\n").takeIf { it.isNotBlank() },
            upcomingLines = upcomingLines,
            footerLine = footerLine,
            rawText = raw,
        )
    }

    fun clearBanner() {
        _uiState.update { it.copy(bannerMessage = null, isBannerError = false) }
    }

    suspend fun ensureCatalogLoaded(forceRefresh: Boolean = false) {
        val current = _uiState.value
        if (current.isLoadingCatalog) return
        if (!forceRefresh && current.hasLoadedOnce && current.channels.isNotEmpty()) return

        val addonBaseUrl = resolveBestCineAddonBaseUrl()
        if (addonBaseUrl == null) {
            _uiState.update {
                it.copy(
                    isLoadingCatalog = false,
                    hasLoadedOnce = true,
                    catalogError = "Conecte sua conta BestCine em Configurações > Conta para carregar os canais de TV ao vivo.",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isLoadingCatalog = true,
                catalogError = null,
                bannerMessage = null,
            )
        }

        val catalogUrl = "$addonBaseUrl/catalog/tv/bestcine_tv_catalog.json"
        log.i { "Carregando grade de TV ao vivo (1 requisição): $catalogUrl" }

        val result = runCatching {
            withContext(Dispatchers.Default) {
                val payload = fetchAddonResponseText(catalogUrl, forceRefresh = forceRefresh)
                json.decodeFromString<BestCineTvCatalogResponseDto>(payload)
            }
        }

        result.fold(
            onSuccess = { dto ->
                val channels = dto.metas
                    .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                    .map { meta ->
                        val groupName = meta.genres.firstOrNull { it.isNotBlank() }?.trim() ?: "Outros"
                        BestCineTvChannel(
                            id = meta.id.trim(),
                            name = meta.name.trim(),
                            group = groupName,
                            logoUrl = meta.poster?.takeIf { it.isNotBlank() },
                            backgroundUrl = meta.background?.takeIf { it.isNotBlank() } ?: meta.poster?.takeIf { it.isNotBlank() },
                            epgSummary = meta.description.orEmpty().trim(),
                        )
                    }

                val favoriteIds = ensureFavoritesLoaded()
                val categories = buildCategories(channels, favoriteIds)

                if (forceRefresh) {
                    BestCineTvZappingGuard.clearCache()
                }

                _uiState.update { state ->
                    val validSelectedCategory = if (categories.any { it.name == state.selectedCategory }) {
                        state.selectedCategory
                    } else {
                        BESTCINE_TV_ALL_CATEGORY
                    }
                    state.copy(
                        isLoadingCatalog = false,
                        hasLoadedOnce = true,
                        channels = channels,
                        categories = categories,
                        favoriteChannelIds = favoriteIds,
                        selectedCategory = validSelectedCategory,
                        catalogError = if (channels.isEmpty()) {
                            "Nenhum canal de TV ativo para sua conta. Verifique se a TV ao vivo está ativada no painel do BestCine."
                        } else {
                            null
                        },
                    )
                }
            },
            onFailure = { error ->
                log.e(error) { "Erro ao carregar grade de TV BestCine" }
                _uiState.update {
                    it.copy(
                        isLoadingCatalog = false,
                        hasLoadedOnce = true,
                        catalogError = "Não foi possível carregar os canais ao vivo: ${error.message ?: "erro de conexão"}",
                    )
                }
            },
        )
    }

    suspend fun openChannelEmbedded(channel: BestCineTvChannel): BestCineTvResolveResult {
        val current = _uiState.value
        if (current.activeChannel?.id == channel.id && current.activeStreams.isNotEmpty()) {
            return BestCineTvResolveResult.Ready(channel, current.activeStreams, fromCache = true)
        }

        // 1. Reaproveita cache curto em RAM (45s) — zero hit no servidor!
        BestCineTvZappingGuard.getCachedStreams(channel.id)?.let { cached ->
            _uiState.update {
                it.copy(
                    openingChannelId = null,
                    activeChannel = channel,
                    activeStreams = cached,
                    activeStreamIndex = 0,
                    bannerMessage = null,
                    isBannerError = false,
                )
            }
            return BestCineTvResolveResult.Ready(channel = channel, streams = cached, fromCache = true)
        }

        val addonBaseUrl = resolveBestCineAddonBaseUrl()
            ?: return BestCineTvResolveResult.Error("Conta BestCine não conectada.")

        // 2. Valida guarda preventiva de zapping ANTES de bater no servidor
        when (val guard = BestCineTvZappingGuard.checkAndRecordNetworkFetch(channel.id)) {
            is BestCineTvZappingGuard.CheckOutcome.Blocked -> {
                _uiState.update {
                    it.copy(
                        openingChannelId = null,
                        bannerMessage = guard.message,
                        isBannerError = true,
                    )
                }
                return BestCineTvResolveResult.Cooldown(
                    message = guard.message,
                    retryAfterSeconds = guard.retryAfterSeconds,
                )
            }
            BestCineTvZappingGuard.CheckOutcome.Allowed -> Unit
        }

        _uiState.update {
            it.copy(
                openingChannelId = channel.id,
                bannerMessage = "Sintonizando ${channel.name}...",
                isBannerError = false,
            )
        }

        val streamUrl = "$addonBaseUrl/stream/tv/${channel.id}.json"
        val fetched = runCatching {
            withContext(Dispatchers.Default) {
                val payload = fetchAddonResponseText(streamUrl, forceRefresh = true)
                StreamParser.parse(
                    payload = payload,
                    addonName = BestCineBranding.APP_NAME,
                    addonId = "bestcine",
                    addonLogo = channel.logoUrl,
                )
            }
        }

        return fetched.fold(
            onSuccess = { streams ->
                val shieldItem = streams.firstOrNull { isShieldWarningStream(it) }
                if (shieldItem != null) {
                    val shieldText = (shieldItem.title ?: shieldItem.description ?: "Aguarde alguns segundos antes de trocar de canal.")
                        .replace("\n\n", " — ")
                        .replace("\n", " ")
                    _uiState.update {
                        it.copy(
                            openingChannelId = null,
                            bannerMessage = shieldText,
                            isBannerError = true,
                        )
                    }
                    return@fold BestCineTvResolveResult.Cooldown(message = shieldText, retryAfterSeconds = 10)
                }

                val playable = streams.filter { !it.url.isNullOrBlank() }
                if (playable.isEmpty()) {
                    val msg = "Nenhuma fonte ao vivo disponível para ${channel.name} no momento."
                    _uiState.update {
                        it.copy(
                            openingChannelId = null,
                            bannerMessage = msg,
                            isBannerError = true,
                        )
                    }
                    return@fold BestCineTvResolveResult.Error(msg)
                }

                BestCineTvZappingGuard.cacheStreams(channel.id, playable)
                _uiState.update {
                    it.copy(
                        openingChannelId = null,
                        activeChannel = channel,
                        activeStreams = playable,
                        activeStreamIndex = 0,
                        bannerMessage = null,
                        isBannerError = false,
                    )
                }
                BestCineTvResolveResult.Ready(channel = channel, streams = playable, fromCache = false)
            },
            onFailure = { error ->
                val msg = "Erro ao abrir ${channel.name}: ${error.message ?: "falha de conexão"}"
                _uiState.update {
                    it.copy(
                        openingChannelId = null,
                        bannerMessage = msg,
                        isBannerError = true,
                    )
                }
                BestCineTvResolveResult.Error(msg)
            },
        )
    }

    fun buildPlayerLaunch(
        channel: BestCineTvChannel,
        stream: StreamItem,
    ): PlayerLaunch? {
        val url = stream.url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request.orEmpty())
        val responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response.orEmpty())
        val label = listOfNotNull(
            stream.name?.trim()?.takeIf { it.isNotEmpty() }?.replace("\n", " "),
            stream.title?.trim()?.takeIf { it.isNotEmpty() }?.lineSequence()?.firstOrNull(),
        ).distinct().joinToString(" • ").ifBlank { BestCineBranding.APP_NAME }

        return PlayerLaunch(
            profileId = ProfileRepository.activeProfileId,
            title = channel.name,
            sourceUrl = url,
            sourceHeaders = requestHeaders,
            sourceResponseHeaders = responseHeaders,
            streamType = stream.streamType,
            logo = channel.logoUrl,
            poster = channel.logoUrl,
            background = channel.backgroundUrl,
            streamTitle = channel.name,
            streamSubtitle = label,
            pauseDescription = channel.epgSummary.takeIf { it.isNotBlank() },
            providerName = BestCineBranding.APP_NAME,
            providerAddonId = "bestcine",
            contentType = "tv",
            videoId = channel.id,
            parentMetaId = channel.id,
            parentMetaType = "tv",
            stream = stream,
            sourceAutoSelected = true,
        )
    }

    private fun resolveBestCineAddonBaseUrl(): String? {
        BestCineAccountRepository.ensureLoaded()
        val session = BestCineAccountRepository.state.value.session
        if (session != null && session.user.tokenInstalacao.isNotBlank()) {
            return "${session.normalizedBaseUrl}/${session.user.tokenInstalacao.trim()}"
        }

        val installed = AddonRepository.uiState.value.addons.firstOrNull { addon ->
            addon.enabled && (
                addon.manifestUrl.contains("bestcine", ignoreCase = true) ||
                    addon.manifest?.catalogs?.any { it.id == "bestcine_tv_catalog" } == true
                )
        }
        return installed?.manifestUrl
            ?.removeSuffix("/manifest.json")
            ?.trimEnd('/')
    }

    private fun isShieldWarningStream(stream: StreamItem): Boolean {
        val name = stream.name.orEmpty()
        val title = stream.title.orEmpty()
        val url = stream.url.orEmpty()
        return name.contains("Shield", ignoreCase = true) ||
            title.contains("ZAPPING MUITO RÁPIDO", ignoreCase = true) ||
            title.contains("EXPORTAÇÃO M3U BLOQUEADA", ignoreCase = true) ||
            title.contains("RASPAGEM M3U DETECTADA", ignoreCase = true) ||
            url.contains("image2url.com/r2/default/videos/", ignoreCase = true)
    }
}
