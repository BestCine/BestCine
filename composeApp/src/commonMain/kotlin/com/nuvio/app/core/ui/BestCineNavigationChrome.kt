package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.bestcine.BestCineBranding
import com.nuvio.app.features.settings.NavBarStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.bestcine_app_icon
import org.jetbrains.compose.resources.painterResource

internal val BestCineDesktopTopBarHeight = 64.dp

/**
 * Barra superior cinematográfica exclusiva do BestCine para Desktop.
 * Substitui a navegação lateral / pílula jelly do upstream por um cabeçalho full-width:
 * Marca BestCine à esquerda · Abas centrais com destaque luminoso azul · Perfil à direita.
 */
@Composable
internal fun BestCineDesktopTopBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier = Modifier,
    scrollState: NuvioNavBarScrollState? = null,
    hazeState: HazeState? = null,
    navBarStyle: NavBarStyle = NavBarStyle.ADAPTIVE,
    isHeroEnabled: Boolean = false,
    profileSwitcherOpen: Boolean = false,
    windowWidth: Dp? = null,
    onBrandClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    val isHomeSelected = items.first().selected
    val scrollOffset = scrollState?.totalScrollOffset ?: 0f
    val isScrolled = scrollOffset > 28f
    val needsSolidChrome = !isHomeSelected || !isHeroEnabled || isScrolled || profileSwitcherOpen

    val solidProgress by animateFloatAsState(
        targetValue = if (needsSolidChrome) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "bestcine_top_bar_solid_progress",
    )
    val heroHoverBoost by animateFloatAsState(
        targetValue = if (!needsSolidChrome && isHovered) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "bestcine_top_bar_hover_boost",
    )

    val topAlpha = (0.72f + 0.10f * heroHoverBoost) * (1f - solidProgress) + 0.95f * solidProgress
    val midAlpha = (0.32f + 0.12f * heroHoverBoost) * (1f - solidProgress) + 0.93f * solidProgress
    val bottomAlpha = 0.90f * solidProgress
    val baseChromeColor = Color(0xFF080E1B)
    val bottomBorderColor = BestCineBranding.PrimaryBlueBright.copy(alpha = 0.22f * solidProgress)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .nuvioTrackTopBarFocus()
            .hoverable(hoverSource),
    ) {
        val viewportWidth = windowWidth ?: maxWidth
        val showTabLabels = navBarStyle != NavBarStyle.COMPACT && viewportWidth >= 820.dp
        val showBrandWordmark = viewportWidth >= 680.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to baseChromeColor.copy(alpha = topAlpha),
                            0.58f to baseChromeColor.copy(alpha = midAlpha),
                            1.0f to baseChromeColor.copy(alpha = bottomAlpha),
                        ),
                    ),
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = statusBarTop)
                    .height(BestCineDesktopTopBarHeight)
                    .padding(horizontal = if (viewportWidth >= 1100.dp) 24.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Esquerda: Marca BestCine
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .then(
                            if (onBrandClick != null) {
                                Modifier.clickable(onClick = onBrandClick)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(BestCineBranding.PrimaryBlue.copy(alpha = 0.18f))
                            .border(
                                width = 1.dp,
                                color = BestCineBranding.PrimaryBlueBright.copy(alpha = 0.42f),
                                shape = RoundedCornerShape(9.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(Res.drawable.bestcine_app_icon),
                            contentDescription = BestCineBranding.APP_NAME,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(7.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    if (showBrandWordmark) {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold)) {
                                    append("Best")
                                }
                                withStyle(
                                    SpanStyle(
                                        color = BestCineBranding.PrimaryBlueBright,
                                        fontWeight = FontWeight.ExtraBold,
                                    ),
                                ) {
                                    append("Cine")
                                }
                            },
                            fontSize = 18.sp,
                            letterSpacing = (-0.4).sp,
                            maxLines = 1,
                        )
                    }
                }

                // Centro: Abas principais de navegação
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(
                        space = if (showTabLabels) 6.dp else 4.dp,
                        alignment = Alignment.CenterHorizontally,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEach { item ->
                        BestCineDesktopTopBarTab(
                            item = item,
                            showLabel = showTabLabels,
                        )
                    }
                }

                // Direita: Perfil ativo / seletor de perfil
                if (trailingContent != null) {
                    Box(contentAlignment = Alignment.CenterEnd) {
                        trailingContent()
                    }
                }
            }

            // Linha inferior luminosa sutil quando a barra está sólida/fosca
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(bottomBorderColor),
            )
        }
    }
}

