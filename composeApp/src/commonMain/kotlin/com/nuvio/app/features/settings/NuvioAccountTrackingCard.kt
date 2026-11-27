package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.DeviceLinkAuthFailure
import com.nuvio.app.core.auth.DeviceLinkAuthRepository
import com.nuvio.app.core.auth.DeviceLinkAuthState
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val NuvioCardBrush = Brush.linearGradient(
    colors = listOf(Color(0xFF1A285C), Color(0xFF3347BD), Color(0xFF5A37D8)),
)
private val NuvioErrorTextColor = Color(0xFFFFDAD6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NuvioAccountProviderCard(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val authError by AuthRepository.error.collectAsStateWithLifecycle()
    val backendConfigured = remember { AuthRepository.isNuvioBackendConfigured() }
    val connectedAuth = (authState as? AuthState.Authenticated)?.takeIf { !it.isAnonymous }
    val isConnected = connectedAuth != null

    var showConnectDialog by rememberSaveable { mutableStateOf(false) }
    var showDisconnectDialog by rememberSaveable { mutableStateOf(false) }
    var isSyncing by rememberSaveable { mutableStateOf(false) }
    var isDisconnecting by rememberSaveable { mutableStateOf(false) }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(isConnected) {
        if (isConnected) {
            showConnectDialog = false
            errorMessage = null
        }
    }

    Box(
        modifier = modifier
            .clip(tokens.shapes.card)
            .background(NuvioCardBrush, tokens.shapes.card)
            .border(
                width = tokens.borders.hairline,
                color = Color.White.copy(alpha = 0.22f),
                shape = tokens.shapes.card,
            ),
    ) {
        Icon(
            imageVector = Icons.Rounded.Sync,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .size(144.dp)
                .alpha(0.08f),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isConnected) 20.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
                Text(
                    text = "Conta Nuvio",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    color = Color.White.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = "Recomendado",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (isConnected) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = connectedAuth.email?.takeIf(String::isNotBlank)
                            ?.let { "Conectado como $it" }
                            ?: "Conta Nuvio conectada",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Sincronizando Continuar assistindo, Assistidos, Biblioteca e addons externos (catálogos, legendas e metadados; streams bloqueados).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                    )
                }

                val syncButtonEnabled = !isSyncing && !isDisconnecting
                val syncButtonContentColor = if (syncButtonEnabled) Color(0xFF171717) else Color.White.copy(alpha = 0.7f)
                Button(
                    onClick = {
                        if (isSyncing || isDisconnecting) return@Button
                        scope.launch {
                            isSyncing = true
                            statusMessage = null
                            errorMessage = null
                            try {
                                val profileId = ProfileRepository.activeProfileId
                                com.nuvio.app.features.addons.AddonRepository.pullFromServer(profileId)
                                LibraryRepository.pullFromServer(profileId)
                                WatchProgressSourceCoordinator.refreshActiveSource(
                                    profileId = profileId,
                                    force = true,
                                )
                                statusMessage = "Sincronização concluída."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                errorMessage = e.message ?: "Não foi possível sincronizar agora."
                            } finally {
                                isSyncing = false
                            }
                        }
                    },
                    enabled = syncButtonEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF171717),
                        disabledContainerColor = Color.White.copy(alpha = 0.34f),
                        disabledContentColor = Color.White.copy(alpha = 0.7f),
                    ),
                ) {
                    if (isSyncing) {
                        NuvioLoadingIndicator(
                            color = syncButtonContentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            tint = syncButtonContentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isSyncing) "Sincronizando..." else "Sincronizar agora",
                        color = syncButtonContentColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { showDisconnectDialog = true },
                        enabled = !isSyncing && !isDisconnecting,
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White.copy(alpha = 0.86f),
                            disabledContentColor = Color.White.copy(alpha = 0.38f),
                        ),
                    ) {
                        Text(
                            text = "Desconectar Conta Nuvio",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Text(
                    text = "Sincronize Continuar assistindo, Assistidos, Biblioteca e addons externos (catálogos, legendas e metadados) entre PC, celular e BestCine TV com uma conta Nuvio gratuita. Streams externos permanecem bloqueados.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.84f),
                )
                val connectButtonContentColor = if (backendConfigured) Color(0xFF171717) else Color.White.copy(alpha = 0.7f)
                Button(
                    onClick = {
                        statusMessage = null
                        errorMessage = null
                        showConnectDialog = true
                    },
                    enabled = backendConfigured,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF171717),
                        disabledContainerColor = Color.White.copy(alpha = 0.34f),
                        disabledContentColor = Color.White.copy(alpha = 0.7f),
                    ),
                ) {
                    Text(
                        text = "Conectar Conta Nuvio",
                        color = connectButtonContentColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (!backendConfigured) {
                    NuvioCardMessage(
                        text = "API da Conta Nuvio não configurada neste build (defina NUVIO_SUPABASE_URL e NUVIO_SUPABASE_ANON_KEY no build/Secrets).",
                        isError = true,
                    )
                }
            }

            statusMessage?.takeIf(String::isNotBlank)?.let { msg ->
                NuvioCardMessage(text = msg, isError = false)
            }
            (errorMessage ?: authError)?.takeIf(String::isNotBlank)?.let { msg ->
                NuvioCardMessage(text = msg, isError = true)
            }
        }
    }

    if (showConnectDialog) {
        NuvioAccountConnectDialog(
            onDismiss = {
                DeviceLinkAuthRepository.cancel()
                showConnectDialog = false
            },
            onConnected = { info ->
                DeviceLinkAuthRepository.cancel()
                showConnectDialog = false
                if (!info.isNullOrBlank()) {
                    statusMessage = info
                } else {
                    statusMessage = "Conta Nuvio conectada! Sincronizando seu histórico..."
                    val profileId = ProfileRepository.activeProfileId
                    SyncManager.pullAllForProfile(profileId)
                    SyncManager.startPeriodicNuvioSyncPull(profileId)
                }
            },
        )
    }

    if (showDisconnectDialog) {
        DialogSurface(
            onDismissRequest = { showDisconnectDialog = false },
            title = "Desconectar Conta Nuvio",
        ) {
            Text(
                text = "A sincronização com a Conta Nuvio será pausada neste aparelho. Sua conta BestCine e seus dados locais serão mantidos.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
            DialogButtons {
                DialogButton(
                    text = "Cancelar",
                    onClick = { showDisconnectDialog = false },
                )
                DialogButton(
                    text = "Desconectar",
                    style = DialogButtonStyle.Destructive,
                    onClick = {
                        showDisconnectDialog = false
                        scope.launch {
                            isDisconnecting = true
                            statusMessage = null
                            errorMessage = null
                            try {
                                AuthRepository.disconnectNuvioTrackingAccount()
                                statusMessage = "Conta Nuvio desconectada. Seus dados locais foram mantidos."
                            } finally {
                                isDisconnecting = false
                            }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NuvioAccountConnectDialog(
    onDismiss: () -> Unit,
    onConnected: (infoMessage: String?) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val deviceLinkState by DeviceLinkAuthRepository.state.collectAsStateWithLifecycle()

    var useCodeMode by rememberSaveable { mutableStateOf(false) }
    var isSignUp by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isSubmitting by rememberSaveable { mutableStateOf(false) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    var copiedNotice by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { DeviceLinkAuthRepository.cancel() }
    }

    DialogSurface(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = if (useCodeMode) "Conectar Conta Nuvio por Código" else if (isSignUp) "Criar Conta Nuvio" else "Entrar na Conta Nuvio",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    useCodeMode = false
                    localError = null
                    DeviceLinkAuthRepository.cancel()
                },
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (!useCodeMode) tokens.colors.accent.copy(alpha = 0.18f) else Color.Transparent,
                ),
            ) {
                Text("E-mail e senha", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = {
                    useCodeMode = true
                    localError = null
                    copiedNotice = null
                    DeviceLinkAuthRepository.start()
                },
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (useCodeMode) tokens.colors.accent.copy(alpha = 0.18f) else Color.Transparent,
                ),
            ) {
                Text("Código / Site", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (!useCodeMode) {
            Text(
                text = "Use sua conta Nuvio para sincronizar Continuar assistindo, Assistidos, Biblioteca e addons externos de catálogo. Streams externos permanecem bloqueados e o BestCine continua intacto.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; localError = null },
                label = { Text("E-mail") },
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; localError = null },
                label = { Text("Senha") },
                singleLine = true,
                enabled = !isSubmitting,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = {
                    isSignUp = !isSignUp
                    localError = null
                },
                enabled = !isSubmitting,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    if (isSignUp) "Já tem conta Nuvio? Entrar" else "Não tem conta Nuvio? Criar conta grátis",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            localError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            DialogButtons {
                DialogButton(
                    text = "Cancelar",
                    enabled = !isSubmitting,
                    onClick = onDismiss,
                )
                DialogButton(
                    text = if (isSubmitting) "Conectando..." else if (isSignUp) "Criar conta" else "Entrar",
                    enabled = !isSubmitting && email.isNotBlank() && password.length >= 6,
                    style = DialogButtonStyle.Primary,
                    onClick = {
                        scope.launch {
                            isSubmitting = true
                            localError = null
                            val res = AuthRepository.connectNuvioTrackingWithEmail(
                                email = email.trim(),
                                password = password,
                                isSignUp = isSignUp,
                            )
                            isSubmitting = false
                            res.fold(
                                onSuccess = { info -> onConnected(info) },
                                onFailure = { err ->
                                    localError = err.message ?: "Não foi possível entrar na Conta Nuvio."
                                },
                            )
                        }
                    },
                )
            }
        } else {
            when (val state = deviceLinkState) {
                DeviceLinkAuthState.Idle,
                DeviceLinkAuthState.Starting,
                -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NuvioLoadingIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Gerando código de conexão...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is DeviceLinkAuthState.Waiting -> {
                    Text(
                        text = "Abra o link abaixo no navegador, faça login na sua Conta Nuvio e autorize o código:",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                clipboardManager.setText(AnnotatedString(state.code))
                                copiedNotice = "Código copiado!"
                            },
                        shape = RoundedCornerShape(NuvioTokens.Radius.md),
                        color = tokens.colors.surfaceElevated,
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = state.code,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = state.verificationUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textMuted,
                            )
                        }
                    }
                    copiedNotice?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = tokens.colors.accent)
                    }
                    if (state.isCompleting) {
                        Text("Autorizado! Finalizando conexão...", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            runCatching { uriHandler.openUri(state.verificationUrl) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Abrir no navegador", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                is DeviceLinkAuthState.Failed -> {
                    val msg = when (state.reason) {
                        DeviceLinkAuthFailure.Expired -> "O código expirou. Gere um novo código."
                        DeviceLinkAuthFailure.Complete -> "Não foi possível concluir a conexão. Tente novamente."
                        DeviceLinkAuthFailure.Start -> "Não foi possível iniciar a conexão por código."
                    }
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = { DeviceLinkAuthRepository.start() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Tentar novamente", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            DialogButtons {
                DialogButton(
                    text = "Fechar",
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun NuvioCardMessage(
    text: String,
    isError: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) NuvioErrorTextColor.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.12f),
        shape = RoundedCornerShape(NuvioTokens.Radius.md),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) NuvioErrorTextColor else Color.White.copy(alpha = 0.86f),
        )
    }
}
