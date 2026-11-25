package com.nuvio.app.bestcine.cast

import androidx.compose.runtime.Composable

internal expect object BestCineCastPlatform {
    val supportsSystemScreenCastShortcut: Boolean

    fun openSystemScreenCastSettings(): Boolean

    suspend fun discoverSsdpLocations(timeoutMs: Long = 2800L): List<String>

    fun startLocalStreamProxy(
        sourceUrl: String,
        headers: Map<String, String>,
        contentType: String,
        isLive: Boolean,
        targetHostIp: String?,
    ): String?

    fun hasLocalProxyReceivedRequest(): Boolean

    fun lastLocalProxyDiagnostic(): String?

    fun stopLocalStreamProxy()
}

@Composable
internal expect fun BestCineCastModalContainer(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
)
