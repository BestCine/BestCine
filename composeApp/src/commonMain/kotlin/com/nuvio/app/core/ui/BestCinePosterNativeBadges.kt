package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlin.math.roundToInt

@Immutable
data class BestCinePosterBadgeSpec(
    val topLabel: String? = null,
    val isAccentTop: Boolean = false,
    val genreLabel: String? = null,
    val ratingLabel: String? = null,
) {
    val hasTop: Boolean get() = !topLabel.isNullOrBlank()
    val hasBottom: Boolean get() = !genreLabel.isNullOrBlank() || !ratingLabel.isNullOrBlank()
    val hasAny: Boolean get() = hasTop || hasBottom
}

private val GENRE_TRANSLATIONS_PT = mapOf(
    "action" to "Ação",
    "adventure" to "Aventura",
    "animation" to "Animação",
    "comedy" to "Comédia",
    "crime" to "Crime",
    "documentary" to "Documentário",
    "drama" to "Drama",
    "family" to "Família",
    "fantasy" to "Fantasia",
    "history" to "História",
    "horror" to "Terror",
    "music" to "Música",
    "mystery" to "Mistério",
    "romance" to "Romance",
    "sci-fi" to "Sci-Fi",
    "science fiction" to "Sci-Fi",
    "ficção científica" to "Sci-Fi",
    "ficcao cientifica" to "Sci-Fi",
    "sci-fi & fantasy" to "Sci-Fi",
    "action & adventure" to "Ação",
    "thriller" to "Suspense",
    "war" to "Guerra",
    "war & politics" to "Guerra",
    "western" to "Faroeste",
    "tv movie" to "Filme TV",
    "kids" to "Infantil",
    "reality" to "Reality",
)

private val RANKED_SHELF_KEYWORDS = listOf(
    "em alta",
    "populares",
    "popular",
    "trending",
    "tendência",
    "tendencia",
    "top ",
    "mais assistidos",
    "sessão do momento",
    "sessao do momento",
    "destaque",
)

private val CINEMA_SHELF_KEYWORDS = listOf(
    "no cinema",
    "nos cinemas",
    "em cartaz",
    "estreia",
    "lançamento",
    "lancamento",
)

internal fun resolveBestCinePosterBadges(
    type: String,
    genres: List<String>,
    imdbRating: String?,
    releaseInfo: String?,
    rawReleaseDate: String?,
    sectionTitle: String? = null,
    rankPosition: Int? = null,
): BestCinePosterBadgeSpec? {
    val primaryGenre = genres.firstOrNull { it.isNotBlank() }?.let(::normalizePosterGenrePt)
    val formattedRating = formatPosterRating(imdbRating)

    val currentYear = CurrentDateProvider.todayIsoDate().take(4).toIntOrNull()
    val releaseYear = extractReleaseYear(rawReleaseDate) ?: extractReleaseYear(releaseInfo)
    val normalizedSection = sectionTitle?.lowercase().orEmpty()
    val isMovie = type.equals("movie", ignoreCase = true)

    val isRankedShelf = rankPosition != null &&
        rankPosition in 1..20 &&
        normalizedSection.isNotBlank() &&
        RANKED_SHELF_KEYWORDS.any { it in normalizedSection }

    val isCinemaShelf = normalizedSection.isNotBlank() &&
        CINEMA_SHELF_KEYWORDS.any { it in normalizedSection }

    val isCurrentYearRelease = currentYear != null && releaseYear != null && releaseYear >= currentYear

    val topLabel: String?
    val isAccentTop: Boolean
    when {
        isRankedShelf -> {
            topLabel = "#$rankPosition Hoje"
            isAccentTop = rankPosition!! <= 3
        }
        isMovie && isCurrentYearRelease && isCinemaShelf -> {
            topLabel = "No Cinema"
            isAccentTop = true
        }
        isCurrentYearRelease -> {
            topLabel = if (isMovie) "Novo Filme" else "Nova Série"
            isAccentTop = false
        }
        else -> {
            topLabel = null
            isAccentTop = false
        }
    }

    if (topLabel == null && primaryGenre == null && formattedRating == null) return null

    return BestCinePosterBadgeSpec(
        topLabel = topLabel,
        isAccentTop = isAccentTop,
        genreLabel = primaryGenre,
        ratingLabel = formattedRating,
    )
}

