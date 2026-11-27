package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState

internal actual val floatingNavigationGlowSupported: Boolean
    get() = false

@Composable
internal actual fun FloatingNavigationBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier,
    scrollState: NuvioNavBarScrollState?,
    hazeState: HazeState?,
    contentPadding: PaddingValues,
    compactSize: Boolean,
    glowEnabled: Boolean,
) {
    BestCineBottomNavigationBar(
        items = items,
        modifier = modifier,
        hazeState = hazeState,
        compactSize = compactSize,
    )
}
