package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.desktopCatalogShelfPosterBaseWidthDp
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.resolveBestCinePosterBadges
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape

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

    HomePosterHoverPreview(
        item = item,
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
            bottomLeftLogoUrl = if (isLandscapeMode && showLandscapeOverlay && effectiveLandscapePoster.isNullOrBlank()) item.logo else null,
            bottomLeftText = if (isLandscapeMode && showLandscapeOverlay && effectiveLandscapePoster.isNullOrBlank() && item.logo.isNullOrBlank() && !posterCardStyle.hideLabelsEnabled) item.name else null,
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
