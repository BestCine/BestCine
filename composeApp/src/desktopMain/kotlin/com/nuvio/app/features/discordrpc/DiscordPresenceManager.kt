package com.nuvio.app.features.discordrpc

import co.touchlab.kermit.Logger
import com.nuvio.app.AppScreenTab
import com.nuvio.app.bestcine.BestCineBranding
import com.nuvio.app.bestcine.tv.BestCineTvRepository
import com.nuvio.app.core.ui.AppPresenceState
import com.nuvio.app.core.ui.PresenceSnapshot
import com.nuvio.app.features.settings.DiscordRichPresenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private class DiscordDisconnected : Exception()

private const val ReconnectDelayMs = 15_000L

// Discord activity type: 0 = Playing, 2 = Listening, 3 = Watching, 5 = Competing.
// Nuvio is a media app, so every presence it publishes is a Watching one -- including the menus,
// where Discord renders "Watching Nuvio" instead of the default "Playing Nuvio" (a game).
private const val WatchingActivityType = 3

internal object DiscordPresenceManager {
    private val log = Logger.withTag("DiscordPresenceManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = DiscordIpcClient(DiscordConfig.CLIENT_ID)
    private var syncJob: Job? = null
    private var lastActivity: DiscordActivity? = null

    fun start() {
        if (DiscordConfig.CLIENT_ID.isBlank()) return
        DiscordRichPresenceRepository.ensureLoaded()
        scope.launch {
            DiscordRichPresenceRepository.enabled.collectLatest { enabled ->
                if (enabled) startSync() else stopSync()
            }
        }
    }

    fun shutdown() {
        runBlocking { stopSync() }
    }

    private suspend fun startSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                val connected = client.connect()
                if (connected) {
                    lastActivity = null
                    try {
                        AppPresenceState.current.collect { snapshot ->
                            val activity = snapshot?.toDiscordActivity() ?: IdleActivity
                            if (activity == lastActivity) return@collect
                            if (client.setActivity(activity)) {
                                lastActivity = activity
                            } else {
                                throw DiscordDisconnected()
                            }
                        }
                    } catch (e: DiscordDisconnected) {
                        log.d { "Discord IPC disconnected, retrying" }
                    }
                }
                delay(ReconnectDelayMs)
            }
        }
    }

    private suspend fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        if (lastActivity != null) client.setActivity(null)
        delay(300L)
        lastActivity = null
        client.disconnect()
    }
}

private const val BestCineLogoAssetKey = "bestcine_logo"

private val DefaultBestCineAssets = DiscordActivityAssets(
    largeImage = BestCineLogoAssetKey,
    largeText = BestCineBranding.APP_NAME,
)

// Shown when nothing has been published yet, so the profile reads "Assistindo BestCine".
private val IdleActivity = DiscordActivity(
    type = WatchingActivityType,
    name = BestCineBranding.APP_NAME,
    details = "Navegando no ${BestCineBranding.APP_NAME}",
    assets = DefaultBestCineAssets,
)

private fun String.toDiscordEpisodeLabel(): String {
    val match = Regex("""S(\d+)E(\d+)(?:\s*-\s*(.*))?""").matchEntire(trim())
        ?: return this
    val season = match.groupValues[1]
    val episode = match.groupValues[2]
    val title = match.groupValues.getOrNull(3).orEmpty().trim()
    return if (title.isBlank()) "T$season, E$episode" else "T$season, E$episode: $title"
}

private fun AppScreenTab.toPortuguesePresenceDetails(): Pair<String, String?> = when (this) {
    AppScreenTab.Home -> "Navegando no Início" to null
    AppScreenTab.Tv -> {
        val activeChannel = BestCineTvRepository.uiState.value.activeChannel
        if (activeChannel != null) {
            "📺 ${activeChannel.name}" to activeChannel.group.takeIf { it.isNotBlank() }
        } else {
            "Assistindo TV ao Vivo" to null
        }
    }
    AppScreenTab.Search -> "Buscando Filmes e Séries" to null
    AppScreenTab.Library -> "Na Biblioteca" to null
    AppScreenTab.Settings -> "Nas Configurações" to null
}

private fun PresenceSnapshot.toDiscordActivity(): DiscordActivity = when (this) {
    is PresenceSnapshot.Tab -> {
        val (detailsText, stateText) = tab.toPortuguesePresenceDetails()
        DiscordActivity(
            type = WatchingActivityType,
            name = BestCineBranding.APP_NAME,
            details = detailsText,
            state = stateText,
            assets = DefaultBestCineAssets,
        )
    }
    is PresenceSnapshot.Details -> DiscordActivity(
        type = WatchingActivityType,
        name = BestCineBranding.APP_NAME,
        details = "Vendo $title",
        assets = DefaultBestCineAssets,
    )
    is PresenceSnapshot.Player -> {
        val episode = episodeLabel?.toDiscordEpisodeLabel()
        // Discord expects Unix timestamps in seconds, not milliseconds.
        val startSecs = (System.currentTimeMillis() - positionMs) / 1_000L
        DiscordActivity(
            type = WatchingActivityType,
            name = BestCineBranding.APP_NAME,
            details = title,
            state = if (isPlaying) episode else episode?.let { "$it • Pausado" } ?: "Pausado",
            timestamps = if (isPlaying) {
                // start + end -> Discord renders a live progress bar with time remaining.
                DiscordActivityTimestamps(
                    start = startSecs,
                    end = if (durationMs > 0L) startSecs + durationMs / 1_000L else null,
                )
            } else {
                null
            },
            assets = DiscordActivityAssets(
                largeImage = posterUrl?.takeIf { it.isNotBlank() } ?: BestCineLogoAssetKey,
                largeText = title,
            ),
        )
    }
}
