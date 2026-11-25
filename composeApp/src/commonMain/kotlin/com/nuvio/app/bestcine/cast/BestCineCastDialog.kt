package com.nuvio.app.bestcine.cast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.bestcine.BestCineBranding
import com.nuvio.app.core.ui.NuvioLoadingIndicator

@Composable
fun BestCineCastDialog(
    visible: Boolean,
    media: BestCineCastMedia?,
    onPauseLocalPlayback: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val castState by BestCineCastController.state.collectAsStateWithLifecycle()

    LaunchedEffect(visible) {
        if (visible && !castState.isCasting) {
            BestCineCastController.startDiscovery()
        }
    }

    val cardShape = RoundedCornerShape(24.dp)
    val cardBgBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF131D33),
            Color(0xFF0B1120),
            Color(0xFF080D18),
        ),
    )
    val cardBorderBrush = Brush.linearGradient(
        colors = listOf(
            BestCineBranding.PrimaryBlueBright.copy(alpha = 0.55f),
            Color.White.copy(alpha = 0.14f),
            BestCineBranding.PrimaryBlue.copy(alpha = 0.25f),
        ),
    )

    BestCineCastModalContainer(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .border(1.2.dp, cardBorderBrush, cardShape),
            shape = cardShape,
            color = Color.Transparent,
            shadowElevation = 28.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardBgBrush),
            ) {
                // ============================================================
                // CABEÇALHO PREMIUM COM GLOW AZUL BESTCINE
                // ============================================================
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    BestCineBranding.PrimaryBlue.copy(alpha = 0.22f),
                                    BestCineBranding.PrimaryBlueBright.copy(alpha = 0.08f),
                                    Color.Transparent,
                                ),
                            ),
                        )
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Ícone principal em squircle com gradiente BestCine
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            BestCineBranding.PrimaryBlueBright,
                                            BestCineBranding.PrimaryBlue,
                                        ),
                                    ),
                                )
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.28f),
                                    RoundedCornerShape(14.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (castState.isCasting) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(23.dp),
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "Transmitir para TV",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                castState.isCasting -> Color(0xFF22C55E)
                                                castState.isScanning -> BestCineBranding.PrimaryBlueBright
                                                castState.devices.isNotEmpty() -> Color(0xFF38BDF8)
                                                else -> Color(0xFF64748B)
                                            },
                                        ),
                                )
                                Text(
                                    text = when {
                                        castState.isCasting -> "Conectado na rede local"
                                        castState.isScanning -> "Buscando Smart TVs no Wi-Fi..."
                                        castState.devices.size == 1 -> "1 Smart TV pronta na rede"
                                        castState.devices.size > 1 -> "${castState.devices.size} Smart TVs prontas na rede"
                                        else -> "DLNA • Rede Wi-Fi local"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF94A3B8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // Botão circular de atualizar
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                                .clickable(enabled = !castState.isScanning) {
                                    BestCineCastController.startDiscovery()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (castState.isScanning) {
                                NuvioLoadingIndicator(size = 16.dp, color = BestCineBranding.PrimaryBlueBright)
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Procurar TVs",
                                    tint = Color(0xFFE2E8F0),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        // Botão circular de fechar
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Fechar",
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

                // ============================================================
                // CORPO DO MODAL
                // ============================================================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Faixa do conteúdo selecionado ("Pronto para enviar")
                    if (media != null && media.title.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.045f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = if (media.isLive) Icons.Rounded.LiveTv else Icons.Rounded.Movie,
                                    contentDescription = null,
                                    tint = if (media.isLive) Color(0xFFF87171) else BestCineBranding.PrimaryBlueBright,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = media.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFF1F5F9),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (media.isLive) {
                                                Color(0xFFEF4444).copy(alpha = 0.20f)
                                            } else {
                                                BestCineBranding.PrimaryBlue.copy(alpha = 0.22f)
                                            },
                                        )
                                        .border(
                                            1.dp,
                                            if (media.isLive) {
                                                Color(0xFFEF4444).copy(alpha = 0.45f)
                                            } else {
                                                BestCineBranding.PrimaryBlueBright.copy(alpha = 0.40f)
                                            },
                                            RoundedCornerShape(6.dp),
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = if (media.isLive) "AO VIVO" else "VÍDEO",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.6.sp,
                                        color = if (media.isLive) Color(0xFFFCA5A5) else Color(0xFF93C5FD),
                                    )
                                }
                            }
                        }
                    }

                    // Painel de controle remoto ativo quando já está transmitindo
                    val activeDevice = castState.activeDevice
                    if (castState.isCasting && activeDevice != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            BestCineBranding.PrimaryBlue.copy(alpha = 0.24f),
                                            BestCineBranding.PrimaryBlue.copy(alpha = 0.10f),
                                        ),
                                    ),
                                )
                                .border(
                                    1.dp,
                                    BestCineBranding.PrimaryBlueBright.copy(alpha = 0.45f),
                                    RoundedCornerShape(18.dp),
                                )
                                .padding(14.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(BestCineBranding.PrimaryBlue.copy(alpha = 0.30f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Tv,
                                            contentDescription = null,
                                            tint = Color(0xFF93C5FD),
                                            modifier = Modifier.size(21.dp),
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = activeDevice.friendlyName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = castState.statusMessage ?: "Transmitindo na TV",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF93C5FD),
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(Color(0xFF22C55E).copy(alpha = 0.18f))
                                            .border(1.dp, Color(0xFF22C55E).copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                                            .padding(horizontal = 9.dp, vertical = 3.dp),
                                    ) {
                                        Text(
                                            text = "ATIVA",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF86EFAC),
                                        )
                                    }
                                }

                                // Controles táteis de reprodução + volume
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (castState.activeMedia?.isLive != true) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.08f))
                                                .clickable { BestCineCastController.seekBySeconds(-15) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Replay10,
                                                contentDescription = "Voltar 15 segundos",
                                                tint = Color.White,
                                                modifier = Modifier.size(21.dp),
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.linearGradient(
                                                    colors = listOf(
                                                        BestCineBranding.PrimaryBlueBright,
                                                        BestCineBranding.PrimaryBlue,
                                                    ),
                                                ),
                                            )
                                            .border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape)
                                            .clickable { BestCineCastController.togglePlayPause() },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = if (castState.isPlayingOnTv) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = if (castState.isPlayingOnTv) "Pausar na TV" else "Reproduzir na TV",
                                            tint = Color.White,
                                            modifier = Modifier.size(28.dp),
                                        )
                                    }

                                    if (castState.activeMedia?.isLive != true) {
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.08f))
                                                .clickable { BestCineCastController.seekBySeconds(15) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Forward10,
                                                contentDescription = "Avançar 15 segundos",
                                                tint = Color.White,
                                                modifier = Modifier.size(21.dp),
                                            )
                                        }
                                    }

                                    if (activeDevice.renderingControlUrl != null) {
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(Color.Black.copy(alpha = 0.28f))
                                                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            IconButton(
                                                onClick = { BestCineCastController.adjustVolume(-10) },
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Rounded.VolumeDown,
                                                    contentDescription = "Diminuir volume",
                                                    tint = Color(0xFFE2E8F0),
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                            Text(
                                                text = "${castState.volumePercent}%",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            IconButton(
                                                onClick = { BestCineCastController.adjustVolume(10) },
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                                                    contentDescription = "Aumentar volume",
                                                    tint = Color(0xFFE2E8F0),
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                Button(
                                    onClick = { BestCineCastController.stopCasting() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFDC2626),
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Parar transmissão na TV", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    // Aviso de erro estilizado
                    castState.errorMessage?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF7F1D1D).copy(alpha = 0.36f),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.50f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = Color(0xFFF87171),
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFECACA),
                                )
                            }
                        }
                    }

                    // Título da seção de dispositivos
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "DISPOSITIVOS NA REDE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp,
                            color = Color(0xFF64748B),
                        )
                        if (castState.devices.isNotEmpty()) {
                            Text(
                                text = "Toque para enviar",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF64748B),
                            )
                        }
                    }

                    // Lista de TVs DLNA encontradas
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (castState.isConnecting) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = BestCineBranding.PrimaryBlue.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, BestCineBranding.PrimaryBlueBright.copy(alpha = 0.30f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 20.dp, horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    NuvioLoadingIndicator(size = 22.dp, color = BestCineBranding.PrimaryBlueBright)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = castState.statusMessage ?: "Enviando vídeo para a TV...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        } else if (castState.devices.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color.White.copy(alpha = 0.035f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (castState.isScanning) {
                                        NuvioLoadingIndicator(size = 26.dp, color = BestCineBranding.PrimaryBlueBright)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Procurando Smart TVs no Wi-Fi...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.06f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Tv,
                                                contentDescription = null,
                                                tint = Color(0xFF64748B),
                                                modifier = Modifier.size(22.dp),
                                            )
                                        }
                                        Text(
                                            text = "Nenhuma Smart TV encontrada",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = "Ligue sua TV (Samsung, LG, Sony, Philips, TCL...) no mesmo Wi-Fi e toque no botão de atualizar acima.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF94A3B8),
                                        )
                                    }
                                }
                            }
                        } else {
                            castState.devices.forEach { device ->
                                val isCurrent = activeDevice?.id == device.id
                                val itemShape = RoundedCornerShape(16.dp)
                                Surface(
                                    onClick = {
                                        val targetMedia = media ?: return@Surface
                                        onPauseLocalPlayback()
                                        BestCineCastController.castMedia(device, targetMedia)
                                    },
                                    enabled = media != null && !castState.isConnecting,
                                    shape = itemShape,
                                    color = if (isCurrent) {
                                        BestCineBranding.PrimaryBlue.copy(alpha = 0.20f)
                                    } else {
                                        Color(0xFF141E33)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (isCurrent) {
                                            BestCineBranding.PrimaryBlueBright.copy(alpha = 0.75f)
                                        } else {
                                            Color.White.copy(alpha = 0.09f)
                                        },
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        // Ícone da TV em container próprio
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    if (isCurrent) {
                                                        BestCineBranding.PrimaryBlue.copy(alpha = 0.30f)
                                                    } else {
                                                        Color.White.copy(alpha = 0.07f)
                                                    },
                                                )
                                                .border(
                                                    1.dp,
                                                    if (isCurrent) {
                                                        BestCineBranding.PrimaryBlueBright.copy(alpha = 0.50f)
                                                    } else {
                                                        Color.White.copy(alpha = 0.08f)
                                                    },
                                                    RoundedCornerShape(12.dp),
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Tv,
                                                contentDescription = null,
                                                tint = if (isCurrent) Color(0xFF93C5FD) else Color(0xFFCBD5E1),
                                                modifier = Modifier.size(22.dp),
                                            )
                                        }

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = device.friendlyName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = device.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF94A3B8),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }

                                        // Botão pill destacado em vez de texto plano
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    if (isCurrent) {
                                                        Color(0xFF22C55E).copy(alpha = 0.20f)
                                                    } else {
                                                        BestCineBranding.PrimaryBlue
                                                    },
                                                )
                                                .border(
                                                    1.dp,
                                                    if (isCurrent) {
                                                        Color(0xFF22C55E).copy(alpha = 0.45f)
                                                    } else {
                                                        BestCineBranding.PrimaryBlueBright.copy(alpha = 0.55f)
                                                    },
                                                    RoundedCornerShape(10.dp),
                                                )
                                                .padding(horizontal = 13.dp, vertical = 7.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = if (isCurrent) "Conectada" else "Transmitir",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (isCurrent) Color(0xFF86EFAC) else Color.White,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Atalho nativo do Android para Chromecast antigo / Smart View / Espelhamento
                    if (BestCineCastController.supportsSystemScreenCastShortcut) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                        Surface(
                            onClick = { BestCineCastController.openSystemScreenCastSettings() },
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.04f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(BestCineBranding.PrimaryBlue.copy(alpha = 0.20f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ScreenShare,
                                        contentDescription = null,
                                        tint = Color(0xFF93C5FD),
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Espelhar tela (Chromecast / Smart View)",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "Para Chromecasts antigos e transmissão sem fio",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF94A3B8),
                                    )
                                }
                            }
                        }
                    }

                    // Rodapé discreto de ajuda
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Wifi,
                            contentDescription = null,
                            tint = Color(0xFF475569),
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "TV e aparelho precisam estar na mesma rede Wi-Fi",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B),
                        )
                    }
                }
            }
        }
    }
}
