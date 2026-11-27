package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.bestcine.BestCineAccountRepository
import com.nuvio.app.bestcine.BestCineBranding
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Serializable
internal data class AddonRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
internal data class AddonPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

private const val ADDON_PUSH_DEBOUNCE_MS = 500L
private const val OPENSUBTITLES_V3_HOST = "opensubtitles-v3.strem.io"

internal fun canonicalAddonKey(url: String): String {
    val trimmed = url.trim()
    val withoutFragment = trimmed.substringBefore("#")
    val query = withoutFragment.substringAfter("?", "")
    val path = withoutFragment.substringBefore("?").trimEnd('/')
        .removeSuffix("/manifest.json")
    val normalizedPath = path.lowercase()
    return if (query.isEmpty()) normalizedPath else "$normalizedPath?$query"
}

/**
 * Mesclagem segura para envio de addons à Conta Nuvio:
 * - Nunca inclui o manifesto/token do BestCine.
 * - Preserva integralmente addons remotos não gerenciados pelo BestCine (ex.: addons de stream do Nuvio oficial).
 * - Aplica adições, toggles e remoções apenas de addons de catálogo gerenciados no BestCine.
 */
internal fun mergeRemoteAndLocalForSafePush(
    remoteRows: List<AddonRow>,
    localExternalCatalogAddons: List<ManagedAddon>,
    removedCanonicalKeys: Set<String>,
    isBestCineUrl: (String) -> Boolean,
): List<AddonPushItem> {
    val localByKey = linkedMapOf<String, ManagedAddon>()
    for (addon in localExternalCatalogAddons) {
        if (isBestCineUrl(addon.manifestUrl)) continue
        if (addon.manifest != null && !addon.manifest.isCatalogOnlyAllowed) continue
        localByKey.putIfAbsent(canonicalAddonKey(addon.manifestUrl), addon)
    }

    val matchedLocalKeys = mutableSetOf<String>()
    val result = mutableListOf<AddonPushItem>()

    for (row in remoteRows) {
        val trimmedUrl = row.url.trim()
        if (trimmedUrl.isEmpty() || isBestCineUrl(trimmedUrl)) continue
        val key = canonicalAddonKey(trimmedUrl)
        if (key in removedCanonicalKeys) continue

        val localMatch = localByKey[key]
        if (localMatch != null) {
            matchedLocalKeys += key
            result += AddonPushItem(
                url = trimmedUrl,
                name = localMatch.userSetName?.takeIf(String::isNotBlank)
                    ?: localMatch.manifest?.name?.takeIf(String::isNotBlank)
                    ?: row.name.orEmpty(),
                enabled = localMatch.enabled,
                sortOrder = result.size,
            )
        } else {
            // Preserva intacto qualquer outro addon remoto (ex.: streams no Nuvio oficial).
            result += AddonPushItem(
                url = trimmedUrl,
                name = row.name.orEmpty(),
                enabled = row.enabled,
                sortOrder = result.size,
            )
        }
    }

    for ((key, addon) in localByKey) {
        if (key in matchedLocalKeys) continue
        if (isBestCineUrl(addon.manifestUrl)) continue
        result += AddonPushItem(
            url = addon.manifestUrl,
            name = addon.userSetName?.takeIf(String::isNotBlank)
                ?: addon.manifest?.name.orEmpty(),
            enabled = addon.enabled,
            sortOrder = result.size,
        )
    }

    return result.mapIndexed { index, item -> item.copy(sortOrder = index) }
}

object AddonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(AddonsUiState())
    val uiState: StateFlow<AddonsUiState> = _uiState.asStateFlow()

    private val syncMutex = Mutex()
    private var initialized = false
    private var currentProfileId: Int = 1
    private var lastPulledUserId: String? = null
    private val pendingRemovedKeys = mutableSetOf<String>()
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private val pushJobsByProfile = mutableMapOf<Int, Job>()

    fun isBestCineOfficialAddonUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()
        val sessionManifest = BestCineAccountRepository.state.value.session?.manifestUrl?.trim().orEmpty()
        if (sessionManifest.isNotEmpty() && canonicalAddonKey(trimmed) == canonicalAddonKey(sessionManifest)) {
            return true
        }
        val prodHost = BestCineBranding.PRODUCTION_BASE_URL
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
            .lowercase()
        val urlHost = lower.substringAfter("://", lower).substringBefore("/").substringBefore("?")
        if (prodHost.isNotEmpty() && urlHost == prodHost) return true
        if (lower.contains("/bc_") && (urlHost.contains("bestcine") || lower.contains("manifest.json"))) {
            return true
        }
        return false
    }

    private fun isReservedSystemAddonUrl(url: String): Boolean {
        if (isBestCineOfficialAddonUrl(url)) return true
        val lower = url.trim().lowercase()
        val host = lower.substringAfter("://", lower).substringBefore("/").substringBefore("?")
        return host == OPENSUBTITLES_V3_HOST
    }

    fun initialize() {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        if (initialized) return
        initialized = true
        currentProfileId = effectiveProfileId
        log.d { "initialize() — loading local addons for profile $currentProfileId" }

        val storedUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(currentProfileId))
        val enabledByUrl = loadLocalEnabledStates()
        log.d { "initialize() — local addon count: ${storedUrls.size}" }
        if (storedUrls.isEmpty()) return

        val orderedUrls = pinBestCineFirst(storedUrls)
        val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
        _uiState.value = AddonsUiState(
            addons = orderedUrls.map { manifestUrl ->
                val isOfficial = isBestCineOfficialAddonUrl(manifestUrl)
                existingByUrl[manifestUrl].toPendingAddon(
                    manifestUrl = manifestUrl,
                    enabled = if (isOfficial) true else enabledByUrl[manifestUrl],
                )
            },
        )

        orderedUrls.forEach { manifestUrl ->
            val existing = existingByUrl[manifestUrl]
            val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == manifestUrl }
            if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                refreshAddon(manifestUrl)
            }
        }
    }

    fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (effectiveProfileId == currentProfileId && initialized) return
        cancelActiveRefreshes()
        currentProfileId = effectiveProfileId
        initialized = false
        _uiState.value = AddonsUiState()
    }

    fun clearLocalState() {
        cancelActiveRefreshes()
        pushJobsByProfile.values.forEach(Job::cancel)
        pushJobsByProfile.clear()
        pendingRemovedKeys.clear()
        lastPulledUserId = null
        currentProfileId = 1
        initialized = false
        _uiState.value = AddonsUiState()
    }

    suspend fun pullFromServer(profileId: Int) {
        initialize()
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous || !AuthRepository.isNuvioBackendConfigured()) {
            return
        }

        val targetProfileId = resolveEffectiveProfileId(profileId)
        var shouldPushLocalExtras = false

        syncMutex.withLock {
            val remoteRows = runCatching {
                fetchRemoteAddonRows(
                    userId = authState.userId,
                    profileId = targetProfileId,
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                log.w(error) { "pullFromServer() — falha ao ler addons da Conta Nuvio; mantendo lista local" }
                return
            }

            val currentAddons = _uiState.value.addons
            val officialAddon = currentAddons.firstOrNull { isBestCineOfficialAddonUrl(it.manifestUrl) }
            val localExternalByKey = currentAddons
                .filterNot { isBestCineOfficialAddonUrl(it.manifestUrl) }
                .associateBy { canonicalAddonKey(it.manifestUrl) }

            val remoteCatalogAddons = mutableListOf<ManagedAddon>()
            val seenRemoteKeys = mutableSetOf<String>()

            for (row in remoteRows) {
                val rawUrl = row.url.trim()
                if (rawUrl.isEmpty() || isReservedSystemAddonUrl(rawUrl)) continue
                val manifestUrl = runCatching { normalizeManifestUrl(rawUrl) }.getOrNull() ?: continue
                val key = canonicalAddonKey(manifestUrl)
                if (!seenRemoteKeys.add(key)) continue
                if (key in pendingRemovedKeys) continue

                val existingLocal = localExternalByKey[key]
                val manifest = existingLocal?.manifest ?: runCatching {
                    val payload = fetchAddonResponseText(url = manifestUrl, forceRefresh = false)
                    AddonManifestParser.parse(manifestUrl = manifestUrl, payload = payload)
                }.getOrNull()

                if (manifest == null) {
                    // Falha de rede ao inspecionar manifesto remoto: mantém apenas se já existia localmente.
                    if (existingLocal != null) {
                        remoteCatalogAddons += existingLocal.copy(
                            userSetName = row.name?.takeIf(String::isNotBlank) ?: existingLocal.userSetName,
                            enabled = row.enabled,
                        )
                    }
                    continue
                }

                // Camada 1: ignora silenciosamente qualquer addon com stream (sem apagá-lo da Conta Nuvio!).
                if (!manifest.isCatalogOnlyAllowed) {
                    log.d { "pullFromServer() — ignorando addon não-catálogo da Conta Nuvio: ${manifest.name}" }
                    continue
                }

                remoteCatalogAddons += ManagedAddon(
                    manifestUrl = manifestUrl,
                    manifest = manifest,
                    userSetName = row.name?.takeIf(String::isNotBlank),
                    enabled = row.enabled,
                    isRefreshing = false,
                    errorMessage = null,
                )
            }

            val isFirstPullForAccount = lastPulledUserId != authState.userId
            val mergedExternal = mutableListOf<ManagedAddon>()
            val mergedKeys = mutableSetOf<String>()

            for (addon in remoteCatalogAddons) {
                val key = canonicalAddonKey(addon.manifestUrl)
                if (mergedKeys.add(key)) {
                    mergedExternal += addon
                }
            }

            if (isFirstPullForAccount) {
                // Primeira sincronização com a Conta Nuvio: preserva addons de catálogo já instalados localmente
                // e envia a mesclagem segura para a nuvem.
                for ((key, localAddon) in localExternalByKey) {
                    if (key !in mergedKeys && (localAddon.manifest == null || localAddon.manifest.isCatalogOnlyAllowed)) {
                        mergedExternal += localAddon
                        mergedKeys += key
                        shouldPushLocalExtras = true
                    }
                }
            }

            val finalAddons = buildList {
                if (officialAddon != null) add(officialAddon.copy(enabled = true))
                addAll(mergedExternal)
            }

            lastPulledUserId = authState.userId
            if (finalAddons != _uiState.value.addons) {
                _uiState.value = AddonsUiState(addons = finalAddons)
                persist()
            }
            log.i {
                "pullFromServer() — concluído (profile=$targetProfileId, " +
                    "catálogos externos=${mergedExternal.size}, total=${finalAddons.size})"
            }
        }

        if (shouldPushLocalExtras) {
            pushToServer()
        }
    }

    suspend fun awaitManifestsLoaded() {
        if (_uiState.value.addons.isEmpty()) return
        uiState.first { state ->
            state.addons.isEmpty() ||
                state.addons.any { it.manifest != null } ||
                state.addons.none { it.isRefreshing }
        }
    }

    private val _pendingTrackingConsent = MutableStateFlow<AddonManifest?>(null)
    val pendingTrackingConsent: StateFlow<AddonManifest?> = _pendingTrackingConsent.asStateFlow()

    fun requestTrackingConsent(manifest: AddonManifest) {
        _pendingTrackingConsent.value = manifest
    }

    fun dismissTrackingConsent() {
        _pendingTrackingConsent.value = null
    }

    suspend fun installWithTrackingConsent(manifest: AddonManifest): AddAddonResult {
        _pendingTrackingConsent.value = null
        val validationError = validateExternalCatalogManifest(manifest)
        if (validationError != null) {
            return AddAddonResult.Error(validationError)
        }
        val manifestUrl = runCatching { normalizeManifestUrl(manifest.transportUrl) }
            .getOrElse { return AddAddonResult.Error(it.message ?: "URL de addon inválida.") }
        if (isBestCineOfficialAddonUrl(manifestUrl)) {
            return AddAddonResult.Error("Este addon já faz parte do BestCine.")
        }
        val key = canonicalAddonKey(manifestUrl)
        if (_uiState.value.addons.any { canonicalAddonKey(it.manifestUrl) == key }) {
            return AddAddonResult.Error("Este addon de catálogo já está instalado.")
        }
        return installManifest(manifestUrl, manifest)
    }

    suspend fun addAddon(rawUrl: String): AddAddonResult {
        initialize()
        val manifestUrl = runCatching { normalizeManifestUrl(rawUrl) }
            .getOrElse { error ->
                return AddAddonResult.Error(error.message ?: "Informe uma URL válida de addon.")
            }

        if (isBestCineOfficialAddonUrl(manifestUrl)) {
            return AddAddonResult.Error("O addon oficial do BestCine já está instalado e ativo.")
        }

        val canonicalKey = canonicalAddonKey(manifestUrl)
        if (_uiState.value.addons.any { canonicalAddonKey(it.manifestUrl) == canonicalKey }) {
            return AddAddonResult.Error("Este addon de catálogo já está instalado.")
        }

        val manifest = runCatching {
            val payload = fetchAddonResponseText(
                url = manifestUrl,
                forceRefresh = true,
            )
            AddonManifestParser.parse(
                manifestUrl = manifestUrl,
                payload = payload,
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            log.w(error) { "addAddon() — falha ao ler manifesto de $manifestUrl" }
            return AddAddonResult.Error(
                error.message ?: "Não foi possível carregar o manifest.json deste addon.",
            )
        }

        val validationError = validateExternalCatalogManifest(manifest)
        if (validationError != null) {
            log.w { "addAddon() bloqueado pela política de somente catálogo: ${manifest.name} ($manifestUrl)" }
            return AddAddonResult.Error(validationError)
        }

        if (manifest.isTrackingAddon) {
            return AddAddonResult.TrackingConsentRequired(manifest)
        }

        return installManifest(manifestUrl, manifest)
    }

    fun upsertBestCineAddon(
        manifestUrl: String,
        manifest: AddonManifest,
        previousManifestUrls: Set<String> = emptySet(),
    ) {
        initialize()
        val normalizedTarget = runCatching { normalizeManifestUrl(manifestUrl) }.getOrDefault(manifestUrl)
        val previousKeys = previousManifestUrls
            .mapNotNull { url -> runCatching { canonicalAddonKey(normalizeManifestUrl(url)) }.getOrNull() }
            .toSet()
        val targetKey = canonicalAddonKey(normalizedTarget)

        val bestCineAddon = ManagedAddon(
            manifestUrl = normalizedTarget,
            manifest = manifest,
            enabled = true,
            isRefreshing = false,
            errorMessage = null,
        )
        // Mantém o addon oficial do BestCine fixo na posição 0 e preserva addons externos de catálogo.
        _uiState.update { current ->
            val externalCatalogAddons = current.addons.filter { existing ->
                val existingKey = canonicalAddonKey(existing.manifestUrl)
                existingKey != targetKey &&
                    existingKey !in previousKeys &&
                    !isBestCineOfficialAddonUrl(existing.manifestUrl)
            }
            current.copy(addons = listOf(bestCineAddon) + externalCatalogAddons)
        }
        persist()
    }

    fun clearAddonsForBestCineLogout() {
        cancelActiveRefreshes()
        pushJobsByProfile.values.forEach(Job::cancel)
        pushJobsByProfile.clear()
        pendingRemovedKeys.clear()
        lastPulledUserId = null
        _uiState.value = AddonsUiState()
        persist()
    }

    private fun validateExternalCatalogManifest(manifest: AddonManifest): String? {
        if (manifest.declaresStreamResource) {
            return "Addon bloqueado: addons com suporte a streams externos não são permitidos no BestCine. Você pode instalar addons de catálogo, legendas e metadados."
        }
        if (!manifest.isCatalogOnlyAllowed) {
            return "Este addon não é permitido pela política do BestCine (streams externos bloqueados)."
        }
        return null
    }

    private fun installManifest(manifestUrl: String, manifest: AddonManifest): AddAddonResult {
        pendingRemovedKeys.remove(canonicalAddonKey(manifestUrl))
        _uiState.update { current ->
            current.copy(
                addons = current.addons + ManagedAddon(
                    manifestUrl = manifestUrl,
                    manifest = manifest,
                    enabled = true,
                    isRefreshing = false,
                    errorMessage = null,
                ),
            )
        }
        persist()
        pushToServer()
        return AddAddonResult.Success(manifest)
    }

    fun removeAddon(manifestUrl: String) {
        if (isBestCineOfficialAddonUrl(manifestUrl)) return
        val targetKey = canonicalAddonKey(manifestUrl)
        activeRefreshJobs.remove(manifestUrl)?.cancel()
        var removed = false
        _uiState.update { current ->
            val next = current.addons.filterNot {
                val match = canonicalAddonKey(it.manifestUrl) == targetKey && !isBestCineOfficialAddonUrl(it.manifestUrl)
                if (match) removed = true
                match
            }
            current.copy(addons = next)
        }
        if (!removed) return
        pendingRemovedKeys += targetKey
        persist()
        pushToServer()
    }

    fun moveAddon(fromIndex: Int, toIndex: Int) {
        val currentAddons = _uiState.value.addons
        val minMovableIndex = if (currentAddons.firstOrNull()?.let { isBestCineOfficialAddonUrl(it.manifestUrl) } == true) {
            1
        } else {
            0
        }
        if (fromIndex < minMovableIndex || toIndex < minMovableIndex) return
        if (fromIndex !in currentAddons.indices || toIndex !in currentAddons.indices || fromIndex == toIndex) return

        val reordered = currentAddons.toMutableList()
        val item = reordered.removeAt(fromIndex)
        reordered.add(toIndex, item)
        _uiState.update { it.copy(addons = reordered) }
        persist()
        pushToServer()
    }

    fun setAddonEnabled(manifestUrl: String, enabled: Boolean) {
        if (isBestCineOfficialAddonUrl(manifestUrl)) return
        val targetKey = canonicalAddonKey(manifestUrl)
        var changed = false
        var needsRefresh = false
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (canonicalAddonKey(addon.manifestUrl) != targetKey) {
                        addon
                    } else if (addon.enabled == enabled) {
                        addon
                    } else {
                        changed = true
                        if (enabled && addon.manifest == null && !addon.isRefreshing) {
                            needsRefresh = true
                        }
                        addon.copy(enabled = enabled)
                    }
                },
            )
        }
        if (!changed) return
        persist()
        pushToServer()
        if (needsRefresh) {
            refreshAddon(manifestUrl)
        }
    }

    fun refreshAll() {
        _uiState.value.addons.filter { it.enabled }.distinctBy { it.manifestUrl }.forEach { addon ->
            refreshAddon(
                manifestUrl = addon.manifestUrl,
                forceRefresh = true,
            )
        }
    }

    fun refreshAddon(
        manifestUrl: String,
        forceRefresh: Boolean = false,
    ) {
        val existingJob = activeRefreshJobs[manifestUrl]
        if (existingJob?.isActive == true) return

        val isOfficial = isBestCineOfficialAddonUrl(manifestUrl)
        markRefreshing(manifestUrl)
        var refreshJob: Job? = null
        refreshJob = scope.launch {
            try {
                val result = runCatching {
                    val payload = fetchAddonResponseText(
                        url = manifestUrl,
                        forceRefresh = forceRefresh,
                    )
                    AddonManifestParser.parse(
                        manifestUrl = manifestUrl,
                        payload = payload,
                    )
                }

                var autoDisabledExternal = false
                _uiState.update { current ->
                    current.copy(
                        addons = current.addons.map { addon ->
                            if (addon.manifestUrl != manifestUrl) {
                                addon
                            } else {
                                result.fold(
                                    onSuccess = { manifest ->
                                        if (!isOfficial && !manifest.isCatalogOnlyAllowed) {
                                            autoDisabledExternal = true
                                            addon.copy(
                                                manifest = null,
                                                enabled = false,
                                                isRefreshing = false,
                                                errorMessage = "Addon desativado automaticamente: addons com suporte a streams externos são bloqueados no BestCine.",
                                            )
                                        } else {
                                            addon.copy(
                                                manifest = manifest,
                                                isRefreshing = false,
                                                errorMessage = null,
                                            )
                                        }
                                    },
                                    onFailure = { error ->
                                        addon.copy(
                                            isRefreshing = false,
                                            errorMessage = error.message ?: getString(Res.string.addon_load_manifest_failed),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
                if (autoDisabledExternal) {
                    persist()
                }
            } finally {
                if (activeRefreshJobs[manifestUrl] === refreshJob) {
                    activeRefreshJobs.remove(manifestUrl)
                }
            }
        }
        activeRefreshJobs[manifestUrl] = refreshJob
    }

    private fun pushToServer() {
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous || !AuthRepository.isNuvioBackendConfigured()) {
            return
        }
        val targetProfileId = resolveEffectiveProfileId(currentProfileId)
        val userId = authState.userId
        pushJobsByProfile.remove(targetProfileId)?.cancel()
        pushJobsByProfile[targetProfileId] = scope.launch {
            delay(ADDON_PUSH_DEBOUNCE_MS)
            syncMutex.withLock {
                val latestAuth = AuthRepository.state.value
                if (latestAuth !is AuthState.Authenticated || latestAuth.isAnonymous || latestAuth.userId != userId) {
                    return@withLock
                }
                // Segurança obrigatória: lê a lista atual da Conta Nuvio ANTES de enviar.
                // Se a leitura falhar, aborta para nunca sobrescrever nem apagar addons do Nuvio oficial.
                val remoteRows = runCatching {
                    fetchRemoteAddonRows(
                        userId = userId,
                        profileId = targetProfileId,
                    )
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    log.w(error) { "pushToServer() abortado: falha ao ler estado atual da Conta Nuvio" }
                    return@withLock
                }

                val removedSnapshot = pendingRemovedKeys.toSet()
                val localExternal = _uiState.value.addons.filterNot { isBestCineOfficialAddonUrl(it.manifestUrl) }
                val mergedItems = mergeRemoteAndLocalForSafePush(
                    remoteRows = remoteRows,
                    localExternalCatalogAddons = localExternal,
                    removedCanonicalKeys = removedSnapshot,
                    isBestCineUrl = ::isBestCineOfficialAddonUrl,
                )

                runCatching {
                    val params = buildJsonObject {
                        put("p_profile_id", targetProfileId)
                        put("p_addons", json.encodeToJsonElement(mergedItems))
                        putSyncOriginClientId()
                    }
                    SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
                }.onSuccess {
                    pendingRemovedKeys.removeAll(removedSnapshot)
                    log.i {
                        "pushToServer() — mesclagem segura enviada à Conta Nuvio " +
                            "(profile=$targetProfileId, total=${mergedItems.size})"
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    log.w(error) { "pushToServer() — falha ao enviar mesclagem para a Conta Nuvio" }
                }
            }
        }
    }

    private suspend fun fetchRemoteAddonRows(
        userId: String,
        profileId: Int,
    ): List<AddonRow> =
        SupabaseProvider.client.postgrest["addons"]
            .select {
                filter {
                    eq("user_id", userId)
                    eq("profile_id", profileId)
                }
                order("sort_order", Order.ASCENDING)
            }
            .decodeList()

    private fun pinBestCineFirst(urls: List<String>): List<String> {
        val official = urls.filter(::isBestCineOfficialAddonUrl)
        val external = urls.filterNot(::isBestCineOfficialAddonUrl)
        return official + external
    }

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl == manifestUrl) {
                        addon.copy(
                            isRefreshing = true,
                            errorMessage = null,
                        )
                    } else {
                        addon
                    }
                },
            )
        }
    }

    private fun persist() {
        val addons = _uiState.value.addons
        AddonStorage.saveInstalledAddonUrls(
            currentProfileId,
            dedupeManifestUrls(addons.map { it.manifestUrl }),
        )
        AddonStorage.saveAddonEnabledStates(
            currentProfileId,
            addons.associate { it.manifestUrl to it.enabled },
        )
    }

    private fun loadLocalEnabledStates(): Map<String, Boolean> =
        AddonStorage.loadAddonEnabledStates(currentProfileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }

    private fun cancelActiveRefreshes() {
        activeRefreshJobs.values.forEach(Job::cancel)
        activeRefreshJobs.clear()
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        // Todos os perfis compartilham o perfil 1 no sync do BestCine + BestCine TV
        return 1
    }

    private fun isUsingPrimaryAddonsFromSecondaryProfile(): Boolean {
        val active = ProfileRepository.state.value.activeProfile
        return active != null && active.profileIndex != 1 && active.usesPrimaryAddons
    }
}

private fun ManagedAddon?.toPendingAddon(
    manifestUrl: String,
    userSetName: String? = null,
    enabled: Boolean? = null,
): ManagedAddon =
    when {
        this == null -> ManagedAddon(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: true,
            userSetName = userSetName,
            enabled = enabled ?: true,
        )
        manifest != null -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = false,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        isRefreshing -> copy(
            manifestUrl = manifestUrl,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        else -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: this.enabled,
            errorMessage = null,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
    }

private fun dedupeManifestUrls(urls: List<String>): List<String> =
    urls.map(::ensureManifestSuffix).distinct()

private fun ensureManifestSuffix(url: String): String {
    val path = url.substringBefore("?").trimEnd('/')
    val query = url.substringAfter("?", "")
    val withSuffix = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
    val manifestUrl = if (query.isEmpty()) withSuffix else "$withSuffix?$query"
    return manifestUrl.encodeUnsafeHttpUrlCharacters()
}

private fun normalizeManifestUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotEmpty()) { runBlocking { getString(Res.string.addons_error_enter_url) } }

    val normalizedScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("stremio://") -> "https://${trimmed.removePrefix("stremio://")}"
        else -> "https://$trimmed"
    }

    val withoutFragment = normalizedScheme.substringBefore("#")
    val query = withoutFragment.substringAfter("?", "")
    val path = withoutFragment.substringBefore("?").trimEnd('/')
    val manifestPath = if (path.endsWith("/manifest.json")) {
        path
    } else {
        "$path/manifest.json"
    }

    val manifestUrl = if (query.isEmpty()) manifestPath else "$manifestPath?$query"
    return manifestUrl.encodeUnsafeHttpUrlCharacters()
}
