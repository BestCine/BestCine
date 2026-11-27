package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.bestcine.BestCineAccountRepository
import com.nuvio.app.bestcine.BestCineBranding
import com.nuvio.app.bestcine.dns.BestCineDnsMode
import com.nuvio.app.bestcine.dns.BestCineDnsSettingsRepository
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch

internal fun LazyListScope.accountSettingsContent(
    isTablet: Boolean,
) {
    item {
        AccountSettingsBody(isTablet = isTablet)
    }
}

@Composable
private fun AccountSettingsBody(
    isTablet: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BestCineAccountCard()
        BestCineDnsCard()
    }
}

@Composable
private fun BestCineAccountCard() {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val state by BestCineAccountRepository.state.collectAsStateWithLifecycle()
    var showLogoutConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        BestCineAccountRepository.ensureLoaded()
        BestCineAccountRepository.refreshAccountStatus(triggerManifestSyncOnPlanChange = true)
    }

    val session = state.session
    val busy = state.isLoggingIn || state.isSyncingManifest

    NuvioSurfaceCard {
        Text(
            text = "Conta ${BestCineBranding.APP_NAME}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (session != null && session.user.tokenInstalacao.isNotBlank()) {
            val isVip = session.user.isApoiadorOrVip
            val goldColor = Color(0xFFFFCA28)

            // Card visual de destaque do Plano (estilo Painel Web BestCine)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isVip) goldColor.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
                border = BorderStroke(
                    1.dp,
                    if (isVip) goldColor.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.10f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "PLANO ATUAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isVip) goldColor.copy(alpha = 0.85f) else tokens.colors.textMuted,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = session.user.planDetailedLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isVip) goldColor else tokens.colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = session.user.planSubtitleLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isVip) Color(0xFFFFECB3) else tokens.colors.textMuted,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            AccountInfoRow(
                label = "Status",
                value = "● Conectado & Sincronizado",
                valueColor = Color(0xFF34D399),
            )
            if (session.user.nome.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                AccountInfoRow(label = "Usuário", value = session.user.nome)
            }
            if (session.user.email.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                AccountInfoRow(label = "Conta Google", value = session.user.email)
            }

            state.statusMessage?.let { msg ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = BestCineBranding.PrimaryBlueBright,
                )
            }
            state.errorMessage?.let { msg ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            NuvioPrimaryButton(
                text = if (state.isSyncingManifest) "Atualizando catálogos..." else "Atualizar catálogos BestCine agora",
                enabled = !busy,
                onClick = {
                    scope.launch { BestCineAccountRepository.syncManifest(forceRefresh = true) }
                },
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = { showLogoutConfirm = true },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTokens.Space.s48 + NuvioTokens.Space.s4),
                shape = tokens.shapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.colors.danger.copy(alpha = 0.16f),
                    contentColor = tokens.colors.danger,
                ),
            ) {
                Text(
                    text = "Sair da conta BestCine",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(
                text = "Conecte sua conta Google do BestCine para usar o aplicativo.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
            Spacer(modifier = Modifier.height(14.dp))
            NuvioPrimaryButton(
                text = if (state.isLoggingIn) "Aguardando login Google..." else "Entrar com Google (BestCine)",
                enabled = !busy,
                onClick = {
                    BestCineAccountRepository.startGoogleLogin(BestCineBranding.PRODUCTION_BASE_URL)
                },
            )
        }
    }

    NuvioStatusModal(
        title = "Sair do BestCine?",
        message = "Você será desconectado e voltará para a tela de login Google do BestCine.",
        isVisible = showLogoutConfirm,
        confirmText = "Sair",
        dismissText = "Cancelar",
        onConfirm = {
            showLogoutConfirm = false
            scope.launch { BestCineAccountRepository.logout() }
        },
        onDismiss = { showLogoutConfirm = false },
    )
}

@Composable
private fun BestCineDnsCard() {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val dnsState by BestCineDnsSettingsRepository.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        BestCineDnsSettingsRepository.ensureLoaded()
    }

    NuvioSurfaceCard {
        Text(
            text = "DNS Próprio Anti-Bloqueio (IPv4 + IPv6)",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Ignora o DNS bloqueado da operadora no PC e resolve domínios diretamente via DNS criptografado (DoH).",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BestCineDnsMode.entries.forEach { mode ->
                val selected = dnsState.mode == mode
                Button(
                    onClick = { BestCineDnsSettingsRepository.setMode(mode) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = tokens.shapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) {
                            BestCineBranding.PrimaryBlue
                        } else {
                            Color.White.copy(alpha = 0.06f)
                        },
                        contentColor = if (selected) Color.White else tokens.colors.textPrimary,
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(
                            text = if (selected) "✓ ${mode.label}" else mode.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) Color.White.copy(alpha = 0.85f) else tokens.colors.textMuted,
                        )
                    }
                }
            }
        }

        if (dnsState.mode != BestCineDnsMode.SYSTEM) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Usar DNS próprio no Player de Vídeo",
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Roteia streams do player (TV, filmes e séries) pelo proxy DoH local em 127.0.0.1.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
                Switch(
                    checked = dnsState.proxyVideoViaDoh,
                    onCheckedChange = BestCineDnsSettingsRepository::setProxyVideoViaDoh,
                )
            }
        }

        dnsState.lastTestResult?.let { resultText ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = resultText,
                style = MaterialTheme.typography.bodySmall,
                color = if (resultText.startsWith("OK")) {
                    BestCineBranding.PrimaryBlueBright
                } else {
                    tokens.colors.danger
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        NuvioPrimaryButton(
            text = if (dnsState.isTesting) "Testando DNS..." else {
                val host = BestCineBranding.PRODUCTION_HOST
                if (host.isNotBlank()) "Testar DNS agora ($host)" else "Testar DNS agora"
            },
            enabled = !dnsState.isTesting,
            onClick = {
                scope.launch { BestCineDnsSettingsRepository.runDiagnosticTest() }
            },
        )
    }
}

@Composable
private fun AccountInfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
