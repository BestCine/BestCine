package com.nuvio.app.features.addons

import com.nuvio.app.bestcine.BestCineAccountRepository
import com.nuvio.app.bestcine.BestCineHmacSigner

internal suspend fun fetchAddonResponseText(
    url: String,
    forceRefresh: Boolean = false,
): String {
    val bestCineHeaders = BestCineHmacSigner.buildHeadersForUrl(url)
    val headers = buildMap {
        if (forceRefresh) put("Cache-Control", "no-cache")
        putAll(bestCineHeaders)
    }
    val payload = try {
        if (headers.isNotEmpty()) {
            httpGetTextWithHeaders(
                url = url,
                headers = headers,
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
