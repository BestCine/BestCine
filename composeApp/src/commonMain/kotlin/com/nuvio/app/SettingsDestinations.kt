package com.nuvio.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.collection.CollectionEditorPage
import com.nuvio.app.features.collection.CollectionEditorScreen
import com.nuvio.app.features.collection.CollectionManagementScreen
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.FolderDetailRepository
import com.nuvio.app.features.collection.FolderDetailScreen
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.settings.SettingsScreen
import com.nuvio.app.navigation.AccountSettingsRoute
import com.nuvio.app.navigation.AddonsSettingsRoute
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.CollectionEditorPageRoute
import com.nuvio.app.navigation.CollectionEditorRoute
import com.nuvio.app.navigation.CollectionsRoute
import com.nuvio.app.navigation.ContinueWatchingSettingsRoute
import com.nuvio.app.navigation.FolderDetailRoute
import com.nuvio.app.navigation.HomescreenSettingsRoute
import com.nuvio.app.navigation.LicensesAttributionsSettingsRoute
import com.nuvio.app.navigation.MetaScreenSettingsRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PartyRoute
import com.nuvio.app.navigation.PluginsSettingsRoute
import com.nuvio.app.navigation.SettingsPageRoute
import com.nuvio.app.navigation.SupportersContributorsSettingsRoute
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_account
import nuvio.composeapp.generated.resources.compose_settings_page_addons
import nuvio.composeapp.generated.resources.compose_settings_page_continue_watching
import nuvio.composeapp.generated.resources.compose_settings_page_homescreen
import nuvio.composeapp.generated.resources.compose_settings_page_licenses_attributions
import nuvio.composeapp.generated.resources.compose_settings_page_meta_screen
import nuvio.composeapp.generated.resources.compose_settings_page_plugins
import nuvio.composeapp.generated.resources.compose_settings_page_supporters_contributors
import nuvio.composeapp.generated.resources.party_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsDestination(
    route: AppRoute,
    navController: NuvioNavigator,
    content: @Composable (onBack: () -> Unit) -> Unit,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    content(onBack)
}

@Composable
internal fun SettingsRootDestination(
    route: SettingsPageRoute,
    navController: NuvioNavigator,
    useNativeNavigation: Boolean,
    collectionsTitle: String,
    onCheckForUpdates: (() -> Unit)?,
    onTestUpdateBanner: (() -> Unit)?,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    val watchPartyTitle = stringResource(Res.string.party_title)
    val homescreenSettingsTitle = stringResource(Res.string.compose_settings_page_homescreen)
    val metaScreenSettingsTitle = stringResource(Res.string.compose_settings_page_meta_screen)
    val continueWatchingSettingsTitle = stringResource(Res.string.compose_settings_page_continue_watching)
    val addonsSettingsTitle = stringResource(Res.string.compose_settings_page_addons)
    val pluginsSettingsTitle = stringResource(Res.string.compose_settings_page_plugins)
    val accountSettingsTitle = stringResource(Res.string.compose_settings_page_account)
    val supportersSettingsTitle = stringResource(Res.string.compose_settings_page_supporters_contributors)
    val licensesSettingsTitle = stringResource(Res.string.compose_settings_page_licenses_attributions)
    SettingsScreen(
        modifier = Modifier.fillMaxSize(),
        initialPageName = route.pageName,
        requestedPageName = null,
        onRequestedPageConsumed = {},
        rootActionsEnabled = false,
        onNavigatePage = if (useNativeNavigation) {
            { pageName, title ->
                navController.navigate(SettingsPageRoute(pageName, title))
            }
        } else {
            null
        },
        onExternalBack = onBack,
        onHomescreenClick = { navController.navigate(HomescreenSettingsRoute(homescreenSettingsTitle)) },
        onMetaScreenClick = { navController.navigate(MetaScreenSettingsRoute(metaScreenSettingsTitle)) },
        onContinueWatchingClick = { navController.navigate(ContinueWatchingSettingsRoute(continueWatchingSettingsTitle)) },
        onAddonsClick = { navController.navigate(AddonsSettingsRoute(addonsSettingsTitle)) },
        onPluginsClick = {
            if (AppFeaturePolicy.pluginsEnabled) {
                navController.navigate(PluginsSettingsRoute(pluginsSettingsTitle))
            }
        },
        onAccountClick = { navController.navigate(AccountSettingsRoute(accountSettingsTitle)) },
        onSupportersContributorsClick = {
            if (AppFeaturePolicy.supportersContributorsPageEnabled) {
                navController.navigate(SupportersContributorsSettingsRoute(supportersSettingsTitle))
            }
        },
        onLicensesAttributionsClick = { navController.navigate(LicensesAttributionsSettingsRoute(licensesSettingsTitle)) },
        onCollectionsClick = {
            navController.navigate(CollectionsRoute(collectionsTitle))
        },
        onWatchPartyClick = {
            navController.navigate(PartyRoute(title = watchPartyTitle))
        },
        onCheckForUpdatesClick = onCheckForUpdates,
        onTestUpdateBannerClick = onTestUpdateBanner,
    )
}

@Composable
internal fun CollectionsDestination(
    route: CollectionsRoute,
    navController: NuvioNavigator,
    newCollectionTitle: String,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    CollectionManagementScreen(
        onBack = onBack,
        onNavigateToEditor = { collectionId ->
            val editorTitle = collectionId
                ?.let { id ->
                    CollectionRepository.collections.value.firstOrNull { it.id == id }?.title
                }
                .orEmpty()
            navController.navigate(
                CollectionEditorRoute(
                    collectionId = collectionId,
                    title = editorTitle.ifBlank { newCollectionTitle },
                ),
            )
        },
    )
}

@Composable
internal fun CollectionEditorDestination(
    route: CollectionEditorRoute,
    navController: NuvioNavigator,
    useNativeNavigation: Boolean,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    CollectionEditorScreen(
        collectionId = route.collectionId,
        onBack = onBack,
        initialPage = if (useNativeNavigation) CollectionEditorPage.Root else null,
        onNavigateToPage = if (useNativeNavigation) {
            { page, title ->
                navController.navigate(
                    CollectionEditorPageRoute(
                        collectionId = route.collectionId,
                        pageName = page.name,
                        title = title,
                    ),
                )
            }
        } else {
            null
        },
    )
}

@Composable
internal fun CollectionEditorPageDestination(
    route: CollectionEditorPageRoute,
    navController: NuvioNavigator,
) {
    val page = remember(route.pageName) {
        runCatching { CollectionEditorPage.valueOf(route.pageName) }.getOrNull()
    }
    val onBack = rememberGuardedPopBackStack(navController, route)
    if (page == null || page == CollectionEditorPage.Root) {
        LaunchedEffect(route) { onBack() }
        return
    }
    CollectionEditorScreen(
        collectionId = route.collectionId,
        initialPage = page,
        initializeRepository = false,
        onBack = onBack,
        onNavigateToPage = { nextPage, title ->
            navController.navigate(
                CollectionEditorPageRoute(
                    collectionId = route.collectionId,
                    pageName = nextPage.name,
                    title = title,
                ),
            )
        },
    )
}

@Composable
internal fun FolderDestination(
    route: FolderDetailRoute,
    navController: NuvioNavigator,
    onCatalogClick: (HomeCatalogSection) -> Unit,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    LaunchedEffect(route.collectionId, route.folderId) {
        FolderDetailRepository.initialize(route.collectionId, route.folderId)
    }
    FolderDetailScreen(
        onBack = onBack,
        onCatalogClick = onCatalogClick,
        onPosterClick = rememberOpenMeta(navController),
    )
}
