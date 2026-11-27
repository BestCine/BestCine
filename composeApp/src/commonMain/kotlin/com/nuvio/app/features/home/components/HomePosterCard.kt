package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.desktopCatalogShelfPosterBaseWidthDp
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.resolveBestCinePosterBadges
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository

@Composable
fun HomePosterCard(
    item: MetaPreview,
    modifier: Modifier = Modifier,
    useLandscapeBackdropMode: Boolean = false,
    isWatched: Boolean = false,
    sectionTitle: String? = null,
    rankPosition: Int? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    showLandscapeOverlay: Boolean = true,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val isLandscapeMode = useLandscapeBackdropMode || posterCardStyle.catalogLandscapeModeEnabled
    val effectiveLandscapePoster = if (posterCardStyle.alwaysShowLandscapeClearlogo) null else item.landscapePoster
    val wantsLandscapeLogoOverlay = isLandscapeMode && showLandscapeOverlay && effectiveLandscapePoster.isNullOrBlank()
    val needsLazyLogo = wantsLandscapeLogoOverlay && item.logo.isNullOrBlank()
    val tmdbLanguage = remember {
        TmdbSettingsRepository.snapshot().language.ifBlank { "pt-BR" }
    }
    var resolvedLogoUrl by remember(item.id, item.type, item.logo, needsLazyLogo, tmdbLanguage) {
        mutableStateOf(
            item.logo?.takeIf { it.isNotBlank() }
                ?: if (needsLazyLogo) {
                    TmdbMetadataService.cachedLocalizedLogo(item.id, item.type, tmdbLanguage)
                } else {
                    null
                },
        )
    }
    LaunchedEffect(item.id, item.type, item.logo, needsLazyLogo, tmdbLanguage) {
        if (needsLazyLogo && resolvedLogoUrl.isNullOrBlank()) {
            val fetched = TmdbMetadataService.fetchLocalizedLogo(item.id, item.type, tmdbLanguage)
            if (!fetched.isNullOrBlank()) {
                resolvedLogoUrl = fetched
            }
        }
    }
    val effectiveLogoUrl = if (wantsLandscapeLogoOverlay) resolvedLogoUrl else null
    val imageUrl = if (isLandscapeMode) {
        effectiveLandscapePoster ?: item.banner ?: item.poster
    } else item.poster
    val fallbackImageUrl = if (isLandscapeMode && !effectiveLandscapePoster.isNullOrBlank()) {
        // Landscape custom poster -> fall back to original backdrop, then portrait
        item.banner ?: item.rawPosterUrl
    } else {
        item.rawPosterUrl
    }
    val nativeBadge = remember(
        posterCardStyle.nativePosterBadgesEnabled,
        isLandscapeMode,
        item.posterShape,
        item.type,
        item.genres,
        item.imdbRating,
        item.releaseInfo,
        item.rawReleaseDate,
        sectionTitle,
        rankPosition,
    ) {
        if (posterCardStyle.nativePosterBadgesEnabled && !isLandscapeMode && item.posterShape == PosterShape.Poster) {
            resolveBestCinePosterBadges(
                type = item.type,
                genres = item.genres,
                imdbRating = item.imdbRating,
                releaseInfo = item.releaseInfo,
                rawReleaseDate = item.rawReleaseDate,
                sectionTitle = sectionTitle,
                rankPosition = rankPosition,
            )
        } else {
            null
        }
    }

    val previewItem = remember(item, effectiveLogoUrl) {
        if (!effectiveLogoUrl.isNullOrBlank() && item.logo.isNullOrBlank()) {
            item.copy(logo = effectiveLogoUrl)
        } else {
            item
        }
    }

    HomePosterHoverPreview(
        item = previewItem,
        isWatched = isWatched,
        onClick = onClick,
        onLongClick = onLongClick,
    ) { hoverModifier ->
        NuvioPosterCard(
            title = item.name,
            imageUrl = imageUrl,
            modifier = modifier.then(hoverModifier),
            fallbackImageUrl = fallbackImageUrl,
            basePosterWidthDp = desktopCatalogShelfPosterBaseWidthDp(posterCardStyle.widthDp),
            shape = if (isLandscapeMode) NuvioPosterShape.Landscape else item.posterShape.toNuvioPosterShape(),
            detailLine = if (isLandscapeMode || posterCardStyle.hideLabelsEnabled) null else item.releaseInfo?.let { formatReleaseDateForDisplay(it) },
            showTitleBelow = !posterCardStyle.hideLabelsEnabled,
            bottomLeftLogoUrl = effectiveLogoUrl,
            bottomLeftText = if (wantsLandscapeLogoOverlay && effectiveLogoUrl.isNullOrBlank() && posterCardStyle.hideLabelsEnabled) item.name else null,
            isWatched = isWatched,
            nativeBadge = nativeBadge,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

private fun PosterShape.toNuvioPosterShape(): NuvioPosterShape =
    when (this) {
        PosterShape.Poster -> NuvioPosterShape.Poster
        PosterShape.Square -> NuvioPosterShape.Square
        PosterShape.Landscape -> NuvioPosterShape.Landscape
    }
