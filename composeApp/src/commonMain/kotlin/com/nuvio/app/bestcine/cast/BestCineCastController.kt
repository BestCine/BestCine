package com.nuvio.app.bestcine.cast

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Controlador universal de transmissão DLNA / UPnP do BestCine.
 * Funciona para Filmes, Séries e TV Ao Vivo em Smart TVs (Samsung antigas e novas,
 * LG, Sony, Philips, TCL, consoles e TV Boxes) na mesma rede Wi-Fi, com proxy HTTP local
 * com DoH (DNS-over-HTTPS) que resolve bloqueios de operadora, TLS antigo e HLS -> MPEG-TS.
 */
object BestCineCastController {
    private val log = Logger.withTag("BestCineCast")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient by lazy { createApiHttpClient() }

    private val _state = MutableStateFlow(BestCineCastState())
    val state: StateFlow<BestCineCastState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var lastKnownPositionSec: Int = 0

    private data class SoapResult(
        val ok: Boolean,
        val statusCode: Int = 0,
        val upnpErrorCode: String? = null,
        val upnpErrorDesc: String? = null,
        val exceptionMessage: String? = null,
    ) {
        fun diagnosticSummary(): String {
            val parts = mutableListOf<String>()
            if (statusCode > 0) parts.add("HTTP $statusCode")
            if (!upnpErrorCode.isNullOrBlank()) {
                val desc = upnpErrorDesc?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                parts.add("UPnP $upnpErrorCode$desc")
            } else if (!exceptionMessage.isNullOrBlank()) {
                parts.add(exceptionMessage)
            }
            return parts.joinToString(" • ")
        }
    }

    val supportsSystemScreenCastShortcut: Boolean
        get() = BestCineCastPlatform.supportsSystemScreenCastShortcut