@Composable
private fun BestCineDesktopTopBarTab(
    item: FloatingNavigationItem,
    showLabel: Boolean,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(11.dp)
    val contentColor by animateColorAsState(
        targetValue = when {
            item.selected -> Color.White
            isHovered -> Color(0xFFE2E8F0)
            else -> Color(0xFF94A3B8)
        },
        animationSpec = tween(160),
        label = "bestcine_top_tab_fg",
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            item.selected -> BestCineBranding.PrimaryBlueBright
            isHovered -> Color(0xFFCBD5E1)
            else -> Color(0xFF8A99B2)
        },
        animationSpec = tween(160),
        label = "bestcine_top_tab_icon",
    )

    val containerModifier = when {
        item.selected -> Modifier
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        BestCineBranding.PrimaryBlue.copy(alpha = 0.24f),
                        BestCineBranding.PrimaryBlueBright.copy(alpha = 0.12f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = BestCineBranding.PrimaryBlueBright.copy(alpha = 0.52f),
                shape = shape,
            )
        isHovered -> Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
        else -> Modifier.clip(shape)
    }

    Box(
        modifier = containerModifier
            .height(40.dp)
            .hoverable(hoverSource)
            .nuvioFocusBorder(shape)
            .selectable(
                selected = item.selected,
                onClick = item.onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            )
            .padding(horizontal = if (showLabel) 14.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                item.content != null -> item.content.invoke(item.onClick)
                item.icon != null -> Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = iconColor,
                    modifier = Modifier.size(19.dp),
                )
                item.drawable != null -> Icon(
                    painter = painterResource(item.drawable),
                    contentDescription = item.label,
                    tint = iconColor,
                    modifier = Modifier.size(19.dp),
                )
            }
            if (showLabel && item.label.isNotBlank()) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (item.selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .width(22.dp)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(BestCineBranding.PrimaryBlueBright),
            )
        }
    }
}

/**
 * Barra inferior edge-to-edge exclusiva do BestCine para Android e iOS.
 * Substitui a pílula flutuante jelly do upstream por um dock translúcido azul-marinho cinema
 * com indicador luminoso superior azul e labels sempre nítidas.
 */
@Composable
internal fun BestCineBottomNavigationBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    compactSize: Boolean = false,
) {
    if (items.isEmpty()) return
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val dockHeight = if (compactSize) 58.dp else 64.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState) {
                        blurRadius = 22.dp
                    }
                } else {
                    Modifier
                },
            )
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xEB0A1120),
                        Color(0xFA060A13),
                    ),
                ),
            ),
    ) {
        // Linha superior luminosa sutil
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.06f),
                            BestCineBranding.PrimaryBlueBright.copy(alpha = 0.30f),
                            Color.White.copy(alpha = 0.06f),
                        ),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = navBarBottom)
                .height(dockHeight)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val iconTint by animateColorAsState(
                    targetValue = if (item.selected) BestCineBranding.PrimaryBlueBright else Color(0xFF8B9AB3),
                    animationSpec = tween(160),
                    label = "bestcine_bottom_icon_tint",
                )
                val labelColor by animateColorAsState(
                    targetValue = if (item.selected) Color.White else Color(0xFF7D8DA7),
                    animationSpec = tween(160),
                    label = "bestcine_bottom_label_color",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = item.selected,
                            onClick = item.onClick,
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // Indicador superior luminoso do item ativo
                    if (item.selected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .width(26.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            BestCineBranding.PrimaryBlue,
                                            BestCineBranding.PrimaryBlueBright,
                                        ),
                                    ),
                                ),
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(42.dp)
                                .height(28.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (item.selected) {
                                        BestCineBranding.PrimaryBlue.copy(alpha = 0.20f)
                                    } else {
                                        Color.Transparent
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                item.content != null -> item.content.invoke(item.onClick)
                                item.icon != null -> Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    tint = iconTint,
                                    modifier = Modifier.size(if (compactSize) 20.dp else 22.dp),
                                )
                                item.drawable != null -> Icon(
                                    painter = painterResource(item.drawable),
                                    contentDescription = item.label,
                                    tint = iconTint,
                                    modifier = Modifier.size(if (compactSize) 20.dp else 22.dp),
                                )
                            }
                        }
                        if (item.label.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.label,
                                fontSize = 10.5.sp,
                                lineHeight = 12.sp,
                                fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = labelColor,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
