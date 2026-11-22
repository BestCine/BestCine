package com.nuvio.app.bestcine.tv

import com.nuvio.app.features.streams.StreamItem
import kotlinx.serialization.Serializable

@Serializable
data class BestCineTvMetaDto(
    val id: String = "",
    val type: String = "tv",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
data class BestCineTvCatalogResponseDto(
    val metas: List<BestCineTvMetaDto> = emptyList(),
)

@Serializable
data class BestCineTvMetaResponseDto(
    val meta: BestCineTvMetaDto? = null,
)

data class BestCineTvEpgDetails(
    val nowLine: String? = null,
    val synopsis: String? = null,
    val upcomingLines: List<String> = emptyList(),
    val footerLine: String? = null,
    val rawText: String = "",
)

data class BestCineTvChannel(
    val id: String,
    val name: String,
    val group: String,
    val logoUrl: String?,
    val backgroundUrl: String?,
    val epgSummary: String,
)

data class BestCineTvCategoryItem(
    val name: String,
    val count: Int,
)

sealed interface BestCineTvResolveResult {
    data class Ready(
        val channel: BestCineTvChannel,
        val streams: List<StreamItem>,
        val fromCache: Boolean,
    ) : BestCineTvResolveResult

    data class Cooldown(
        val message: String,
        val retryAfterSeconds: Int,
    ) : BestCineTvResolveResult

    data class Error(
        val message: String,
    ) : BestCineTvResolveResult
}

data class BestCineTvUiState(
    val isLoadingCatalog: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val channels: List<BestCineTvChannel> = emptyList(),
    val categories: List<BestCineTvCategoryItem> = listOf(BestCineTvCategoryItem(BESTCINE_TV_ALL_CATEGORY, 0)),
    val selectedCategory: String = BESTCINE_TV_ALL_CATEGORY,
    val categorySearchQuery: String = "",
    val searchQuery: String = "",
    val openingChannelId: String? = null,
    val activeChannel: BestCineTvChannel? = null,
    val activeStreams: List<StreamItem> = emptyList(),
    val activeStreamIndex: Int = 0,
    val isFullscreen: Boolean = false,
    val activeEpgDetails: BestCineTvEpgDetails? = null,
    val isLoadingEpg: Boolean = false,
    val bannerMessage: String? = null,
    val isBannerError: Boolean = false,
    val catalogError: String? = null,
) {
    val activeStream: StreamItem?
        get() = activeStreams.getOrNull(activeStreamIndex) ?: activeStreams.firstOrNull()
}

const val BESTCINE_TV_ALL_CATEGORY = "Todos"