    fun openSystemScreenCastSettings(): Boolean =
        BestCineCastPlatform.openSystemScreenCastSettings()

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun startDiscovery() {
        scanJob?.cancel()
        _state.update { it.copy(isScanning = true, errorMessage = null) }
        scanJob = scope.launch {
            runCatching {
                val locations = BestCineCastPlatform.discoverSsdpLocations(timeoutMs = 2800L)
                val resolved = locations.distinct().map { locationUrl ->
                    async { fetchDeviceDescription(locationUrl) }
                }.awaitAll()
                    .filterNotNull()
                    .distinctBy { it.avTransportControlUrl }
                    .sortedBy { it.friendlyName.lowercase() }

                _state.update { current ->
                    current.copy(
                        isScanning = false,
                        devices = resolved,
                        statusMessage = if (resolved.isEmpty()) {
                            "Nenhuma Smart TV DLNA encontrada neste Wi-Fi."
                        } else {
                            null
                        },
                    )
                }
            }.onFailure { error ->
                log.w(error) { "Falha na descoberta DLNA" }
                _state.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = "Não foi possível procurar dispositivos na rede Wi-Fi.",
                    )
                }
            }
        }
    }

    fun castMedia(device: BestCineCastDevice, media: BestCineCastMedia) {
        scope.launch {
            _state.update {
                it.copy(
                    isConnecting = true,
                    errorMessage = null,
                    statusMessage = "Conectando a ${device.friendlyName}...",
                )
            }

            runCatching {
                val dlnaMimeType = inferDlnaMimeType(media.url, media.contentType, media.isLive)
                val isHlsOrLive = media.isLive ||
                    media.url.substringBefore('?').lowercase().endsWith(".m3u8") ||
                    media.contentType.contains("mpegurl", ignoreCase = true)

                // Sempre tenta proxy local primeiro (resolve DoH, TLS 1.3 para TVs antigas, headers e HLS->TS)
                val proxiedUrl = BestCineCastPlatform.startLocalStreamProxy(
                    sourceUrl = media.url,
                    headers = media.headers,
                    contentType = dlnaMimeType,
                    isLive = isHlsOrLive,
                    targetHostIp = device.hostIp.ifBlank { null },
                )
                val usingProxy = !proxiedUrl.isNullOrBlank()
                val playbackUrl = proxiedUrl ?: media.url

                log.i {
                    "DLNA cast -> device=${device.friendlyName} (${device.hostIp}) " +
                        "mime=$dlnaMimeType live=$isHlsOrLive proxy=$usingProxy url=$playbackUrl"
                }

                // 1. Limpa reprodução anterior na TV (ignora erro se já estiver parada)
                runCatching {
                    sendAvTransportAction(
                        controlUrl = device.avTransportControlUrl,
                        action = "Stop",
                        bodyXml = "<InstanceID>0</InstanceID>",
                    )
                }

                // 2. Define URI + metadados DIDL-Lite com fallback automático
                var setUriResult = trySetAvTransportUri(
                    controlUrl = device.avTransportControlUrl,
                    title = media.title,
                    uri = playbackUrl,
                    mimeType = dlnaMimeType,
                    isLive = isHlsOrLive,
                )

                // Se usou proxy e a TV sequer alcançou o proxy (ex.: Firewall do Windows bloqueando),
                // tenta enviar o link direto como plano B antes de falhar!
                var proxyBlockedByFirewall = usingProxy && !BestCineCastPlatform.hasLocalProxyReceivedRequest()
                if (!setUriResult.ok && proxyBlockedByFirewall && !isHlsOrLive && media.headers.isEmpty()) {
                    log.w { "Proxy local não recebeu conexão da TV (possível Firewall); tentando link direto..." }
                    val directResult = trySetAvTransportUri(
                        controlUrl = device.avTransportControlUrl,
                        title = media.title,
                        uri = media.url,
                        mimeType = dlnaMimeType,
                        isLive = false,
                    )
                    if (directResult.ok) {
                        BestCineCastPlatform.stopLocalStreamProxy()
                        setUriResult = directResult
                        proxyBlockedByFirewall = false
                    }
                }

                if (!setUriResult.ok) {
                    val diag = setUriResult.diagnosticSummary()
                    val proxyDiag = BestCineCastPlatform.lastLocalProxyDiagnostic()
                    val extra = when {
                        proxyBlockedByFirewall ->
                            " A TV não conseguiu acessar o proxy local ($playbackUrl). Libere o BestCine no Firewall do Windows (Rede Privada)."
                        !proxyDiag.isNullOrBlank() ->
                            " Detalhe do proxy: $proxyDiag."
                        else -> ""
                    }
                    error(
                        "A TV recusou receber o vídeo" +
                            (if (diag.isNotBlank()) " ($diag)" else "") +
                            ".$extra",
                    )
                }

                // 3. Pequena pausa para TVs Samsung mais antigas prepararem o transporte antes do Play
                delay(300L)
                var playResult = sendAvTransportAction(
                    controlUrl = device.avTransportControlUrl,
                    action = "Play",
                    bodyXml = "<InstanceID>0</InstanceID><Speed>1</Speed>",
                )
                if (!playResult.ok) {
                    delay(600L)
                    playResult = sendAvTransportAction(
                        controlUrl = device.avTransportControlUrl,
                        action = "Play",
                        bodyXml = "<InstanceID>0</InstanceID><Speed>1</Speed>",
                    )
                }
                if (!playResult.ok) {
                    val diag = playResult.diagnosticSummary()
                    val proxyDiag = BestCineCastPlatform.lastLocalProxyDiagnostic()
                    val extra = when {
                        usingProxy && !BestCineCastPlatform.hasLocalProxyReceivedRequest() ->
                            " A TV não conseguiu alcançar o proxy local deste PC ($playbackUrl) — libere o BestCine no Firewall do Windows (Rede Privada)."
                        !proxyDiag.isNullOrBlank() ->
                            " Detalhe do proxy: $proxyDiag."
                        else -> ""
                    }
                    error(
                        "A TV recebeu o link, mas não conseguiu iniciar a reprodução" +
                            (if (diag.isNotBlank()) " ($diag)" else "") +
                            ".$extra",
                    )
                }

                lastKnownPositionSec = (media.startPositionMs / 1000L).toInt().coerceAtLeast(0)
                if (!media.isLive && lastKnownPositionSec >= 5) {
                    delay(1200L)
                    runCatching {
                        sendAvTransportAction(
                            controlUrl = device.avTransportControlUrl,
                            action = "Seek",
                            bodyXml = """
                                <InstanceID>0</InstanceID>
                                <Unit>REL_TIME</Unit>
                                <Target>${formatDlnaTime(lastKnownPositionSec)}</Target>
                            """.trimIndent(),
                        )
                    }
                }

                val currentVol = device.renderingControlUrl?.let { fetchCurrentVolume(it) } ?: 50
                _state.update {
                    it.copy(
                        activeDevice = device,
                        activeMedia = media,
                        isConnecting = false,
                        isPlayingOnTv = true,
                        volumePercent = currentVol,
                        statusMessage = "Transmitindo para ${device.friendlyName}",
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                log.w(error) { "Erro ao transmitir para ${device.friendlyName}" }
                BestCineCastPlatform.stopLocalStreamProxy()
                _state.update {
                    it.copy(
                        isConnecting = false,
                        activeDevice = null,
                        activeMedia = null,
                        isPlayingOnTv = false,
                        statusMessage = null,
                        errorMessage = error.message ?: "Não foi possível iniciar a transmissão na TV.",
                    )
                }
            }
        }
    }

    private suspend fun trySetAvTransportUri(
        controlUrl: String,
        title: String,
        uri: String,
        mimeType: String,
        isLive: Boolean,
    ): SoapResult {
        val metadataCandidates = buildList {
            add(buildDidlLiteMetadata(title, uri, mimeType, isLive))
            if (mimeType != "video/mp4") {
                add(buildDidlLiteMetadata(title, uri, "video/mp4", isLive))
            }
            add("") // Fallback sem DIDL-Lite (aceito por vários modelos Samsung/LG antigos)
        }
        var lastResult = SoapResult(ok = false)
        for (didl in metadataCandidates) {
            lastResult = sendAvTransportAction(
                controlUrl = controlUrl,
                action = "SetAVTransportURI",
                bodyXml = """
                    <InstanceID>0</InstanceID>
                    <CurrentURI>${escapeXml(uri)}</CurrentURI>
                    <CurrentURIMetaData>${escapeXml(didl)}</CurrentURIMetaData>
                """.trimIndent(),
            )
            if (lastResult.ok) return lastResult
            log.w { "SetAVTransportURI falhou (${lastResult.diagnosticSummary()}); tentando próximo perfil de metadados..." }
        }
        return lastResult
    }

    fun togglePlayPause() {
        val current = _state.value
        val device = current.activeDevice ?: return
        val nextPlaying = !current.isPlayingOnTv
        scope.launch {
            val action = if (nextPlaying) "Play" else "Pause"
            val body = if (nextPlaying) {
                "<InstanceID>0</InstanceID><Speed>1</Speed>"
            } else {
                "<InstanceID>0</InstanceID>"
            }
            val result = sendAvTransportAction(device.avTransportControlUrl, action, body)
            if (result.ok) {
                _state.update {
                    it.copy(
                        isPlayingOnTv = nextPlaying,
                        statusMessage = if (nextPlaying) {
                            "Transmitindo para ${device.friendlyName}"
                        } else {
                            "Pausado em ${device.friendlyName}"
                        },
                    )
                }
            }
        }
    }

    fun seekBySeconds(deltaSec: Int) {
        val current = _state.value
        val device = current.activeDevice ?: return
        if (current.activeMedia?.isLive == true) return
        scope.launch {
            val tvPos = fetchCurrentPositionSeconds(device.avTransportControlUrl) ?: lastKnownPositionSec
            val targetSec = (tvPos + deltaSec).coerceAtLeast(0)
            val result = sendAvTransportAction(
                controlUrl = device.avTransportControlUrl,
                action = "Seek",
                bodyXml = """
                    <InstanceID>0</InstanceID>
                    <Unit>REL_TIME</Unit>
                    <Target>${formatDlnaTime(targetSec)}</Target>
                """.trimIndent(),
            )
            if (result.ok) {
                lastKnownPositionSec = targetSec
            }
        }
    }

    fun adjustVolume(deltaPercent: Int) {
        val current = _state.value
        val device = current.activeDevice ?: return
        val rcUrl = device.renderingControlUrl ?: return
        val nextVol = (current.volumePercent + deltaPercent).coerceIn(0, 100)
        scope.launch {
            val result = sendSoapAction(
                controlUrl = rcUrl,
                serviceType = RENDERING_CONTROL_SERVICE,
                action = "SetVolume",
                bodyXml = """
                    <InstanceID>0</InstanceID>
                    <Channel>Master</Channel>
                    <DesiredVolume>$nextVol</DesiredVolume>
                """.trimIndent(),
            )
            if (result.ok) {
                _state.update { it.copy(volumePercent = nextVol) }
            }
        }
    }

    fun stopCasting() {
        val device = _state.value.activeDevice
        scope.launch {
            if (device != null) {
                runCatching {
                    sendAvTransportAction(
                        controlUrl = device.avTransportControlUrl,
                        action = "Stop",
                        bodyXml = "<InstanceID>0</InstanceID>",
                    )
                }
            }
            BestCineCastPlatform.stopLocalStreamProxy()
            lastKnownPositionSec = 0
            _state.update {
                it.copy(
                    activeDevice = null,
                    activeMedia = null,
                    isConnecting = false,
                    isPlayingOnTv = false,
                    statusMessage = null,
                    errorMessage = null,
                )
            }
        }
    }

    private suspend fun fetchDeviceDescription(locationUrl: String): BestCineCastDevice? = runCatching {
        val response = httpClient.get(locationUrl)
        if (!response.status.isSuccess()) return@runCatching null
        val xml = response.bodyAsText()
        val avTransportPath = extractServiceControlUrl(xml, "AVTransport") ?: return@runCatching null
        val renderingPath = extractServiceControlUrl(xml, "RenderingControl")
        val urlBase = extractXmlTag(xml, "URLBase")?.trim()?.takeIf { it.isNotBlank() } ?: locationUrl
        val friendlyName = extractXmlTag(xml, "friendlyName")?.trim()?.takeIf { it.isNotBlank() }
            ?: extractHostFromUrl(locationUrl)
            ?: "Smart TV"
        val manufacturer = extractXmlTag(xml, "manufacturer")?.trim().orEmpty()
        val modelName = extractXmlTag(xml, "modelName")?.trim().orEmpty()
        val udn = extractXmlTag(xml, "UDN")?.trim()?.takeIf { it.isNotBlank() } ?: locationUrl
        val controlUrl = resolveRelativeUrl(urlBase, avTransportPath) ?: return@runCatching null
        val renderingUrl = renderingPath?.let { resolveRelativeUrl(urlBase, it) }

        BestCineCastDevice(
            id = udn,
            friendlyName = decodeXmlEntities(friendlyName),
            manufacturer = decodeXmlEntities(manufacturer),
            modelName = decodeXmlEntities(modelName),
            hostIp = extractHostFromUrl(locationUrl).orEmpty(),
            locationUrl = locationUrl,
            avTransportControlUrl = controlUrl,
            renderingControlUrl = renderingUrl,
        )
    }.getOrNull()

    private suspend fun sendAvTransportAction(
        controlUrl: String,
        action: String,
        bodyXml: String,
    ): SoapResult = sendSoapAction(
        controlUrl = controlUrl,
        serviceType = AV_TRANSPORT_SERVICE,
        action = action,
        bodyXml = bodyXml,
    )

    private suspend fun sendSoapAction(
        controlUrl: String,
        serviceType: String,
        action: String,
        bodyXml: String,
    ): SoapResult = runCatching {
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:$action xmlns:u="$serviceType">
                  $bodyXml
                </u:$action>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val resp = httpClient.post(controlUrl) {
            contentType(ContentType.Text.Xml.withParameter("charset", "utf-8"))
            header("SOAPACTION", "\"$serviceType#$action\"")
            setBody(envelope)
        }
        val status = resp.status.value
        if (resp.status.isSuccess()) {
            SoapResult(ok = true, statusCode = status)
        } else {
            val body = runCatching { resp.bodyAsText() }.getOrDefault("")
            val errCode = extractXmlTag(body, "errorCode")?.trim()
            val errDesc = extractXmlTag(body, "errorDescription")?.trim()
            SoapResult(
                ok = false,
                statusCode = status,
                upnpErrorCode = errCode,
                upnpErrorDesc = errDesc,
            )
        }
    }.getOrElse { ex ->
        SoapResult(ok = false, exceptionMessage = ex.message)
    }

    private suspend fun fetchCurrentPositionSeconds(controlUrl: String): Int? = runCatching {
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:GetPositionInfo xmlns:u="$AV_TRANSPORT_SERVICE">
                  <InstanceID>0</InstanceID>
                </u:GetPositionInfo>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val resp = httpClient.post(controlUrl) {
            contentType(ContentType.Text.Xml.withParameter("charset", "utf-8"))
            header("SOAPACTION", "\"$AV_TRANSPORT_SERVICE#GetPositionInfo\"")
            setBody(envelope)
        }
        if (!resp.status.isSuccess()) return@runCatching null
        val relTime = extractXmlTag(resp.bodyAsText(), "RelTime") ?: return@runCatching null
        parseDlnaTimeToSeconds(relTime)
    }.getOrNull()

    private suspend fun fetchCurrentVolume(controlUrl: String): Int? = runCatching {
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:GetVolume xmlns:u="$RENDERING_CONTROL_SERVICE">
                  <InstanceID>0</InstanceID>
                  <Channel>Master</Channel>
                </u:GetVolume>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val resp = httpClient.post(controlUrl) {
            contentType(ContentType.Text.Xml.withParameter("charset", "utf-8"))
            header("SOAPACTION", "\"$RENDERING_CONTROL_SERVICE#GetVolume\"")
            setBody(envelope)
        }
        if (!resp.status.isSuccess()) return@runCatching null
        extractXmlTag(resp.bodyAsText(), "CurrentVolume")?.trim()?.toIntOrNull()?.coerceIn(0, 100)
    }.getOrNull()

    private fun extractServiceControlUrl(xml: String, serviceKeyword: String): String? {
        val serviceRegex = Regex("<service\\b[^>]*>([\\s\\S]*?)</service>", RegexOption.IGNORE_CASE)
        for (match in serviceRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val serviceType = extractXmlTag(block, "serviceType").orEmpty()
            if (serviceType.contains(serviceKeyword, ignoreCase = true)) {
                val ctrl = extractXmlTag(block, "controlURL")?.trim()
                if (!ctrl.isNullOrBlank()) return ctrl
            }
        }
        return null
    }

    private fun extractXmlTag(xml: String, tagName: String): String? {
        val regex = Regex("<(?:[\\w-]+:)?$tagName\\b[^>]*>([\\s\\S]*?)</(?:[\\w-]+:)?$tagName>", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.getOrNull(1)
    }

    private fun resolveRelativeUrl(baseUrl: String, path: String): String? {
        val clean = path.trim()
        if (clean.isEmpty()) return null
        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            return clean
        }
        val schemeIdx = baseUrl.indexOf("://")
        if (schemeIdx <= 0) return null
        val pathIdx = baseUrl.indexOf('/', schemeIdx + 3)
        val origin = if (pathIdx > 0) baseUrl.substring(0, pathIdx) else baseUrl
        return if (clean.startsWith("/")) {
            "$origin$clean"
        } else {
            val dir = if (pathIdx > 0) baseUrl.substringBeforeLast('/') else origin
            "$dir/$clean"
        }
    }

    private fun extractHostFromUrl(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isBlank()) return null
        val authority = afterScheme.substringBefore('/')
        return authority.substringBefore(':').takeIf { it.isNotBlank() }
    }

    private fun inferDlnaMimeType(url: String, hint: String, isLive: Boolean): String {
        val lower = url.substringBefore('?').lowercase()
        val hintLower = hint.lowercase()
        return when {
            isLive || lower.endsWith(".m3u8") || lower.endsWith(".ts") || hintLower.contains("mpegurl") || hintLower.contains("mp2t") ->
                "video/mp2t"
            lower.endsWith(".mkv") || hintLower.contains("matroska") || hintLower.contains("mkv") ->
                "video/x-mkv"
            lower.endsWith(".webm") || hintLower.contains("webm") ->
                "video/webm"
            else -> "video/mp4"
        }
    }

    private fun buildDidlLiteMetadata(
        title: String,
        uri: String,
        mimeType: String,
        isLive: Boolean,
    ): String {
        val safeTitle = escapeXml(title.ifBlank { "BestCine" })
        val safeUri = escapeXml(uri)
        val op = if (isLive) "00" else "01"
        val protocolInfo = "http-get:*:$mimeType:DLNA.ORG_OP=$op;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        val upnpClass = if (isLive) "object.item.videoItem.videoBroadcast" else "object.item.videoItem.movie"
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>$safeTitle</dc:title><upnp:class>$upnpClass</upnp:class><res protocolInfo="$protocolInfo">$safeUri</res></item></DIDL-Lite>"""
    }

    private fun formatDlnaTime(totalSeconds: Int): String {
        val clamped = totalSeconds.coerceAtLeast(0)
        val h = clamped / 3600
        val m = (clamped % 3600) / 60
        val s = clamped % 60
        return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }

    private fun parseDlnaTimeToSeconds(text: String): Int? {
        val parts = text.trim().substringBefore('.').split(':')
        if (parts.size != 3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val s = parts[2].toIntOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }

    private fun escapeXml(input: String): String =
        input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun decodeXmlEntities(input: String): String =
        input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

    private const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RENDERING_CONTROL_SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"
}
