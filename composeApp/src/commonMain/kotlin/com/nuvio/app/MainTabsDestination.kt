package com.nuvio.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.bestcine.BestCineAccountRepository
import com.nuvio.app.bestcine.BestCineBranding
import com.nuvio.app.bestcine.tv.BestCineTvRepository
import com.nuvio.app.core.ui.BestCineDesktopTopBarHeight
import com.nuvio.app.core.ui.FullscreenActionButton
import com.nuvio.app.core.ui.isFullscreenActionSupported
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.LocalNuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioClassicNavigationBar
import com.nuvio.app.core.ui.DesktopNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationItem
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.rememberNuvioNavBarScrollState
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.player.rememberIsInPictureInPicture
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import com.nuvio.app.features.settings.DesktopNavigationLayout
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.features.settings.ThemeSettingsRepository
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_profile
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.compose_nav_settings
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.stringResource
@Composable
internal fun MainTabsDestination(
    selectedTab: AppScreenTab,
    initialHomeReady: Boolean,
    rootRouteActive: Boolean,
    useTabletFloatingTabBar: Boolean,
    useNativeNavigation: Boolean,
    useNativeTabBar: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    liquidGlassNativeTabBarEnabled: Boolean,
    desktopNavigationLayout: DesktopNavigationLayout,
    requests: AppTabRequests,
    state: AppTabState,
    actions: (isTabletLayout: Boolean) -> AppTabActions,
    onBack: () -> Unit,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
) {
    PlatformBackHandler(enabled = rootRouteActive && !isDesktop, onBack = onBack)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val isAndroidTv = rememberIsAndroidTv()
        val isTabletLayout = isAndroidTv || useTabletFloatingTabBar || screenWidth >= 768.dp
        val tabActions = remember(actions, isTabletLayout) { actions(isTabletLayout) }
        val useNativeBottomTabs = if (useNativeNavigation) {
            useNativeTabBar
        } else {
            liquidGlassNativeTabBarSupported && liquidGlassNativeTabBarEnabled && initialHomeReady
        }
        val useDesktopSidebar = isDesktop &&
            isTabletLayout &&
            !useNativeBottomTabs &&
            desktopNavigationLayout == DesktopNavigationLayout.Sidebar
        val useDesktopTopBar = isDesktop && !useNativeBottomTabs && !useDesktopSidebar
        val useFloatingTopBar = !isDesktop && isTabletLayout && !useNativeBottomTabs
        val topChromePadding = if (useFloatingTopBar || useDesktopTopBar) {
            val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            if (useDesktopTopBar) {
                statusBarPadding + BestCineDesktopTopBarHeight + 6.dp
            } else {
                max(statusBarPadding + 24.dp, 48.dp) + 64.dp
            }
        } else {
            null
        }
        val tabsRouteActiveState = rememberUpdatedState(rootRouteActive)
        val navBarScrollState = rememberNuvioNavBarScrollState()
        LaunchedEffect(selectedTab) {
            navBarScrollState.switchToTab(selectedTab)
        }
        val navBarHazeState = rememberHazeState()
        val navBarStyleSetting by remember { ThemeSettingsRepository.navBarStyle }.collectAsStateWithLifecycle()
        val homeCatalogSettingsUiState by remember { HomeCatalogSettingsRepository.uiState }.collectAsStateWithLifecycle()
        val bestCineTvState by remember { BestCineTvRepository.uiState }.collectAsStateWithLifecycle()
        val isInPip = rememberIsInPictureInPicture()
        val isBestCineTvFullscreen = selectedTab == AppScreenTab.Tv &&
            (bestCineTvState.isFullscreen || (!isDesktop && isInPip)) &&
            bestCineTvState.activeChannel != null

        val sidebarHoverSource = remember { MutableInteractionSource() }
        val isSidebarHovered by sidebarHoverSource.collectIsHoveredAsState()
        var isProfileStackVisible by remember { mutableStateOf(false) }

        val isSidebarExpanded = when (navBarStyleSetting) {
            NavBarStyle.EXPANDED -> true
            NavBarStyle.COMPACT -> isProfileStackVisible
            else -> isSidebarHovered || isProfileStackVisible // ADAPTIVE
        }

        val animatedSidebarWidth by animateDpAsState(
            targetValue = if (isSidebarExpanded) DesktopSidebarExpandedWidth else DesktopSidebarCollapsedWidth,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "desktop_sidebar_width",
        )

        val isSidebarAlwaysExpanded = useDesktopSidebar && navBarStyleSetting == NavBarStyle.EXPANDED
        val targetContentStartPadding = if (useDesktopSidebar && !isBestCineTvFullscreen) {
            if (isSidebarAlwaysExpanded) DesktopSidebarExpandedWidth else DesktopSidebarCollapsedWidth
        } else {
            0.dp
        }
        val contentStartPadding by animateDpAsState(
            targetValue = targetContentStartPadding,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            label = "main_content_start_padding",
        )
        val navBarGlowEnabled by ThemeSettingsRepository.navBarGlowEnabled.collectAsStateWithLifecycle()
        var isTopProfileSwitcherOpen by remember { mutableStateOf(false) }
        val floatingNavigationItems = listOf(
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Home,
                onClick = { onTabSelected(AppScreenTab.Home) },
                icon = Icons.Filled.Home,
                label = stringResource(Res.string.compose_nav_home),
            ),
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Tv,
                onClick = { onTabSelected(AppScreenTab.Tv) },
                icon = Icons.Rounded.LiveTv,
                label = "TV",
            ),
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Search,
                onClick = { onTabSelected(AppScreenTab.Search) },
                drawable = Res.drawable.sidebar_search,
                label = stringResource(Res.string.compose_nav_search),
            ),
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Library,
                onClick = { onTabSelected(AppScreenTab.Library) },
                drawable = Res.drawable.sidebar_library,
                label = stringResource(Res.string.compose_nav_library),
            ),
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Settings,
                onClick = { onTabSelected(AppScreenTab.Settings) },
                label = stringResource(if (isDesktop) Res.string.compose_nav_settings else Res.string.compose_nav_profile),
                content = { onClick ->
                    ProfileSwitcherTab(
                        selected = selectedTab == AppScreenTab.Settings,
                        onClick = onClick,
                        onProfileSelected = onProfileSelected,
                        onAddProfileRequested = onAddProfileRequested,
                        hazeState = navBarHazeState,
                        onPopupStateChanged = { isTopProfileSwitcherOpen = it },
                        avatarSize = if (isDesktop && screenWidth < 800.dp) 26 else if (isDesktop && screenWidth > 1600.dp) 30 else 28,
                        popupBelowAnchor = isDesktop || isTabletLayout,
                    )
                },
            ),
        )

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (initialHomeReady) 1f else 0f),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (!isDesktop && !isTabletLayout && !useNativeBottomTabs && !isBestCineTvFullscreen && navBarStyleSetting == NavBarStyle.CLASSIC) {
                    NuvioClassicNavigationBar {
                        NavItem(
                            selected = selectedTab == AppScreenTab.Home,
                            onClick = { onTabSelected(AppScreenTab.Home) },
                            icon = Icons.Filled.Home,
                            contentDescription = stringResource(Res.string.compose_nav_home),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Tv,
                            onClick = { onTabSelected(AppScreenTab.Tv) },
                            icon = Icons.Rounded.LiveTv,
                            contentDescription = "TV",
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Search,
                            onClick = { onTabSelected(AppScreenTab.Search) },
                            icon = Res.drawable.sidebar_search,
                            contentDescription = stringResource(Res.string.compose_nav_search),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Library,
                            onClick = { onTabSelected(AppScreenTab.Library) },
                            icon = Res.drawable.sidebar_library,
                            contentDescription = stringResource(Res.string.compose_nav_library),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Settings,
                            onClick = { onTabSelected(AppScreenTab.Settings) },
                        ) {
                            ProfileSwitcherTab(
                                selected = selectedTab == AppScreenTab.Settings,
                                onClick = { onTabSelected(AppScreenTab.Settings) },
                                onProfileSelected = onProfileSelected,
                                onAddProfileRequested = onAddProfileRequested,
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            val tvOverscanHorizontal = if (isAndroidTv && !isBestCineTvFullscreen) 28.dp else 0.dp
            val tvOverscanVertical = if (isAndroidTv && !isBestCineTvFullscreen) 14.dp else 0.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = tvOverscanHorizontal, vertical = tvOverscanVertical),
            ) {
                val requiresNavBarHaze = if (useDesktopTopBar) {
                    true
                } else if (isTabletLayout) {
                    useFloatingTopBar
                } else {
                    !useNativeBottomTabs && navBarStyleSetting != NavBarStyle.CLASSIC
                }
                val shouldAttachNestedScroll = if (isDesktop || isTabletLayout) {
                    true
                } else {
                    navBarStyleSetting == NavBarStyle.ADAPTIVE
                }
                CompositionLocalProvider(
                    LocalNuvioBottomNavigationOverlayPadding provides if (useNativeBottomTabs) 49.dp else if (!isDesktop && !isTabletLayout && navBarStyleSetting != NavBarStyle.CLASSIC) 76.dp else 0.dp,
                    LocalNuvioNavBarScrollState provides navBarScrollState,
                ) {
                    AppTabHost(
                        selectedTab = selectedTab,
                        requests = requests,
                        state = state.copy(
                            tabsRouteActiveState = tabsRouteActiveState,
                            topChromePadding = if (isBestCineTvFullscreen) 0.dp else topChromePadding,
                        ),
                        actions = tabActions,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (requiresNavBarHaze) Modifier.hazeSource(state = navBarHazeState) else Modifier)
                            .then(if (shouldAttachNestedScroll) Modifier.nestedScroll(navBarScrollState.nestedScrollConnection) else Modifier)
                            .padding(innerPadding)
                            .padding(start = contentStartPadding),
                    )
                }

                AnimatedVisibility(
                    visible = useDesktopSidebar && !isBestCineTvFullscreen,
                    enter = fadeIn(tween(200)) + slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it },
                    exit = fadeOut(tween(160)) + slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it },
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    DesktopHoverSidebar(
                        selectedTab = selectedTab,
                        onTabSelected = onTabSelected,
                        onProfileSelected = onProfileSelected,
                        onAddProfileRequested = onAddProfileRequested,
                        sidebarExpanded = isSidebarExpanded,
                        sidebarWidth = animatedSidebarWidth,
                        hoverSource = sidebarHoverSource,
                        profileStackVisible = isProfileStackVisible,
                        onProfileStackVisibleChange = { isProfileStackVisible = it },
                    )
                }

                if (useFloatingTopBar && !isBestCineTvFullscreen) {
                    TabletFloatingTopBar(
                        selectedTab = selectedTab,
                        onTabSelected = onTabSelected,
                        onProfileSelected = onProfileSelected,
                        onAddProfileRequested = onAddProfileRequested,
                        navBarStyleSetting = navBarStyleSetting,
                        isHeroEnabled = homeCatalogSettingsUiState.heroEnabled,
                        hazeState = navBarHazeState,
                        scrollState = navBarScrollState,
                        windowWidth = screenWidth,
                    )
                }

                if (useDesktopTopBar && !isBestCineTvFullscreen) {
                    val desktopTopBarItems = listOf(
                        FloatingNavigationItem(
                            selected = selectedTab == AppScreenTab.Home,
                            onClick = { onTabSelected(AppScreenTab.Home) },
                            icon = Icons.Filled.Home,
                            label = stringResource(Res.string.compose_nav_home),
                        ),
                        FloatingNavigationItem(
                            selected = selectedTab == AppScreenTab.Tv,
                            onClick = { onTabSelected(AppScreenTab.Tv) },
                            icon = Icons.Rounded.LiveTv,
                            label = "TV",
                        ),
                        FloatingNavigationItem(
                            selected = selectedTab == AppScreenTab.Search,
                            onClick = { onTabSelected(AppScreenTab.Search) },
                            drawable = Res.drawable.sidebar_search,
                            label = stringResource(Res.string.compose_nav_search),
                        ),
                        FloatingNavigationItem(
                            selected = selectedTab == AppScreenTab.Library,
                            onClick = { onTabSelected(AppScreenTab.Library) },
                            drawable = Res.drawable.sidebar_library,
                            label = stringResource(Res.string.compose_nav_library),
                        ),
                        FloatingNavigationItem(
                            selected = selectedTab == AppScreenTab.Settings,
                            onClick = { onTabSelected(AppScreenTab.Settings) },
                            icon = Icons.Rounded.Settings,
                            label = stringResource(Res.string.compose_nav_settings),
                        ),
                    )
                    DesktopNavigationBar(
                        items = desktopTopBarItems,
                        modifier = Modifier.align(Alignment.TopCenter),
                        scrollState = navBarScrollState,
                        hazeState = navBarHazeState,
                        navBarStyle = navBarStyleSetting,
                        isHeroEnabled = homeCatalogSettingsUiState.heroEnabled,
                        profileSwitcherOpen = isTopProfileSwitcherOpen,
                        windowWidth = screenWidth,
                        glowEnabled = navBarGlowEnabled,
                        onBrandClick = { onTabSelected(AppScreenTab.Home) },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (isFullscreenActionSupported) {
                                    FullscreenActionButton(
                                        buttonSize = 36.dp,
                                        iconSize = 18.dp,
                                        containerColor = Color.White.copy(alpha = 0.08f),
                                        contentColor = Color.White,
                                    )
                                }
                                BestCineTopBarProfileButton(
                                    selected = isTopProfileSwitcherOpen,
                                    onProfileSelected = onProfileSelected,
                                    onAddProfileRequested = onAddProfileRequested,
                                    hazeState = navBarHazeState,
                                    onPopupStateChanged = { isTopProfileSwitcherOpen = it },
                                    compact = screenWidth < 960.dp,
                                )
                            }
                        },
                    )
                }

                if (!isDesktop && !isTabletLayout && !useNativeBottomTabs && !isBestCineTvFullscreen && navBarStyleSetting != NavBarStyle.CLASSIC) {
                    when (navBarStyleSetting) {
                        NavBarStyle.EXPANDED -> navBarScrollState.expand()
                        NavBarStyle.COMPACT -> navBarScrollState.collapse()
                        else -> {}
                    }
                    FloatingNavigationBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        scrollState = navBarScrollState,
                        hazeState = navBarHazeState,
                        items = floatingNavigationItems,
                        glowEnabled = navBarGlowEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun BestCineTopBarProfileButton(
    selected: Boolean,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState?,
    onPopupStateChanged: (Boolean) -> Unit,
    compact: Boolean,
) {
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val bestCineState by BestCineAccountRepository.state.collectAsStateWithLifecycle()
    val activeProfile = profileState.activeProfile
    val profileName = activeProfile?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.compose_nav_profile)
    val bestCineUser = bestCineState.session?.user
    val isVip = bestCineUser?.isApoiadorOrVip == true
    val planBadgeText = bestCineUser?.planShortBadge ?: "Gratuito"
    val goldColor = Color(0xFFFFCA28)
    val chipShape = RoundedCornerShape(999.dp)

    ProfileSwitcherTab(
        selected = selected,
        onClick = {},
        onProfileSelected = onProfileSelected,
        onAddProfileRequested = onAddProfileRequested,
        openPopupOnClick = true,
        hazeState = hazeState,
        onPopupStateChanged = onPopupStateChanged,
        popupBelowAnchor = true,
        triggerContent = { isOpen ->
            Row(
                modifier = Modifier
                    .clip(chipShape)
                    .background(
                        when {
                            isOpen -> BestCineBranding.PrimaryBlue.copy(alpha = 0.24f)
                            isVip -> goldColor.copy(alpha = 0.10f)
                            else -> Color.White.copy(alpha = 0.06f)
                        },
                    )
                    .border(
                        width = 1.dp,
                        color = when {
                            isOpen -> BestCineBranding.PrimaryBlueBright.copy(alpha = 0.65f)
                            isVip -> goldColor.copy(alpha = 0.36f)
                            else -> Color.White.copy(alpha = 0.12f)
                        },
                        shape = chipShape,
                    )
                    .padding(
                        start = 4.dp,
                        end = if (compact) 4.dp else 12.dp,
                        top = 4.dp,
                        bottom = 4.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = if (isVip) {
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(goldColor.copy(alpha = 0.22f))
                            .padding(2.dp)
                    } else {
                        Modifier
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    ActiveProfileMiniAvatar(
                        profile = activeProfile,
                        avatars = avatars,
                        selected = isVip || isOpen,
                        size = 28,
                    )
                }
                if (!compact) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = profileName,
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 110.dp),
                        )
                        val displayPlanBadge = if (isVip && !planBadgeText.contains("👑") && !planBadgeText.contains("⭐")) {
                            "👑 $planBadgeText"
                        } else {
                            planBadgeText
                        }
                        Text(
                            text = displayPlanBadge,
                            color = if (isVip) goldColor else Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = if (isVip) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
    )
}
