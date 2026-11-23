package com.nuvio.app.features.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.bestcine.BestCineBranding
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.toContinueWatchingItem

internal data class LibraryHistoryRecord(
    val stableKey: String,
    val contentId: String,
    val contentType: String,
    val title: String,
    val posterUrl: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val lastPositionMs: Long,
    val durationMs: Long,
    val progressFraction: Float,
    val isCompleted: Boolean,
    val timestampMs: Long,
    val progressEntry: WatchProgressEntry?,
    val watchedItem: WatchedItem?,
) {
    fun toMetaPreview(): MetaPreview = MetaPreview(
        id = contentId,
        type = contentType.ifBlank { if (seasonNumber != null) "series" else "movie" },
        name = title,
        poster = posterUrl,
    )
}

internal fun buildLibraryContinueWatchingRecords(
    progressEntries: List<WatchProgressEntry>,
): List<LibraryHistoryRecord> {
    val byKey = linkedMapOf<String, WatchProgressEntry>()
    progressEntries
        .sortedByDescending { it.lastUpdatedEpochMs }
        .forEach { entry ->
            if (entry.isEffectivelyCompleted) return@forEach
            val fraction = entry.effectiveProgressFraction()
            if (entry.lastPositionMs <= 0L && fraction <= 0f) return@forEach
            val key = historyRecordKey(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
            if (!byKey.containsKey(key)) {
                byKey[key] = entry
            }
        }
    return byKey.values.map { entry ->
        val fraction = entry.effectiveProgressFraction()
        LibraryHistoryRecord(
            stableKey = "cw:${historyRecordKey(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)}",
            contentId = entry.parentMetaId,
            contentType = entry.parentMetaType,
            title = entry.title.ifBlank { entry.parentMetaId },
            posterUrl = entry.poster?.takeIf { it.isNotBlank() }
                ?: entry.episodeThumbnail?.takeIf { it.isNotBlank() }
                ?: entry.background?.takeIf { it.isNotBlank() },
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            episodeTitle = entry.episodeTitle?.takeIf { it.isNotBlank() },
            lastPositionMs = entry.lastPositionMs.coerceAtLeast(0L),
            durationMs = entry.durationMs.coerceAtLeast(0L),
            progressFraction = fraction,
            isCompleted = false,
            timestampMs = entry.lastUpdatedEpochMs,
            progressEntry = entry,
            watchedItem = null,
        )
    }
}

internal fun buildLibraryHistoryRecords(
    progressEntries: List<WatchProgressEntry>,
    watchedItems: List<WatchedItem>,
): List<LibraryHistoryRecord> {
    val watchedByKey = linkedMapOf<String, WatchedItem>()
    watchedItems
        .sortedByDescending { it.markedAtEpochMs }
        .forEach { item ->
            val key = historyRecordKey(item.id, item.season, item.episode)
            if (!watchedByKey.containsKey(key)) {
                watchedByKey[key] = item
            }
        }

    val merged = linkedMapOf<String, LibraryHistoryRecord>()

    progressEntries
        .sortedByDescending { it.lastUpdatedEpochMs }
        .forEach { entry ->
            val key = historyRecordKey(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
            if (merged.containsKey(key)) return@forEach
            val matchingWatched = watchedByKey[key]
            val isCompleted = entry.isEffectivelyCompleted || matchingWatched != null
            val timestamp = maxOf(entry.lastUpdatedEpochMs, matchingWatched?.markedAtEpochMs ?: 0L)
            merged[key] = LibraryHistoryRecord(
                stableKey = "hist:$key",
                contentId = entry.parentMetaId,
                contentType = entry.parentMetaType,
                title = entry.title.ifBlank { matchingWatched?.name ?: entry.parentMetaId },
                posterUrl = entry.poster?.takeIf { it.isNotBlank() }
                    ?: matchingWatched?.poster?.takeIf { it.isNotBlank() }
                    ?: entry.episodeThumbnail?.takeIf { it.isNotBlank() }
                    ?: entry.background?.takeIf { it.isNotBlank() },
                seasonNumber = entry.seasonNumber,
                episodeNumber = entry.episodeNumber,
                episodeTitle = entry.episodeTitle?.takeIf { it.isNotBlank() },
                lastPositionMs = entry.lastPositionMs.coerceAtLeast(0L),
                durationMs = entry.durationMs.coerceAtLeast(0L),
                progressFraction = if (isCompleted) 1f else entry.effectiveProgressFraction(),
                isCompleted = isCompleted,
                timestampMs = timestamp,
                progressEntry = entry,
                watchedItem = matchingWatched,
            )
        }

    watchedByKey.forEach { (key, item) ->
        if (merged.containsKey(key)) return@forEach
        merged[key] = LibraryHistoryRecord(
            stableKey = "hist:$key",
            contentId = item.id,
            contentType = item.type,
            title = item.name.ifBlank { item.id },
            posterUrl = item.poster?.takeIf { it.isNotBlank() },
            seasonNumber = item.season,
            episodeNumber = item.episode,
            episodeTitle = null,
            lastPositionMs = 0L,
            durationMs = 0L,
            progressFraction = 1f,
            isCompleted = true,
            timestampMs = item.markedAtEpochMs,
            progressEntry = null,
            watchedItem = item,
        )
    }

    return merged.values.sortedByDescending { it.timestampMs }
}

internal fun LazyListScope.libraryHistoryListContent(
    records: List<LibraryHistoryRecord>,
    isHistoryTab: Boolean,
    onOpenDetails: (MetaPreview) -> Unit,
    onContinueClick: ((ContinueWatchingItem) -> Unit)?,
    onRemoveRecord: (LibraryHistoryRecord) -> Unit,
) {
    if (records.isEmpty()) {
        item(key = if (isHistoryTab) "library-history-empty" else "library-cw-empty") {
            HomeEmptyStateCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                title = if (isHistoryTab) {
                    "Seu histórico está vazio"
                } else {
                    "Nada em andamento no momento"
                },
                message = if (isHistoryTab) {
                    "Os filmes e episódios que você assistir aparecerão automaticamente aqui, do mais recente para o mais antigo."
                } else {
                    "Comece a assistir um filme ou série para retomar rapidamente de onde parou."
                },
            )
        }
        return
    }

    item(key = if (isHistoryTab) "library-history-count" else "library-cw-count") {
        val countLabel = if (isHistoryTab) {
            "${records.size} registro(s), mais recentes primeiro"
        } else {
            "${records.size} título(s) em andamento"
        }
        Text(
            text = countLabel,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    items(
        items = records,
        key = { it.stableKey },
    ) { record ->
        LibraryHistoryRowCard(
            record = record,
            onOpenDetails = { onOpenDetails(record.toMetaPreview()) },
            onContinue = if (!record.isCompleted && record.progressEntry != null && onContinueClick != null) {
                { onContinueClick(record.progressEntry.toContinueWatchingItem()) }
            } else {
                null
            },
            onRemove = { onRemoveRecord(record) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LibraryHistoryRowCard(
    record: LibraryHistoryRecord,
    onOpenDetails: () -> Unit,
    onContinue: (() -> Unit)?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitle = formatHistorySubtitle(record)
    val metaLine = formatHistoryMetaLine(record)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF131823).copy(alpha = 0.92f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenDetails),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            val isCompact = maxWidth < 500.dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Capa (Poster)
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 70.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!record.posterUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = record.posterUrl,
                            contentDescription = record.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // Informações centrais
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.5.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = 12.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (record.isCompleted) {
                                Color(0xFF94A3B8)
                            } else {
                                Color.White.copy(alpha = 0.56f)
                            },
                            fontSize = 11.5.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (!record.isCompleted && record.progressFraction > 0f) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (isCompact) 1f else 0.38f)
                                .height(3.5.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.14f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(record.progressFraction.coerceIn(0.02f, 1f))
                                    .height(3.5.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(BestCineBranding.PrimaryBlue),
                            )
                        }
                    }

                    if (isCompact && onContinue != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        ContinuePillButton(onClick = onContinue)
                    }
                }

                // Botões da direita
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!isCompact && onContinue != null) {
                        ContinuePillButton(onClick = onContinue)
                    }
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Remover do histórico",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinuePillButton(
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.White.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "Continuar",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                ),
            )
        }
    }
}

