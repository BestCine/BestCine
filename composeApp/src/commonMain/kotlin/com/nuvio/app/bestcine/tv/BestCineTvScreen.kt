package com.nuvio.app.bestcine.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.bestcine.BestCineBranding
import com.nuvio.app.bestcine.cast.BestCineCastController
import com.nuvio.app.bestcine.cast.BestCineCastDialog
import com.nuvio.app.bestcine.cast.BestCineCastMedia
import com.nuvio.app.bestcine.cast.BestCineCastPlayerLockOverlay
import com.nuvio.app.isDesktop
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.isFullscreenActionActive
import com.nuvio.app.core.ui.isFullscreenActionSupported
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.toggleFullscreenAction
import com.nuvio.app.features.player.GestureFeedbackIcon
import com.nuvio.app.features.player.GestureFeedbackState
import com.nuvio.app.features.player.HidePlayerSystemBars
import com.nuvio.app.features.player.LockPlayerToLandscape
import com.nuvio.app.features.player.LockPlayerToPortrait
import com.nuvio.app.features.player.ManagePlayerPictureInPicture
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerAudioLevel
import com.nuvio.app.features.player.PlayerControlsAction
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerGestureOverlay
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLeftGestureBoundary
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.PlayerRightGestureBoundary
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSideGesture
import com.nuvio.app.features.player.PlayerSideGestureSystemEdgeExclusion
import com.nuvio.app.features.player.PlayerVerticalGestureDominanceRatio
import com.nuvio.app.features.player.PlayerVerticalGestureMinHeightFraction
import com.nuvio.app.features.player.PlayerVerticalGestureSensitivity
import com.nuvio.app.features.player.PlayerVerticalGestureTouchSlopMultiplier
import com.nuvio.app.features.player.rememberIsInPictureInPicture
import com.nuvio.app.features.player.rememberPlayerGestureController
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.player.sanitizePlaybackResponseHeaders
import com.nuvio.app.features.player.togglePlayerPictureInPicture
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_brightness_level
import nuvio.composeapp.generated.resources.compose_player_muted
import nuvio.composeapp.generated.resources.compose_player_volume_level
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun BestCineTvScreen(
    isSelectedTab: Boolean,
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    onPlayFullscreen: (PlayerLaunch) -> Unit,
) {
    val uiState by BestCineTvRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val tokens = MaterialTheme.nuvio
    val isInPip = rememberIsInPictureInPicture()
    val isFullscreen = (uiState.isFullscreen || (!isDesktop && isInPip)) &&
        uiState.activeChannel != null &&
        uiState.activeStream != null
    val isWindowFullscreen = isFullscreenActionActive()
    var enteredWindowFullscreenByTv by remember { mutableStateOf(false) }
    var previousWindowFullscreen by remember { mutableStateOf(isWindowFullscreen) }
    var epgBrtMinute by remember { mutableStateOf(BestCineTvRepository.currentBrtMinute()) }

    LaunchedEffect(isSelectedTab) {
        if (!isSelectedTab) return@LaunchedEffect
        while (true) {
            val currentMin = BestCineTvRepository.currentBrtMinute()
            if (epgBrtMinute != currentMin) {
                epgBrtMinute = currentMin
            }
            delay(60_000L)
        }
    }

    fun enterFullscreen() {
        if (uiState.activeChannel == null || uiState.activeStream == null) return
        BestCineTvRepository.setFullscreen(true)
        if (isFullscreenActionSupported && !isWindowFullscreen) {
            enteredWindowFullscreenByTv = true
            toggleFullscreenAction()
        }
    }

    fun exitFullscreen() {
        BestCineTvRepository.setFullscreen(false)
        if (enteredWindowFullscreenByTv && isWindowFullscreen) {
            enteredWindowFullscreenByTv = false
            toggleFullscreenAction()
        }
    }

    fun toggleTvFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    LaunchedEffect(isWindowFullscreen) {
        if (previousWindowFullscreen && !isWindowFullscreen && uiState.isFullscreen) {
            enteredWindowFullscreenByTv = false
            BestCineTvRepository.setFullscreen(false)
        }
        previousWindowFullscreen = isWindowFullscreen
    }

    LaunchedEffect(isSelectedTab) {
        if (isSelectedTab) {
            BestCineTvRepository.ensureCatalogLoaded(forceRefresh = false)
        } else {
            if (uiState.isFullscreen) {
                exitFullscreen()
            }
            if (uiState.activeChannel != null) {
                // Ao sair da aba TV (ex: ir pesquisar filmes/séries), fecha o player embutido
                // mantendo o grupo selecionado para que ao voltar carregue na grade de canais do grupo!
                BestCineTvRepository.closeEmbeddedPlayer()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (BestCineTvRepository.uiState.value.isFullscreen) {
                BestCineTvRepository.setFullscreen(false)
            }
            if (BestCineTvRepository.uiState.value.activeChannel != null) {
                BestCineTvRepository.closeEmbeddedPlayer()
            }
        }
    }

    PlatformBackHandler(enabled = isSelectedTab && isFullscreen) {
        exitFullscreen()
    }

    val tvCastDialogOpen by BestCineCastController.isCastDialogOpen.collectAsStateWithLifecycle()
    val tvCastState by BestCineCastController.state.collectAsStateWithLifecycle()
    if (isSelectedTab && isFullscreen && !isInPip) {
        if (tvCastDialogOpen || tvCastState.isCasting) {
            LockPlayerToPortrait()
        } else {
            LockPlayerToLandscape()
            HidePlayerSystemBars()
        }
    }

    LaunchedEffect(Unit) {
        BestCineTvRepository.ensureCatalogLoaded(forceRefresh = false)
    }

    // Pesquisa da esquerda ("Buscar grupo ou canal..."): encontra tanto grupos quanto canais!
    val filteredCategories = remember(uiState.categories, uiState.channels, uiState.categorySearchQuery) {
        val q = uiState.categorySearchQuery.trim()
        if (q.isEmpty()) {
            uiState.categories
        } else {
            val matchingChannels = uiState.channels.filter { ch ->
                ch.name.contains(q, ignoreCase = true) ||
                    ch.group.contains(q, ignoreCase = true) ||
                    ch.epgSummary.contains(q, ignoreCase = true)
            }
            val matchingCountsByGroup = matchingChannels.groupingBy { it.group.lowercase() }.eachCount()

            uiState.categories.mapNotNull { category ->
                if (category.name == BESTCINE_TV_ALL_CATEGORY) {
                    if (matchingChannels.isNotEmpty()) {
                        category.copy(count = matchingChannels.size)
                    } else {
                        null
                    }
                } else if (category.name == BESTCINE_TV_FAVORITES_CATEGORY) {
                    val favMatches = matchingChannels.count { it.id in uiState.favoriteChannelIds }
                    when {
                        category.name.contains(q, ignoreCase = true) -> category
                        favMatches > 0 -> category.copy(count = favMatches)
                        else -> null
                    }
                } else {
                    val groupNameMatches = category.name.contains(q, ignoreCase = true)
                    val channelMatchCount = matchingCountsByGroup[category.name.lowercase()] ?: 0
                    when {
                        groupNameMatches -> category
                        channelMatchCount > 0 -> category.copy(count = channelMatchCount)
                        else -> null
                    }
                }
            }
        }
    }

    val filteredChannels = remember(
        uiState.channels,
        uiState.selectedCategory,
        uiState.favoriteChannelIds,
        uiState.categorySearchQuery,
        uiState.searchQuery,
    ) {
        val leftQuery = uiState.categorySearchQuery.trim()
        val rightQuery = uiState.searchQuery.trim()
        val isFavoritesCategory = uiState.selectedCategory == BESTCINE_TV_FAVORITES_CATEGORY

        // Se o usuário digitou o nome de um canal na busca da esquerda e o grupo atual não tem esse canal,
        // exibe automaticamente os canais encontrados em qualquer grupo!
        val selectedGroupMatchesLeftQuery = leftQuery.isNotEmpty() &&
            uiState.selectedCategory != BESTCINE_TV_ALL_CATEGORY &&
            uiState.selectedCategory.contains(leftQuery, ignoreCase = true)

        val hasMatchInSelectedCategory = if (leftQuery.isEmpty() || uiState.selectedCategory == BESTCINE_TV_ALL_CATEGORY) {
            true
        } else if (isFavoritesCategory) {
            uiState.channels.any { ch ->
                ch.id in uiState.favoriteChannelIds && (
                    ch.name.contains(leftQuery, ignoreCase = true) ||
                        ch.group.contains(leftQuery, ignoreCase = true) ||
                        ch.epgSummary.contains(leftQuery, ignoreCase = true)
                    )
            }
        } else {
            uiState.channels.any { ch ->
                ch.group.equals(uiState.selectedCategory, ignoreCase = true) && (
                    ch.name.contains(leftQuery, ignoreCase = true) ||
                        ch.group.contains(leftQuery, ignoreCase = true) ||
                        ch.epgSummary.contains(leftQuery, ignoreCase = true)
                    )
            }
        }

        uiState.channels.filter { channel ->
            val matchesCategory = uiState.selectedCategory == BESTCINE_TV_ALL_CATEGORY ||
                (!hasMatchInSelectedCategory && leftQuery.isNotEmpty()) ||
                (if (isFavoritesCategory) channel.id in uiState.favoriteChannelIds else channel.group.equals(uiState.selectedCategory, ignoreCase = true))

            val matchesLeftQuery = leftQuery.isEmpty() ||
                selectedGroupMatchesLeftQuery ||
                channel.name.contains(leftQuery, ignoreCase = true) ||
                channel.group.contains(leftQuery, ignoreCase = true) ||
                channel.epgSummary.contains(leftQuery, ignoreCase = true)

            val matchesRightQuery = rightQuery.isEmpty() ||
                channel.name.contains(rightQuery, ignoreCase = true) ||
                channel.group.contains(rightQuery, ignoreCase = true) ||
                channel.epgSummary.contains(rightQuery, ignoreCase = true)

            matchesCategory && matchesLeftQuery && matchesRightQuery
        }
    }

    fun selectChannel(channel: BestCineTvChannel) {
        scope.launch {
            BestCineTvRepository.openChannelEmbedded(channel)
        }
    }

    fun zapRelative(delta: Int) {
        val current = uiState.activeChannel ?: return
        val list = filteredChannels.ifEmpty { uiState.channels }
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == current.id }
        val nextIdx = if (idx < 0) 0 else (idx + delta + list.size) % list.size
        selectChannel(list[nextIdx])
    }

    val screenBackground by animateColorAsState(
        targetValue = if (isFullscreen) Color.Black else tokens.colors.background,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tv_screen_background",
    )
    val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val animatedTopPadding by animateDpAsState(
        targetValue = if (isFullscreen) 0.dp else (topChromePadding ?: (statusBarTopPadding + 12.dp)),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tv_top_padding",
    )
    val animatedContentHorizontalPadding by animateDpAsState(
        targetValue = if (isFullscreen) 0.dp else 18.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tv_content_horizontal_padding",
    )
    val animatedContentVerticalPadding by animateDpAsState(
        targetValue = if (isFullscreen) 0.dp else 8.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tv_content_vertical_padding",
    )
    val bottomNavOverlayPadding = if (!isFullscreen && LocalNuvioBottomNavigationOverlayPadding.current > 0.dp) {
        nuvioSafeBottomPadding()
    } else {
        0.dp
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(screenBackground)
            .padding(top = animatedTopPadding),
    ) {
        val isCompactMobile = maxWidth < 680.dp

        Row(
            modifier = Modifier.fillMaxSize(),
        ) {
            // ============================================================
            // COLUNA 1 (ESQUERDA): PAINEL VERTICAL DE CATEGORIAS / GRUPOS (DESKTOP / TABLET)
            // ============================================================
            AnimatedVisibility(
                visible = !isFullscreen && !isCompactMobile,
                enter = fadeIn(tween(200)) + expandHorizontally(tween(220, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(140)) + shrinkHorizontally(tween(220, easing = FastOutSlowInEasing)),
            ) {
                Row(modifier = Modifier.fillMaxHeight()) {
                    BestCineTvCategoriesSidebar(
                        categories = filteredCategories,
                        selectedCategory = uiState.selectedCategory,
                        categorySearchQuery = uiState.categorySearchQuery,
                        onCategorySearchChange = BestCineTvRepository::setCategorySearchQuery,
                        onSelectCategory = BestCineTvRepository::setSelectedCategory,
                    )

                    VerticalDivider(
                        thickness = 1.dp,
                        color = Color.White.copy(alpha = 0.08f),
                    )
                }
            }

            // ============================================================
            // ÁREA PRINCIPAL (DIREITA): GRADE OU PLAYER EMBUTIDO + CANAIS
            // ============================================================
            val horizontalContentPadding = if (isFullscreen) {
                0.dp
            } else if (isCompactMobile) {
                12.dp
            } else {
                animatedContentHorizontalPadding
            }
            val bottomContentPadding = if (isFullscreen || bottomNavOverlayPadding > 0.dp) {
                0.dp
            } else {
                animatedContentVerticalPadding
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(
                        start = horizontalContentPadding,
                        end = horizontalContentPadding,
                        top = animatedContentVerticalPadding,
                        bottom = bottomContentPadding,
                    ),
            ) {
                AnimatedVisibility(
                    visible = !isFullscreen,
                    enter = fadeIn(tween(200)) + expandVertically(tween(220, easing = FastOutSlowInEasing)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(220, easing = FastOutSlowInEasing)),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Cabeçalho superior
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(BestCineBranding.PrimaryBlue.copy(alpha = 0.18f))
                                    .border(1.dp, BestCineBranding.PrimaryBlue.copy(alpha = 0.45f), RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LiveTv,
                                    contentDescription = null,
                                    tint = BestCineBranding.PrimaryBlueBright,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (uiState.selectedCategory == BESTCINE_TV_ALL_CATEGORY) {
                                        "TV ao Vivo"
                                    } else {
                                        uiState.selectedCategory
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    color = tokens.colors.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (filteredChannels.size == 1) {
                                        "1 canal disponível"
                                    } else {
                                        "${filteredChannels.size} canais disponíveis"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.colors.textMuted,
                                )
                            }

                            if (!isCompactMobile) {
                                // Busca de canal/programa em memória (Desktop/Tablet)
                                CompactSearchField(
                                    value = uiState.searchQuery,
                                    placeholder = "Buscar canal ou programa...",
                                    onValueChange = BestCineTvRepository::setSearchQuery,
                                    modifier = Modifier.width(240.dp),
                                )
                            }

                            if (uiState.activeChannel != null) {
                                Button(
                                    onClick = BestCineTvRepository::closeEmbeddedPlayer,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White.copy(alpha = 0.08f),
                                        contentColor = tokens.colors.textPrimary,
                                    ),
                                ) {
                                    Icon(Icons.Rounded.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Grade", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            Button(
                                onClick = {
                                    scope.launch { BestCineTvRepository.ensureCatalogLoaded(forceRefresh = true) }
                                },
                                enabled = !uiState.isLoadingCatalog,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    contentColor = tokens.colors.textPrimary,
                                ),
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                if (!isCompactMobile) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Atualizar", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        if (isCompactMobile && uiState.activeChannel == null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            CompactSearchField(
                                value = uiState.searchQuery,
                                placeholder = "Buscar grupo, canal ou programa...",
                                onValueChange = { q ->
                                    BestCineTvRepository.setSearchQuery(q)
                                    BestCineTvRepository.setCategorySearchQuery(q)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(filteredCategories, key = { it.name }) { category ->
                                    val isSelected = category.name == uiState.selectedCategory
                                    Surface(
                                        onClick = { BestCineTvRepository.setSelectedCategory(category.name) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) {
                                            BestCineBranding.PrimaryBlue.copy(alpha = 0.24f)
                                        } else {
                                            Color.White.copy(alpha = 0.06f)
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) BestCineBranding.PrimaryBlueBright else Color.White.copy(alpha = 0.10f),
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = category.name,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (isSelected) Color.White else tokens.colors.textPrimary,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            )
                                            Text(
                                                text = "${category.count}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) BestCineBranding.PrimaryBlueBright else tokens.colors.textMuted,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                    // Banner de aviso/erro (nunca empurra o layout durante "Sintonizando...")
                    val topAlertMessage = uiState.bannerMessage?.takeIf { uiState.openingChannelId == null }
                    topAlertMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (uiState.isBannerError) {
                                tokens.colors.danger.copy(alpha = 0.16f)
                            } else {
                                BestCineBranding.PrimaryBlue.copy(alpha = 0.16f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (uiState.isBannerError) {
                                    tokens.colors.danger.copy(alpha = 0.45f)
                                } else {
                                    BestCineBranding.PrimaryBlue.copy(alpha = 0.40f)
                                },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (uiState.isBannerError) tokens.colors.danger else tokens.colors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = BestCineTvRepository::clearBanner,
                                    modifier = Modifier.size(22.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Fechar aviso",
                                        tint = tokens.colors.textMuted,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            // Conteúdo principal
            when {
                uiState.isLoadingCatalog && uiState.channels.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            NuvioLoadingIndicator(size = 34.dp, color = BestCineBranding.PrimaryBlueBright)
                            Text(
                                text = "Carregando grade de TV ao vivo...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = tokens.colors.textSecondary,
                            )
                        }
                    }
                }

                uiState.catalogError != null && uiState.channels.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(24.dp),
                        ) {
                            Text(
                                text = uiState.catalogError.orEmpty(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = tokens.colors.textSecondary,
                            )
                            Button(
                                onClick = {
                                    scope.launch { BestCineTvRepository.ensureCatalogLoaded(forceRefresh = true) }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BestCineBranding.PrimaryBlue,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text("Tentar novamente")
                            }
                        }
                    }
                }

                uiState.activeChannel != null && uiState.activeStream != null -> {
                    // ============================================================
                    // MODO PLAYER EMBUTIDO + LISTA DE CANAIS DO GRUPO AO LADO
                    // (TELA CHEIA IN-PLACE SUAVE, SEM RECARREGAR O STREAM!)
                    // ============================================================
                    BestCineTvSplitPlayerLayout(
                        isSelectedTab = isSelectedTab,
                        isFullscreen = isFullscreen,
                        isCompactMobile = isCompactMobile,
                        bottomOverlayPadding = bottomNavOverlayPadding,
                        epgBrtMinute = epgBrtMinute,
                        channels = filteredChannels.ifEmpty { uiState.channels },
                        favoriteChannelIds = uiState.favoriteChannelIds,
                        activeChannel = uiState.activeChannel!!,
                        activeStreams = uiState.activeStreams,
                        activeStreamIndex = uiState.activeStreamIndex,
                        activeEpgDetails = uiState.activeEpgDetails,
                        isLoadingEpg = uiState.isLoadingEpg,
                        openingChannelId = uiState.openingChannelId,
                        openingChannelName = uiState.openingChannelId?.let { id ->
                            uiState.channels.firstOrNull { it.id == id }?.name
                        },
                        onSelectChannel = ::selectChannel,
                        onToggleFavoriteChannel = BestCineTvRepository::toggleFavoriteChannel,
                        onSelectSourceIndex = BestCineTvRepository::selectActiveStreamIndex,
                        onPreviousChannel = { zapRelative(-1) },
                        onNextChannel = { zapRelative(+1) },
                        onEnterFullscreen = ::enterFullscreen,
                        onExitFullscreen = ::exitFullscreen,
                        onToggleFullscreen = ::toggleTvFullscreen,
                        onClosePlayer = {
                            exitFullscreen()
                            BestCineTvRepository.closeEmbeddedPlayer()
                        },
                    )
                }

                filteredChannels.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (uiState.selectedCategory == BESTCINE_TV_FAVORITES_CATEGORY) {
                                "Nenhum canal favorito ainda. Toque na estrela ☆ em qualquer canal para adicioná-lo aqui."
                            } else {
                                "Nenhum canal encontrado para esse filtro."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = tokens.colors.textMuted,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }

                else -> {
                    // ============================================================
                    // MODO GRADE COMPACTA (ESTILO IPTV)
                    // ============================================================
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = if (isCompactMobile) 140.dp else 158.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                bottom = if (bottomNavOverlayPadding > 0.dp) {
                                    bottomNavOverlayPadding + 16.dp
                                } else {
                                    24.dp
                                },
                            ),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(filteredChannels, key = { it.id }) { channel ->
                                BestCineTvGridCard(
                                    channel = channel,
                                    epgBrtMinute = epgBrtMinute,
                                    isFavorite = channel.id in uiState.favoriteChannelIds,
                                    isOpening = uiState.openingChannelId == channel.id,
                                    enabled = uiState.openingChannelId == null,
                                    onToggleFavorite = { BestCineTvRepository.toggleFavoriteChannel(channel.id) },
                                    onClick = { selectChannel(channel) },
                                )
                            }
                        }

                        val openingGridName = uiState.openingChannelId?.let { id ->
                            uiState.channels.firstOrNull { it.id == id }?.name
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = uiState.openingChannelId != null,
                            enter = fadeIn(tween(150)),
                            exit = fadeOut(tween(180)),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(
                                    bottom = if (bottomNavOverlayPadding > 0.dp) {
                                        bottomNavOverlayPadding + 12.dp
                                    } else {
                                        20.dp
                                    },
                                ),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color(0xE60A1120),
                                border = BorderStroke(1.dp, BestCineBranding.PrimaryBlueBright.copy(alpha = 0.48f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    NuvioLoadingIndicator(size = 16.dp, color = BestCineBranding.PrimaryBlueBright)
                                    Text(
                                        text = if (!openingGridName.isNullOrBlank()) {
                                            "Sintonizando $openingGridName..."
                                        } else {
                                            "Sintonizando canal..."
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun BestCineTvCategoriesSidebar(
    categories: List<BestCineTvCategoryItem>,
    selectedCategory: String,
    categorySearchQuery: String,
    onCategorySearchChange: (String) -> Unit,
    onSelectCategory: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    Column(
        modifier = Modifier
            .width(210.dp)
            .fillMaxHeight()
            .background(Color(0xFF0A0E18))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        CompactSearchField(
            value = categorySearchQuery,
            placeholder = "Buscar grupo ou canal...",
            onValueChange = onCategorySearchChange,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            items(categories, key = { it.name }) { category ->
                val isSelected = category.name == selectedCategory
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (isSelected) BestCineBranding.PrimaryBlue else Color.Transparent,
                        )
                        .clickable { onSelectCategory(category.name) }
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) Color.White else tokens.colors.textSecondary,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = category.count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.White.copy(alpha = 0.92f) else tokens.colors.textMuted,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun BestCineTvSplitPlayerLayout(
    isSelectedTab: Boolean,
    isFullscreen: Boolean,
    epgBrtMinute: Int,
    channels: List<BestCineTvChannel>,
    favoriteChannelIds: Set<String>,
    activeChannel: BestCineTvChannel,
    activeStreams: List<StreamItem>,
    activeStreamIndex: Int,
    activeEpgDetails: BestCineTvEpgDetails?,
    isLoadingEpg: Boolean,
    openingChannelId: String?,
    openingChannelName: String? = null,
    onSelectChannel: (BestCineTvChannel) -> Unit,
    onToggleFavoriteChannel: (String) -> Unit,
    onSelectSourceIndex: (Int) -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onExitFullscreen: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onClosePlayer: () -> Unit,
    isCompactMobile: Boolean = false,
    bottomOverlayPadding: Dp = 0.dp,
) {
    val tokens = MaterialTheme.nuvio
    val currentStream = activeStreams.getOrNull(activeStreamIndex) ?: activeStreams.firstOrNull() ?: return
    val sourceUrl = currentStream.url?.trim().orEmpty()
    val requestHeaders = remember(currentStream) {
        sanitizePlaybackHeaders(currentStream.behaviorHints.proxyHeaders?.request.orEmpty())
    }
    val responseHeaders = remember(currentStream) {
        sanitizePlaybackResponseHeaders(currentStream.behaviorHints.proxyHeaders?.response.orEmpty())
    }

    LaunchedEffect(activeChannel.id) {
        BestCineTvRepository.loadChannelEpgDetails(activeChannel)
    }

    var playbackRetryNonce by remember(activeChannel.id, sourceUrl) { mutableStateOf(0) }
    var failedSourceIndices by remember(activeChannel.id) { mutableStateOf(emptySet<Int>()) }
    var failoverStatusMessage by remember(activeChannel.id) { mutableStateOf<String?>(null) }
    var hasPlayedCurrentSource by remember(activeChannel.id, sourceUrl, playbackRetryNonce) { mutableStateOf(false) }

    var controller by remember(activeChannel.id, sourceUrl, playbackRetryNonce) { mutableStateOf<PlayerEngineController?>(null) }
    var snapshot by remember(activeChannel.id, sourceUrl, playbackRetryNonce) { mutableStateOf(PlayerPlaybackSnapshot()) }
    var playerError by remember(activeChannel.id, sourceUrl, playbackRetryNonce) { mutableStateOf<String?>(null) }
    var isMuted by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(PlayerResizeMode.Fit) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    var playerControlsVisible by remember(activeChannel.id, sourceUrl) { mutableStateOf(true) }
    var controlsActivityTick by remember { mutableStateOf(0) }
    val isInPip = rememberIsInPictureInPicture()
    val castState by BestCineCastController.state.collectAsStateWithLifecycle()
    var showCastDialog by remember { mutableStateOf(false) }

    fun nextUntriedSourceIndex(excluding: Set<Int>): Int? {
        if (activeStreams.size <= 1) return null
        for (offset in 1 until activeStreams.size) {
            val candidate = (activeStreamIndex + offset) % activeStreams.size
            if (candidate !in excluding) return candidate
        }
        return null
    }

    val hasUntriedFallbackSources = activeStreams.size > 1 &&
        nextUntriedSourceIndex(failedSourceIndices + activeStreamIndex) != null

    val selectSourceManually: (Int) -> Unit = { idx ->
        failedSourceIndices = failedSourceIndices - idx
        failoverStatusMessage = null
        playerError = null
        onSelectSourceIndex(idx)
    }

    val retryActiveChannelPlayback: () -> Unit = {
        failedSourceIndices = emptySet()
        failoverStatusMessage = null
        playerError = null
        playbackRetryNonce += 1
    }

    // Auto-failover imediato quando a fonte atual retorna erro de reprodução
    LaunchedEffect(
        activeChannel.id,
        activeStreamIndex,
        sourceUrl,
        playerError,
        activeStreams.size,
        openingChannelId,
    ) {
        if (playerError == null || activeStreams.size <= 1 || openingChannelId != null) {
            return@LaunchedEffect
        }
        delay(500L)
        val updatedFailed = failedSourceIndices + activeStreamIndex
        val nextIdx = nextUntriedSourceIndex(updatedFailed)
        failedSourceIndices = updatedFailed
        if (nextIdx != null) {
            val currentLabel = formatBestCineTvSourceLabel(activeStreams.getOrNull(activeStreamIndex), activeStreamIndex)
            val nextLabel = formatBestCineTvSourceLabel(activeStreams.getOrNull(nextIdx), nextIdx)
            failoverStatusMessage = "$currentLabel indisponível — alternando para $nextLabel..."
            playerError = null
            onSelectSourceIndex(nextIdx)
        } else {
            failoverStatusMessage = null
        }
    }

    // Auto-failover por timeout caso a fonte trave carregando sem nunca iniciar
    LaunchedEffect(
        activeChannel.id,
        activeStreamIndex,
        sourceUrl,
        playbackRetryNonce,
        hasPlayedCurrentSource,
        playerError,
        activeStreams.size,
        openingChannelId,
        isSelectedTab,
        castState.isCasting,
        castState.isConnecting,
    ) {
        if (
            !isSelectedTab ||
            sourceUrl.isBlank() ||
            hasPlayedCurrentSource ||
            playerError != null ||
            activeStreams.size <= 1 ||
            openingChannelId != null ||
            castState.isCasting ||
            castState.isConnecting
        ) {
            return@LaunchedEffect
        }
        delay(12_000L)
        if (hasPlayedCurrentSource || snapshot.isPlaying || playerError != null) {
            return@LaunchedEffect
        }
        val updatedFailed = failedSourceIndices + activeStreamIndex
        val nextIdx = nextUntriedSourceIndex(updatedFailed)
        failedSourceIndices = updatedFailed
        if (nextIdx != null) {
            val currentLabel = formatBestCineTvSourceLabel(activeStreams.getOrNull(activeStreamIndex), activeStreamIndex)
            val nextLabel = formatBestCineTvSourceLabel(activeStreams.getOrNull(nextIdx), nextIdx)
            failoverStatusMessage = "$currentLabel sem resposta — alternando para $nextLabel..."
            onSelectSourceIndex(nextIdx)
        } else {
            failoverStatusMessage = null
            playerError = "Nenhuma fonte respondeu no tempo limite."
        }
    }

    // Oculta o aviso de alternância assim que a nova fonte estabiliza em reprodução
    LaunchedEffect(failoverStatusMessage, snapshot.isPlaying) {
        if (failoverStatusMessage != null && snapshot.isPlaying) {
            delay(2600L)
            failoverStatusMessage = null
        }
    }

    if (isSelectedTab && sourceUrl.isNotBlank()) {
        ManagePlayerPictureInPicture(
            isPlaying = snapshot.isPlaying,
            videoSize = IntSize(snapshot.videoWidth, snapshot.videoHeight),
        )
    }

    // Ao entrar/sair da tela cheia ou pausar, garante os controles visíveis imediatamente
    LaunchedEffect(isFullscreen) {
        playerControlsVisible = true
        controlsActivityTick += 1
    }

    LaunchedEffect(snapshot.isPlaying, snapshot.isLoading) {
        if (!snapshot.isPlaying || snapshot.isLoading) {
            playerControlsVisible = true
        }
    }

    // Auto-hide sincronizado com Kotlin para que qualquer movimento do mouse reexiba sempre
    LaunchedEffect(
        playerControlsVisible,
        controlsActivityTick,
        snapshot.isPlaying,
        snapshot.isLoading,
    ) {
        if (!playerControlsVisible || !snapshot.isPlaying || snapshot.isLoading) {
            return@LaunchedEffect
        }
        delay(4500L)
        playerControlsVisible = false
    }

    val resizeModeLabel = when (resizeMode) {
        PlayerResizeMode.Fit -> "Fit"
        PlayerResizeMode.Fill -> "Fill"
        PlayerResizeMode.Zoom -> "Zoom"
        PlayerResizeMode.Stretch -> "Stretch"
    }
    val speedLabel = if (playbackSpeed == 1f) "1x" else "${playbackSpeed}x"
    val isSwitchingChannel = openingChannelId != null
    val tuningMessage = if (isSwitchingChannel) {
        if (!openingChannelName.isNullOrBlank()) "Sintonizando $openingChannelName..." else "Sintonizando canal..."
    } else {
        null
    }
    val playerControlsState = PlayerControlsState(
        title = if (isSwitchingChannel && !openingChannelName.isNullOrBlank()) openingChannelName else activeChannel.name,
        episodeText = activeChannel.group,
        streamTitle = tuningMessage
            ?: activeEpgDetails?.nowLine
            ?: activeChannel.epgSummary.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty(),
        providerName = BestCineBranding.APP_NAME,
        pauseOverlayEnabled = false,
        resizeModeLabel = resizeModeLabel,
        playbackSpeedLabel = speedLabel,
        holdToSpeedEnabled = false,
        playLabel = "Play",
        pauseLabel = "Pause",
        closeLabel = if (isFullscreen) "Sair da tela cheia" else "Fechar player",
        pipLabel = "Picture-in-Picture",
        pipPlaceholderTitle = "Reproduzindo em janela flutuante",
        pipRestoreLabel = "Restaurar",
        pipWindowTitle = "${activeChannel.name} • ${BestCineBranding.APP_NAME}",
        themeAccentColor = "#1E6BFF",
        themeAccentStrongColor = "#4299FF",
        themeSelectedRingColor = "rgba(30, 107, 255, .38)",
        isPlaying = snapshot.isPlaying && !isSwitchingChannel,
        isLoading = snapshot.isLoading || isSwitchingChannel || (!hasPlayedCurrentSource && playerError == null),
        controlsVisible = playerControlsVisible || isSwitchingChannel,
        durationMs = snapshot.durationMs,
        positionMs = snapshot.positionMs,
        bufferedPositionMs = snapshot.bufferedPositionMs,
        showSources = false,
        showEpisodes = false,
        showExternalPlayer = false,
        showCast = sourceUrl.isNotBlank(),
        castActive = castState.isCasting,
        castLabel = "Transmitir para TV",
        showSubmitIntro = false,
        showVideoSettings = false,
    )

    val rowSpacing by animateDpAsState(
        targetValue = if (isFullscreen) 0.dp else 14.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tv_split_row_spacing",
    )

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val workspaceWidth = maxWidth
        val channelListWidth = (workspaceWidth * 0.36f).coerceIn(305.dp, 390.dp)
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            // ============================================================
            // LISTA VERTICAL DOS CANAIS DO GRUPO (ZAPPING COM 1 CLIQUE)
            // ============================================================
            AnimatedVisibility(
                visible = !isFullscreen && !isCompactMobile,
                enter = fadeIn(tween(200)) + expandHorizontally(tween(220, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(140)) + shrinkHorizontally(tween(220, easing = FastOutSlowInEasing)),
            ) {
                Column(
                    modifier = Modifier
                        .width(channelListWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0D1422))
                        .border(1.dp, Color(0xFF22314A).copy(alpha = 0.72f), RoundedCornerShape(14.dp))
                        .padding(8.dp),
                ) {
                Text(
                    text = "Canais do grupo (${channels.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(channels, key = { it.id }) { channel ->
                        val isActive = channel.id == activeChannel.id
                        val isOpening = openingChannelId == channel.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    when {
                                        isActive -> BestCineBranding.PrimaryBlue.copy(alpha = 0.24f)
                                        else -> Color.Transparent
                                    },
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isActive) BestCineBranding.PrimaryBlue else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable(enabled = openingChannelId == null) { onSelectChannel(channel) }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF151E32)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!channel.logoUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = channel.logoUrl,
                                        contentDescription = channel.name,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(4.dp),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.LiveTv,
                                        contentDescription = null,
                                        tint = BestCineBranding.PrimaryBlueBright,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = channel.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isActive) Color.White else tokens.colors.textPrimary,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val epgWindow = channel.epgWindow
                                if (epgWindow != null) {
                                    Text(
                                        text = epgWindow.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isActive) BestCineBranding.PrimaryBlueBright else tokens.colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    BestCineEpgProgressBar(
                                        startLabel = epgWindow.startLabel,
                                        endLabel = epgWindow.endLabel,
                                        progress = epgWindow.progressAtBrtMinute(epgBrtMinute),
                                        compact = true,
                                    )
                                } else if (channel.epgFirstLine.isNotEmpty()) {
                                    Text(
                                        text = channel.epgFirstLine,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isActive) BestCineBranding.PrimaryBlueBright else tokens.colors.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            val isFavorite = channel.id in favoriteChannelIds
                            IconButton(
                                onClick = { onToggleFavoriteChannel(channel.id) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                    contentDescription = if (isFavorite) "Remover favorito" else "Favoritar canal",
                                    tint = if (isFavorite) Color(0xFFFFCA28) else Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }

                            if (isOpening) {
                                NuvioLoadingIndicator(size = 14.dp, color = BestCineBranding.PrimaryBlueBright)
                            } else if (isActive) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(BestCineBranding.PrimaryBlueBright),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ============================================================
        // PLAYER EMBUTIDO (EXPANDE SUAVEMENTE IN-PLACE) + EPG COMPLETO
        // ============================================================
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            val previewHeight = if (isCompactMobile) {
                val aspectTarget = (maxWidth * 9f / 16f).coerceIn(180.dp, 230.dp)
                val maxAllowedForEpg = ((maxHeight - bottomOverlayPadding) * 0.38f).coerceAtLeast(168.dp)
                minOf(aspectTarget, maxAllowedForEpg)
            } else {
                val aspectTarget = maxWidth * 9f / 16f
                val maxAllowedForEpg = (maxHeight * 0.49f).coerceIn(245.dp, 385.dp)
                minOf(aspectTarget, maxAllowedForEpg).coerceAtLeast(240.dp)
            }
            val animatedPlayerHeight by animateDpAsState(
                targetValue = if (isFullscreen) maxHeight else previewHeight,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "tv_player_height",
            )
            // No Desktop (HWND/SwingPanel retangular), usa moldura slim de 10dp com respiro
            // simétrico de 8dp + linha interna de 1dp, mantendo o retângulo 100% encaixado sem
            // jamais colidir com a quina curva. No Android/iOS aplica clip concêntrico real.
            val animatedCornerRadius by animateDpAsState(
                targetValue = if (isFullscreen) 0.dp else if (isDesktop) 10.dp else 14.dp,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "tv_player_corner_radius",
            )
            val animatedInnerCornerRadius by animateDpAsState(
                targetValue = if (isFullscreen || isDesktop) 0.dp else 10.dp,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "tv_player_inner_corner_radius",
            )
            val animatedFramePadding by animateDpAsState(
                targetValue = if (isFullscreen) 0.dp else if (isDesktop) 8.dp else 4.dp,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "tv_player_frame_padding",
            )
            val animatedSpacing by animateDpAsState(
                targetValue = if (isFullscreen) 0.dp else 8.dp,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "tv_player_column_spacing",
            )
            // No preview, preenche toda a largura da moldura sem barras pretas nas laterais;
            // em tela cheia, respeita o modo escolhido no botão Fit/Fill/Zoom.
            val effectiveResizeMode = if (isFullscreen) {
                resizeMode
            } else if (resizeMode == PlayerResizeMode.Fit) {
                PlayerResizeMode.Stretch
            } else {
                resizeMode
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(animatedSpacing),
            ) {
                // Moldura moderna com encaixe limpo ao redor do player (sem quina invadindo a curva)
                Surface(
                    shape = RoundedCornerShape(animatedCornerRadius),
                    color = if (isFullscreen) Color.Black else Color(0xFF0A101D),
                    border = if (isFullscreen) {
                        null
                    } else {
                        BorderStroke(1.dp, Color(0xFF22314A).copy(alpha = 0.85f))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(animatedPlayerHeight),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(animatedFramePadding)
                            .then(
                                if (!isFullscreen && !isDesktop) {
                                    Modifier.clip(RoundedCornerShape(animatedInnerCornerRadius))
                                } else {
                                    Modifier
                                },
                            )
                            .background(Color.Black)
                            .then(
                                if (!isFullscreen && isDesktop) {
                                    Modifier.border(1.dp, Color.White.copy(alpha = 0.08f))
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val isCastingNow = castState.isCasting || castState.isConnecting
                        if (isCastingNow) {
                            BestCineCastPlayerLockOverlay(
                                castState = castState,
                                fallbackTitle = activeChannel.name,
                                compact = !isFullscreen,
                                onOpenControls = { showCastDialog = true },
                                onBack = if (isFullscreen) onExitFullscreen else null,
                            )
                        } else if (isSelectedTab && sourceUrl.isNotBlank()) {
                            key("${activeChannel.id}|$sourceUrl|$playbackRetryNonce") {
                                PlatformPlayerSurface(
                                    sourceUrl = sourceUrl,
                                    sourceHeaders = requestHeaders,
                                    sourceResponseHeaders = responseHeaders,
                                    streamType = currentStream.streamType,
                                    modifier = Modifier.fillMaxSize(),
                                    playWhenReady = true,
                                    resizeMode = effectiveResizeMode,
                                    playerControlsState = playerControlsState,
                                onPlayerControlsEvent = { type, value ->
                                    when (type) {
                                        "cursorActivity" -> {
                                            playerControlsVisible = true
                                            controlsActivityTick += 1
                                            true
                                        }
                                        "hideChrome" -> {
                                            if (snapshot.isPlaying && !snapshot.isLoading) {
                                                playerControlsVisible = false
                                            }
                                            true
                                        }
                                        "toggleFullscreen" -> {
                                            onToggleFullscreen()
                                            playerControlsVisible = true
                                            controlsActivityTick += 1
                                            true
                                        }
                                        "setPlaybackSpeed" -> {
                                            val speed = value.toFloat().coerceIn(0.5f, 2f)
                                            playbackSpeed = speed
                                            controller?.setPlaybackSpeed(speed)
                                            playerControlsVisible = true
                                            controlsActivityTick += 1
                                            true
                                        }
                                        else -> false
                                    }
                                },
                                onPlayerControlsAction = { action ->
                                    when (action) {
                                        PlayerControlsAction.ToggleChrome -> {
                                            playerControlsVisible = !playerControlsVisible
                                            if (playerControlsVisible) {
                                                controlsActivityTick += 1
                                            }
                                            true
                                        }
                                        PlayerControlsAction.Back -> {
                                            if (isFullscreen) {
                                                onExitFullscreen()
                                            } else {
                                                onClosePlayer()
                                            }
                                            true
                                        }
                                        PlayerControlsAction.TogglePlayback,
                                        PlayerControlsAction.KeyboardTogglePlayback -> {
                                            val c = controller
                                            if (c != null) {
                                                if (snapshot.isPlaying) c.pause() else c.play()
                                            }
                                            playerControlsVisible = true
                                            controlsActivityTick += 1
                                            true
                                        }
                                        PlayerControlsAction.ResizeMode -> {
                                            resizeMode = when (resizeMode) {
                                                PlayerResizeMode.Fit -> PlayerResizeMode.Fill
                                                PlayerResizeMode.Fill -> PlayerResizeMode.Zoom
                                                PlayerResizeMode.Zoom -> PlayerResizeMode.Stretch
                                                PlayerResizeMode.Stretch -> PlayerResizeMode.Fit
                                            }
                                            playerControlsVisible = true
                                            controlsActivityTick += 1
                                            true
                                        }
                                        PlayerControlsAction.Cast -> {
                                            showCastDialog = true
                                            playerControlsVisible = true
                                            controlsActivityTick += 1
                                            true
                                        }
                                        PlayerControlsAction.PictureInPicture -> {
                                            togglePlayerPictureInPicture()
                                            true
                                        }
                                        PlayerControlsAction.KeyboardVolumeUp -> {
                                            controller?.adjustVolumeBy(0.1f)
                                            playerControlsVisible = true
                                            controlsActivityTick += 1
                                            true
                                        }
                                        PlayerControlsAction.KeyboardVolumeDown -> {
                                            controller?.adjustVolumeBy(-0.1f)
                                            playerControlsVisible = true
                                            controlsActivityTick += 1
                                            true
                                        }
                                        else -> {
                                            playerControlsVisible = true
                                            controlsActivityTick += 1
                                            false
                                        }
                                    }
                                },
                                onControllerReady = { readyController ->
                                    controller = readyController
                                    if (isMuted) {
                                        readyController.setMuted(true)
                                    }
                                },
                                onSnapshot = { snap ->
                                    snapshot = snap
                                    if (snap.isPlaying) {
                                        playerError = null
                                        hasPlayedCurrentSource = true
                                        if (activeStreamIndex in failedSourceIndices) {
                                            failedSourceIndices = failedSourceIndices - activeStreamIndex
                                        }
                                    }
                                },
                                onError = { err -> playerError = err },
                            )
                        }
                        if (playerError != null && hasUntriedFallbackSources && openingChannelId == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xD9080C16))
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    NuvioLoadingIndicator(
                                        size = 30.dp,
                                        color = BestCineBranding.PrimaryBlueBright,
                                    )
                                    Text(
                                        text = "Alternando para outra fonte...",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        } else if (playerError != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xE6080C16))
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.LiveTv,
                                        contentDescription = null,
                                        tint = BestCineBranding.PrimaryBlueBright,
                                        modifier = Modifier.size(30.dp),
                                    )
                                    Text(
                                        text = "Canal em manutenção ou indisponível",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = if (activeStreams.size > 1) {
                                            "Todas as fontes disponíveis foram testadas. Tente novamente ou escolha outro canal."
                                        } else {
                                            "Tente outra alternativa ou volte novamente mais tarde."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.colors.textMuted,
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Button(
                                            onClick = retryActiveChannelPlayback,
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = BestCineBranding.PrimaryBlue,
                                                contentColor = Color.White,
                                            ),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Tentar novamente")
                                        }
                                        if (isFullscreen) {
                                            Button(
                                                onClick = onExitFullscreen,
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color.White.copy(alpha = 0.14f),
                                                    contentColor = Color.White,
                                                ),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.FullscreenExit,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Sair da tela cheia")
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (!isDesktop && isFullscreen && !isInPip) {
                            BestCineTvMobileFullscreenOverlay(
                                channelName = activeChannel.name,
                                programTitle = activeEpgDetails?.nowWindow?.title
                                    ?: activeChannel.epgWindow?.title
                                    ?: activeChannel.group,
                                isPlaying = snapshot.isPlaying,
                                isLoading = snapshot.isLoading,
                                isMuted = isMuted,
                                isFavorite = activeChannel.id in favoriteChannelIds,
                                isCasting = castState.isCasting,
                                controlsVisible = playerControlsVisible,
                                resizeModeLabel = resizeModeLabel,
                                activeStreams = activeStreams,
                                activeStreamIndex = activeStreamIndex,
                                openingChannelId = openingChannelId,
                                onToggleControls = {
                                    playerControlsVisible = !playerControlsVisible
                                    if (playerControlsVisible) {
                                        controlsActivityTick += 1
                                    }
                                },
                                onUserActivity = {
                                    playerControlsVisible = true
                                    controlsActivityTick += 1
                                },
                                onTogglePlayPause = {
                                    val c = controller
                                    if (c != null) {
                                        if (snapshot.isPlaying) c.pause() else c.play()
                                    }
                                    playerControlsVisible = true
                                    controlsActivityTick += 1
                                },
                                onPreviousChannel = {
                                    playerControlsVisible = true
                                    controlsActivityTick += 1
                                    onPreviousChannel()
                                },
                                onNextChannel = {
                                    playerControlsVisible = true
                                    controlsActivityTick += 1
                                    onNextChannel()
                                },
                                onToggleMute = {
                                    val nextMuted = !isMuted
                                    isMuted = nextMuted
                                    controller?.setMuted(nextMuted)
                                    playerControlsVisible = true
                                    controlsActivityTick += 1
                                },
                                onToggleFavorite = {
                                    onToggleFavoriteChannel(activeChannel.id)
                                    playerControlsVisible = true
                                    controlsActivityTick += 1
                                },
                                onCycleResizeMode = {
                                    resizeMode = when (resizeMode) {
                                        PlayerResizeMode.Fit -> PlayerResizeMode.Fill
                                        PlayerResizeMode.Fill -> PlayerResizeMode.Zoom
                                        PlayerResizeMode.Zoom -> PlayerResizeMode.Stretch
                                        PlayerResizeMode.Stretch -> PlayerResizeMode.Fit
                                    }
                                    playerControlsVisible = true
                                    controlsActivityTick += 1
                                },
                                onSelectSourceIndex = { idx ->
                                    selectSourceManually(idx)
                                    playerControlsVisible = true
                                    controlsActivityTick += 1
                                },
                                onTogglePip = {
                                    togglePlayerPictureInPicture()
                                },
                                onCastClick = {
                                    showCastDialog = true
                                    playerControlsVisible = true
                                    controlsActivityTick += 1
                                },
                                onExitFullscreen = onExitFullscreen,
                            )
                        }
                        val showInPlayerLoadingOverlay = !isDesktop &&
                            !isCastingNow &&
                            !isInPip &&
                            playerError == null &&
                            (isSwitchingChannel || (!snapshot.isPlaying && (snapshot.isLoading || !hasPlayedCurrentSource))) &&
                            !(isFullscreen && playerControlsVisible && !isSwitchingChannel)

                        androidx.compose.animation.AnimatedVisibility(
                            visible = showInPlayerLoadingOverlay,
                            enter = fadeIn(tween(150)),
                            exit = fadeOut(tween(180)),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(if (isSwitchingChannel) Color(0x99060A13) else Color(0x66060A13)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                ) {
                                    NuvioLoadingIndicator(
                                        size = 34.dp,
                                        color = BestCineBranding.PrimaryBlueBright,
                                    )
                                    Text(
                                        text = tuningMessage ?: "Carregando ${activeChannel.name}...",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = failoverStatusMessage != null && playerError == null && !isInPip,
                            enter = fadeIn(tween(180)),
                            exit = fadeOut(tween(220)),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = if (isFullscreen) 18.dp else 10.dp, start = 16.dp, end = 16.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color(0xCC0B1220),
                                border = BorderStroke(1.dp, BestCineBranding.PrimaryBlueBright.copy(alpha = 0.45f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (!snapshot.isPlaying) {
                                        NuvioLoadingIndicator(
                                            size = 14.dp,
                                            color = BestCineBranding.PrimaryBlueBright,
                                        )
                                    }
                                    Text(
                                        text = failoverStatusMessage.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Player pausado em segundo plano",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                        )
                    }
                    }
                }

                AnimatedVisibility(
                    visible = !isFullscreen,
                    enter = fadeIn(tween(200)) + expandVertically(tween(220, easing = FastOutSlowInEasing)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(220, easing = FastOutSlowInEasing)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Barra compacta de controles + fontes
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0D1422),
                            border = BorderStroke(1.dp, Color(0xFF22314A).copy(alpha = 0.72f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(
                                    horizontal = if (isCompactMobile) 10.dp else 12.dp,
                                    vertical = if (isCompactMobile) 8.dp else 7.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (isCompactMobile) {
                                    // Layout Mobile Compacto: Linha 1 = Nome do Canal + AO VIVO + Fechar (X)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = activeChannel.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = tokens.colors.textPrimary,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(BestCineBranding.PrimaryBlue.copy(alpha = 0.22f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = "AO VIVO",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BestCineBranding.PrimaryBlueBright,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                        IconButton(
                                            onClick = onClosePlayer,
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Fechar player",
                                                tint = tokens.colors.textMuted,
                                                modifier = Modifier.size(17.dp),
                                            )
                                        }
                                    }
                                    // Linha 2 Mobile = Botões compactos bem espaçados (Tela cheia sem quebrar linha!)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                                        ) {
                                            IconButton(
                                                onClick = onPreviousChannel,
                                                enabled = openingChannelId == null,
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.SkipPrevious,
                                                    contentDescription = "Canal anterior",
                                                    tint = tokens.colors.textPrimary,
                                                    modifier = Modifier.size(19.dp),
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    val c = controller ?: return@IconButton
                                                    if (snapshot.isPlaying) c.pause() else c.play()
                                                },
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    imageVector = if (snapshot.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                    contentDescription = if (snapshot.isPlaying) "Pausar" else "Reproduzir",
                                                    tint = BestCineBranding.PrimaryBlueBright,
                                                    modifier = Modifier.size(19.dp),
                                                )
                                            }
                                            IconButton(
                                                onClick = onNextChannel,
                                                enabled = openingChannelId == null,
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.SkipNext,
                                                    contentDescription = "Próximo canal",
                                                    tint = tokens.colors.textPrimary,
                                                    modifier = Modifier.size(19.dp),
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    isMuted = !isMuted
                                                    controller?.setMuted(isMuted)
                                                },
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    imageVector = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                                    contentDescription = if (isMuted) "Ativar som" else "Silenciar",
                                                    tint = tokens.colors.textPrimary,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            val activeIsFavorite = activeChannel.id in favoriteChannelIds
                                            IconButton(
                                                onClick = { onToggleFavoriteChannel(activeChannel.id) },
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    imageVector = if (activeIsFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                                    contentDescription = if (activeIsFavorite) "Remover favorito" else "Favoritar canal",
                                                    tint = if (activeIsFavorite) Color(0xFFFFCA28) else tokens.colors.textPrimary,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                            IconButton(
                                                onClick = { showCastDialog = true },
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    imageVector = if (castState.isCasting) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                                                    contentDescription = "Transmitir para TV",
                                                    tint = if (castState.isCasting) BestCineBranding.PrimaryBlueBright else tokens.colors.textPrimary,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                            IconButton(
                                                onClick = { togglePlayerPictureInPicture() },
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.PictureInPictureAlt,
                                                    contentDescription = "Picture-in-Picture",
                                                    tint = if (isInPip) BestCineBranding.PrimaryBlueBright else tokens.colors.textPrimary,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Button(
                                                onClick = onEnterFullscreen,
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                                                modifier = Modifier.height(32.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = BestCineBranding.PrimaryBlue,
                                                    contentColor = Color.White,
                                                ),
                                            ) {
                                                Icon(Icons.Rounded.Fullscreen, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "Tela cheia",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // Layout Desktop / Tablet (1 linha horizontal enxuta)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        IconButton(
                                            onClick = onPreviousChannel,
                                            enabled = openingChannelId == null,
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.SkipPrevious,
                                                contentDescription = "Canal anterior",
                                                tint = tokens.colors.textPrimary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                val c = controller ?: return@IconButton
                                                if (snapshot.isPlaying) c.pause() else c.play()
                                            },
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (snapshot.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                contentDescription = if (snapshot.isPlaying) "Pausar" else "Reproduzir",
                                                tint = BestCineBranding.PrimaryBlueBright,
                                                modifier = Modifier.size(22.dp),
                                            )
                                        }

                                        IconButton(
                                            onClick = onNextChannel,
                                            enabled = openingChannelId == null,
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.SkipNext,
                                                contentDescription = "Próximo canal",
                                                tint = tokens.colors.textPrimary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                isMuted = !isMuted
                                                controller?.setMuted(isMuted)
                                            },
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                                contentDescription = if (isMuted) "Ativar som" else "Silenciar",
                                                tint = tokens.colors.textPrimary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = activeChannel.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = tokens.colors.textPrimary,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(BestCineBranding.PrimaryBlue.copy(alpha = 0.22f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    text = "AO VIVO",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BestCineBranding.PrimaryBlueBright,
                                                )
                                            }
                                        }

                                        val activeIsFavoriteDesktop = activeChannel.id in favoriteChannelIds
                                        IconButton(
                                            onClick = { onToggleFavoriteChannel(activeChannel.id) },
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (activeIsFavoriteDesktop) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                                contentDescription = if (activeIsFavoriteDesktop) "Remover favorito" else "Favoritar canal",
                                                tint = if (activeIsFavoriteDesktop) Color(0xFFFFCA28) else tokens.colors.textPrimary,
                                                modifier = Modifier.size(19.dp),
                                            )
                                        }

                                        IconButton(
                                            onClick = { showCastDialog = true },
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (castState.isCasting) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                                                contentDescription = "Transmitir para TV",
                                                tint = if (castState.isCasting) BestCineBranding.PrimaryBlueBright else tokens.colors.textPrimary,
                                                modifier = Modifier.size(19.dp),
                                            )
                                        }

                                        IconButton(
                                            onClick = { togglePlayerPictureInPicture() },
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PictureInPictureAlt,
                                                contentDescription = "Picture-in-Picture",
                                                tint = if (isInPip) BestCineBranding.PrimaryBlueBright else tokens.colors.textPrimary,
                                                modifier = Modifier.size(19.dp),
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(2.dp))

                                        Button(
                                            onClick = onEnterFullscreen,
                                            shape = RoundedCornerShape(9.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                            modifier = Modifier.height(34.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = BestCineBranding.PrimaryBlue,
                                                contentColor = Color.White,
                                            ),
                                        ) {
                                            Icon(Icons.Rounded.Fullscreen, contentDescription = null, modifier = Modifier.size(17.dp))
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text("Tela cheia", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                        }

                                        IconButton(
                                            onClick = onClosePlayer,
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Fechar player",
                                                tint = tokens.colors.textMuted,
                                                modifier = Modifier.size(19.dp),
                                            )
                                        }
                                    }
                                }

                                if (playerError != null) {
                                    Text(
                                        text = if (activeStreams.size > 1) {
                                            "Canal em manutenção nesta fonte. Tente outra opção abaixo ou escolha outro canal."
                                        } else {
                                            "Canal em manutenção ou temporariamente indisponível. Tente outra alternativa."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.colors.danger,
                                    )
                                }

                                if (activeStreams.size > 1) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "Fontes:",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = tokens.colors.textMuted,
                                        )
                                        activeStreams.forEachIndexed { idx, stream ->
                                            val selected = idx == activeStreamIndex
                                            val label = formatBestCineTvSourceLabel(stream, idx)
                                            Surface(
                                                onClick = { selectSourceManually(idx) },
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (selected) BestCineBranding.PrimaryBlue else Color.White.copy(alpha = 0.06f),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (selected) BestCineBranding.PrimaryBlueBright else Color.White.copy(alpha = 0.10f),
                                                ),
                                            ) {
                                                Text(
                                                    text = label,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (selected) Color.White else tokens.colors.textSecondary,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ============================================================
                        // PAINEL COMPLETO DE PROGRAMAÇÃO (EPG) ABAIXO DO PLAYER
                        // ============================================================
                        BestCineTvEpgPanel(
                            channel = activeChannel,
                            epgDetails = activeEpgDetails,
                            epgBrtMinute = epgBrtMinute,
                            isLoading = isLoadingEpg,
                            bottomExtraPadding = 16.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(bottom = bottomOverlayPadding),
                        )
                    }
                }
            }
        }
    }
    }

    BestCineCastDialog(
        visible = showCastDialog,
        media = sourceUrl.takeIf { it.isNotBlank() }?.let { url ->
            BestCineCastMedia(
                title = activeChannel.name,
                url = url,
                headers = requestHeaders,
                contentType = currentStream.streamType?.takeIf { it.isNotBlank() } ?: "application/vnd.apple.mpegurl",
                isLive = true,
            )
        },
        onPauseLocalPlayback = { controller?.pause() },
        onDismiss = { showCastDialog = false },
    )
}

@Composable
private fun BestCineTvEpgPanel(
    channel: BestCineTvChannel,
    epgDetails: BestCineTvEpgDetails?,
    epgBrtMinute: Int,
    isLoading: Boolean,
    bottomExtraPadding: androidx.compose.ui.unit.Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val epgScrollState = rememberScrollState()
    LaunchedEffect(channel.id) {
        epgScrollState.scrollTo(0)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0E1422),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(epgScrollState)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = bottomExtraPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Programação (EPG)",
                    style = MaterialTheme.typography.titleSmall,
                    color = BestCineBranding.PrimaryBlueBright,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "• ${channel.group}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isLoading) {
                    NuvioLoadingIndicator(size = 14.dp, color = BestCineBranding.PrimaryBlueBright)
                }
            }

            val nowLine = epgDetails?.nowLine
            val synopsis = epgDetails?.synopsis
            val upcoming = epgDetails?.upcomingLines.orEmpty()

            if (nowLine != null) {
                val nowWindow = epgDetails?.nowWindow
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = BestCineBranding.PrimaryBlue.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, BestCineBranding.PrimaryBlue.copy(alpha = 0.30f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            text = nowWindow?.title ?: nowLine,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (nowWindow != null) {
                            BestCineEpgProgressBar(
                                startLabel = nowWindow.startLabel,
                                endLabel = nowWindow.endLabel,
                                progress = nowWindow.progressAtBrtMinute(epgBrtMinute),
                                compact = false,
                            )
                        }
                        if (!synopsis.isNullOrBlank()) {
                            Text(
                                text = synopsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = tokens.colors.textSecondary,
                                lineHeight = 20.sp,
                            )
                        }
                    }
                }
            } else if (!synopsis.isNullOrBlank()) {
                Text(
                    text = synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    lineHeight = 20.sp,
                )
            } else if (channel.epgSummary.isNotBlank()) {
                Text(
                    text = channel.epgSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    lineHeight = 20.sp,
                )
            } else {
                Text(
                    text = "Guia de programação (EPG) indisponível para este canal no momento.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }

            if (upcoming.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "A seguir na grade",
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    upcoming.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = Color.White.copy(alpha = 0.04f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = item,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BestCineTvGridCard(
    channel: BestCineTvChannel,
    epgBrtMinute: Int,
    isFavorite: Boolean,
    isOpening: Boolean,
    enabled: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF101624),
        border = BorderStroke(
            1.dp,
            if (isOpening) BestCineBranding.PrimaryBlueBright else Color.White.copy(alpha = 0.08f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF16223A), Color(0xFF0B101C)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.LiveTv,
                        contentDescription = null,
                        tint = BestCineBranding.PrimaryBlueBright.copy(alpha = 0.7f),
                        modifier = Modifier.size(34.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(onClick = onToggleFavorite),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = if (isFavorite) "Remover favorito" else "Favoritar canal",
                        tint = if (isFavorite) Color(0xFFFFCA28) else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(16.dp),
                    )
                }

                if (isOpening) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioLoadingIndicator(size = 24.dp, color = BestCineBranding.PrimaryBlueBright)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val epgWindow = channel.epgWindow
                if (epgWindow != null) {
                    Text(
                        text = epgWindow.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    BestCineEpgProgressBar(
                        startLabel = epgWindow.startLabel,
                        endLabel = epgWindow.endLabel,
                        progress = epgWindow.progressAtBrtMinute(epgBrtMinute),
                        compact = true,
                    )
                } else {
                    if (channel.epgFirstLine.isNotEmpty()) {
                        Text(
                            text = channel.epgFirstLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Mantém altura visual uniforme com os cards que exibem a barra de progresso
                    Spacer(modifier = Modifier.height(15.dp))
                }
            }
        }
    }
}

@Composable
private fun CompactSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = tokens.colors.textMuted,
            modifier = Modifier.size(16.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = tokens.colors.textPrimary,
                ),
                cursorBrush = SolidColor(BestCineBranding.PrimaryBlueBright),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(
                onClick = { onValueChange("") },
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Limpar",
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private val EpgTrackColor = Color(0x24FFFFFF)
private val EpgProgressColor = Color(0xFFEF4444)

@Composable
private fun BestCineEpgProgressBar(
    startLabel: String,
    endLabel: String,
    progress: Float,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val barHeight = if (compact) 3.5.dp else 5.dp
    val fontSize = if (compact) 9.5.sp else 11.sp
    val clampedProgress = progress.coerceIn(0.02f, 1f)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = startLabel,
            fontSize = fontSize,
            lineHeight = fontSize,
            color = tokens.colors.textMuted,
            maxLines = 1,
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(barHeight),
        ) {
            val radius = size.height * 0.5f
            val corner = CornerRadius(radius, radius)
            drawRoundRect(
                color = EpgTrackColor,
                size = size,
                cornerRadius = corner,
            )
            val fillWidth = (size.width * clampedProgress).coerceAtLeast(size.height)
            drawRoundRect(
                color = EpgProgressColor,
                size = Size(fillWidth.coerceAtMost(size.width), size.height),
                cornerRadius = corner,
            )
        }
        Text(
            text = endLabel,
            fontSize = fontSize,
            lineHeight = fontSize,
            color = tokens.colors.textMuted,
            maxLines = 1,
        )
    }
}

private fun formatBestCineTvSourceLabel(stream: StreamItem?, index: Int): String =
    stream?.name
        ?.replace("\n", " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "Fonte ${index + 1}"

@Composable
private fun BestCineTvMobileFullscreenOverlay(
    channelName: String,
    programTitle: String,
    isPlaying: Boolean,
    isLoading: Boolean,
    isMuted: Boolean,
    isFavorite: Boolean,
    isCasting: Boolean = false,
    controlsVisible: Boolean,
    resizeModeLabel: String,
    activeStreams: List<StreamItem>,
    activeStreamIndex: Int,
    openingChannelId: String?,
    onToggleControls: () -> Unit,
    onUserActivity: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCycleResizeMode: () -> Unit,
    onSelectSourceIndex: (Int) -> Unit,
    onTogglePip: () -> Unit,
    onCastClick: (() -> Unit)? = null,
    onExitFullscreen: () -> Unit,
) {
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val horizontalSafePadding = maxOf(
        safePadding.calculateLeftPadding(layoutDirection),
        safePadding.calculateRightPadding(layoutDirection),
    )
    val playerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val gestureController = rememberPlayerGestureController(
        restoreBrightness = playerSettings.touchGesturesEnabled,
    )
    val sideGestureSystemEdgeExclusionPx = with(density) {
        PlayerSideGestureSystemEdgeExclusion.toPx()
    }
    val gestureScope = rememberCoroutineScope()
    var gestureFeedback by remember { mutableStateOf<GestureFeedbackState?>(null) }
    var renderedGestureFeedback by remember { mutableStateOf<GestureFeedbackState?>(null) }
    var gestureFeedbackJob by remember { mutableStateOf<Job?>(null) }

    val showGestureFeedback: (GestureFeedbackState) -> Unit = { feedback ->
        gestureFeedbackJob?.cancel()
        gestureFeedback = feedback
        renderedGestureFeedback = feedback
        gestureFeedbackJob = gestureScope.launch {
            delay(900L)
            gestureFeedback = null
        }
    }
    val showBrightnessFeedback: (Float) -> Unit = { level ->
        val clamped = level.coerceIn(0f, 1f)
        val percentage = (clamped * 100f).roundToInt()
        showGestureFeedback(
            GestureFeedbackState(
                messageRes = Res.string.compose_player_brightness_level,
                messageArgs = listOf("$percentage%"),
                icon = GestureFeedbackIcon.Brightness,
                level = clamped,
            ),
        )
    }
    val showVolumeFeedback: (PlayerAudioLevel) -> Unit = { audio ->
        val clamped = audio.fraction.coerceIn(0f, 1f)
        val percentage = (clamped * 100f).roundToInt()
        showGestureFeedback(
            GestureFeedbackState(
                messageRes = if (audio.isMuted) {
                    Res.string.compose_player_muted
                } else {
                    Res.string.compose_player_volume_level
                },
                messageArgs = if (audio.isMuted) emptyList() else listOf("$percentage%"),
                icon = if (audio.isMuted) GestureFeedbackIcon.VolumeMuted else GestureFeedbackIcon.Volume,
                isDanger = audio.isMuted,
                level = if (audio.isMuted) 0f else clamped,
            ),
        )
    }

    val onToggleControlsState = rememberUpdatedState(onToggleControls)
    val touchGesturesEnabledState = rememberUpdatedState(playerSettings.touchGesturesEnabled)
    val showBrightnessFeedbackState = rememberUpdatedState(showBrightnessFeedback)
    val showVolumeFeedbackState = rememberUpdatedState(showVolumeFeedback)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(gestureController, sideGestureSystemEdgeExclusionPx) {
                val controller = gestureController ?: return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    if (!touchGesturesEnabledState.value) {
                        return@awaitEachGesture
                    }
                    val width = size.width.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
                    val height = size.height.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
                    val edgeExclusionPx = sideGestureSystemEdgeExclusionPx.coerceAtMost(height * 0.25f)
                    if (down.position.y <= edgeExclusionPx || down.position.y >= height - edgeExclusionPx) {
                        return@awaitEachGesture
                    }
                    val region = when {
                        down.position.x < width * PlayerLeftGestureBoundary -> PlayerSideGesture.Brightness
                        down.position.x > width * PlayerRightGestureBoundary -> PlayerSideGesture.Volume
                        else -> null
                    } ?: return@awaitEachGesture

                    val initialBrightness = if (region == PlayerSideGesture.Brightness) {
                        controller.currentBrightness()
                    } else {
                        null
                    }
                    val initialVolume = if (region == PlayerSideGesture.Volume) {
                        controller.currentVolume()
                    } else {
                        null
                    }
                    if (region == PlayerSideGesture.Brightness && initialBrightness == null) {
                        return@awaitEachGesture
                    }
                    if (region == PlayerSideGesture.Volume && initialVolume == null) {
                        return@awaitEachGesture
                    }

                    var totalDx = 0f
                    var totalDy = 0f
                    var activeSideGesture: PlayerSideGesture? = null
                    var activationDy = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || change.isConsumed) break

                        val delta = change.position - change.previousPosition
                        totalDx += delta.x
                        totalDy += delta.y

                        if (activeSideGesture == null) {
                            val activationSlop = maxOf(
                                viewConfiguration.touchSlop * PlayerVerticalGestureTouchSlopMultiplier,
                                height * PlayerVerticalGestureMinHeightFraction,
                            )
                            if (
                                abs(totalDy) > activationSlop &&
                                abs(totalDy) > abs(totalDx) * PlayerVerticalGestureDominanceRatio
                            ) {
                                activeSideGesture = region
                                activationDy = totalDy
                            } else {
                                continue
                            }
                        }

                        val activeDy = totalDy - activationDy
                        val deltaFraction = (-activeDy / height) * PlayerVerticalGestureSensitivity
                        when (activeSideGesture) {
                            PlayerSideGesture.Brightness -> {
                                controller.setBrightness((initialBrightness ?: 0f) + deltaFraction)
                                    ?.let { level -> showBrightnessFeedbackState.value(level) }
                            }
                            PlayerSideGesture.Volume -> {
                                controller.setVolume((initialVolume?.fraction ?: 0f) + deltaFraction)
                                    ?.let { audio -> showVolumeFeedbackState.value(audio) }
                            }
                            null -> Unit
                        }
                        change.consume()
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleControlsState.value() },
                )
            },
    ) {
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black.copy(alpha = 0.72f),
                                0.30f to Color.Black.copy(alpha = 0.18f),
                                0.70f to Color.Black.copy(alpha = 0.18f),
                                1.0f to Color.Black.copy(alpha = 0.78f),
                            ),
                        ),
                    )
                    .padding(safePadding)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                // Topo: Voltar + Nome do Canal + AO VIVO + Favoritar
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconButton(
                        onClick = onExitFullscreen,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.50f)),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Sair da tela cheia",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = channelName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFEF4444).copy(alpha = 0.90f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "AO VIVO",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }
                        }
                        if (programTitle.isNotBlank()) {
                            Text(
                                text = programTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.50f)),
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = if (isFavorite) "Remover favorito" else "Favoritar canal",
                            tint = if (isFavorite) Color(0xFFFFCA28) else Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // Centro: Canal anterior · Play/Pause · Próximo canal
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    IconButton(
                        onClick = onPreviousChannel,
                        enabled = openingChannelId == null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.52f)),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = "Canal anterior",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(BestCineBranding.PrimaryBlue.copy(alpha = 0.92f))
                            .clickable(onClick = onTogglePlayPause),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLoading || openingChannelId != null) {
                            NuvioLoadingIndicator(size = 28.dp, color = Color.White)
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproduzir",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = onNextChannel,
                        enabled = openingChannelId == null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.52f)),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Próximo canal",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                // Base: Fontes (se houver >1) + Mudo + Proporção + PiP + Sair da tela cheia
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (activeStreams.size > 1) {
                            activeStreams.forEachIndexed { idx, stream ->
                                val selected = idx == activeStreamIndex
                                val label = stream.name
                                    ?.replace("\n", " ")
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: "Fonte ${idx + 1}"
                                Surface(
                                    onClick = { onSelectSourceIndex(idx) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) {
                                        BestCineBranding.PrimaryBlue
                                    } else {
                                        Color.Black.copy(alpha = 0.55f)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) BestCineBranding.PrimaryBlueBright else Color.White.copy(alpha = 0.20f),
                                    ),
                                ) {
                                    Text(
                                        text = label,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.50f)),
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                            contentDescription = if (isMuted) "Ativar som" else "Silenciar",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Surface(
                        onClick = onCycleResizeMode,
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                    ) {
                        Text(
                            text = resizeModeLabel,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    if (onCastClick != null) {
                        IconButton(
                            onClick = {
                                onUserActivity()
                                onCastClick()
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.50f)),
                        ) {
                            Icon(
                                imageVector = if (isCasting) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                                contentDescription = "Transmitir para TV",
                                tint = if (isCasting) BestCineBranding.PrimaryBlueBright else Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            onUserActivity()
                            onTogglePip()
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.50f)),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PictureInPictureAlt,
                            contentDescription = "Picture-in-Picture",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    IconButton(
                        onClick = onExitFullscreen,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.50f)),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FullscreenExit,
                            contentDescription = "Sair da tela cheia",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        PlayerGestureOverlay(
            currentFeedback = gestureFeedback,
            renderedFeedback = renderedGestureFeedback,
            useLegacyLayout = playerSettings.useLegacyPlayerLayout,
            horizontalSafePadding = horizontalSafePadding,
            horizontalPadding = 18.dp,
        )
    }
}
