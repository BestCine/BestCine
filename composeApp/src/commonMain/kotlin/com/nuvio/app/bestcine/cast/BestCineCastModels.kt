package com.nuvio.app.bestcine.cast

data class BestCineCastDevice(
    val id: String,
    val friendlyName: String,
    val manufacturer: String = "",
    val modelName: String = "",
    val hostIp: String = "",
    val locationUrl: String,
    val avTransportControlUrl: String,
    val renderingControlUrl: String? = null,
) {
    val subtitle: String
        get() = listOf(manufacturer, modelName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" • ")
            .ifBlank { hostIp.ifBlank { "Dispositivo DLNA / Smart TV" } }
}

data class BestCineCastMedia(
    val title: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val contentType: String = "video/mp4",
    val isLive: Boolean = false,
    val startPositionMs: Long = 0L,
    val durationMs: Long = 0L,
)

data class BestCineCastState(
    val isScanning: Boolean = false,
    val devices: List<BestCineCastDevice> = emptyList(),
    val activeDevice: BestCineCastDevice? = null,
    val activeMedia: BestCineCastMedia? = null,
    val isConnecting: Boolean = false,
    val isPlayingOnTv: Boolean = false,
    val volumePercent: Int = 50,
    val positionSec: Int = 0,
    val durationSec: Int = 0,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val isCasting: Boolean
        get() = activeDevice != null && activeMedia != null
}