private fun historyRecordKey(
    contentId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String {
    val cleanId = contentId.trim()
    return if (seasonNumber != null && episodeNumber != null) {
        "$cleanId:S${seasonNumber}E${episodeNumber}"
    } else {
        cleanId
    }
}

private fun WatchProgressEntry.effectiveProgressFraction(): Float {
    normalizedProgressPercent?.let { pct ->
        if (pct > 0f) return (pct / 100f).coerceIn(0f, 1f)
    }
    if (durationMs > 0L && lastPositionMs > 0L) {
        return (lastPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
    return 0f
}

private fun formatHistorySubtitle(record: LibraryHistoryRecord): String {
    if (record.seasonNumber != null && record.episodeNumber != null) {
        val code = "T${record.seasonNumber} E${record.episodeNumber}"
        val epTitle = record.episodeTitle?.trim().orEmpty()
        return if (epTitle.isNotEmpty()) "$code • $epTitle" else code
    }
    return when (record.contentType.trim().lowercase()) {
        "movie" -> "Filme"
        "series", "show", "tv" -> "Série"
        "anime" -> "Anime"
        else -> "Filme"
    }
}

private fun formatHistoryMetaLine(record: LibraryHistoryRecord): String {
    val timeText = formatLibraryHistoryTimestamp(record.timestampMs)
    if (record.isCompleted) {
        return if (timeText.isNotEmpty()) "✓ Assistido • $timeText" else "✓ Assistido"
    }

    val parts = mutableListOf<String>()
    if (record.lastPositionMs > 0L) {
        parts += "${formatWatchedClock(record.lastPositionMs)} assistidos"
    }
    val remainingMs = (record.durationMs - record.lastPositionMs).coerceAtLeast(0L)
    if (record.durationMs > 0L && remainingMs > 0L) {
        parts += formatRemainingPortuguese(remainingMs)
    }
    if (timeText.isNotEmpty()) {
        parts += timeText
    }
    return parts.joinToString(" • ").ifEmpty { "Em andamento" }
}

private fun formatWatchedClock(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    val minStr = minutes.toString().padStart(2, '0')
    val secStr = seconds.toString().padStart(2, '0')
    return if (hours > 0L) {
        "$hours:$minStr:$secStr"
    } else {
        "$minutes:$secStr"
    }
}

private fun formatRemainingPortuguese(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0L)
    if (totalMin <= 0L) return "falta menos de 1 min"
    val hours = totalMin / 60L
    val mins = totalMin % 60L
    return when {
        hours > 0L && mins > 0L -> "faltam $hours h $mins min"
        hours > 0L -> "faltam $hours h"
        totalMin == 1L -> "falta 1 min"
        else -> "faltam $totalMin min"
    }
}
