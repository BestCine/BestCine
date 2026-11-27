package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.bestcine.catalog.BestCineStreamingCatalogConfig
import com.nuvio.app.bestcine.catalog.BestCineStreamingCatalogs
import com.nuvio.app.bestcine.catalog.BestCineStreamingProvider
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.action_reset
import nuvio.composeapp.generated.resources.layout_hide_unreleased
import nuvio.composeapp.generated.resources.layout_hide_unreleased_sub
import nuvio.composeapp.generated.resources.layout_catalog_type
import nuvio.composeapp.generated.resources.layout_catalog_type_sub
import nuvio.composeapp.generated.resources.settings_homescreen_empty_message
import nuvio.composeapp.generated.resources.settings_homescreen_empty_title
import nuvio.composeapp.generated.resources.settings_homescreen_keep_home_focused
import nuvio.composeapp.generated.resources.settings_homescreen_limit_reached
import nuvio.composeapp.generated.resources.settings_homescreen_load_failed_title
import nuvio.composeapp.generated.resources.settings_homescreen_no_sources_selected
import nuvio.composeapp.generated.resources.settings_homescreen_pin_to_move_toast
import nuvio.composeapp.generated.resources.settings_homescreen_section_catalogs
import nuvio.composeapp.generated.resources.settings_homescreen_section_catalogs_collections
import nuvio.composeapp.generated.resources.settings_homescreen_section_collections
import nuvio.composeapp.generated.resources.settings_homescreen_section_hero
import nuvio.composeapp.generated.resources.settings_homescreen_section_hero_sources
import nuvio.composeapp.generated.resources.settings_homescreen_selected_count
import nuvio.composeapp.generated.resources.settings_homescreen_show_hero
import nuvio.composeapp.generated.resources.settings_homescreen_show_hero_description
import nuvio.composeapp.generated.resources.settings_homescreen_summary
import nuvio.composeapp.generated.resources.settings_homescreen_summary_hint
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal fun LazyListScope.homescreenSettingsContent(
    isTablet: Boolean,
    heroEnabled: Boolean,
    showCatalogType: Boolean,
    hideUnreleasedContent: Boolean,
    items: List<HomeCatalogSettingsItem>,
    isCatalogLoading: Boolean,
    catalogErrorMessage: String?,
) {
    val selectedHeroSourceCount = items.count { it.heroSourceEnabled }
    val enabledCatalogCount = items.count { it.enabled }
    item {
        HomescreenSummaryCard(
            isTablet = isTablet,
            enabledCatalogCount = enabledCatalogCount,
            totalCatalogCount = items.size,
            selectedHeroSourceCount = selectedHeroSourceCount,
        )
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_homescreen_section_hero),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_homescreen_show_hero),
                    description = stringResource(Res.string.settings_homescreen_show_hero_description),
                    checked = heroEnabled,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setHeroEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.layout_catalog_type),
                    description = stringResource(Res.string.layout_catalog_type_sub),
                    checked = showCatalogType,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setShowCatalogType,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.layout_hide_unreleased),
                    description = stringResource(Res.string.layout_hide_unreleased_sub),
                    checked = hideUnreleasedContent,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setHideUnreleasedContent,
                )
            }
        }
    }
    item {
        val catalogOnlyItems = items.filter { !it.isCollection }
        if (heroEnabled && catalogOnlyItems.isNotEmpty()) {
            var heroSourcesExpanded by remember { mutableStateOf(false) }
            SettingsSection(
                title = stringResource(Res.string.settings_homescreen_section_hero_sources),
                isTablet = isTablet,
            ) {
                HeroSourcesDropdown(
                    isTablet = isTablet,
                    items = catalogOnlyItems,
                    selectedHeroSourceCount = selectedHeroSourceCount,
                    expanded = heroSourcesExpanded,
                    onExpandedChange = { heroSourcesExpanded = it },
                )
            }
        }
    }
    item(key = "bestcine_streaming_catalogs_br") {
        val settingsUiState by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
        SettingsSection(
            title = "Catálogos BestCine (Brasil)",
            isTablet = isTablet,
        ) {
            BestCineStreamingCatalogsSectionCard(
                isTablet = isTablet,
                showBestCineDefaultCatalogs = settingsUiState.showBestCineDefaultCatalogs,
                config = settingsUiState.streamingCatalogConfig,
            )
        }
    }
    item {
        if (isCatalogLoading && items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                NuvioLoadingIndicator(
                    modifier = Modifier.size(28.dp),
                )
            }
        } else if (catalogErrorMessage != null && items.isEmpty()) {
            HomeEmptyStateCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(Res.string.settings_homescreen_load_failed_title),
                message = catalogErrorMessage,
                actionLabel = stringResource(Res.string.action_retry),
                onActionClick = AddonRepository::refreshAll,
            )
        } else if (items.isEmpty()) {
            HomeEmptyStateCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(Res.string.settings_homescreen_empty_title),
                message = stringResource(Res.string.settings_homescreen_empty_message),
            )
        } else {
            val catalogCount = items.count { !it.isCollection }
            val collectionCount = items.count { it.isCollection }
            val sectionTitle = when {
                collectionCount > 0 && catalogCount > 0 -> stringResource(Res.string.settings_homescreen_section_catalogs_collections)
                collectionCount > 0 -> stringResource(Res.string.settings_homescreen_section_collections)
                else -> stringResource(Res.string.settings_homescreen_section_catalogs)
            }
            SettingsSection(
                title = sectionTitle,
                isTablet = isTablet,
                actions = {
                    NuvioActionLabel(
                        text = stringResource(Res.string.action_reset),
                        onClick = HomeCatalogSettingsRepository::resetToDefaults,
                    )
                },
            ) {
                val hapticFeedback = LocalHapticFeedback.current
                val pinToMoveToast = stringResource(Res.string.settings_homescreen_pin_to_move_toast)

                HomescreenCatalogList(
                    isTablet = isTablet,
                    items = items,
                    onPinnedDragAttempt = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        NuvioToastController.show(pinToMoveToast)
                    },
                )
            }
        }
    }
}

