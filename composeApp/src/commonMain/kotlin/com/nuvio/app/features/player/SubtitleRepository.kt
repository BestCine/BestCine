package com.nuvio.app.features.player

import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_no_subtitles_found
import org.jetbrains.compose.resources.getString

object SubtitleRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitles: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingProgress = MutableStateFlow<SubtitleLoadingProgress?>(null)
    internal val loadingProgress: StateFlow<SubtitleLoadingProgress?> = _loadingProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var activeFetchJob: Job? = null

    fun fetchAddonSubtitles(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras? = null,
    ) {
        activeFetchJob?.cancel()
        _loadingProgress.value = null
        activeFetchJob = scope.launch {
            executeSubtitleFetch(type, videoId, extras)
        }
    }

    /**
     * Like [fetchAddonSubtitles], but first computes the OpenSubtitles video hash
     * when the stream extras do not already contain one.
     */
    fun fetchAddonSubtitlesWithHashComputation(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras?,
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        onExtrasUpdated: (SubtitleRequestExtras) -> Unit,
    ) {
        activeFetchJob?.cancel()
        _loadingProgress.value = null
        activeFetchJob = scope.launch {
            _isLoading.value = true
            _error.value = null
            _addonSubtitles.value = emptyList()

            val enrichedExtras = try {
                val hashResult = computeVideoHash(sourceUrl, sourceHeaders)
                if (hashResult != null) {
                    val (hash, fileSize) = hashResult
                    val updated = (extras ?: SubtitleRequestExtras()).copy(
                        videoHash = hash,
                        videoSize = extras?.videoSize ?: fileSize,
                    )
                    onExtrasUpdated(updated)
                    updated
                } else extras
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                extras
            }

            executeSubtitleLoad(type, videoId, enrichedExtras)
        }
    }

    fun clear() {
        activeFetchJob?.cancel()
        _loadingProgress.value = null
        _addonSubtitles.value = emptyList()
        _isLoading.value = false
        _error.value = null
    }

    private suspend fun executeSubtitleFetch(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras?,
    ) {
        _isLoading.value = true
        _error.value = null
        _addonSubtitles.value = emptyList()
        executeSubtitleLoad(type, videoId, extras)
    }

    private suspend fun resolveSubtitleVideoId(type: String, videoId: String): String {
        if (!videoId.startsWith("tmdb:", ignoreCase = true)) return videoId
        val parts = videoId.split(":")
        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: return videoId
        val imdbId = runCatching {
            TmdbService.tmdbToImdb(tmdbId = tmdbId, mediaType = canonicalSubtitleType(type))
        }.getOrNull()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return videoId
        return if (parts.size >= 4) {
            "$imdbId:${parts[2]}:${parts[3]}"
        } else {
            imdbId
        }
    }

    private suspend fun executeSubtitleLoad(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras?,
    ) {
        val resolvedVideoId = resolveSubtitleVideoId(type, videoId)
        val requests = addonSubtitleRequests(type, resolvedVideoId, extras)
        if (requests.isEmpty()) {
            _isLoading.value = false
            return
        }

        _loadingProgress.value = SubtitleLoadingProgress(total = requests.size)
        loadAddonSubtitles(requests) { request, subtitles ->
            _addonSubtitles.update { it + subtitles }
            _loadingProgress.update { progress ->
                progress?.copy(completed = progress.completed + 1, addonName = request.addonName)
            }
        }

        if (_addonSubtitles.value.isEmpty()) {
            _error.value = getString(Res.string.compose_player_no_subtitles_found)
        }
        _isLoading.value = false
    }
}

internal data class SubtitleLoadingProgress(
    val total: Int,
    val completed: Int = 0,
    val addonName: String? = null,
)
