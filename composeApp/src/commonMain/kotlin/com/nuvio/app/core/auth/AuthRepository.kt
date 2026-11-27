package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.bestcine.BestCineAccountRepository
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import com.nuvio.app.core.sync.SyncManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private const val KEY_HAD_ACCOUNT_SESSION = "had_account_session"
private const val KEY_SIGNED_OUT_NOTICE = "signed_out_notice"

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var initialized = false
    private var sessionStatusJob: Job? = null
    private var validatedRemoteUserId: String? = null
    private val refreshMutex = Mutex()

    fun initialize() {
        if (initialized) return
        initialized = true

        val savedAnonId = AuthStorage.loadAnonymousUserId()
        if (savedAnonId != null) {
            _state.value = AuthState.Authenticated(
                userId = savedAnonId,
                email = null,
                isAnonymous = true,
            )
        }

        sessionStatusJob = scope.launch {
            if (AuthStorage.loadFlag(KEY_SIGNED_OUT_NOTICE)) {
                _error.value = getString(Res.string.auth_notice_signed_out)
            }
            if (!isNuvioBackendConfigured()) return@launch
            SupabaseProvider.client.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = status.session.user
                        val userId = user?.id.orEmpty()
                        if (userId.isBlank()) return@collect
                        if (!validateRemoteSession(userId)) return@collect
                        AuthStorage.clearAnonymousUserId()
                        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, true)
                        AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
                        _error.value = null
                        _state.value = AuthState.Authenticated(
                            userId = userId,
                            email = user?.email,
                            isAnonymous = false,
                        )
                    }
                    is SessionStatus.NotAuthenticated -> {
                        if (AuthStorage.loadAnonymousUserId() != null && !AuthStorage.loadFlag(KEY_HAD_ACCOUNT_SESSION)) {
                            return@collect
                        }
                        if (AuthStorage.loadFlag(KEY_HAD_ACCOUNT_SESSION)) {
                            resetAfterLostSession()
                        } else {
                            _state.value = AuthState.Unauthenticated
                        }
                    }
                    is SessionStatus.Initializing -> {
                        if (AuthStorage.loadAnonymousUserId() == null && !BestCineAccountRepository.state.value.isConnected) {
                            _state.value = AuthState.Loading
                        }
                    }
                    is SessionStatus.RefreshFailure -> Unit
                }
            }
        }
    }

    private suspend fun validateRemoteSession(userId: String): Boolean {
        if (userId.isBlank() || validatedRemoteUserId == userId) return true

        return runCatching {
            SupabaseProvider.client.auth.retrieveUserForCurrentSession(false)
            validatedRemoteUserId = userId
            true
        }.getOrElse { e ->
            val invalid = if (e.isJwtExpiredError()) refreshRejected() else e.isInvalidSessionError()
            if (invalid) {
                log.w(e) { "Stored Supabase session no longer belongs to an active account; clearing local auth" }
                clearLocalSessionAfterRemoteInvalidation()
                false
            } else {
                log.w(e) { "Unable to validate stored Supabase session; keeping cached auth state" }
                true
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun signInAnonymously() {
        _error.value = null
        val userId = AuthStorage.loadAnonymousUserId()?.takeIf { it.isNotBlank() }
            ?: "bestcine-local-user"
        AuthStorage.saveAnonymousUserId(userId)
        _state.value = AuthState.Authenticated(
            userId = userId,
            email = null,
            isAnonymous = true,
        )
    }

    fun isNuvioBackendConfigured(): Boolean {
        val config = ServerConfigurationRepository.active.value
        return config.backendUrl.isNotBlank() && config.publishableKey.isNotBlank()
    }

    fun hasStoredNuvioTrackingSession(): Boolean =
        AuthStorage.loadFlag(KEY_HAD_ACCOUNT_SESSION) ||
            !AuthStorage.loadSupabaseSessionJson().isNullOrBlank()

    fun prepareForNuvioTrackingSignIn() {
        _error.value = null
        AuthStorage.clearAnonymousUserId()
    }

    fun restoreAnonymousIfNotAuthenticated() {
        val current = _state.value
        if (current !is AuthState.Authenticated || current.isAnonymous) {
            signInAnonymously()
        }
    }

    suspend fun connectNuvioTrackingWithEmail(
        email: String,
        password: String,
        isSignUp: Boolean,
    ): Result<String?> {
        if (!isNuvioBackendConfigured()) {
            val msg = "API da Conta Nuvio não configurada neste build."
            _error.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        _error.value = null
        val previousAnonId = AuthStorage.loadAnonymousUserId()
        AuthStorage.clearAnonymousUserId()
        return try {
            if (isSignUp) {
                SupabaseProvider.client.auth.signUpWith(Email) {
                    this.email = email
                    this.password = password
                }
            } else {
                SupabaseProvider.client.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
            }
            val session = SupabaseProvider.client.auth.currentSessionOrNull()
            val user = session?.user
            if (session != null && user != null && user.id.isNotBlank()) {
                AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, true)
                AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
                validatedRemoteUserId = user.id
                _state.value = AuthState.Authenticated(
                    userId = user.id,
                    email = user.email ?: email,
                    isAnonymous = false,
                )
                Result.success(null)
            } else {
                // Cadastro criado, mas aguardando confirmação de e-mail
                if (previousAnonId != null) {
                    AuthStorage.saveAnonymousUserId(previousAnonId)
                    signInAnonymously()
                }
                Result.success("Conta criada! Confirme o link enviado ao seu e-mail e entre novamente.")
            }
        } catch (e: CancellationException) {
            if (previousAnonId != null && SupabaseProvider.client.auth.currentSessionOrNull() == null) {
                AuthStorage.saveAnonymousUserId(previousAnonId)
            }
            throw e
        } catch (e: Throwable) {
            log.e(e) { "Nuvio tracking email auth failed (signUp=$isSignUp)" }
            if (previousAnonId != null) {
                AuthStorage.saveAnonymousUserId(previousAnonId)
                signInAnonymously()
            }
            val msg = e.safeAuthErrorDescription()
                ?: if (isSignUp) getString(Res.string.auth_sign_up_failed)
                else getString(Res.string.auth_sign_in_failed)
            _error.value = msg
            Result.failure(IllegalStateException(msg, e))
        }
    }

    suspend fun disconnectNuvioTrackingAccount(): Result<Unit> {
        _error.value = null
        SyncManager.cancelAccountSync()
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
        validatedRemoteUserId = null
        val remoteSignOut = runCatching {
            if (isNuvioBackendConfigured()) {
                SupabaseProvider.client.auth.signOut()
            }
        }
        val fallbackClear = if (remoteSignOut.isFailure && isNuvioBackendConfigured()) {
            runCatching { SupabaseProvider.client.auth.clearSession() }
        } else {
            Result.success(Unit)
        }
        AuthStorage.clearSupabaseSessionJson()
        // Mantém a conta BestCine e os dados locais intactos
        signInAnonymously()
        val cancellation = remoteSignOut.exceptionOrNull() as? CancellationException
            ?: fallbackClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return Result.success(Unit)
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        Unit
    }.onFailure { e ->
        log.e(e) { "Email sign-up failed" }
        _error.value = e.safeAuthErrorDescription()
            ?: getString(Res.string.auth_sign_up_failed)
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }.onFailure { e ->
        log.e(e) { "Email sign-in failed" }
        _error.value = e.safeAuthErrorDescription()
            ?: getString(Res.string.auth_sign_in_failed)
    }

    suspend fun signOut(): Result<Unit> {
        _error.value = null
        val anonymousRead = runCatching { AuthStorage.loadAnonymousUserId() }
        val wasAnonymous = anonymousRead.getOrNull() != null
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
        validatedRemoteUserId = null
        val remoteSignOut = if (wasAnonymous) {
            Result.success(Unit)
        } else {
            runCatching { SupabaseProvider.client.auth.signOut() }
        }

        val fallbackSessionClear = if (remoteSignOut.isFailure) {
            runCatching { SupabaseProvider.client.auth.clearSession() }
                .onFailure { error -> log.w(error) { "Failed to clear Supabase session after sign-out failure" } }
        } else {
            Result.success(Unit)
        }
        val localCleanup = runCatching { LocalAccountDataCleaner.wipe() }
        _state.value = AuthState.Unauthenticated

        val failure = anonymousRead.exceptionOrNull()
            ?: anonymousClear.exceptionOrNull()
            ?: remoteSignOut.exceptionOrNull()
            ?: fallbackSessionClear.exceptionOrNull()
            ?: localCleanup.exceptionOrNull()
        val cancellation = remoteSignOut.exceptionOrNull() as? CancellationException
            ?: fallbackSessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) {
            Result.success(Unit)
        } else {
            log.e(failure) { "Sign-out did not complete cleanly; all local cleanup steps were attempted" }
            _error.value = failure.message ?: runCatching {
                getString(Res.string.auth_sign_out_failed)
            }.getOrDefault("Sign out failed")
            Result.failure(failure)
        }
    }

    suspend fun prepareForServerSwitch(): Result<Unit> {
        _error.value = null
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        validatedRemoteUserId = null
        val sessionClear = runCatching { SupabaseProvider.client.auth.clearSession() }
        _state.value = AuthState.Unauthenticated
        val failure = anonymousClear.exceptionOrNull() ?: sessionClear.exceptionOrNull()
        val cancellation = sessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) Result.success(Unit) else Result.failure(failure)
    }

    fun reinitialize() {
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        initialized = false
        validatedRemoteUserId = null
        _state.value = AuthState.Loading
        initialize()
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean {
        val invalid = if (error.isJwtExpiredError()) refreshRejected() else error.isDeletedAccountError()
        if (!invalid) return false

        log.w(error) { "$source failed because the current Supabase account/session is no longer valid; clearing local auth" }
        clearLocalSessionAfterRemoteInvalidation()
        return true
    }

    private suspend fun refreshRejected(): Boolean = refreshMutex.withLock {
        try {
            SupabaseProvider.client.auth.refreshCurrentSession()
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w(e) { "Supabase session refresh failed" }
            e.isInvalidRefreshError()
        }
    }

    private suspend fun clearLocalSessionAfterRemoteInvalidation() {
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        runCatching {
            SupabaseProvider.client.auth.clearSession()
        }.onFailure { e ->
            log.w(e) { "Failed to clear Supabase session after remote invalidation; continuing local reset" }
        }
        resetAfterLostSession()
    }

    private suspend fun resetAfterLostSession() {
        val notice = runCatching { getString(Res.string.auth_notice_signed_out) }.getOrNull()
        validatedRemoteUserId = null
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        if (BestCineAccountRepository.state.value.isConnected) {
            // BestCine ativo: desconecta apenas o rastreamento Nuvio sem apagar dados locais nem a conta BestCine
            SyncManager.cancelAccountSync()
            AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
            _error.value = notice
            signInAnonymously()
            return
        }
        AuthStorage.clearAnonymousUserId()
        val localCleanup = runCatching { LocalAccountDataCleaner.wipe() }
        AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, true)
        _error.value = notice
        _state.value = AuthState.Unauthenticated
        localCleanup.onFailure { error ->
            log.e(error) { "Local account cleanup failed after losing the session" }
        }
    }

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.functions.invoke("delete-account")
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        SupabaseProvider.client.auth.signOut()
        validatedRemoteUserId = null
        try {
            LocalAccountDataCleaner.wipe()
        } finally {
            _state.value = AuthState.Unauthenticated
        }
    }.onFailure { e ->
        log.e(e) { "Account deletion failed" }
        _error.value = e.message ?: getString(Res.string.auth_account_deletion_failed)
    }

    fun clearError() {
        _error.value = null
    }

    private fun Throwable.safeAuthErrorDescription(): String? =
        findCause<AuthRestException>()
            ?.errorDescription
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: findCause<RestException>()
                ?.description
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
}