@Composable
private fun HeroSourcesDropdown(
    isTablet: Boolean,
    items: List<HomeCatalogSettingsItem>,
    selectedHeroSourceCount: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val noSourcesSelected = stringResource(Res.string.settings_homescreen_no_sources_selected)
    SettingsGroup(isTablet = isTablet) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clickable { onExpandedChange(!expanded) },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(
                        Res.string.settings_homescreen_selected_count,
                        selectedHeroSourceCount,
                        HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = items.filter { it.heroSourceEnabled }
                        .joinToString(separator = ", ") { it.displayTitle }
                        .ifBlank { noSourcesSelected },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                SettingsGroupDivider(isTablet = isTablet)
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                    SettingsSwitchRow(
                        title = item.displayTitle,
                        description = if (!item.heroSourceEnabled &&
                            selectedHeroSourceCount >= HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT
                        ) {
                            stringResource(
                                Res.string.settings_homescreen_limit_reached,
                                item.addonName,
                                HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT,
                            )
                        } else {
                            item.addonName
                        },
                        checked = item.heroSourceEnabled,
                        enabled = item.heroSourceEnabled ||
                            selectedHeroSourceCount < HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT,
                        isTablet = isTablet,
                        onCheckedChange = { HomeCatalogSettingsRepository.setHeroSourceEnabled(item.key, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomescreenSummaryCard(
    isTablet: Boolean,
    enabledCatalogCount: Int,
    totalCatalogCount: Int,
    selectedHeroSourceCount: Int,
) {
    SettingsGroup(isTablet = isTablet) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Res.string.settings_homescreen_keep_home_focused),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    Res.string.settings_homescreen_summary,
                    enabledCatalogCount,
                    totalCatalogCount,
                    selectedHeroSourceCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.settings_homescreen_summary_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomescreenCatalogList(
    isTablet: Boolean,
    items: List<HomeCatalogSettingsItem>,
    onPinnedDragAttempt: () -> Unit,
) {
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
    ) { from, to ->
        val fromItem = items.getOrNull(from.index)
        val toItem = items.getOrNull(to.index)
        if (fromItem?.isPinnedToTop == true || toItem?.isPinnedToTop == true) {
            return@rememberReorderableLazyListState
        }
        HomeCatalogSettingsRepository.moveByIndex(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    SettingsGroup(isTablet = isTablet) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (isTablet) 900.dp else 680.dp),
            state = lazyListState,
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                ReorderableItem(
                    reorderableLazyListState,
                    key = item.key,
                    enabled = !item.isPinnedToTop,
                ) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)

                    Surface(shadowElevation = elevation) {
                        Column {
                            if (index > 0) {
                                SettingsGroupDivider(isTablet = isTablet)
                            }
                            HomescreenCatalogRow(
                                item = item,
                                isTablet = isTablet,
                                expanded = expandedKey == item.key,
                                onExpandedChange = { shouldExpand ->
                                    expandedKey = if (shouldExpand) item.key else null
                                },
                                onTitleChange = { HomeCatalogSettingsRepository.setCustomTitle(item.key, it) },
                                onEnabledChange = { HomeCatalogSettingsRepository.setEnabled(item.key, it) },
                                dragHandleScope = this@ReorderableItem,
                                onPinnedDragAttempt = onPinnedDragAttempt,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BestCineStreamingCatalogsSectionCard(
    isTablet: Boolean,
    showBestCineDefaultCatalogs: Boolean,
    config: BestCineStreamingCatalogConfig,
) {
    val tokens = MaterialTheme.nuvio
    val currentMoment = remember { BestCineStreamingCatalogs.currentMomentPeriod() }
    val currentSeasonal = remember { BestCineStreamingCatalogs.currentSeasonalEvent() }
    val selectedRuntimeSet = remember(config.selectedRuntimeIds) { config.selectedRuntimeIds.toSet() }
    val selectedSet = remember(config.selectedProviderIds) { config.selectedProviderIds.toSet() }
    val typesMultiplier = (if (config.includeMovies) 1 else 0) + (if (config.includeSeries) 1 else 0)
    val activeRowCount = if (config.enabled) selectedSet.size * typesMultiplier else 0

    SettingsGroup(isTablet = isTablet) {
        SettingsSwitchRow(
            title = "Catálogos padrão do BestCine na Home",
            description = "Exibe as fileiras padrão do BestCine na Tela Inicial. Desative se quiser mostrar apenas seus addons de catálogo externos (os streams e a TV ao Vivo continuam funcionando normalmente).",
            checked = showBestCineDefaultCatalogs,
            isTablet = isTablet,
            onCheckedChange = HomeCatalogSettingsRepository::setShowBestCineDefaultCatalogs,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = "Filmes & Séries • Lançamentos",
            description = "Exibe fileiras dedicadas com os filmes e séries mais recentes no Brasil via TMDB (cache de 6h).",
            checked = config.releasesCatalogEnabled,
            isTablet = isTablet,
            onCheckedChange = HomeCatalogSettingsRepository::setReleasesCatalogEnabled,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = "Sessão do Momento (Dinâmico por Horário)",
            description = "Agora: ${currentMoment.label} (${currentMoment.subtitle}). Muda automaticamente entre Manhã, Tarde, Noite e Madrugada.",
            checked = config.momentCatalogEnabled,
            isTablet = isTablet,
            onCheckedChange = HomeCatalogSettingsRepository::setMomentCatalogEnabled,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = "Catálogo Sazonal Automático",
            description = if (currentSeasonal != null) {
                "Ativo hoje: ${currentSeasonal.title} (${currentSeasonal.subtitle}). Aparece automaticamente em Natal, Halloween, Dia das Mães, Pais, Namorados, Crianças e Férias."
            } else {
                "Aparece só perto de datas comemorativas (Natal, Halloween, Dia das Mães, Pais, Namorados, Crianças e Férias). Fora de época não consome rede."
            },
            checked = config.seasonalCatalogEnabled,
            isTablet = isTablet,
            onCheckedChange = HomeCatalogSettingsRepository::setSeasonalCatalogEnabled,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = "Catálogos por Tempo / Duração",
            description = "Fileiras opcionais por duração: filmes rápidos (até 90 min), minisséries curtas e épicos (+2h30).",
            checked = config.runtimeCatalogsEnabled,
            isTablet = isTablet,
            onCheckedChange = HomeCatalogSettingsRepository::setRuntimeCatalogsEnabled,
        )
        AnimatedVisibility(visible = config.runtimeCatalogsEnabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BestCineStreamingCatalogs.allRuntimeCatalogs.forEach { runtime ->
                        StreamingTogglePill(
                            label = runtime.displayName,
                            selected = runtime.id in selectedRuntimeSet,
                            accentColor = Color(runtime.accentHex),
                            showAccentDot = true,
                            onClick = { HomeCatalogSettingsRepository.toggleRuntimeCatalog(runtime.id) },
                        )
                    }
                }
            }
        }
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = "Ativar catálogos por streaming",
            description = "Adiciona fileiras opcionais de plataformas do Brasil via TMDB. Desativado por padrão para desempenho máximo.",
            checked = config.enabled,
            isTablet = isTablet,
            onCheckedChange = HomeCatalogSettingsRepository::setStreamingCatalogsEnabled,
        )

        AnimatedVisibility(visible = config.enabled) {
            Column {
                SettingsGroupDivider(isTablet = isTablet)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Tipos de catálogo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = when (activeRowCount) {
                                0 -> "Nenhuma fileira ativa"
                                1 -> "1 fileira ativa"
                                else -> "$activeRowCount fileiras ativas"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (activeRowCount > 8) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StreamingTogglePill(
                            label = "Filmes",
                            selected = config.includeMovies,
                            accentColor = MaterialTheme.colorScheme.primary,
                            onClick = { HomeCatalogSettingsRepository.setStreamingIncludeMovies(!config.includeMovies) },
                        )
                        StreamingTogglePill(
                            label = "Séries",
                            selected = config.includeSeries,
                            accentColor = MaterialTheme.colorScheme.primary,
                            onClick = { HomeCatalogSettingsRepository.setStreamingIncludeSeries(!config.includeSeries) },
                        )
                    }
                }

                SettingsGroupDivider(isTablet = isTablet)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Escolha as plataformas",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BestCineStreamingCatalogs.allProviders.forEach { provider ->
                            val isSelected = provider.id in selectedSet
                            StreamingProviderChip(
                                provider = provider,
                                selected = isSelected,
                                onClick = { HomeCatalogSettingsRepository.toggleStreamingProvider(provider.id) },
                            )
                        }
                    }

                    Text(
                        text = if (activeRowCount > 8) {
                            "Dica: manter entre 3 e 6 fileiras ativas garante abertura instantânea e menor uso de memória."
                        } else {
                            "As fileiras ativadas aparecem automaticamente na Tela Inicial e na lista abaixo para reordenar ou renomear."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (activeRowCount > 8) Color(0xFFF59E0B) else tokens.colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingProviderChip(
    provider: BestCineStreamingProvider,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = Color(provider.accentHex)
    StreamingTogglePill(
        label = provider.displayName,
        selected = selected,
        accentColor = accent,
        showAccentDot = true,
        onClick = onClick,
    )
}

@Composable
private fun StreamingTogglePill(
    label: String,
    selected: Boolean,
    accentColor: Color,
    showAccentDot: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val containerColor = if (selected) {
        accentColor.copy(alpha = 0.18f)
    } else {
        tokens.colors.surfaceElevated
    }
    val borderColor = if (selected) {
        accentColor.copy(alpha = 0.72f)
    } else {
        tokens.colors.borderSubtle
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (showAccentDot) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = accentColor,
                )
            }
        }
    }
}
