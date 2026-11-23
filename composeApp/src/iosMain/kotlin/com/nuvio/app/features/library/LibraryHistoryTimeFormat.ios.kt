package com.nuvio.app.features.library

import com.nuvio.app.core.time.EpisodeReleaseDatePlatform

internal actual fun formatLibraryHistoryTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val iso = EpisodeReleaseDatePlatform.localIsoDateAtEpochMs(epochMs) ?: return ""
    val today = EpisodeReleaseDatePlatform.localIsoDateAtEpochMs(EpisodeReleaseDatePlatform.nowEpochMs())
    return if (iso == today) "hoje" else iso
}