private fun normalizePosterGenrePt(raw: String): String? {
    val cleaned = raw.trim().takeIf { it.isNotEmpty() } ?: return null
    val mapped = GENRE_TRANSLATIONS_PT[cleaned.lowercase()]
    return mapped ?: cleaned
}

private fun formatPosterRating(raw: String?): String? {
    val value = raw?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
    if (value <= 0.0 || value > 10.0) return null
    val roundedTenths = (value * 10.0).roundToInt()
    val whole = roundedTenths / 10
    val frac = roundedTenths % 10
    return "$whole.$frac"
}

private fun extractReleaseYear(raw: String?): Int? {
    val text = raw?.trim() ?: return null
    if (text.length < 4) return null
    val firstFour = text.substring(0, 4).toIntOrNull()
    if (firstFour != null && firstFour in 1900..2100) return firstFour
    var digits = 0
    for (i in text.indices) {
        if (text[i] in '0'..'9') {
            digits++
            if (digits == 4) {
                val candidate = text.substring(i - 3, i + 1).toIntOrNull()
                if (candidate != null && candidate in 1900..2100) return candidate
            }
        } else {
            digits = 0
        }
    }
    return null
}

private val TopBadgeShape = RoundedCornerShape(bottomStart = 7.dp, bottomEnd = 7.dp)

@Composable
internal fun BoxScope.BestCinePosterNativeOverlay(
    badge: BestCinePosterBadgeSpec?,
    isCompactCard: Boolean = false,
) {
    if (badge == null || !badge.hasAny) return

    if (badge.hasTop) {
        val bgColor = if (badge.isAccentTop) Color(0xE8EEF4FF) else Color(0xD90E1626)
        val borderColor = if (badge.isAccentTop) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.18f)
        val textColor = if (badge.isAccentTop) Color(0xFF0A1020) else Color(0xFFF1F5F9)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .clip(TopBadgeShape)
                .background(bgColor)
                .border(width = 0.75.dp, color = borderColor, shape = TopBadgeShape)
                .padding(
                    horizontal = if (isCompactCard) 7.dp else 9.dp,
                    vertical = if (isCompactCard) 2.dp else 2.5.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = badge.topLabel.orEmpty(),
                color = textColor,
                fontSize = if (isCompactCard) 9.sp else 10.sp,
                lineHeight = if (isCompactCard) 11.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (badge.hasBottom) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val scrim = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.68f to Color.Transparent,
                            0.86f to Color(0xB8060A14),
                            1.0f to Color(0xEE060A14),
                        ),
                    )
                    onDrawBehind { drawRect(scrim) }
                },
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    horizontal = if (isCompactCard) 5.dp else 7.dp,
                    vertical = if (isCompactCard) 4.dp else 5.dp,
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fontSize = if (isCompactCard) 9.5.sp else 10.5.sp
            val lineHeight = if (isCompactCard) 11.sp else 12.5.sp
            if (!badge.genreLabel.isNullOrBlank()) {
                Text(
                    text = badge.genreLabel,
                    color = Color(0xFFE2E8F0),
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (!badge.genreLabel.isNullOrBlank() && !badge.ratingLabel.isNullOrBlank()) {
                Text(
                    text = " • ",
                    color = Color(0xFF94A3B8),
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (!badge.ratingLabel.isNullOrBlank()) {
                Text(
                    text = "★ ",
                    color = Color(0xFFFFCA28),
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = badge.ratingLabel,
                    color = Color.White,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
