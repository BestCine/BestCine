package com.nuvio.app.features.addons

import com.nuvio.app.bestcine.BestCineAccountRepository

internal suspend fun fetchAddonResponseText(
    url: String,
    forceRefresh: Boolean = false,
): String {
    val payload = try {
        if (forceRefresh) {
            httpGetTextWithHeaders(
                url = url,
                headers = mapOf("Cache-Control" to "no-cache"),
            )
        } else {
            httpGetText(url)
        }
    } catch (error: Throwable) {
        BestCineAccountRepository.inspectAddonExceptionForBan(url, error)
        throw error
    }
    BestCineAccountRepository.inspectAddonResponseForBan(url, payload)
    return payload
}
