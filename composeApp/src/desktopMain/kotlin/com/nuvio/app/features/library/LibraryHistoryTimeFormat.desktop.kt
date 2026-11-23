package com.nuvio.app.features.library

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal actual fun formatLibraryHistoryTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = epochMs }
    val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "hoje, $timeText"
        sameYear && dayDiff == 1 -> "ontem, $timeText"
        else -> {
            val dateText = SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(epochMs))
            "$dateText, $timeText"
        }
    }
}
