package com.nuvio.app.bestcine

import co.touchlab.kermit.Logger
import com.nuvio.app.bestcine.tv.BestCineTvRepository
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonManifestParser
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class BestCineAccountUiState(
    val session: BestCineSession? = null,
    val isLoggingIn: Boolean = false,
    val isSyncingManifest: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val isConnected: Boolean
        get() = session != null && session.user.tokenInstalacao.isNotBlank()
}

object BestCineAccountRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("BestCineAccount")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(BestCineAccountUiState())
    val state: StateFlow<BestCineAccountUiState> = _state.asStateFlow()

    private val _tvQrState = MutableStateFlow(BestCineTvQrUiState())
    val tvQrState: StateFlow<BestCineTvQrUiState> = _tvQrState.asStateFlow()

    private var loaded = false
    private var banWatchdogJob: Job? = null
    private var tvQrPollJob: Job? = null
    private val syncManifestMutex = Mutex()
    private const val BAN_WATCHDOG_INTERVAL_MS = 60_000L

    fun ensureLoaded() {
        if (loaded) {
            if (_state.value.isConnected) ensureBanWatchdogRunning()
            return
        }
        loaded = true
        val raw = BestCinePlatformStorage.loadSessionJson().orEmpty().trim()
        if (raw.isEmpty()) return
        val restored = runCatching { json.decodeFromString<BestCineSession>(raw) }.getOrNull()
        if (restored != null && restored.user.tokenInstalacao.isNotBlank()) {
            if (restored.user.isBanned) {
                BestCinePlatformStorage.clearSession()
                _state.update {
                    it.copy(
                        session = null,
                        errorMessage = formatBanMessage(restored.user.motivoBan),
                    )
                }
                return
            }
            _state.update { it.copy(session = restored) }
            ensureBanWatchdogRunning()
        }
    }

    fun clearMessages() {
        _state.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun cancelLogin() {
        stopTvQrLogin()
        BestCinePlatformAuth.cancelPendingLogin()
        _state.update {
            it.copy(
                isLoggingIn = false,
                statusMessage = null,
                errorMessage = null,
            )
        }
    }

    fun stopTvQrLogin() {
        tvQrPollJob?.cancel()
        tvQrPollJob = null
        _tvQrState.value = BestCineTvQrUiState()
    }

    fun startTvQrLogin(
        baseUrl: String = BestCineBranding.PRODUCTION_BASE_URL,
    ) {
        ensureLoaded()
        tvQrPollJob?.cancel()
        val cleanBase = baseUrl.trim().trimEnd('/')
        tvQrPollJob = scope.launch {
            _tvQrState.value = BestCineTvQrUiState(isLoading = true)
            val codeResponse = runCatching {
                val raw = httpRequestRaw(
                    method = "POST",
                    url = "$cleanBase/api/tv/code",
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                        "Cache-Control" to "no-store",
                    ),
                    body = "{}",
                )
                if (raw.status !in 200..299) {
                    val parsedErr = runCatching {
                        json.decodeFromString<BestCineTvCodeResponse>(raw.body).error
                    }.getOrNull()
                    error(parsedErr ?: "Servidor retornou HTTP ${raw.status}")
                }
                json.decodeFromString<BestCineTvCodeResponse>(raw.body)
            }.getOrElse { err ->
                _tvQrState.value = BestCineTvQrUiState(
                    errorMessage = "Não foi possível gerar o QR Code: ${err.message ?: "verifique sua conexão"}",
                )
                return@launch
            }

            if (!codeResponse.ok || codeResponse.deviceCode.isBlank() || codeResponse.verifyUrl.isBlank()) {
                _tvQrState.value = BestCineTvQrUiState(
                    errorMessage = codeResponse.error ?: "Resposta inválida ao gerar o QR Code.",
                )
                return@launch
            }

            val displayCode = codeResponse.displayCode.ifBlank { codeResponse.userCode }
            val verifyBase = codeResponse.verifyBaseUrl.ifBlank { "$cleanBase/tv" }
                .removePrefix("https://")
                .removePrefix("http://")
            val pollIntervalMs = (codeResponse.interval.coerceIn(2, 10)) * 1_000L
            val expiresAtMs = bestCineCurrentTimeMillis() + (codeResponse.expiresIn.coerceIn(60, 600)) * 1_000L

            _tvQrState.value = BestCineTvQrUiState(
                isLoading = false,
                isPolling = true,
                isExpired = false,
                displayCode = displayCode,
                verifyBaseUrl = verifyBase,
                verifyUrl = codeResponse.verifyUrl,
                errorMessage = null,
            )

            while (isActive) {
                if (bestCineCurrentTimeMillis() >= expiresAtMs) {
                    _tvQrState.update {
                        it.copy(
                            isPolling = false,
                            isExpired = true,
                            errorMessage = "QR Code expirado. Gere um novo código para conectar.",
                        )
                    }
                    return@launch
                }

                delay(pollIntervalMs)

                val pollRaw = runCatching {
                    httpRequestRaw(
                        method = "GET",
                        url = "$cleanBase/api/tv/poll?device_code=${codeResponse.deviceCode}",
                        headers = mapOf(
                            "Accept" to "application/json",
                            "Cache-Control" to "no-store",
                        ),
                        body = "",
                    )
                }.getOrNull() ?: continue

                if (pollRaw.status == 410 || pollRaw.status == 400) {
                    _tvQrState.update {
                        it.copy(
                            isPolling = false,
                            isExpired = true,
                            errorMessage = "QR Code expirado. Clique em gerar novo código.",
                        )
                    }
                    return@launch
                }

                if (pollRaw.status !in 200..299) continue

                val poll = runCatching {
                    json.decodeFromString<BestCineTvPollResponse>(pollRaw.body)
                }.getOrNull() ?: continue

                if (poll.status == "approved" && poll.logado && poll.usuario != null && poll.usuario.tokenInstalacao.isNotBlank()) {
                    _tvQrState.value = BestCineTvQrUiState()
                    completeAuthenticatedUser(
                        user = poll.usuario,
                        baseUrl = cleanBase,
                        previousUrl = _state.value.session?.manifestUrl,
                    )
                    return@launch
                }
            }
        }
    }

    private suspend fun completeAuthenticatedUser(
        user: BestCineUser,
        baseUrl: String,
        previousUrl: String?,
    ): Boolean {
        if (user.isBanned) {
            val banMsg = formatBanMessage(user.motivoBan)
            BestCinePlatformStorage.clearSession()
            _state.update {
                it.copy(
                    session = null,
                    isLoggingIn = false,
                    isSyncingManifest = false,
                    statusMessage = null,
                    errorMessage = banMsg,
                )
            }
            NuvioToastController.show(banMsg)
            return false
        }

        val newSession = BestCineSession(
            baseUrl = baseUrl.trim().trimEnd('/'),
            user = user,
            lastManifestSyncMs = 0L,
        )
        persistSession(newSession)
        _state.update {
            it.copy(
                isLoggingIn = false,
                isSyncingManifest = true,
                statusMessage = "Conta conectada! Carregando catálogos do BestCine...",
                errorMessage = null,
            )
        }

        val manifestUrl = newSession.manifestUrl
        val initialManifest = runCatching {
            withContext(Dispatchers.Default) {
                val payload = fetchAddonResponseText(
                    url = manifestUrl,
                    forceRefresh = true,
                )
                AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                )
            }
        }.onFailure { error ->
            log.w(error) { "Falha ao baixar manifest imediato após login (tentará novamente em segundo plano)" }
        }.getOrNull()

        if (initialManifest != null && isSuspendedManifest(initialManifest.name, initialManifest.description)) {
            val reason = extractBanReason(initialManifest.description.orEmpty())
            forceLogoutForBan(reason)
            return false
        }

        val syncedSession = if (initialManifest != null) {
            AddonRepository.upsertBestCineAddon(
                manifestUrl = manifestUrl,
                manifest = initialManifest,
                previousManifestUrls = setOfNotNull(previousUrl),
            )
            val activeAddons = AddonRepository.uiState.value.addons.enabledAddons()
            HomeCatalogSettingsRepository.syncBestCineCatalogOrder(
                addons = activeAddons,
                bestCineManifestUrl = manifestUrl,
            )
            HomeRepository.refresh(addons = activeAddons, force = true)
            newSession.copy(lastManifestSyncMs = bestCineCurrentTimeMillis()).also {
                persistSession(it)
            }
        } else {
            newSession
        }

        _state.update {
            it.copy(
                session = syncedSession,
                isLoggingIn = false,
                isSyncingManifest = false,
                statusMessage = if (initialManifest != null) {
                    "Catálogos atualizados (${initialManifest.catalogs.size} catálogos na ordem do servidor)."
                } else {
                    "Conta conectada! Carregando catálogos do BestCine..."
                },
                errorMessage = null,
            )
        }

        ensureAppEntered()
        ensureBanWatchdogRunning()
        if (initialManifest == null) {
            scope.launch {
                syncManifest(
                    previousManifestUrls = setOfNotNull(previousUrl),
                    forceRefresh = true,
                    skipStatusRefresh = true,
                )
            }
        }
        return true
    }

    fun startGoogleLogin(
        baseUrl: String = BestCineBranding.PRODUCTION_BASE_URL,
    ) {
        scope.launch {
            loginWithGoogle(baseUrl)
        }
    }

    suspend fun loginWithGoogle(
        baseUrl: String = BestCineBranding.PRODUCTION_BASE_URL,
    ): Boolean = scope.async {
        ensureLoaded()
        if (_state.value.isLoggingIn) return@async false
        val previousUrl = _state.value.session?.manifestUrl
        _state.update {
            it.copy(
                isLoggingIn = true,
                statusMessage = "Abrindo seu navegador para autenticar no BestCine...",
                errorMessage = null,
            )
        }

        val authResult = BestCinePlatformAuth.launchGoogleLogin(baseUrl)
        when (authResult) {
            is BestCineAuthResult.Cancelled -> {
                _state.update {
                    it.copy(
                        isLoggingIn = false,
                        statusMessage = null,
                        errorMessage = null,
                    )
                }
                false
            }
            is BestCineAuthResult.Error -> {
                _state.update {
                    it.copy(
                        isLoggingIn = false,
                        statusMessage = null,
                        errorMessage = authResult.message,
                    )
                }
                false
            }
            is BestCineAuthResult.Success -> {
                if (authResult.user.isBanned) {
                    val banMsg = formatBanMessage(authResult.user.motivoBan)
                    BestCinePlatformStorage.clearSession()
                    _state.update {
                        it.copy(
                            session = null,
                            isLoggingIn = false,
                            statusMessage = null,
                            errorMessage = banMsg,
                        )
                    }
                    NuvioToastController.show(banMsg)
                    return@async false
                }
                val newSession = BestCineSession(
                    baseUrl = baseUrl.trim().trimEnd('/'),
                    user = authResult.user,
                    lastManifestSyncMs = 0L,
                )
                persistSession(newSession)
                // Baixa e registra o manifest ANTES de sinalizar isConnected=true (evita abrir a Home vazia e evita cancelamento da tela de login)
                _state.update {
                    it.copy(
                        isLoggingIn = false,
                        isSyncingManifest = true,
                        statusMessage = "Conta conectada! Carregando catálogos do BestCine...",
                        errorMessage = null,
                    )
                }

                val manifestUrl = newSession.manifestUrl
                val initialManifest = runCatching {
                    withContext(Dispatchers.Default) {
                        val payload = fetchAddonResponseText(
                            url = manifestUrl,
                            forceRefresh = true,
                        )
                        AddonManifestParser.parse(
                            manifestUrl = manifestUrl,
                            payload = payload,
                        )
                    }
                }.onFailure { error ->
                    log.w(error) { "Falha ao baixar manifest imediato após login (tentará novamente em segundo plano)" }
                }.getOrNull()

                if (initialManifest != null && isSuspendedManifest(initialManifest.name, initialManifest.description)) {
                    val reason = extractBanReason(initialManifest.description.orEmpty())
                    forceLogoutForBan(reason)
                    return@async false
                }

                val syncedSession = if (initialManifest != null) {
                    AddonRepository.upsertBestCineAddon(
                        manifestUrl = manifestUrl,
                        manifest = initialManifest,
                        previousManifestUrls = setOfNotNull(previousUrl),
                    )
                    val activeAddons = AddonRepository.uiState.value.addons.enabledAddons()
                    HomeCatalogSettingsRepository.syncBestCineCatalogOrder(
                        addons = activeAddons,
                        bestCineManifestUrl = manifestUrl,
                    )
                    HomeRepository.refresh(addons = activeAddons, force = true)
                    newSession.copy(lastManifestSyncMs = bestCineCurrentTimeMillis()).also {
                        persistSession(it)
                    }
                } else {
                    newSession
                }

                _state.update {
                    it.copy(
                        session = syncedSession,
                        isLoggingIn = false,
                        isSyncingManifest = false,
                        statusMessage = if (initialManifest != null) {
                            "Catálogos atualizados (${initialManifest.catalogs.size} catálogos na ordem do servidor)."
                        } else {
                            "Conta conectada! Carregando catálogos do BestCine..."
                        },
                        errorMessage = null,
                    )
                }

                ensureAppEntered()
                ensureBanWatchdogRunning()
                if (initialManifest == null) {
                    scope.launch {
                        syncManifest(
                            previousManifestUrls = setOfNotNull(previousUrl),
                            forceRefresh = true,
                            skipStatusRefresh = true,
                        )
                    }
                }
                true
            }
        }
    }.await()

    fun startTokenLogin(
        rawInput: String,
        defaultBaseUrl: String = BestCineBranding.PRODUCTION_BASE_URL,
    ) {
        scope.launch {
            loginWithTokenOrUrl(rawInput, defaultBaseUrl)
        }
    }

    suspend fun loginWithTokenOrUrl(
        rawInput: String,
        defaultBaseUrl: String = BestCineBranding.PRODUCTION_BASE_URL,
    ): Boolean = scope.async {
        ensureLoaded()
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(errorMessage = "Informe seu token bc_... ou link do manifest.") }
            return@async false
        }

        val (resolvedBaseUrl, resolvedToken) = parseTokenInput(trimmed, defaultBaseUrl)
        if (resolvedToken.isBlank()) {
            _state.update { it.copy(errorMessage = "Token BestCine inválido. Deve começar com bc_...") }
            return@async false
        }

        val previousUrl = _state.value.session?.manifestUrl
        _state.update {
            it.copy(
                isLoggingIn = true,
                statusMessage = "Validando token BestCine...",
                errorMessage = null,
            )
        }

        val testUrl = "${resolvedBaseUrl.trimEnd('/')}/$resolvedToken/manifest.json"
        val manifestResult = runCatching {
            withContext(Dispatchers.Default) {
                val payload = fetchAddonResponseText(testUrl, forceRefresh = true)
                AddonManifestParser.parse(manifestUrl = testUrl, payload = payload)
            }
        }

        manifestResult.fold(
            onSuccess = { manifest ->
                if (isSuspendedManifest(manifest.name, manifest.description)) {
                    val reason = extractBanReason(manifest.description.orEmpty())
                    forceLogoutForBan(reason)
                    return@fold false
                }
                val newSession = BestCineSession(
                    baseUrl = resolvedBaseUrl.trimEnd('/'),
                    user = BestCineUser(
                        nome = "Conta BestCine",
                        email = "",
                        plano = "conectado",
                        tokenInstalacao = resolvedToken,
                    ),
                    lastManifestSyncMs = bestCineCurrentTimeMillis(),
                )
                persistSession(newSession)
                AddonRepository.upsertBestCineAddon(
                    manifestUrl = newSession.manifestUrl,
                    manifest = manifest,
                    previousManifestUrls = setOfNotNull(previousUrl),
                )
                val activeAddons = AddonRepository.uiState.value.addons.enabledAddons()
                HomeCatalogSettingsRepository.syncBestCineCatalogOrder(
                    addons = activeAddons,
                    bestCineManifestUrl = newSession.manifestUrl,
                )
                HomeRepository.refresh(addons = activeAddons, force = true)
                _state.update {
                    it.copy(
                        session = newSession,
                        isLoggingIn = false,
                        isSyncingManifest = false,
                        statusMessage = "Catálogos BestCine sincronizados (${manifest.catalogs.size}).",
                        errorMessage = null,
                    )
                }
                ensureAppEntered()
                ensureBanWatchdogRunning()
                true
            },
            onFailure = { error ->
                _state.update {
                    it.copy(
                        isLoggingIn = false,
                        statusMessage = null,
                        errorMessage = "Não foi possível validar o token no BestCine: ${error.message ?: "erro de conexão"}",
                    )
                }
                false
            },
        )
    }.await()

    fun syncManifestOnStartupAsync() {
        ensureLoaded()
        if (!_state.value.isConnected) return
        scope.launch {
            refreshAccountStatus(triggerManifestSyncOnPlanChange = false)
            syncManifest(forceRefresh = true, skipStatusRefresh = true)
        }
    }

    /**
     * Consulta /api/app/status?token=bc_... para sincronizar em tempo real:
     * - Mudanças de plano (Gratuito <-> 👑 Apoiador <-> ⭐ VIP) e data de vencimento
     * - Suspensão/banimento da conta
     * - Nome e e-mail atualizados
     */
    suspend fun refreshAccountStatus(
        triggerManifestSyncOnPlanChange: Boolean = true,
    ): BestCineUser? {
        ensureLoaded()
        val currentSession = _state.value.session ?: return null
        val token = currentSession.user.tokenInstalacao.trim()
        if (token.isBlank()) return null

        val statusUrl = "${currentSession.normalizedBaseUrl}/api/app/status?token=$token"
        val rawJson = runCatching {
            withContext(Dispatchers.Default) {
                httpGetTextWithHeaders(
                    url = statusUrl,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Cache-Control" to "no-store, no-cache",
                    ),
                )
            }
        }.getOrElse { error ->
            val msg = error.message.orEmpty()
            if (msg.contains("401")) {
                log.w { "Token BestCine inválido ou revogado (HTTP 401). Desconectando sessão local." }
                logout()
            }
            return null
        }

        val parsed = runCatching {
            json.decodeFromString<BestCineMeResponse>(rawJson)
        }.getOrNull() ?: return null

        if (parsed.tokenInvalido || (!parsed.logado && parsed.usuario == null)) {
            log.w { "Token BestCine inválido reportado por /api/app/status. Desconectando sessão local." }
            logout()
            return null
        }

        val freshUser = parsed.usuario ?: return null
        if (freshUser.isBanned) {
            forceLogoutForBan(freshUser.motivoBan)
            return null
        }

        val latestSession = _state.value.session ?: return null
        val oldUser = latestSession.user
        val planChanged = !oldUser.plano.trim().equals(freshUser.plano.trim(), ignoreCase = true) ||
            oldUser.vencimentoApoio?.trim() != freshUser.vencimentoApoio?.trim()
        val userChanged = oldUser != freshUser

        if (userChanged) {
            val updatedSession = latestSession.copy(user = freshUser)
            persistSession(updatedSession)
            _state.update { it.copy(session = updatedSession) }
            log.i {
                "Conta BestCine sincronizada em tempo real: plano=${freshUser.plano}, vencimento=${freshUser.vencimentoApoio}"
            }
        }

        if (planChanged && triggerManifestSyncOnPlanChange) {
            scope.launch {
                syncManifest(forceRefresh = true, skipStatusRefresh = true)
                BestCineTvRepository.ensureCatalogLoaded(forceRefresh = true)
            }
        }

        return freshUser
    }

    suspend fun syncManifest(
        previousManifestUrls: Set<String> = emptySet(),
        forceRefresh: Boolean = true,
        skipStatusRefresh: Boolean = false,
    ): Boolean = scope.async {
        syncManifestMutex.withLock {
            ensureLoaded()
            if (!skipStatusRefresh) {
                refreshAccountStatus(triggerManifestSyncOnPlanChange = false)
            }
            val currentSession = _state.value.session ?: return@withLock false

            _state.update {
                it.copy(
                    isSyncingManifest = true,
                    errorMessage = null,
                )
            }

            try {
                val manifestUrl = currentSession.manifestUrl
                log.i { "Sincronizando manifest BestCine: $manifestUrl" }

                val result = runCatching {
                    withContext(Dispatchers.Default) {
                        val payload = fetchAddonResponseText(
                            url = manifestUrl,
                            forceRefresh = forceRefresh,
                        )
                        AddonManifestParser.parse(
                            manifestUrl = manifestUrl,
                            payload = payload,
                        )
                    }
                }

                result.fold(
                    onSuccess = { manifest ->
                        if (isSuspendedManifest(manifest.name, manifest.description)) {
                            val reason = extractBanReason(manifest.description.orEmpty())
                            forceLogoutForBan(reason)
                            return@fold false
                        }
                        AddonRepository.upsertBestCineAddon(
                            manifestUrl = manifestUrl,
                            manifest = manifest,
                            previousManifestUrls = previousManifestUrls,
                        )
                        val activeAddons = AddonRepository.uiState.value.addons.enabledAddons()
                        HomeCatalogSettingsRepository.syncBestCineCatalogOrder(
                            addons = activeAddons,
                            bestCineManifestUrl = manifestUrl,
                        )
                        HomeRepository.refresh(addons = activeAddons, force = true)
                        val latestUser = _state.value.session?.user ?: currentSession.user
                        val updatedSession = currentSession.copy(
                            user = latestUser,
                            lastManifestSyncMs = bestCineCurrentTimeMillis(),
                        )
                        persistSession(updatedSession)
                        _state.update {
                            it.copy(
                                session = updatedSession,
                                isSyncingManifest = false,
                                statusMessage = "Catálogos atualizados (${manifest.catalogs.size} catálogos na ordem do servidor).",
                                errorMessage = null,
                            )
                        }
                        log.i { "Manifest BestCine sincronizado com sucesso: ${manifest.catalogs.size} catálogos" }
                        true
                    },
                    onFailure = { error ->
                        log.w(error) { "Falha ao atualizar manifest BestCine (mantendo cache local)" }
                        _state.update {
                            it.copy(
                                isSyncingManifest = false,
                                errorMessage = "Não foi possível atualizar o manifest agora: ${error.message ?: "sem conexão"}",
                            )
                        }
                        false
                    },
                )
            } finally {
                if (_state.value.isSyncingManifest) {
                    _state.update { it.copy(isSyncingManifest = false) }
                }
            }
        }
    }.await()

    suspend fun logout() {
        ensureLoaded()
        banWatchdogJob?.cancel()
        banWatchdogJob = null
        BestCineTvRepository.closeEmbeddedPlayer()
        BestCinePlatformStorage.clearSession()
        _state.value = BestCineAccountUiState()
        AddonRepository.clearAddonsForBestCineLogout()
        // Mantém AuthRepository/ProfileStorage intactos para preservar os perfis locais do usuário ao relogar!
    }

    /**
     * Inspeciona todas as respostas HTTP do addon (/manifest.json, /stream/, /catalog/, /meta/).
     * Se detectar que a conta foi suspensa/banida pela moderação ou pelo BestCine Shield,
     * desloga o usuário imediatamente, fecha qualquer player ativo e retorna para a tela de login.
     */
    fun inspectAddonResponseForBan(url: String, payload: String) {
        if (!_state.value.isConnected) return
        if (!isBestCineTokenUrl(url)) return
        if (isBanPayload(payload)) {
            val reason = extractBanReason(payload)
            scope.launch {
                forceLogoutForBan(reason)
            }
        }
    }

    fun inspectAddonExceptionForBan(url: String, error: Throwable) {
        if (!_state.value.isConnected) return
        if (!isBestCineTokenUrl(url)) return
        val msg = error.message.orEmpty()
        if (msg.contains("Conta suspensa pela moderação", ignoreCase = true) ||
            msg.contains("CONTA SUSPENSA", ignoreCase = true)
        ) {
            val reason = extractBanReason(msg)
            scope.launch {
                forceLogoutForBan(reason)
            }
        }
    }

    suspend fun forceLogoutForBan(reason: String? = null) {
        val banMessage = formatBanMessage(reason)
        log.w { "Conta BestCine banida/suspensa detectada! Deslogando imediatamente: $banMessage" }
        banWatchdogJob?.cancel()
        banWatchdogJob = null
        BestCineTvRepository.closeEmbeddedPlayer()
        BestCinePlatformStorage.clearSession()
        _state.value = BestCineAccountUiState(
            session = null,
            isLoggingIn = false,
            isSyncingManifest = false,
            statusMessage = null,
            errorMessage = banMessage,
        )
        AddonRepository.clearAddonsForBestCineLogout()
        NuvioToastController.show(banMessage)
    }

    private fun ensureBanWatchdogRunning() {
        if (banWatchdogJob?.isActive == true) return
        banWatchdogJob = scope.launch {
            while (isActive && _state.value.isConnected) {
                delay(BAN_WATCHDOG_INTERVAL_MS)
                val session = _state.value.session ?: break
                val refreshedUser = refreshAccountStatus(triggerManifestSyncOnPlanChange = true)
                if (refreshedUser == null && _state.value.isConnected) {
                    runCatching {
                        // Fallback caso o endpoint /api/app/status ainda não esteja ativo no servidor
                        fetchAddonResponseText(url = session.manifestUrl, forceRefresh = true)
                    }
                }
            }
        }
    }

    private fun isBestCineTokenUrl(url: String): Boolean {
        val session = _state.value.session ?: return false
        val token = session.user.tokenInstalacao.trim()
        return token.isNotBlank() && url.contains("/$token/")
    }

    private fun isSuspendedManifest(name: String, description: String?): Boolean {
        return name.contains("Conta Suspensa", ignoreCase = true) ||
            description?.contains("suspensa pela moderação", ignoreCase = true) == true
    }

    private fun isBanPayload(payload: String): Boolean {
        return payload.contains("BestCine (Conta Suspensa)", ignoreCase = true) ||
            payload.contains("BestCine - Conta Suspensa", ignoreCase = true) ||
            payload.contains("CONTA SUSPENSA / BANIDA", ignoreCase = true) ||
            payload.contains("Sua conta pessoal foi suspensa pela moderação", ignoreCase = true) ||
            payload.contains("Conta suspensa pela moderação", ignoreCase = true)
    }

    private fun extractBanReason(text: String): String? {
        val match = Regex("Motivo:\\s*([^\\n\"\\\\]+)", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.getOrNull(1)
            ?.replace("Acesse o Discord: dsc.gg/bestcine", "", ignoreCase = true)
            ?.trim()
            ?.trimEnd('.')
            ?.takeIf { it.isNotBlank() }
    }

    private fun formatBanMessage(reason: String?): String {
        val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        return if (cleanReason != null) {
            "🚫 Sua conta BestCine foi suspensa pela moderação (Motivo: $cleanReason). Para recorrer: dsc.gg/bestcine"
        } else {
            "🚫 Sua conta BestCine foi suspensa pela moderação. Para recorrer, acesse nosso Discord: dsc.gg/bestcine"
        }
    }

    private fun ensureAppEntered() {
        val currentAuth = AuthRepository.state.value
        if (currentAuth !is AuthState.Authenticated) {
            AuthRepository.signInAnonymously()
        }
    }

    private fun persistSession(session: BestCineSession) {
        BestCinePlatformStorage.saveSessionJson(json.encodeToString(session))
    }

    private fun parseTokenInput(input: String, defaultBaseUrl: String): Pair<String, String> {
        val cleaned = input.trim().replace("stremio://", "https://")
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            val match = Regex("^(https?://[^/]+)/((?:bc|usr)_[a-zA-Z0-9_-]+)").find(cleaned)
            if (match != null) {
                return match.groupValues[1] to match.groupValues[2]
            }
        }
        val tokenOnly = Regex("((?:bc|usr)_[a-zA-Z0-9_-]+)").find(cleaned)?.groupValues?.get(1).orEmpty()
        return defaultBaseUrl.trimEnd('/') to tokenOnly
    }
}
