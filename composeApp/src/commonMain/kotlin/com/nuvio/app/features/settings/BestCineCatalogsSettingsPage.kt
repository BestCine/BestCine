package com.nuvio.app.features.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioFocusBorder
import com.nuvio.app.features.home.HomeCatalogSettingsRepository

/**
 * Item de menu lateral exclusivo e destacado do BestCine ("Catálogos") nas Configurações
 * Desktop/Tablet, com identidade visual própria para deixar claro que são recursos exclusivos BestCine.
 */
@Composable
internal fun BestCineCatalogsSidebarItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Space.s12)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.listGap, vertical = NuvioTokens.Space.s4)
            .clip(shape)
            .then(
                if (selected) {
                    Modifier
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    BestCineBranding.PrimaryBlue.copy(alpha = 0.24f),
                                    BestCineBranding.PrimaryBlueBright.copy(alpha = 0.08f),
                                ),
                            ),
                            shape,
                        )
                        .border(1.dp, BestCineBranding.PrimaryBlueBright.copy(alpha = 0.48f), shape)
                } else {
                    Modifier
                },
            )
            .nuvioFocusBorder(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(BestCineBranding.PrimaryBlueBright),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier
                .size(tokens.icons.xl)
                .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                .background(
                    if (selected) {
                        BestCineBranding.PrimaryBlue.copy(alpha = 0.28f)
                    } else {
                        Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = if (selected) BestCineBranding.PrimaryBlueBright else tokens.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Catálogos",
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) Color.White else tokens.colors.textMuted,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Exclusivo BestCine",
                fontSize = 11.sp,
                color = if (selected) Color(0xFF93C5FD) else tokens.colors.textMuted.copy(alpha = 0.72f),
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (selected) {
                        BestCineBranding.PrimaryBlue.copy(alpha = 0.34f)
                    } else {
                        Color.White.copy(alpha = 0.06f)
                    },
                )
                .border(
                    width = 1.dp,
                    color = if (selected) {
                        BestCineBranding.PrimaryBlueBright.copy(alpha = 0.55f)
                    } else {
                        Color.White.copy(alpha = 0.12f)
                    },
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = "BC",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = if (selected) Color(0xFFDBEAFE) else tokens.colors.textMuted,
            )
        }
    }
}

/**
 * Cartão destacado na raiz das Configurações (Mobile) para levar direto aos Catálogos BestCine.
 */
@Composable
internal fun BestCineCatalogsFeaturedRootCard(
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(
            width = 1.2.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    BestCineBranding.PrimaryBlueBright.copy(alpha = 0.80f),
                    BestCineBranding.PrimaryBlue.copy(alpha = 0.35f),
                ),
            ),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            BestCineBranding.PrimaryBlue.copy(alpha = 0.28f),
                            Color(0xFF0E182E),
                        ),
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                BestCineBranding.PrimaryBlueBright,
                                BestCineBranding.PrimaryBlue,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Catálogos",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(BestCineBranding.PrimaryBlue.copy(alpha = 0.42f))
                            .border(
                                1.dp,
                                BestCineBranding.PrimaryBlueBright.copy(alpha = 0.65f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "BESTCINE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.7.sp,
                            color = Color(0xFFDBEAFE),
                        )
                    }
                }
                Text(
                    text = "Lançamentos, Sessão do Momento, Sazonal, por Duração e Streamings (Brasil)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFBFDBFE),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFF93C5FD),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Página dedicada "Catálogos BestCine" acessível em 1 clique pelo menu lateral/raiz destacado.
 */
internal fun LazyListScope.bestCineCatalogsSettingsContent(
    isTablet: Boolean,
    onOpenHomescreenOrder: () -> Unit,
) {
    item(key = "bestcine_catalogs_hero_banner") {
        val bannerShape = RoundedCornerShape(20.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(bannerShape)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            BestCineBranding.PrimaryBlue.copy(alpha = 0.30f),
                            BestCineBranding.PrimaryBlueBright.copy(alpha = 0.10f),
                            Color(0xFF0D1526),
                        ),
                    ),
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            BestCineBranding.PrimaryBlueBright.copy(alpha = 0.65f),
                            Color.White.copy(alpha = 0.12f),
                        ),
                    ),
                    shape = bannerShape,
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    BestCineBranding.PrimaryBlueBright,
                                    BestCineBranding.PrimaryBlue,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(BestCineBranding.PrimaryBlue.copy(alpha = 0.45f))
                                .border(
                                    1.dp,
                                    BestCineBranding.PrimaryBlueBright.copy(alpha = 0.65f),
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "EXCLUSIVO BESTCINE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.7.sp,
                                color = Color(0xFFDBEAFE),
                            )
                        }
                    }
                    Text(
                        text = "Catálogos Inteligentes (Brasil)",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Personalize as fileiras inteligentes exclusivas do BestCine exibidas na sua Tela Inicial.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFBFDBFE),
                    )
                }
            }
        }
    }

    item(key = "bestcine_catalogs_controls_section") {
        val settingsUiState by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
        SettingsSection(
            title = "Catálogos BestCine (Brasil)",
            isTablet = isTablet,
        ) {
            BestCineStreamingCatalogsSectionCard(
                isTablet = isTablet,
                config = settingsUiState.streamingCatalogConfig,
            )
        }
    }

    item(key = "bestcine_catalogs_reorder_shortcut") {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = "Organizar ordem na Tela Inicial",
                description = "Reordene, renomeie ou oculte todas as fileiras exibidas na Home.",
                icon = Icons.Default.Dashboard,
                isTablet = isTablet,
                onClick = onOpenHomescreenOrder,
            )
        }
    }
}
