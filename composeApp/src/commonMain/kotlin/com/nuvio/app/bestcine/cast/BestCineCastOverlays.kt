package com.nuvio.app.bestcine.cast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.bestcine.BestCineBranding
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.isDesktop

/**
 * Tela de bloqueio exibida no lugar do player local (em Filmes/Séries e TV Ao Vivo)
 * enquanto há transmissão DLNA ativa na Smart TV:
 * - Impede reprodução simultânea no celular/PC e na TV;
 * - Permite reabrir os controles DLNA (volume, avançar/voltar, trocar TV) com 1 toque;
 * - Oferece ações diretas de Pausar/Reproduzir e Parar transmissão.
 */
@Composable
fun BestCineCastPlayerLockOverlay(
    castState: BestCineCastState,
    fallbackTitle: String = "",
    compact: Boolean = false,
    onOpenControls: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) {
        BestCineCastController.registerPlayerCastOverlay()
        onDispose {
            BestCineCastController.unregisterPlayerCastOverlay()
        }
    }

    val safePadding = if (compact) PaddingValues(0.dp) else WindowInsets.safeDrawing.asPaddingValues()
    val deviceName = castState.activeDevice?.friendlyName ?: "Smart TV"
    val mediaTitle = castState.activeMedia?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0E172A),
                        Color(0xFF070C17),
                        Color(0xFF050810),
                    ),
                ),
            )
            .clickable(onClick = onOpenControls),
    ) {
        // Botão Voltar no canto superior esquerdo (para sair do player de Filmes/Séries sem derrubar a TV)
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(safePadding)
                    .padding(14.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Voltar",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 460.dp)
                .padding(horizontal = if (compact) 14.dp else 24.dp, vertical = if (compact) 10.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 46.dp else 64.dp)
                    .clip(RoundedCornerShape(if (compact) 14.dp else 20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                BestCineBranding.PrimaryBlueBright,
                                BestCineBranding.PrimaryBlue,
                            ),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(if (compact) 14.dp else 20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (castState.isConnecting) {
                    NuvioLoadingIndicator(size = if (compact) 22.dp else 28.dp, color = Color.White)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.CastConnected,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (compact) 24.dp else 32.dp),
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = if (castState.isConnecting) {
                        castState.statusMessage ?: "Conectando à TV..."
                    } else {
                        "Transmitindo para $deviceName"
                    },
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (mediaTitle.isNotBlank()) {
                    Text(
                        text = mediaTitle,
                        style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF93C5FD),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!castState.isConnecting) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { BestCineCastController.togglePlayPause() },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(if (compact) 34.dp else 40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BestCineBranding.PrimaryBlue,
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            imageVector = if (castState.isPlayingOnTv) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (castState.isPlayingOnTv) "Pausar" else "Reproduzir",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenControls,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(if (compact) 34.dp else 40.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Controles",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    Button(
                        onClick = { BestCineCastController.stopCasting() },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(if (compact) 34.dp else 40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFDC2626),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        if (!compact) {
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Parar",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mini-barra flutuante global (estilo YouTube/Netflix) exibida quando o usuário sai do player
 * e navega pela Home/Catálogos/Busca enquanto a transmissão DLNA continua passando na TV.
 * Garante que o controle DLNA possa SEMPRE ser reaberto de qualquer tela!
 */
@Composable
fun BoxScope.BestCineGlobalCastMiniBar() {
    val castState by BestCineCastController.state.collectAsStateWithLifecycle()
    val overlayVisible by BestCineCastController.isPlayerCastOverlayVisible.collectAsStateWithLifecycle()
    var showCastDialog by remember { mutableStateOf(false) }

    val showMiniBar = castState.isCasting && !overlayVisible
    val navPadding = WindowInsets.navigationBars.asPaddingValues()

    AnimatedVisibility(
        visible = showMiniBar,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(navPadding)
            .padding(
                start = 14.dp,
                end = 14.dp,
                bottom = if (isDesktop) 18.dp else 82.dp,
            ),
    ) {
        val barShape = RoundedCornerShape(18.dp)
        Surface(
            onClick = { showCastDialog = true },
            shape = barShape,
            color = Color(0xFF10192D),
            border = BorderStroke(1.dp, BestCineBranding.PrimaryBlueBright.copy(alpha = 0.55f)),
            shadowElevation = 20.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                BestCineBranding.PrimaryBlue.copy(alpha = 0.26f),
                                Color(0xFF0E1629),
                            ),
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BestCineBranding.PrimaryBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CastConnected,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Transmitindo para ${castState.activeDevice?.friendlyName ?: "TV"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = castState.activeMedia?.title?.takeIf { it.isNotBlank() }
                            ?: "Toque para abrir os controles",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF93C5FD),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = { BestCineCastController.togglePlayPause() },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f)),
                ) {
                    Icon(
                        imageVector = if (castState.isPlayingOnTv) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (castState.isPlayingOnTv) "Pausar na TV" else "Reproduzir na TV",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }

                IconButton(
                    onClick = { BestCineCastController.stopCasting() },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFDC2626).copy(alpha = 0.85f)),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = "Parar transmissão",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }

    BestCineCastDialog(
        visible = showCastDialog,
        media = castState.activeMedia,
        onDismiss = { showCastDialog = false },
    )
}
